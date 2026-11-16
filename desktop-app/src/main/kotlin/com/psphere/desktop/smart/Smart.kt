package com.psphere.desktop.smart

import com.psphere.core.model.Media
import com.psphere.core.node.SmartItem
import com.psphere.core.node.SmartLibrary
import com.psphere.core.node.SmartOps
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

sealed class SmartState {
    /** The models aren't downloaded. */
    object Off : SmartState()
    data class Downloading(val done: Long, val total: Long) : SmartState()
    /** Looking at photos it hasn't seen yet. Everything already works for the ones done. */
    data class Scanning(val done: Int, val total: Int, val paused: Boolean) : SmartState()
    data class Ready(val photos: Int, val people: Int, val places: Int) : SmartState()
    data class Failed(val message: String) : SmartState()
}

/**
 * Smart photos on this computer: an optional download (the models), a background scan that looks
 * at each photo once (what's in it, faces, text, where it was taken), and answers for this app and
 * for linked phones and tablets. Scanning runs on one low-priority thread, so the computer stays usable.
 */
object Smart : SmartLibrary {
    private lateinit var dir: File
    private val modelsDir get() = File(dir, "models")
    private lateinit var index: SmartIndex
    private lateinit var facts: Facts

    /** The models, loaded only while needed (several hundred MB) and unloaded after a few idle minutes. */
    private class Models(dir: File) {
        val clip = Clip(dir, threads = 1)
        val faces = Faces(dir, threads = 1)
        val ocr = Ocr(dir, threads = 1)
        fun closeScanParts() { clip.closeVision(); faces.close(); ocr.close() }
        fun close() { clip.close(); faces.close(); ocr.close() }
    }
    private var models: Models? = null
    private var usedAt = 0L
    /** Every use of the models holds this, so they're never unloaded mid-use. */
    private val lock = Any()

    /** One low-priority thread for all model work. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "psphere-smart").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var scanJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var cancelDownload = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Photos that couldn't be read, so they aren't retried on every pass. */
    private val unreadable = HashSet<String>()

    private val _state = MutableStateFlow<SmartState>(SmartState.Off)
    val state: StateFlow<SmartState> = _state

    /** Called once the engine has started. */
    fun start(dataDir: File) {
        dir = File(dataDir, "smart")
        index = SmartIndex(File(dir, "index.bin"))
        facts = Facts(File(dir, "facts.bin"))
        if (SmartModels.installed(modelsDir)) turnOn()
        // Look again whenever something changes, e.g. a backup brought new photos.
        Engine.scope.launch { Engine.state.map { it.revision }.distinctUntilChanged().collect { wake.trySend(Unit) } }
    }

    // ── What the Settings screen does ────────────────────────────────────────

    fun download() {
        if (downloadJob?.isActive == true) return
        cancelDownload = false
        downloadJob = Engine.scope.launch {
            _state.value = SmartState.Downloading(0, SmartModels.totalBytes)
            try {
                SmartModels.download(modelsDir, { cancelDownload }) { done, total -> _state.value = SmartState.Downloading(done, total) }
                turnOn()
            } catch (e: InterruptedException) {
                _state.value = SmartState.Off
            } catch (e: Exception) {
                _state.value = if (cancelDownload) SmartState.Off
                else SmartState.Failed(e.message?.takeIf { "HTTP" in it || "damaged" in it } ?: "Couldn't download. Check your internet connection and try again.")
            }
        }
    }

    fun cancelDownload() { cancelDownload = true }

    fun setPaused(paused: Boolean) {
        Engine.prefs.smartPaused = paused
        (_state.value as? SmartState.Scanning)?.let { _state.value = it.copy(paused = paused) }
        wake.trySend(Unit)
    }

    /** Deletes the models and everything smart photos learned (names too). Photos are not touched. */
    fun remove() {
        Engine.node.smart = null
        scanJob?.cancel()
        scope.launch {
            synchronized(lock) { models?.close(); models = null }
            index.clear()
            facts.clear()
            synchronized(unreadable) { unreadable.clear() }
            modelsDir.deleteRecursively()
            _state.value = SmartState.Off
        }
    }

    // ── Scanning ─────────────────────────────────────────────────────────────

    private fun turnOn() {
        Engine.node.smart = this
        _state.value = SmartState.Scanning(facts.count(), facts.count(), Engine.prefs.smartPaused)
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive) {
                scanOnce()
                // Wait for a change (and look again every few minutes regardless), unloading
                // the models once they've been idle for a while.
                for (minute in 1..5) {
                    val woke = withTimeoutOrNull(60_000L) { wake.receive() } != null
                    unloadIfIdle()
                    if (woke) break
                }
                delay(2_000) // let a burst of changes settle
            }
        }
    }

    private fun ready() = SmartState.Ready(facts.count(), facts.people().size, facts.places().size)

    private suspend fun scanOnce() {
        val library = runCatching { Engine.node.library() }.getOrNull() ?: return
        val media = library.filter { it.item.media != Media.OTHER }.associate { it.item.hash to it.item.media }
        index.retain(media.keys)
        facts.retain(media.keys)
        val todo = media.keys.filter { h ->
            (!index.has(h) || !facts.isDone(h)) && synchronized(unreadable) { h !in unreadable }
        }
        val total = media.size
        if (todo.isEmpty()) { index.save(); _state.value = ready(); return }
        if (withModels { } == null) return // the models couldn't load; the card says why
        var done = total - todo.size
        for (hash in todo) {
            while (Engine.prefs.smartPaused) {
                _state.value = SmartState.Scanning(done, total, paused = true)
                withTimeoutOrNull(60_000L) { wake.receive() }
            }
            _state.value = SmartState.Scanning(done, total, paused = false)
            runCatching { look(hash, media.getValue(hash)) }.onFailure { synchronized(unreadable) { unreadable += hash } }
            done++
            delay(PAUSE_BETWEEN_PHOTOS_MS)
        }
        facts.tidy()
        index.save(); facts.save()
        synchronized(lock) { models?.closeScanParts() }
        _state.value = ready()
        Engine.bump()
    }

    /** Looks at one photo: what's in it (from its preview), and its faces, text and place (from the full photo when it's here). */
    private fun look(hash: String, media: Media) {
        val preview = Engine.node.previews.get(hash)
        if (!index.has(hash)) {
            val v = preview?.let { p -> withModels { it.clip.embedImage(p) } }
            if (v != null) index.put(hash, v)
        }
        if (facts.isDone(hash)) return
        val full = if (media == Media.PHOTO) readLocal(hash) else null
        val img = full?.let { Images.readUpright(it, FULL_PX) } ?: preview?.let { Images.readUpright(it, 480) }
        if (img == null && preview == null && full == null) { synchronized(unreadable) { unreadable += hash }; return }
        val found = img?.let { i -> withModels { it.faces.find(i) } }.orEmpty()
        val crops = found.map { f -> img?.let { faceCrop(it, f) } }
        val words = if (img != null && media == Media.PHOTO) withModels { it.ocr.read(img) }.orEmpty() else ""
        val place = full?.let { Places.position(it) }?.let { (lat, lon) -> Places.at(lat, lon) }
        facts.put(hash, found, crops, words, place)
    }

    /** The full photo's bytes, if this computer has it (its own, or a backup it keeps). */
    private fun readLocal(hash: String): ByteArray? {
        val src = Engine.node.openLocal(hash) ?: return null
        return src.use { if (it.size in 1..MAX_FULL_BYTES) it.stream.readBytes() else null }
    }

    /** A small square picture of a face, for People. */
    private fun faceCrop(img: BufferedImage, f: FoundFace): ByteArray? {
        val cx = (f.x + f.w / 2) * img.width; val cy = (f.y + f.h / 2) * img.height
        val side = (max(f.w * img.width, f.h * img.height) * 1.7f).coerceAtMost(min(img.width, img.height).toFloat())
        val x = (cx - side / 2).coerceIn(0f, img.width - side).toInt(); val y = (cy - side / 2).coerceIn(0f, img.height - side).toInt()
        val out = BufferedImage(FACE_PX, FACE_PX, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(img, 0, 0, FACE_PX, FACE_PX, x, y, x + side.toInt(), y + side.toInt(), null); dispose()
        }
        return Images.jpeg(out, 0.85f)
    }

    /** Runs [block] with the models, loading them first if needed. Null if they can't be loaded. */
    private fun <T> withModels(block: (Models) -> T): T? = synchronized(lock) {
        val m = models ?: runCatching { Models(modelsDir) }
            .onFailure { _state.value = SmartState.Failed("Smart photos couldn't start on this computer (${it.message?.take(120)}).") }
            .getOrNull()?.also { models = it }
            ?: return null
        usedAt = System.currentTimeMillis()
        block(m)
    }

    private fun unloadIfIdle() = synchronized(lock) {
        if (models != null && System.currentTimeMillis() - usedAt > IDLE_UNLOAD_MS) { models?.close(); models = null }
    }

    // ── Answers (this app and linked devices) ────────────────────────────────

    override fun ask(op: String, args: List<String>): List<SmartItem>? {
        if (_state.value is SmartState.Off || _state.value is SmartState.Downloading || !SmartModels.installed(modelsDir)) return null
        return when (op) {
            SmartOps.SEARCH -> search(args.firstOrNull().orEmpty())
            SmartOps.EXPLORE -> explore()
            SmartOps.PERSON -> photos(facts.photosOf(args.firstOrNull()?.toIntOrNull() ?: -1))
            SmartOps.PLACE -> photos(facts.photosAt(args.firstOrNull().orEmpty()))
            SmartOps.NAME -> {
                val id = args.getOrNull(0)?.toIntOrNull() ?: return emptyList()
                facts.name(id, args.getOrNull(1).orEmpty())
                Engine.bump()
                peopleItems()
            }
            SmartOps.PHOTO -> {
                val h = args.firstOrNull().orEmpty()
                val shown = facts.people().associate { it.first.id to it.second.size }
                facts.peopleIn(h).mapNotNull { p -> shown[p.id]?.let { personItem(p, it) } } +
                    listOfNotNull(facts.placeOf(h)?.let { pl -> facts.places().firstOrNull { it.first.id == pl.id }?.let { placeItem(it.first, it.second) } })
            }
            else -> emptyList()
        }
    }

    /** When each library photo was taken, worked out once per change of the library. */
    private var takenCache: Pair<List<com.psphere.core.node.LibraryItem>, Map<String, Long>>? = null

    @Synchronized
    private fun takenAt(): Map<String, Long> {
        val lib = Engine.node.library()
        takenCache?.let { (l, m) -> if (l === lib) return m }
        return lib.associate { it.item.hash to it.item.takenAt }.also { takenCache = lib to it }
    }

    /** Photos, newest first. */
    private fun photos(hashes: Set<String>): List<SmartItem> {
        val taken = takenAt()
        return hashes.filter { it in taken }.sortedByDescending { taken[it] }.map { SmartItem(SmartItem.Kind.PHOTO, it) }
    }

    /**
     * One search box for everything: names of people and places narrow the photos down ("Mom in
     * Goa"), words found in photos come next ("invoice"), then photos that look like the description.
     */
    private fun search(query: String): List<SmartItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // A thing from Explore shows exactly the photos its card counted.
        THINGS.indexOfFirst { it.second == q }.takeIf { it >= 0 }?.let { i ->
            val v = (thingVectors ?: withModels { m -> THINGS.map { m.clip.embedText(it.second) } }?.also { thingVectors = it })?.get(i)
                ?: return emptyList()
            return cut(index.search(v, 300, -1f), THING_MIN_SCORE, THING_WITHIN).map { SmartItem(SmartItem.Kind.PHOTO, it.first, score = it.second) }
        }
        val lower = q.lowercase()
        var rest = " $lower "
        val sets = ArrayList<Set<String>>()
        for ((p, hs) in facts.people()) {
            if (p.name.isNotEmpty() && contains(rest, p.name)) { sets += hs; rest = rest.replace(p.name.lowercase(), " ") }
        }
        for (pl in facts.allPlaces().sortedByDescending { it.city.length }) {
            for (name in listOf(pl.city, pl.region, pl.country)) {
                if (name.length >= 3 && contains(rest, name)) { sets += facts.places().filter { (o, _) -> o.city == pl.city || o.region == name || o.country == name }.flatMap { it.second }.toSet(); rest = rest.replace(name.lowercase(), " "); break }
            }
        }
        rest = rest.replace(Regex("\\b(in|at|and|with|of|the|photos?|pictures?)\\b"), " ").trim()
        val clipHits = if (rest.isNotEmpty()) withModels { it.clip.embedText(rest) }?.let { index.search(it, 1000, -1f) }.orEmpty() else emptyList()
        if (sets.isNotEmpty()) {
            val base = sets.reduce { a, b -> a intersect b }
            if (rest.isEmpty()) return photos(base)
            // "food in Paris": of the photos from Paris, the ones that look like food.
            val within = clipHits.filter { it.first in base }
            val fits = cut(within, MIN_SCORE, WITHIN_BEST)
            val shown = fits.ifEmpty { within }
            return shown.map { SmartItem(SmartItem.Kind.PHOTO, it.first, score = it.second) }
        }
        val words = rest.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        val textHits = facts.withText(words)
        // When the words are written in some photos ("invoice"), only clear look-alikes join them.
        val looks = if (textHits.isEmpty()) cut(clipHits) else clipHits.filter { it.second >= STRONG_SCORE }
        val out = LinkedHashMap<String, Float>()
        val clipScore = clipHits.associate { it.first to it.second }
        textHits.sortedByDescending { clipScore[it] ?: 0f }.forEach { out[it] = 1f }
        looks.forEach { (h, s) -> out.putIfAbsent(h, s) }
        return out.map { (h, s) -> SmartItem(SmartItem.Kind.PHOTO, h, score = s) }
    }

    /** Keeps the photos that match about as well as the best one; the rest are noise. */
    private fun cut(hits: List<Pair<String, Float>>, minScore: Float = MIN_SCORE, within: Float = WITHIN_BEST): List<Pair<String, Float>> {
        val best = hits.firstOrNull()?.second ?: return emptyList()
        return hits.filter { it.second >= minScore && it.second >= best - within }
    }

    private fun contains(haystack: String, name: String) =
        Regex("(^|[^\\p{L}])" + Regex.escape(name.lowercase()) + "($|[^\\p{L}])").containsMatchIn(haystack)

    /** The things' search words never change, so they're turned into vectors once. */
    @Volatile private var thingVectors: List<FloatArray>? = null

    private fun explore(): List<SmartItem> {
        val vectors = thingVectors ?: withModels { m -> THINGS.map { m.clip.embedText(it.second) } }?.also { thingVectors = it }
        val things = vectors.orEmpty().mapIndexedNotNull { i, v ->
            val (label, words) = THINGS[i]
            val hits = cut(index.search(v, 300, -1f), THING_MIN_SCORE, THING_WITHIN)
            if (hits.size >= MIN_THING_PHOTOS) SmartItem(SmartItem.Kind.THING, words, label, count = hits.size, cover = hits.first().first) else null
        }
        val places = facts.places().map { (pl, hs) -> placeItem(pl, hs) }
        return peopleItems() + places + things
    }

    private fun peopleItems() = facts.people().map { (p, hs) -> personItem(p, hs.size) }

    private fun personItem(p: Person, photos: Int) =
        SmartItem(SmartItem.Kind.PERSON, p.id.toString(), p.name, count = photos, cover = p.coverHash, image = p.coverJpeg)

    private fun placeItem(pl: Place, hs: Set<String>): SmartItem {
        val taken = takenAt()
        val cover = hs.maxByOrNull { taken[it] ?: 0L } ?: ""
        return SmartItem(SmartItem.Kind.PLACE, pl.id, pl.city, listOf(pl.region, pl.country).filter { it.isNotEmpty() && it != pl.city }.joinToString(", "), hs.size, cover)
    }

    /** Kinds of things shown on the Explore page, each with the words used to find them. */
    private val THINGS = listOf(
        "Selfies" to "a selfie", "Food" to "a photo of food", "Pets" to "a photo of a pet dog or cat",
        "Beaches" to "a photo of a beach", "Mountains" to "a photo of mountains", "Sunsets" to "a photo of a sunset",
        "Flowers" to "a photo of flowers", "Cars" to "a photo of a car", "Snow" to "a photo of snow",
        "Birthdays" to "a birthday cake", "Screenshots" to "a screenshot of a phone screen", "Documents" to "a photo of a document",
    )

    /** Lowest similarity worth showing at all. Real matches score about 0.25–0.35. */
    private const val MIN_SCORE = 0.215f
    private const val WITHIN_BEST = 0.05f
    /** A look-alike this close is shown even next to photos with the words written in them. */
    private const val STRONG_SCORE = 0.25f
    private const val THING_MIN_SCORE = 0.24f
    private const val THING_WITHIN = 0.04f
    private const val MIN_THING_PHOTOS = 2
    /** Faces and text are read from the full photo at up to this size. */
    private const val FULL_PX = 1600
    private const val MAX_FULL_BYTES = 80L * 1024 * 1024
    private const val FACE_PX = 160
    /** A short rest between photos keeps the fan quiet during a big first scan. */
    private const val PAUSE_BETWEEN_PHOTOS_MS = 40L
    private const val IDLE_UNLOAD_MS = 3 * 60_000L
}
