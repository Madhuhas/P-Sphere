package com.psphere.sync

import android.content.Context
import android.util.Log
import com.psphere.PSphereApp
import com.psphere.data.TokenDataStore
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Runs all available sync engines for a given directory.
 *
 * Core sync (PSphere backend) is optional — it is skipped silently if:
 *   - no JWT is stored (user never logged in to a Core server), or
 *   - the Core server is unreachable.
 *
 * P2P sync runs independently and works with zero backend infrastructure
 * as long as at least one peer is on the same LAN.
 */
object SyncOrchestrator {

    data class OrchestratorResult(
        val coreUploaded: Int,
        val coreFailed: Int,
        val coreSkipped: Int,
        val p2pUploaded: Int,
        val p2pDownloaded: Int,
        val p2pPeers: Int,
    )

    suspend fun syncAll(
        context: Context,
        rootDir: File,
        onCoreProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OrchestratorResult {
        var coreUploaded = 0; var coreFailed = 0; var coreSkipped = 0

        // ── Core sync (optional) ──────────────────────────────────────────────
        try {
            val jwt = TokenDataStore(context).jwtToken.first()
            if (jwt != null) {
                Log.d(TAG, "Core sync starting...")
                val plan = SyncManager.buildSyncPlan(context, rootDir)
                val result = SyncManager.executePlan(context, plan, onCoreProgress)
                coreUploaded = result.uploaded
                coreFailed   = result.failed
                coreSkipped  = result.skipped
                Log.d(TAG, "Core sync done — ↑$coreUploaded, ✗$coreFailed, =$coreSkipped")
            } else {
                Log.d(TAG, "Core sync skipped — no JWT (Core server not configured)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Core sync failed — ${e.message}")
        }

        // ── P2P sync (always attempted) ───────────────────────────────────────
        var p2pUploaded = 0; var p2pDownloaded = 0; var p2pPeers = 0
        try {
            // Guard: PSphereApp.instance may not be set in test or early lifecycle
            val peerManager = if (PSphereApp.isInitialized) PSphereApp.instance.peerSyncManager else null
            if (peerManager != null) {
                val p2pResult = peerManager.syncWithPeers()
                p2pUploaded   = p2pResult.uploaded
                p2pDownloaded = p2pResult.downloaded
                p2pPeers      = p2pResult.peersFound
                // Record which files are now safely on a peer so "free up space" can delete them
                if (p2pResult.syncedFilePaths.isNotEmpty()) {
                    SyncedFilesTracker.record(context, p2pResult.syncedFilePaths)
                }
                Log.d(TAG, "P2P sync done — peers=$p2pPeers ↑$p2pUploaded ↓$p2pDownloaded")
            } else {
                Log.d(TAG, "P2P sync skipped — PeerSyncManager not yet initialized")
            }
        } catch (e: Exception) {
            Log.w(TAG, "P2P sync failed — ${e.message}")
        }

        return OrchestratorResult(
            coreUploaded  = coreUploaded,
            coreFailed    = coreFailed,
            coreSkipped   = coreSkipped,
            p2pUploaded   = p2pUploaded,
            p2pDownloaded = p2pDownloaded,
            p2pPeers      = p2pPeers,
        )
    }

    private const val TAG = "SyncOrchestrator"
}
