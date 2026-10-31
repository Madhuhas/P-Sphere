package com.psphere.desktop.engine

import com.psphere.core.net.Wire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

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
 * Finds other PSphere devices on the same network with mDNS and advertises this one, using the
 * same service type and TXT attributes as the Android app.
 *
 * Runs one JmDNS per local network address: a laptop often has Wi-Fi plus virtual adapters
 * (Hyper-V, VPNs), and JmDNS only listens on the one address it is given. Checks every
 * [NETWORK_CHECK_S] seconds whether the addresses changed (Wi-Fi switched, laptop woke up)
 * and starts over if so.
 */
class Discovery(
    private val deviceId: String,
    private val onFound: (NearbyDevice) -> Unit,
    private val onLost: (id: String) -> Unit,
    private val onNetworkReset: () -> Unit,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "discovery").apply { isDaemon = true } }
    private val instances = LinkedHashMap<String, JmDNS>()
    private var port = 0
    @Volatile private var displayName = ""
    @Volatile private var linkable = false

    private val listener = object : ServiceListener {
        override fun serviceAdded(e: ServiceEvent) {
            val id = idOf(e.name) ?: return
            if (id == deviceId) return
            e.dns.requestServiceInfo(e.type, e.name, true, RESOLVE_TIMEOUT_MS)
        }
        override fun serviceRemoved(e: ServiceEvent) { idOf(e.name)?.let(onLost) }
        override fun serviceResolved(e: ServiceEvent) {
            val info = e.info
            val id = idOf(info.name) ?: return
            if (id == deviceId) return
            val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
            if (info.port <= 0) return
            onFound(NearbyDevice(
                id = id,
                name = info.getPropertyString("name")?.takeIf { it.isNotBlank() } ?: id,
                host = host,
                port = info.port,
                linkable = info.getPropertyString("link") == "1",
                compatible = info.getPropertyString("v") == Wire.VERSION.toString(),
            ))
        }
    }

    fun start(port: Int, displayName: String) {
        this.port = port
        this.displayName = displayName
        worker.execute { syncInstances() }
        worker.scheduleWithFixedDelay({ syncInstances() }, NETWORK_CHECK_S, NETWORK_CHECK_S, TimeUnit.SECONDS)
    }

    fun stop() {
        worker.execute { closeAll() }
        worker.shutdown()
        runCatching { worker.awaitTermination(3, TimeUnit.SECONDS) }
    }

    /** Re-announces with the new name or "linkable" flag. */
    fun update(displayName: String = this.displayName, linkable: Boolean = this.linkable) {
        if (displayName == this.displayName && linkable == this.linkable) return
        this.displayName = displayName
        this.linkable = linkable
        worker.execute {
            instances.values.forEach { dns ->
                runCatching { dns.unregisterAllServices() }
                runCatching { dns.registerService(serviceInfo()) }
            }
        }
    }

    /** Forget what was found and search again ("Search again", or after waking up). */
    fun restart() {
        worker.execute {
            closeAll()
            onNetworkReset()
            syncInstances()
        }
    }

    private fun serviceInfo(): ServiceInfo = ServiceInfo.create(
        SERVICE_TYPE, "$PREFIX$deviceId", port, 0, 0,
        mapOf("id" to deviceId, "name" to displayName.take(60), "v" to Wire.VERSION.toString(), "link" to if (linkable) "1" else "0"),
    )

    /** Opens a JmDNS for each new local address and closes the ones whose address went away. */
    private fun syncInstances() {
        val current = localAddresses()
        val gone = instances.keys - current.map { it.hostAddress }.toSet()
        if (gone.isNotEmpty()) {
            gone.forEach { addr -> instances.remove(addr)?.let { runCatching { it.close() } } }
            onNetworkReset()
        }
        for (addr in current) {
            if (addr.hostAddress in instances) continue
            runCatching {
                val dns = JmDNS.create(addr, "psphere-$deviceId")
                dns.addServiceListener(SERVICE_TYPE, listener)
                dns.registerService(serviceInfo())
                instances[addr.hostAddress] = dns
            }
        }
    }

    private fun closeAll() {
        instances.values.forEach { runCatching { it.unregisterAllServices(); it.close() } }
        instances.clear()
    }

    companion object {
        const val SERVICE_TYPE = "_psphere._tcp.local."
        private const val PREFIX = "PSphere-"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
        private const val NETWORK_CHECK_S = 30L

        fun idOf(serviceName: String): String? =
            serviceName.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringAfterLast('-')?.takeIf { it.isNotEmpty() }

        /** Private IPv4 addresses of network adapters that are up: home Wi-Fi and Ethernet. */
        fun localAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
        }.getOrDefault(emptyList())
    }
}
