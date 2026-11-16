package com.psphere.desktop.smart

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs

/** A group of faces that belong to one person. Names are set by the user. */
class Person(val id: Int, var name: String, var centroid: FloatArray, var faces: Int, var coverHash: String, var coverSize: Float, var coverJpeg: ByteArray?)

/** One face in one photo, and the person it was grouped into. */
class FaceRec(var person: Int, val x: Float, val y: Float, val w: Float, val h: Float, val embedding: FloatArray)

/**
 * What smart photos learned about each photo, other than search: its faces (grouped into
 * people), its text and where it was taken. A photo with an entry was looked at, even if it
 * had no faces or text. Saved in one file.
 */
class Facts(private val file: File) {
    private val faces = HashMap<String, List<FaceRec>>()
    private val text = HashMap<String, String>()
    /** Photo → place id ("" when it has no GPS position). */
    private val placeOf = HashMap<String, String>()
    private val places = HashMap<String, Place>()
    private val people = HashMap<Int, Person>()
    private var nextPerson = 1
    private var dirty = 0

    init { load() }

    @Synchronized fun isDone(hash: String) = hash in faces && hash in text && hash in placeOf
    @Synchronized fun count() = faces.size

    /** Stores what was found in one photo, grouping each face with the most similar person. */
    @Synchronized
    fun put(hash: String, found: List<FoundFace>, crops: List<ByteArray?>, words: String, place: Place?) {
        faces[hash] = found.mapIndexed { i, f ->
            val p = closestPerson(f.embedding)?.takeIf { it.second >= JOIN }?.first ?: newPerson(f.embedding)
            p.centroid = Clip.normalize(FloatArray(p.centroid.size) { k -> p.centroid[k] * p.faces + f.embedding[k] })
            p.faces++
            val size = f.w * f.h
            if (crops.getOrNull(i) != null && (p.coverJpeg == null || size > p.coverSize)) {
                p.coverHash = hash; p.coverSize = size; p.coverJpeg = crops[i]
            }
            FaceRec(p.id, f.x, f.y, f.w, f.h, f.embedding)
        }
        text[hash] = words
        placeOf[hash] = place?.id ?: ""
        place?.let { places[it.id] = it }
        if (++dirty >= 20) save()
    }

    private fun closestPerson(e: FloatArray): Pair<Person, Float>? =
        people.values.map { it to dot(it.centroid, e) }.maxByOrNull { it.second }

    private fun newPerson(e: FloatArray) = Person(nextPerson++, "", e.copyOf(), 0, "", 0f, null).also { people[it.id] = it }

    /** Forgets photos that left the library, and people with no faces left. */
    @Synchronized
    fun retain(keep: Set<String>) {
        val gone = faces.keys - keep
        if (gone.isEmpty() && (text.keys - keep).isEmpty()) return
        gone.forEach { h -> faces.remove(h)?.forEach { f -> people[f.person]?.let { it.faces-- } } }
        text.keys.retainAll(keep); placeOf.keys.retainAll(keep)
        people.values.removeIf { it.faces <= 0 }
        save()
    }

    // ── People ───────────────────────────────────────────────────────────────

    /** People seen in at least two photos (or named), named ones first, then by how often they appear. */
    @Synchronized
    fun people(): List<Pair<Person, Set<String>>> {
        val photos = HashMap<Int, MutableSet<String>>()
        faces.forEach { (h, fs) -> fs.forEach { photos.getOrPut(it.person) { HashSet() } += h } }
        return people.values.mapNotNull { p -> photos[p.id]?.let { p to it } }
            .filter { (p, hs) -> p.name.isNotEmpty() || hs.size >= MIN_PHOTOS }
            .sortedWith(compareBy<Pair<Person, Set<String>>>({ it.first.name.isEmpty() }, { -it.second.size }))
    }

    @Synchronized fun photosOf(person: Int): Set<String> = faces.filterValues { fs -> fs.any { it.person == person } }.keys.toSet()

    @Synchronized fun peopleIn(hash: String): List<Person> = faces[hash].orEmpty().mapNotNull { people[it.person] }.distinctBy { it.id }

    /** Names a person. Giving the name someone else already has merges the two. */
    @Synchronized
    fun name(id: Int, name: String) {
        val p = people[id] ?: return
        val clean = name.trim().take(40)
        val same = people.values.firstOrNull { it.id != id && clean.isNotEmpty() && it.name.equals(clean, ignoreCase = true) }
        if (same != null) {
            faces.values.forEach { fs -> fs.forEach { if (it.person == id) it.person = same.id } }
            same.centroid = Clip.normalize(FloatArray(same.centroid.size) { k -> same.centroid[k] * same.faces + p.centroid[k] * p.faces })
            same.faces += p.faces
            if (p.coverSize > same.coverSize && p.coverJpeg != null) { same.coverHash = p.coverHash; same.coverSize = p.coverSize; same.coverJpeg = p.coverJpeg }
            people.remove(id)
        } else {
            p.name = clean
        }
        save()
    }

    /** Joins groups that turned out to be the same person, e.g. after many more photos. */
    @Synchronized
    fun tidy() {
        var merged = true
        while (merged) {
            merged = false
            val list = people.values.toList()
            loop@ for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                if (a.name.isNotEmpty() && b.name.isNotEmpty() && !a.name.equals(b.name, true)) continue
                if (dot(a.centroid, b.centroid) >= MERGE) {
                    val (keep, drop) = if (b.name.isNotEmpty() && a.name.isEmpty()) b to a else a to b
                    faces.values.forEach { fs -> fs.forEach { if (it.person == drop.id) it.person = keep.id } }
                    keep.centroid = Clip.normalize(FloatArray(keep.centroid.size) { k -> keep.centroid[k] * keep.faces + drop.centroid[k] * drop.faces })
                    keep.faces += drop.faces
                    if (drop.coverSize > keep.coverSize && drop.coverJpeg != null) { keep.coverHash = drop.coverHash; keep.coverSize = drop.coverSize; keep.coverJpeg = drop.coverJpeg }
                    people.remove(drop.id)
                    merged = true
                    break@loop
                }
            }
        }
        save()
    }

    // ── Text and places ──────────────────────────────────────────────────────

    @Synchronized fun textOf(hash: String) = text[hash].orEmpty()

    /** Photos whose text contains every word of [words]. */
    @Synchronized
    fun withText(words: List<String>): Set<String> =
        if (words.isEmpty()) emptySet() else text.filterValues { t -> words.all { t.contains(it, ignoreCase = true) } }.keys.toSet()

    @Synchronized fun placeOf(hash: String): Place? = placeOf[hash]?.let { places[it] }

    /** Places with their photos, most photos first. */
    @Synchronized
    fun places(): List<Pair<Place, Set<String>>> =
        placeOf.entries.filter { it.value.isNotEmpty() }.groupBy({ it.value }, { it.key })
            .mapNotNull { (id, hs) -> places[id]?.let { it to hs.toSet() } }
            .sortedByDescending { it.second.size }

    @Synchronized fun photosAt(placeId: String): Set<String> = placeOf.filterValues { it == placeId }.keys.toSet()

    @Synchronized fun allPlaces(): Collection<Place> = places.values.toList()

    // ── Saving ───────────────────────────────────────────────────────────────

    @Synchronized
    fun clear() { faces.clear(); text.clear(); placeOf.clear(); places.clear(); people.clear(); nextPerson = 1; file.delete() }

    @Synchronized
    fun save() {
        dirty = 0
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { o ->
            o.writeInt(FORMAT); o.writeInt(nextPerson)
            o.writeInt(people.size)
            people.values.forEach { p ->
                o.writeInt(p.id); o.writeUTF(p.name); vec(o, p.centroid); o.writeInt(p.faces)
                o.writeUTF(p.coverHash); o.writeFloat(p.coverSize)
                val c = p.coverJpeg ?: ByteArray(0); o.writeInt(c.size); o.write(c)
            }
            o.writeInt(places.size)
            places.values.forEach { o.writeUTF(it.id); o.writeUTF(it.city); o.writeUTF(it.region); o.writeUTF(it.country) }
            o.writeInt(faces.size)
            faces.forEach { (h, fs) ->
                o.writeUTF(h); o.writeUTF(text[h].orEmpty().take(8_000)); o.writeUTF(placeOf[h].orEmpty())
                o.writeShort(fs.size)
                fs.forEach { f -> o.writeInt(f.person); o.writeFloat(f.x); o.writeFloat(f.y); o.writeFloat(f.w); o.writeFloat(f.h); vec(o, f.embedding) }
            }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { i ->
                if (i.readInt() != FORMAT) return
                nextPerson = i.readInt()
                repeat(i.readInt()) {
                    val p = Person(i.readInt(), i.readUTF(), vec(i), i.readInt(), i.readUTF(), i.readFloat(), null)
                    p.coverJpeg = ByteArray(i.readInt()).also { i.readFully(it) }.takeIf { it.isNotEmpty() }
                    people[p.id] = p
                }
                repeat(i.readInt()) { val pl = Place(i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF()); places[pl.id] = pl }
                repeat(i.readInt()) {
                    val h = i.readUTF(); text[h] = i.readUTF(); placeOf[h] = i.readUTF()
                    faces[h] = List(i.readUnsignedShort()) { FaceRec(i.readInt(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat(), vec(i)) }
                }
            }
        }.onFailure { clear() } // damaged: photos are looked at again
    }

    /** Fingerprints are stored as 8-bit numbers with a scale, like the search index. */
    private fun vec(o: DataOutputStream, v: FloatArray) {
        val scale = (v.maxOfOrNull { abs(it) } ?: 0f).coerceAtLeast(1e-6f) / 127f
        o.writeFloat(scale); o.writeShort(v.size)
        v.forEach { o.writeByte((it / scale).toInt().coerceIn(-127, 127)) }
    }

    private fun vec(i: DataInputStream): FloatArray {
        val scale = i.readFloat(); val n = i.readUnsignedShort()
        return FloatArray(n) { i.readByte() * scale }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (k in a.indices) s += a[k] * b[k]; return s }

    companion object {
        private const val FORMAT = 1
        /** A face joins a person when it is at least this close to their average face (see Faces.SAME_PERSON). */
        const val JOIN = 0.45f
        /** Two people whose average faces are this close are the same person. */
        private const val MERGE = 0.6f
        /** People only show once they're in this many photos, so strangers in the background don't. */
        private const val MIN_PHOTOS = 2
    }
}
