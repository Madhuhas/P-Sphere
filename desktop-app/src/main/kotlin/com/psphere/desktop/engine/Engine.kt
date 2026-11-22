package com.psphere.desktop.engine

import com.psphere.core.node.CodeConfirmer
import com.psphere.core.node.DeviceStatus
import com.psphere.core.node.RingResult
import com.psphere.core.node.HostPort
import com.psphere.core.node.LinkedDevice
import com.psphere.core.node.Node
import com.psphere.core.node.NodeServer
import com.psphere.core.node.PairingOffer
import com.psphere.core.node.SyncProgress
import com.psphere.core.node.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** What PSphere is busy with right now. */
sealed class Activity {
    object Idle : Activity()
    data class Scanning(val done: Int, val total: Int) : Activity()
    /** A sync this laptop started. */
    data class Syncing(val deviceName: String, val progress: SyncProgress?) : Activity()
    /** A sync another device started with this laptop. */
    data class Serving(val deviceName: String, val progress: SyncProgress?) : Activity()
}

data class EngineState(
    /** Bumped whenever anything in the node changes, so screens recompute what they show. */
    val revision: Int = 0,
    val nearby: Map<String, NearbyDevice> = emptyMap(),
    val activity: Activity = Activity.Idle,
    /** Latest result per linked device. */
    val results: Map<String, SyncResult> = emptyMap(),
    val scanErrors: List<String> = emptyList(),
    /** Set while this device shows its QR code. */
    val linkOffer: PairingOffer? = null,
)

/** A 6-digit code the user must compare on both screens. */
class CodeRequest(val otherDevice: String, val code: String, private val answer: CompletableFuture<Boolean>) {
    fun reply(ok: Boolean) { answer.complete(ok) }
}

/** Something worth a notification, e.g. a finished backup while the window is closed. */
data class Notice(val title: String, val text: String, val warning: Boolean = false)

/**
 * The app-wide backup engine: one [Node], its server, discovery, and automatic backups.
 * Lives as long as the process; screens observe [state]. Mirrors the Android app's Engine.
 */
object Engine {
    /** Fixed so linked devices can reconnect at the remembered address; falls back to any free port. */
    private const val PREFERRED_PORT = 47_820
    private const val AUTO_SYNC_GAP_MS = 60_000L
    private const val PERIODIC_MS = 15 * 60_000L

    lateinit var node: Node
        private set
    lateinit var prefs: DesktopPrefs
        private set
    lateinit var dataDir: File
        private set
    private var server: NodeServer? = null
    private var discovery: Discovery? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backupMutex = Mutex()
    private val lastAuto = HashMap<String, Long>()
    private var closeLinkJob: Job? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state
    val codeRequest = MutableStateFlow<CodeRequest?>(null)
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices

    val isStarted: Boolean get() = ::node.isInitialized

    /** Starts the engine on the calling thread. Blocks for a few seconds; never call it on the UI thread. */
    @Synchronized
    fun start(dataDir: File) {
        if (isStarted) return
        this.dataDir = dataDir
        prefs = DesktopPrefs(File(dataDir, "desktop.properties"))
        node = Node(File(dataDir, "core"), defaultDeviceName(), DesktopPlatform())
        node.onChange = { bump() }
        node.codeConfirmer = CodeConfirmer { name, code -> askUser(name, code) }
        node.onIncomingProgress = { p -> _state.update { it.copy(activity = Activity.Serving(p.peerName, p)) } }
        node.onIncomingDone = { r -> onIncomingDone(r) }
        node.onShelfAdded = { added -> com.psphere.core.shelf.shelfNotice(added)?.let { (title, text) -> notify(Notice(title, text)) } }
        node.statusProvider = ::ownStatus
        node.onRing = { _, name, on -> onRingRequest(name, on) }

        val srv = runCatching { NodeServer(node, PREFERRED_PORT).also { it.start() } }
            .getOrElse { NodeServer(node, 0).also { it.start() } }
        server = srv
        discovery = Discovery(
            node.id,
            onFound = { d -> _state.update { it.copy(nearby = it.nearby + (d.id to d)) }; onDeviceSeen(d) },
            onLost = { id -> _state.update { it.copy(nearby = it.nearby - id) } },
            onNetworkReset = { _state.update { it.copy(nearby = emptyMap()) } },
        ).also { it.start(srv.localPort, node.deviceName) }

        com.psphere.desktop.smart.Smart.start(dataDir)
        scope.launch { scan() }
        // Once things have settled, look for anything that keeps devices from connecting here.
        scope.launch { delay(30_000); checkConnection() }
        scope.launch {
            while (isActive) {
                delay(PERIODIC_MS)
                if (onlineLinked().isNotEmpty()) backUp(quiet = true)
            }
        }
    }

    fun stop() {
        Ringer.stop()
        shelfHidden.value.takeIf { it.isNotEmpty() }?.let { runCatching { node.shelfRemove(it) } }
        runCatching { discovery?.stop() }
        runCatching { server?.stop() }
        runCatching { node.save() }
    }

    /** The computer's name, e.g. "DESKTOP-4KQ1", tidied; the user can change it on first start. */
    private fun defaultDeviceName(): String =
        DesktopSystem.computerName()?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Laptop"

    fun bump() = _state.update { it.copy(revision = it.revision + 1) }

    private fun notify(n: Notice) { _notices.tryEmit(n) }

    // ── Devices ──────────────────────────────────────────────────────────────

    fun linked(): List<LinkedDevice> = node.devices().sortedBy { it.name.lowercase() }

    fun isOnline(id: String) = _state.value.nearby[id]?.compatible == true

    /** Addresses of linked devices currently seen on the network. */
    fun onlineLinked(): Map<String, HostPort> =
        _state.value.nearby.values.filter { it.compatible && node.device(it.id) != null }.associate { it.id to HostPort(it.host, it.port) }

    fun searchAgain() = discovery?.restart()

    private fun onDeviceSeen(d: NearbyDevice) {
        val linked = node.device(d.id) ?: return
        if (!d.compatible) return
        node.noteAddress(d.id, HostPort(d.host, d.port))
        refreshStatus()
        val now = System.currentTimeMillis()
        synchronized(lastAuto) {
            if (now - (lastAuto[d.id] ?: 0) < AUTO_SYNC_GAP_MS) return
            lastAuto[d.id] = now
        }
        scope.launch {
            // Both devices notice each other at about the same time. The one with the larger ID
            // waits a moment and skips if the other already started a sync with it.
            if (node.id > d.id) {
                delay(4_000)
                if (System.currentTimeMillis() - (node.device(d.id)?.lastSyncAt ?: 0) < 30_000) return@launch
            }
            backUp(onlyDevice = linked.id, quiet = true)
        }
    }

    // ── Backing up ───────────────────────────────────────────────────────────

    suspend fun scan() = withContext(Dispatchers.IO) {
        _state.update { it.copy(activity = Activity.Scanning(0, 0)) }
        try {
            val r = node.scan { done, total -> _state.update { it.copy(activity = Activity.Scanning(done, total)) } }
            _state.update { it.copy(scanErrors = r.errors) }
        } catch (e: Exception) {
            _state.update { it.copy(scanErrors = listOf("Couldn't check your folders: ${e.message}")) }
        } finally {
            _state.update { it.copy(activity = Activity.Idle, revision = it.revision + 1) }
        }
    }

    /**
     * Scans this laptop's folders, then syncs with every linked device that can be reached
     * (or just [onlyDevice]). Devices not seen on the network are tried at their last address.
     */
    suspend fun backUp(onlyDevice: String? = null, quiet: Boolean = false): List<SyncResult> = withContext(Dispatchers.IO) {
        if (quiet && backupMutex.isLocked) return@withContext emptyList()
        backupMutex.withLock {
            scan()
            val targets = linked().filter { onlyDevice == null || it.id == onlyDevice }
            val online = onlineLinked()
            val results = ArrayList<SyncResult>()
            for (dev in targets) {
                val addresses = listOfNotNull(online[dev.id])
                // Don't keep retrying devices that aren't around during automatic runs.
                if (quiet && addresses.isEmpty()) continue
                _state.update { it.copy(activity = Activity.Syncing(dev.name, null)) }
                val r = node.syncWith(dev.id, addresses) { p -> _state.update { it.copy(activity = Activity.Syncing(dev.name, p)) } }
                results += r
                _state.update { it.copy(results = it.results + (dev.id to r)) }
                noticeFor(r)?.let(::notify)
            }
            _state.update { it.copy(activity = Activity.Idle, revision = it.revision + 1) }
            results
        }
    }

    private fun onIncomingDone(r: SyncResult) {
        _state.update {
            it.copy(results = it.results + (r.peerId to r), activity = if (it.activity is Activity.Serving) Activity.Idle else it.activity)
        }
        noticeFor(r)?.let(::notify)
    }

    /** Only syncs that moved files, or that went wrong, are worth interrupting someone for. */
    private fun noticeFor(r: SyncResult): Notice? = when {
        r.error != null -> null
        r.notes.any { "out of space" in it } -> Notice("Running out of space", r.notes.first { "out of space" in it }, warning = true)
        r.received > 0 && r.sent > 0 -> Notice("Backed up with ${r.peerName}", "Received ${filesText(r.received)}, sent ${filesText(r.sent)}.")
        r.received > 0 -> Notice("Backed up ${filesText(r.received)} from ${r.peerName}", "They're in ${node.backupFolder()?.displayName ?: "your backup location"}.")
        r.sent > 0 -> Notice("Sent ${filesText(r.sent)} to ${r.peerName}", "${r.peerName} is up to date.")
        else -> null
    }

    private fun filesText(n: Int) = if (n == 1) "1 file" else "%,d files".format(n)

    // ── Linking ──────────────────────────────────────────────────────────────

    /** Shows this device as linkable for 10 minutes and returns the QR code contents. */
    fun openLink(): PairingOffer {
        val offer = node.openPairing(Discovery.localAddresses().map { it.hostAddress }, server?.localPort ?: 0)
        discovery?.update(linkable = true)
        _state.update { it.copy(linkOffer = offer) }
        closeLinkJob?.cancel()
        closeLinkJob = scope.launch { delay(Node.PAIRING_WINDOW_MS); closeLink() }
        return offer
    }

    fun closeLink() {
        closeLinkJob?.cancel()
        node.closePairing()
        discovery?.update(linkable = false)
        _state.update { it.copy(linkOffer = null) }
    }

    suspend fun linkWithCode(device: NearbyDevice): LinkedDevice = withContext(Dispatchers.IO) {
        node.linkWithCode(HostPort(device.host, device.port), CodeConfirmer { name, code -> askUser(name, code) })
            .also { node.noteAddress(it.id, HostPort(device.host, device.port)); bump(); launchFirstSync(it) }
    }

    private fun launchFirstSync(d: LinkedDevice) {
        scope.launch { delay(1_000); backUp(onlyDevice = d.id, quiet = true) }
    }

    /** Blocks the calling (network) thread until the user compares the code, for up to 2 minutes. */
    private fun askUser(name: String, code: String): Boolean {
        val answer = CompletableFuture<Boolean>()
        codeRequest.value = CodeRequest(name, code, answer)
        notify(Notice("Link with $name?", "Open PSphere and check that $name shows the same code."))
        return try {
            answer.get(120, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        } finally {
            codeRequest.value = null
        }
    }

    // ── Shelf ────────────────────────────────────────────────────────────────

    /** Tells the other devices about a change on the Shelf right away, in the background. */
    fun shareShelf() { scope.launch { runCatching { node.shelfSync(onlineLinked()) } } }

    /** Items just removed on this device, hidden while the user can still undo. */
    val shelfHidden = MutableStateFlow<Set<String>>(emptySet())
    private var shelfRemoval: Job? = null
    private const val SHELF_UNDO_MS = 4_500L

    /**
     * Hides [ids] now and removes them on every device once the undo time is up, so an undo
     * never reaches the other devices. A newer removal makes the previous one final at once.
     */
    @Synchronized
    fun removeShelfLater(ids: Collection<String>) {
        val previous = shelfHidden.value
        shelfRemoval?.cancel()
        if (previous.isNotEmpty()) scope.launch { commitShelfRemoval(previous) }
        val now = ids.toSet()
        shelfHidden.value = now
        shelfRemoval = scope.launch { delay(SHELF_UNDO_MS); commitShelfRemoval(now) }
    }

    @Synchronized
    fun undoShelfRemoval() {
        shelfRemoval?.cancel()
        shelfRemoval = null
        shelfHidden.value = emptySet()
    }

    private fun commitShelfRemoval(ids: Set<String>) {
        node.shelfRemove(ids)
        shelfHidden.update { it - ids }
        shareShelf()
    }

    // ── Device status and ringing ────────────────────────────────────────────

    /** Battery, and free space where backups are kept (or in the home folder before one is chosen). */
    private fun ownStatus(): DeviceStatus {
        val b = Battery.read()
        val dir = node.backupFolder()?.displayName?.let(::File)?.takeIf { it.exists() } ?: File(System.getProperty("user.home"))
        val (main, others) = Drives.list(dir)
        return DeviceStatus(b?.percent ?: -1, b?.charging ?: false, main.freeBytes, main.totalBytes, driveName = main.name, otherDrives = others)
    }

    /** Asks the devices on this Wi-Fi for their battery and free space, in the background. */
    fun refreshStatus() { scope.launch { runCatching { node.statusSync(onlineLinked()) } } }

    /** The name of the device ringing this computer right now, or null. */
    val ringingFrom = MutableStateFlow<String?>(null)
    /** Devices this computer is ringing (shown as "Stop ringing"). */
    val ringingOut = MutableStateFlow<Set<String>>(emptySet())
    /** A short message about ringing a device ("Ringing Tablet…", "Tablet has ringing turned off"). */
    data class RingNote(val deviceId: String, val text: String, val problem: Boolean)
    private val _ringNotes = MutableSharedFlow<RingNote>(extraBufferCapacity = 4)
    val ringNotes: SharedFlow<RingNote> = _ringNotes
    private var ringTimeout: Job? = null
    private val ringOutTimeouts = HashMap<String, Job>()
    const val RING_MS = 60_000L

    private fun onRingRequest(name: String, on: Boolean): Boolean {
        if (!prefs.allowRing) return false
        if (on) startRinging(name) else stopRinging()
        return true
    }

    @Synchronized
    private fun startRinging(name: String) {
        ringingFrom.value = name
        Ringer.start()
        ringTimeout?.cancel()
        ringTimeout = scope.launch { delay(RING_MS); stopRinging() }
    }

    @Synchronized
    fun stopRinging() {
        ringTimeout?.cancel()
        Ringer.stop()
        ringingFrom.value = null
    }

    /** Starts or stops [deviceId] ringing. */
    fun ring(deviceId: String, on: Boolean = true) {
        val name = node.device(deviceId)?.name ?: "That device"
        if (on) ringingOut.update { it + deviceId } else ringingOut.update { it - deviceId }
        synchronized(ringOutTimeouts) {
            ringOutTimeouts.remove(deviceId)?.cancel()
            if (on) ringOutTimeouts[deviceId] = scope.launch { delay(RING_MS); ringingOut.update { it - deviceId } }
        }
        scope.launch {
            val r = node.ring(deviceId, on, onlineLinked())
            if (r != RingResult.OK) ringingOut.update { it - deviceId }
            val note = when (r) {
                RingResult.OK -> if (on) "Ringing $name…" else null
                RingResult.TURNED_OFF -> "$name has ringing turned off. You can turn it on in PSphere's Settings on $name."
                RingResult.NEEDS_UPDATE -> "Update PSphere on $name to ring it."
                RingResult.UNREACHABLE -> "Couldn't reach $name. PSphere needs to be running on it, on this Wi-Fi."
            }
            if (note != null) _ringNotes.tryEmit(RingNote(deviceId, note, r != RingResult.OK))
            else _ringNotes.tryEmit(RingNote(deviceId, "", false))
        }
    }

    // ── Connection check ─────────────────────────────────────────────────────

    /** The latest connection check, or null before the first one. */
    val connection = MutableStateFlow<ConnectionCheck.Result?>(null)
    val checkingConnection = MutableStateFlow(false)

    /** Runs the connection check in the background. */
    fun checkConnection() {
        if (!checkingConnection.compareAndSet(false, true)) return
        scope.launch {
            try { connection.value = runCatching { ConnectionCheck.run() }.getOrNull() ?: connection.value }
            finally { checkingConnection.value = false }
        }
    }

    /** Applies a fix (Windows asks for permission), then checks again. */
    fun applyFix(fix: ConnectionCheck.Fix, done: (Boolean) -> Unit) {
        scope.launch {
            val ok = ConnectionCheck.apply(fix)
            done(ok)
            if (ok) { searchAgain(); delay(3_000) }
            checkConnection()
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun rename(name: String) {
        node.rename(name)
        discovery?.update(displayName = node.deviceName)
        bump()
    }

    fun unlink(id: String) { node.unlink(id); bump() }
}
