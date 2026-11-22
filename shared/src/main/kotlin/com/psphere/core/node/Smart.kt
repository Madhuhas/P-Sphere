package com.psphere.core.node

import com.psphere.core.net.Codec.smartItem
import com.psphere.core.net.Codec.str
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row of a smart answer: a photo, a person, a place or a kind of thing ("Food"). */
data class SmartItem(
    val kind: Kind,
    /** Photo hash, person id, place id, or the thing's search words. */
    val id: String,
    /** Person's name ("" until named), town, or thing. */
    val title: String = "",
    /** Region and country for a place. */
    val subtitle: String = "",
    /** How many photos. */
    val count: Int = 0,
    /** Hash of a photo to show for it. */
    val cover: String = "",
    /** Search score for photos, best first. */
    val score: Float = 0f,
    /** A small JPEG of the person's face. */
    val image: ByteArray? = null,
) {
    enum class Kind { PHOTO, PERSON, PLACE, THING }

    override fun equals(other: Any?) = other is SmartItem && other.kind == kind && other.id == id && other.title == title &&
        other.subtitle == subtitle && other.count == count && other.cover == cover && other.score == score
    override fun hashCode() = 31 * kind.hashCode() + id.hashCode()
}

/** What can be asked of the smart features. Arguments are listed with each. */
object SmartOps {
    /** (words) → photos, best first. Words can describe the photo, name a person or place, or be text in it. */
    const val SEARCH = "search"
    /** () → people, places and things, for the Explore page. */
    const val EXPLORE = "explore"
    /** (person id) → that person's photos. */
    const val PERSON = "person"
    /** (place id) → photos taken there. */
    const val PLACE = "place"
    /** (person id, name) → the people after naming; giving an existing name merges the two. */
    const val NAME = "name"
    /** (photo hash) → the people and place in one photo. */
    const val PHOTO = "photo"

    /** Answers worth keeping on phones, so People and Places still show away from home. */
    internal val CACHED = setOf(EXPLORE, PERSON, PLACE, PHOTO)
}

/** The optional smart features. The desktop app plugs its models in here once they're downloaded. */
fun interface SmartLibrary {
    /** The answer, or null while the features aren't ready. */
    fun ask(op: String, args: List<String>): List<SmartItem>?
}

data class SmartAnswer(
    val items: List<SmartItem>,
    /** Devices that answered, this one included. Empty when no reachable device has smart features. */
    val from: List<String>,
    /** True when no device could be asked and this is the last answer kept on this device. */
    val saved: Boolean = false,
)

/** "1 year ago today" and so on: photos taken around this day in earlier years. */
data class Memory(val yearsAgo: Int, val items: List<LibraryItem>) {
    val title: String get() = if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago"
}

/** Builds Memories from the library: one per earlier year with photos taken within a day of today. */
internal fun memoriesOf(library: List<LibraryItem>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Memory> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return library.mapNotNull { li ->
        val day = Instant.ofEpochMilli(li.item.takenAt).atZone(zone).toLocalDate()
        val years = today.year - day.year
        if (years < 1) return@mapNotNull null
        val sameDay = runCatching { day.withYear(today.year) }.getOrElse { LocalDate.of(today.year, 3, 1) }
        if (kotlin.math.abs(sameDay.toEpochDay() - today.toEpochDay()) <= 1) years to li else null
    }.groupBy({ it.first }, { it.second })
        .map { (years, items) -> Memory(years, items.sortedBy { it.item.takenAt }.take(60)) }
        .sortedBy { it.yearsAgo }
}

/**
 * The last smart answers, kept on disk so phones can show People and Places away from home.
 * Each remembers the device that gave it, and is only used while that device is still linked.
 */
internal class SmartCache(private val file: File) {
    private class Saved(val device: String, val items: List<SmartItem>)
    private val answers = LinkedHashMap<String, Saved>(64, 0.75f, true)
    private var loaded = false

    private fun key(op: String, args: List<String>) = (listOf(op) + args).joinToString("\u0000")

    @Synchronized
    fun get(op: String, args: List<String>, linked: Set<String>): List<SmartItem>? {
        load()
        return answers[key(op, args)]?.takeIf { it.device in linked }?.items
    }

    @Synchronized
    fun put(op: String, args: List<String>, device: String, items: List<SmartItem>) {
        load()
        answers[key(op, args)] = Saved(device, items)
        while (answers.size > MAX_ANSWERS) answers.remove(answers.keys.first())
        save()
    }

    /** Forgets everything [device] said, e.g. when it's unlinked. */
    @Synchronized
    fun forget(device: String) {
        load()
        if (answers.values.removeIf { it.device == device }) save()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != FORMAT) return
                repeat(input.readInt()) {
                    val k = input.str()
                    val device = input.str()
                    answers[k] = Saved(device, List(input.readInt()) { input.smartItem() })
                }
            }
        }.onFailure { answers.clear() }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
                out.writeInt(FORMAT)
                out.writeInt(answers.size)
                answers.forEach { (k, v) -> out.str(k); out.str(v.device); out.writeInt(v.items.size); v.items.forEach { out.smartItem(it) } }
            }
            com.psphere.core.replaceFile(tmp, file)
        }
    }

    companion object {
        private const val MAX_ANSWERS = 200
        /** Saved files without it (from before answers knew their device) are discarded. */
        private const val FORMAT = 0x50534332
    }
}
