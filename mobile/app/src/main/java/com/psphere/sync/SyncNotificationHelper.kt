package com.psphere.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object SyncNotificationHelper {

    private const val CHANNEL_ID   = "psphere_sync"
    private const val CHANNEL_NAME = "PSphere Sync"
    private const val NOTIF_SYNC_ID     = 1001
    private const val NOTIF_COMPLETE_ID = 1002

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Background photo sync progress"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun showSyncProgress(context: Context, done: Int, total: Int) {
        createChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PSphere — Syncing")
            .setContentText(if (total > 0) "$done / $total files" else "Starting…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_SYNC_ID, notif)
        }
    }

    fun showSyncComplete(context: Context, uploaded: Int, downloaded: Int, failed: Int) {
        createChannel(context)
        val msg = buildList {
            if (uploaded   > 0) add("↑$uploaded uploaded")
            if (downloaded > 0) add("↓$downloaded received")
            if (failed     > 0) add("$failed failed")
            if (isEmpty())      add("Already up to date")
        }.joinToString(" · ")

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("PSphere — Sync complete")
            .setContentText(msg)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIF_SYNC_ID)
            NotificationManagerCompat.from(context).notify(NOTIF_COMPLETE_ID, notif)
        }
    }

    fun showSyncError(context: Context, message: String) {
        createChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("PSphere — Sync failed")
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIF_SYNC_ID)
            NotificationManagerCompat.from(context).notify(NOTIF_COMPLETE_ID, notif)
        }
    }

    fun cancelProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_SYNC_ID) }
    }
}
