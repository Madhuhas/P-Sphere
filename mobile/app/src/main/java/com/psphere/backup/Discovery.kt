package com.psphere.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.psphere.core.net.Wire
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A PSphere device seen on the local network. Linked or not: linking needs to see unlinked ones. */
data class NearbyDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** It is showing "Link a device" right now. */
    val linkable: Boolean,
    /** It speaks the same protocol version. */
    val compatible: Boolean,
)

/**
 * Finds other PSphere devices on the same Wi-Fi with mDNS (NSD) and advertises this one.
 *
 * Restarts itself when Wi-Fi drops or comes back, because Android silently stops delivering
 * NSD results after a network change and devices found on the old network are no longer valid.
 */
class Discovery(
    context: Context,
    private val deviceId: String,
    private val onFound: (NearbyDevice) -> Unit,
    private val onLost: (id: String) -> Unit,
    private val onNetworkReset: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())

    private val serviceName = "$PREFIX$deviceId"

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var port = 0
    @Volatile private var displayName = ""
    @Volatile private var linkable = false
    @Volatile private var running = false

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)

    fun start(port: Int, displayName: String) {
        this.port = port
        this.displayName = displayName
        running = true
        acquireMulticastLock()
        startAdvertising()
        startDiscovery()
        watchNetwork()
    }

    fun stop() {
        running = false
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        stopNsd()
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    /** Re-announces with the new name or "linkable" flag. */
    fun update(displayName: String = this.displayName, linkable: Boolean = this.linkable) {
        if (displayName == this.displayName && linkable == this.linkable) return
        this.displayName = displayName
        this.linkable = linkable
        handler.post {
            if (!running) return@post
            registrationListener?.let { runCatching { nsd.unregisterService(it) } }
            registrationListener = null
            handler.postDelayed({ startAdvertising() }, 300)
        }
    }

    /** Re-announce and search again, e.g. after the Wi-Fi network changed or "Search again". */
    fun restart() {
        if (!running) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, RESTART_DEBOUNCE_MS)
    }

    private val restartRunnable = Runnable {
        if (!running) return@Runnable
        Log.d(TAG, "Restarting discovery")
        stopNsd()
        onNetworkReset()
        startAdvertising()
        startDiscovery()
    }

    private fun stopNsd() {
        handler.removeCallbacksAndMessages(null)
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        resolveQueue.clear()
        isResolving.set(false)
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("psphere-mdns").apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.w(TAG, "Could not acquire multicast lock", it) }
    }

    private fun watchNetwork() {
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Also fires once right after registering; the restart is debounced, so that's harmless
            // and covers the case where the app started before Wi-Fi was connected.
            override fun onAvailable(network: Network) { restart() }
            override fun onLost(network: Network) { handler.post { onNetworkReset() } }
        }
        runCatching { connectivity.registerNetworkCallback(request, cb); networkCallback = cb }
            .onFailure { Log.w(TAG, "Could not watch network changes", it) }
    }

    private fun startAdvertising() {
        if (registrationListener != null || port == 0 || !running) return
        // Qualify the name: inside apply{}, a bare `serviceName` is NsdServiceInfo's own (null)
        // property, which made registration fail with "service name is missing".
        val name = this.serviceName
        val info = NsdServiceInfo().apply {
            setServiceName(name)
            setServiceType(SERVICE_TYPE)
            setPort(this@Discovery.port)
            runCatching {
                setAttribute("id", deviceId)
                setAttribute("name", displayName.take(60))
                setAttribute("v", Wire.VERSION.toString())
                setAttribute("link", if (linkable) "1" else "0")
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.d(TAG, "Registered as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Registration failed: $code")
                registrationListener = null
                if (running) handler.postDelayed({ startAdvertising() }, RETRY_MS)
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
        }
        registrationListener = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service", e)
            registrationListener = null
        }
    }

    private fun startDiscovery() {
        if (discoveryListener != null || !running) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.e(TAG, "Discovery start failed: $code")
                discoveryListener = null
                if (running) handler.postDelayed({ startDiscovery() }, RETRY_MS)
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                val id = idOf(info.serviceName) ?: return
                if (id == deviceId) return
                enqueueResolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                idOf(info.serviceName)?.let(onLost)
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            discoveryListener = null
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.offer(info)
        drainResolveQueue()
    }

    // NsdManager can only resolve one service at a time on older Android versions, so queue them.
    @Suppress("DEPRECATION")
    private fun drainResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll() ?: run { isResolving.set(false); return }

        val timeout = Runnable {
            Log.w(TAG, "Resolve timed out for ${next.serviceName}")
            isResolving.set(false)
            drainResolveQueue()
        }
        handler.postDelayed(timeout, RESOLVE_TIMEOUT_MS)

        fun done() { handler.removeCallbacks(timeout); isResolving.set(false); drainResolveQueue() }

        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    // FAILURE_ALREADY_ACTIVE: try this one again shortly instead of dropping it.
                    if (code == NsdManager.FAILURE_ALREADY_ACTIVE) handler.postDelayed({ enqueueResolve(next) }, 1_000)
                    done()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    val id = idOf(info.serviceName)
                    if (host != null && id != null && info.port > 0) {
                        val attrs = runCatching { info.attributes }.getOrNull().orEmpty()
                        fun attr(k: String) = attrs[k]?.toString(Charsets.UTF_8)
                        onFound(NearbyDevice(
                            id = id,
                            name = attr("name")?.takeIf { it.isNotBlank() } ?: id,
                            host = host,
                            port = info.port,
                            linkable = attr("link") == "1",
                            compatible = attr("v") == Wire.VERSION.toString(),
                        ))
                    }
                    done()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "resolveService threw", e)
            done()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_psphere._tcp."
        private const val PREFIX = "PSphere-"
        private const val TAG = "Discovery"
        private const val RESOLVE_TIMEOUT_MS = 15_000L
        private const val RESTART_DEBOUNCE_MS = 1_500L
        private const val RETRY_MS = 10_000L

        /** Older (1.x) devices used "PSphere-<model>-<id>"; they're reported as incompatible via the TXT version. */
        fun idOf(serviceName: String): String? =
            serviceName.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringAfterLast('-')?.takeIf { it.isNotEmpty() }
    }
}
