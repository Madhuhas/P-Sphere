package com.psphere.desktop.smart

import com.psphere.core.model.Media
import com.psphere.core.node.SmartSearch
import com.psphere.desktop.engine.Engine
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
import java.io.File
import java.util.concurrent.Executors

sealed class SmartState {
    /** The search model isn't downloaded. */
    object Off : SmartState()
    data class Downloading(val done: Long, val total: Long) : SmartState()
    /** Looking at photos it hasn't seen yet. Search already works for the ones done. */
    data class Scanning(val done: Int, val total: Int, val paused: Boolean) : SmartState()
    data class Ready(val photos: Int) : SmartState()
    data class Failed(val message: String) : SmartState()
}

/**
 * Smart search on this computer: an optional download (the search model), a background scan
 * that looks at each photo's preview once, and answers to searches from this app and from linked
 * phones and tablets. Scanning runs on one low-priority thread, so the computer stays usable.
 */
object Smart : SmartSearch {
    private lateinit var dir: File
    private val modelsDir get() = File(dir, "models")
    private lateinit var index: SmartIndex
    /** Loaded only while needed: about 400 MB of memory, released after a few idle minutes. */
    private var clip: Clip? = null
    private var clipUsedAt = 0L
    /** Every use of the model holds this, so it's never unloaded mid-use. */
    private val clipLock = Any()

    /** One low-priority thread for all model work. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "psphere-smart").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private var scanJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var cancelDownload = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Previews that couldn't be read, so they aren't retried on every pass. */
    private val unreadable = HashSet<String>()

    private val _state = MutableStateFlow<SmartState>(SmartState.Off)
    val state: StateFlow<SmartState> = _state

    /** Called once the engine has started. */
    fun start(dataDir: File) {
        dir = File(dataDir, "smart")
        index = SmartIndex(File(dir, "index.bin"))
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

    /** Deletes the model and everything smart search learned. Photos are not touched. */
    fun remove() {
        Engine.node.smartSearch = null
        scanJob?.cancel()
        scope.launch {
            synchronized(clipLock) { clip?.close(); clip = null }
            index.clear()
            synchronized(unreadable) { unreadable.clear() }
            modelsDir.deleteRecursively()
            _state.value = SmartState.Off
        }
    }

    // ── Scanning ─────────────────────────────────────────────────────────────

    private fun turnOn() {
        Engine.node.smartSearch = this
        _state.value = SmartState.Scanning(index.size(), index.size(), Engine.prefs.smartPaused)
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive) {
                scanOnce()
                // Wait for a change (and look again every few minutes regardless), unloading
                // the model once it has been idle for a while.
                for (minute in 1..5) {
                    val woke = withTimeoutOrNull(60_000L) { wake.receive() } != null
                    unloadIfIdle()
                    if (woke) break
                }
                delay(2_000) // let a burst of changes settle
            }
        }
    }

    private suspend fun scanOnce() {
        val library = runCatching { Engine.node.library() }.getOrNull() ?: return
        val wanted = library.filter { it.item.media != Media.OTHER }.map { it.item.hash }.toSet()
        index.retain(wanted)
        val todo = wanted.filter { !index.has(it) && synchronized(unreadable) { it !in unreadable } && Engine.node.previews.has(it) }
        val total = index.size() + todo.size
        if (todo.isEmpty()) { index.save(); _state.value = SmartState.Ready(index.size()); return }
        if (withClip { } == null) return // the model couldn't load; the card says why
        var done = index.size()
        for (hash in todo) {
            while (Engine.prefs.smartPaused) {
                _state.value = SmartState.Scanning(done, total, paused = true)
                withTimeoutOrNull(60_000L) { wake.receive() }
                if (!scope.isActive) return
            }
            _state.value = SmartState.Scanning(done, total, paused = false)
            val preview = Engine.node.previews.get(hash)
            val vector = preview?.let { p -> withClip { runCatching { it.embedImage(p) }.getOrNull() } }
            if (vector != null) index.put(hash, vector) else synchronized(unreadable) { unreadable += hash }
            done++
            delay(PAUSE_BETWEEN_PHOTOS_MS)
        }
        index.save()
        synchronized(clipLock) { clip?.closeVision() }
        _state.value = SmartState.Ready(index.size())
        Engine.bump()
    }

    /** Runs [block] with the model, loading it first if needed. Null if it can't be loaded. */
    private fun <T> withClip(block: (Clip) -> T): T? = synchronized(clipLock) {
        val model = clip ?: runCatching { Clip(modelsDir, threads = 1) }
            .onFailure { _state.value = SmartState.Failed("Smart search couldn't start on this computer (${it.message?.take(120)}).") }
            .getOrNull()?.also { clip = it }
            ?: return null
        clipUsedAt = System.currentTimeMillis()
        block(model)
    }

    private fun unloadIfIdle() = synchronized(clipLock) {
        if (clip != null && System.currentTimeMillis() - clipUsedAt > IDLE_UNLOAD_MS) { clip?.close(); clip = null }
    }

    // ── Searching (this app and linked devices) ──────────────────────────────

    override fun search(query: String, limit: Int): List<Pair<String, Float>>? {
        if (_state.value is SmartState.Off || _state.value is SmartState.Downloading || !SmartModels.installed(modelsDir)) return null
        val q = withClip { it.embedText(query) } ?: return null
        val hits = index.search(q, limit, MIN_SCORE)
        val best = hits.firstOrNull()?.second ?: return emptyList()
        // Keep the photos that match about as well as the best one; the rest are noise.
        return hits.filter { it.second >= best - WITHIN_BEST }
    }

    /** Lowest similarity worth showing at all. Real matches score about 0.25–0.35. */
    private const val MIN_SCORE = 0.20f
    private const val WITHIN_BEST = 0.05f
    /** A short rest between photos keeps the fan quiet during a big first scan. */
    private const val PAUSE_BETWEEN_PHOTOS_MS = 40L
    private const val IDLE_UNLOAD_MS = 3 * 60_000L
}
