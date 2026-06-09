package com.psphere.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.psphere.data.TokenDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val ONE_TIME_TAG = "psphere_sync_once"
    private const val PERIODIC_TAG = "psphere_sync_periodic"

    private suspend fun constraints(context: Context): Constraints {
        val store       = TokenDataStore(context)
        val wifiOnly    = store.wifiOnly.first()
        val chargingOnly = store.chargingOnly.first()
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(chargingOnly)
            .build()
    }

    suspend fun syncNow(context: Context, rootDir: String? = null) {
        val data = if (rootDir != null) workDataOf(SyncWorker.KEY_ROOT_DIR to rootDir) else workDataOf()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(data)
            .addTag(ONE_TIME_TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME_TAG, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun schedulePeriodic(context: Context, intervalHours: Long = 6) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(PERIODIC_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_TAG,
            ExistingPeriodicWorkPolicy.UPDATE,   // UPDATE re-applies new constraints
            request
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_TAG)
    }

    fun syncState(context: Context): Flow<SyncWorkerState> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(ONE_TIME_TAG)
            .map { infos ->
                val info = infos.firstOrNull()
                when (info?.state) {
                    WorkInfo.State.RUNNING   -> SyncWorkerState.Running(
                        done  = info.progress.getInt(SyncWorker.PROGRESS_DONE,  0),
                        total = info.progress.getInt(SyncWorker.PROGRESS_TOTAL, 0),
                    )
                    WorkInfo.State.SUCCEEDED -> SyncWorkerState.Succeeded(
                        coreUploaded   = info.outputData.getInt(SyncWorker.KEY_CORE_UPLOADED,   0),
                        coreFailed     = info.outputData.getInt(SyncWorker.KEY_CORE_FAILED,     0),
                        coreSkipped    = info.outputData.getInt(SyncWorker.KEY_CORE_SKIPPED,    0),
                        p2pUploaded    = info.outputData.getInt(SyncWorker.KEY_P2P_UPLOADED,    0),
                        p2pDownloaded  = info.outputData.getInt(SyncWorker.KEY_P2P_DOWNLOADED,  0),
                        p2pPeers       = info.outputData.getInt(SyncWorker.KEY_P2P_PEERS,       0),
                    )
                    WorkInfo.State.FAILED    -> SyncWorkerState.Failed(
                        message = info?.outputData?.getString("error") ?: "Sync failed"
                    )
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED   -> SyncWorkerState.Queued
                    else                     -> SyncWorkerState.Idle
                }
            }
}

sealed class SyncWorkerState {
    object Idle   : SyncWorkerState()
    object Queued : SyncWorkerState()
    data class Failed(val message: String = "Sync failed") : SyncWorkerState()

    data class Running(val done: Int, val total: Int) : SyncWorkerState()

    data class Succeeded(
        val coreUploaded:  Int,
        val coreFailed:    Int,
        val coreSkipped:   Int,
        val p2pUploaded:   Int,
        val p2pDownloaded: Int,
        val p2pPeers:      Int,
    ) : SyncWorkerState()
}
