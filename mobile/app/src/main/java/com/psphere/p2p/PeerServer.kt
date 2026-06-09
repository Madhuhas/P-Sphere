package com.psphere.p2p

import android.util.Log
import com.psphere.sync.FileHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket

/**
 * TCP server that accepts incoming P2P sync connections from peers.
 * Each accepted connection runs a [PeerSession] in server role on a separate coroutine.
 * Binds to port 0 so the OS picks a free port; call [port] after [start] to read it.
 *
 * File scan is cached for [SCAN_CACHE_TTL_MS] so multiple simultaneous connections
 * don't all trigger independent full directory scans.
 */
class PeerServer(
    private val localDeviceId: String,
    private val syncDir: File,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    var port: Int = 0
        private set

    // ── Cached file scan ──────────────────────────────────────────────────────
    private var cachedFiles: List<FileHasher.FileEntry> = emptyList()
    private var cacheTimestamp: Long = 0L
    private val scanLock = Any()

    private fun getLocalFiles(): List<FileHasher.FileEntry> = synchronized(scanLock) {
        val now = System.currentTimeMillis()
        if (now - cacheTimestamp > SCAN_CACHE_TTL_MS || cachedFiles.isEmpty()) {
            cachedFiles = FileHasher.scanDirectory(syncDir)
            cacheTimestamp = now
            Log.d(TAG, "File scan refreshed: ${cachedFiles.size} files")
        }
        cachedFiles
    }

    fun invalidateCache() = synchronized(scanLock) { cacheTimestamp = 0L }

    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket(0)
        serverSocket = ss
        port = ss.localPort
        Log.d(TAG, "P2P server listening on port $port")

        scope.launch {
            while (isActive) {
                try {
                    val clientSocket = ss.accept()
                    launch {
                        PeerSession(
                            socket        = clientSocket,
                            localDeviceId = localDeviceId,
                            localFiles    = getLocalFiles(),
                            receiveDir    = File(syncDir, "p2p_received"),
                            isServer      = true,
                        ).run()
                        invalidateCache() // new files received — invalidate so next session rescans
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Accept error", e)
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        private const val TAG = "PeerServer"
        private const val SCAN_CACHE_TTL_MS = 60_000L // 1 minute
    }
}
