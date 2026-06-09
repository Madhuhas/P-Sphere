package com.psphere.sync

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class SyncWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ROOT_DIR       = "root_dir"
        const val KEY_CORE_UPLOADED  = "core_uploaded"
        const val KEY_CORE_FAILED    = "core_failed"
        const val KEY_CORE_SKIPPED   = "core_skipped"
        const val KEY_P2P_UPLOADED   = "p2p_uploaded"
        const val KEY_P2P_DOWNLOADED = "p2p_downloaded"
        const val KEY_P2P_PEERS      = "p2p_peers"
        const val PROGRESS_DONE      = "progress_done"
        const val PROGRESS_TOTAL     = "progress_total"
    }

    override suspend fun doWork(): Result {
        val rootPath = inputData.getString(KEY_ROOT_DIR)
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath

        val rootDir = java.io.File(rootPath)
        if (!rootDir.exists()) return Result.failure(
            workDataOf("error" to "Sync directory not found: $rootPath")
        )

        return try {
            val result = SyncOrchestrator.syncAll(
                context = context,
                rootDir = rootDir,
                onCoreProgress = { done, total ->
                    setProgressAsync(workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total))
                    SyncNotificationHelper.showSyncProgress(context, done, total)
                },
            )

            FileHasher.clearCache()

            val allFailed = result.coreUploaded == 0 && result.p2pUploaded == 0
                         && result.p2pDownloaded == 0 && result.coreFailed > 0

            if (allFailed && runAttemptCount < 3) {
                SyncNotificationHelper.cancelProgress(context)
                Result.retry()
            } else {
                SyncNotificationHelper.showSyncComplete(
                    context    = context,
                    uploaded   = result.coreUploaded + result.p2pUploaded,
                    downloaded = result.p2pDownloaded,
                    failed     = result.coreFailed,
                )
                Result.success(
                    workDataOf(
                        KEY_CORE_UPLOADED  to result.coreUploaded,
                        KEY_CORE_FAILED    to result.coreFailed,
                        KEY_CORE_SKIPPED   to result.coreSkipped,
                        KEY_P2P_UPLOADED   to result.p2pUploaded,
                        KEY_P2P_DOWNLOADED to result.p2pDownloaded,
                        KEY_P2P_PEERS      to result.p2pPeers,
                    )
                )
            }
        } catch (e: Exception) {
            FileHasher.clearCache()
            if (runAttemptCount < 3) {
                SyncNotificationHelper.cancelProgress(context)
                Result.retry()
            } else {
                SyncNotificationHelper.showSyncError(context, e.message ?: "Unknown error")
                Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
            }
        }
    }
}
