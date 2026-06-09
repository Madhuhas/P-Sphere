package com.psphere.p2p

import android.content.Context
import android.os.Build
import android.util.Log
import com.psphere.sync.FileHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

data class PeerSyncResult(
    val peersFound: Int,
    val uploaded: Int,
    val downloaded: Int,
    val syncedFilePaths: List<String> = emptyList(),
)

/**
 * Top-level manager for phone-to-phone sync.
 * Starts an NSD advertiser + discoverer and a TCP server on app launch.
 * Call [syncWithPeers] from a WorkManager job to push/pull missing files.
 */
class PeerSyncManager(context: Context, private val syncDir: File) {

    private val localId = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "-")
    private val peers = ConcurrentHashMap<String, PeerDevice>()

    private val server = PeerServer(localId, syncDir)
    private val discovery = PeerDiscovery(
        context = context,
        onPeerFound = { peer ->
            Log.d(TAG, "Peer found: ${peer.name} @ ${peer.ipAddress}:${peer.port}")
            peers[peer.id] = peer
        },
        onPeerLost = { id ->
            Log.d(TAG, "Peer lost: $id")
            peers.remove(id)
        }
    )

    /** Start the TCP server and mDNS discovery. Call once on app create. */
    fun start() {
        server.start()
        discovery.startAdvertising(server.port)
        discovery.startDiscovery()
        Log.d(TAG, "P2P started — local device: $localId, server port: ${server.port}")
    }

    /** Stop all P2P activity. Call on app termination. */
    fun stop() {
        discovery.stop()
        server.stop()
    }

    /** Returns a snapshot of currently visible peers. */
    fun knownPeers(): List<PeerDevice> = peers.values.toList()

    /**
     * Scans local files then opens a sync session with every known peer.
     * Returns combined upload/download counts.
     */
    suspend fun syncWithPeers(): PeerSyncResult = withContext(Dispatchers.IO) {
        val currentPeers = peers.values.toList()
        if (currentPeers.isEmpty()) {
            Log.d(TAG, "No peers found, skipping P2P sync")
            return@withContext PeerSyncResult(0, 0, 0)
        }

        val localFiles = FileHasher.scanDirectory(syncDir)
        var totalUploaded = 0
        var totalDownloaded = 0
        val allSyncedPaths = mutableListOf<String>()

        for (peer in currentPeers) {
            try {
                Log.d(TAG, "Syncing with ${peer.name}...")
                val socket = Socket(peer.ipAddress, peer.port)
                val session = PeerSession(
                    socket        = socket,
                    localDeviceId = localId,
                    localFiles    = localFiles,
                    receiveDir    = File(syncDir, "p2p_received"),
                    isServer      = false,
                )
                session.run()
                totalUploaded   += session.uploaded
                totalDownloaded += session.downloaded
                allSyncedPaths  += session.uploadedPaths
                Log.d(TAG, "Synced with ${peer.name}: ↑${session.uploaded} ↓${session.downloaded}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync with ${peer.name}", e)
            }
        }

        PeerSyncResult(
            peersFound      = currentPeers.size,
            uploaded        = totalUploaded,
            downloaded      = totalDownloaded,
            syncedFilePaths = allSyncedPaths,
        )
    }

    companion object { private const val TAG = "PeerSyncManager" }
}
