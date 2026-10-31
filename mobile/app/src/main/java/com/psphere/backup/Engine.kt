package com.psphere.backup

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.psphere.core.node.CodeConfirmer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** What PSphere is busy with right now. */
sealed class Activity {
    object Idle : Activity()
    data class Scanning(val done: Int, val total: Int) : Activity()
    data class Syncing(val deviceName: String, val progress: SyncProgress?) : Activity()
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

/**
 * The app-wide backup engine: one [Node], its server, discovery, and automatic backups.
 * Lives as long as the process; screens observe [state].
 */
object Engine {
    private const val TAG = "Engine"
    /** Fixed so linked devices can reconnect at the remembered address; falls back to any free port. */
    private const val PREFERRED_PORT = 47_820
    private const val AUTO_SYNC_GAP_MS = 60_000L
    private const val PERIODIC_MS = 15 * 60_000L

    lateinit var node: Node
        private set
    private lateinit var app: Context
    private var server: NodeServer? = null
    private var discovery: Discovery? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backupMutex = Mutex()
    private val lastAuto = HashMap<String, Long>()
    private var closeLinkJob: Job? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state
    val codeRequest = MutableStateFlow<CodeRequest?>(null)

    val isStarted: Boolean get() = ::node.isInitialized

    /** True once [start] has finished. Screens read [node], so nothing is shown before this. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /**
     * Starts the engine without blocking the caller. Loading the catalog, creating the identity
     * and opening the server can take seconds, which on the main thread freezes the app.
     */
    fun startInBackground(context: Context) {
        scope.launch {
            // A failed start would otherwise leave the splash screen up forever; crash visibly instead.
            runCatching { start(context) }.onFailure { e -> Handler(Looper.getMainLooper()).post { throw e } }
        }
    }

    /** Starts the engine on the calling thread. Blocks; never call it on the main thread. */
    @Synchronized
    fun start(context: Context) {
        if (isStarted) return
        app = context.applicationContext
        node = Node(File(app.filesDir, "core"), defaultDeviceName(app), AndroidPlatform(app))
        node.onChange = { bump() }
        node.codeConfirmer = CodeConfirmer { name, code -> askUser(name, code) }

        val srv = runCatching { NodeServer(node, PREFERRED_PORT).also { it.start() } }
            .getOrElse { NodeServer(node, 0).also { it.start() } }
        server = srv
        discovery = Discovery(
            app, node.id,
            onFound = { d -> _state.update { it.copy(nearby = it.nearby + (d.id to d)) }; onDeviceSeen(d) },
            onLost = { id -> _state.update { it.copy(nearby = it.nearby - id) } },
            onNetworkReset = { _state.update { it.copy(nearby = emptyMap()) } },
        ).also { it.start(srv.localPort, node.deviceName) }

        scope.launch { scan() }
        scope.launch {
            while (isActive) {
                delay(PERIODIC_MS)
                if (onlineLinked().isNotEmpty()) backUp(quiet = true)
            }
        }
        _ready.value = true
    }

    private fun defaultDeviceName(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun bump() = _state.update { it.copy(revision = it.revision + 1) }

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
            Log.e(TAG, "Scan failed", e)
        } finally {
            _state.update { it.copy(activity = Activity.Idle, revision = it.revision + 1) }
        }
    }

    /**
     * Scans this device's folders, then syncs with every linked device that can be reached
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
            }
            _state.update { it.copy(activity = Activity.Idle, revision = it.revision + 1) }
            results
        }
    }

    // ── Linking ──────────────────────────────────────────────────────────────

    /** Shows this device as linkable for 10 minutes and returns the QR code contents. */
    fun openLink(): PairingOffer {
        val offer = node.openPairing(localAddresses(), server?.localPort ?: 0)
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

    suspend fun linkWithQr(contents: String): LinkedDevice = withContext(Dispatchers.IO) {
        node.linkWithQr(contents).also { bump(); launchFirstSync(it) }
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
        return try {
            answer.get(120, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        } finally {
            codeRequest.value = null
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun rename(name: String) {
        node.rename(name)
        discovery?.update(displayName = node.deviceName)
        bump()
    }

    fun unlink(id: String) { node.unlink(id); bump() }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress!! }
    }.getOrDefault(emptyList())
}
