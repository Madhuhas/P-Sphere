package com.psphere.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Advertises this device on the LAN and discovers other PSphere peers using mDNS (Android NSD).
 * Android NsdManager only allows one concurrent resolve, so discovered services are serialised
 * through a queue and resolved one at a time.
 */
class PeerDiscovery(
    context: Context,
    private val onPeerFound: (PeerDevice) -> Unit,
    private val onPeerLost: (id: String) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val safeModel = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "-")
    private val serviceName = "PSphere-$safeModel"

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)

    fun startAdvertising(port: Int) {
        if (registrationListener != null) return
        val info = NsdServiceInfo().apply {
            setServiceName(serviceName)
            setServiceType(SERVICE_TYPE)
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.d(TAG, "Registered as ${i.serviceName}")
            }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Registration failed: $code")
                registrationListener = null
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {
                Log.d(TAG, "Unregistered")
            }
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Unregistration failed: $code")
            }
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service", e)
            registrationListener = null
        }
    }

    fun startDiscovery() {
        if (discoveryListener != null) return
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "Discovery started")
            }
            override fun onDiscoveryStopped(type: String) {
                Log.d(TAG, "Discovery stopped")
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.e(TAG, "Discovery start failed: $code")
                discoveryListener = null
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {
                Log.e(TAG, "Discovery stop failed: $code")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                // Use exact match to avoid false positives when device models are substrings
                // of each other (e.g. "Pixel" matches inside "Pixel-Pro").
                val isSelf = info.serviceName == serviceName
                if (!isSelf) enqueueResolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val id = info.serviceName.removePrefix("PSphere-")
                onPeerLost(id)
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            discoveryListener = null
        }
    }

    fun stop() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        resolveQueue.clear()
        isResolving.set(false)
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.offer(info)
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll() ?: run { isResolving.set(false); return }
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    Log.e(TAG, "Resolve failed for ${info.serviceName}: $code")
                    isResolving.set(false); drainResolveQueue()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val ip = info.host?.hostAddress ?: run {
                        isResolving.set(false); drainResolveQueue(); return
                    }
                    val peer = PeerDevice(
                        id = info.serviceName.removePrefix("PSphere-"),
                        name = info.serviceName,
                        ipAddress = ip,
                        port = info.port,
                    )
                    Log.d(TAG, "Resolved peer: ${peer.name} @ ${peer.ipAddress}:${peer.port}")
                    onPeerFound(peer)
                    isResolving.set(false); drainResolveQueue()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "resolveService threw", e)
            isResolving.set(false); drainResolveQueue()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_psphere._tcp."
        private const val TAG = "PeerDiscovery"
    }
}
