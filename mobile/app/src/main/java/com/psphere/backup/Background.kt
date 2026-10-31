package com.psphere.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.psphere.MainActivity
import com.psphere.R
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Small app settings that aren't part of the shared core. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("psphere", Context.MODE_PRIVATE)

    fun onboarded(ctx: Context) = p(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context) = p(ctx).edit().putBoolean("onboarded", true).apply()

    fun chargingOnly(ctx: Context) = p(ctx).getBoolean("charging_only", false)
    fun setChargingOnly(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("charging_only", v).apply(); BackupScheduler.schedule(ctx) }

    fun intervalHours(ctx: Context) = p(ctx).getInt("interval_hours", 6)
    fun setIntervalHours(ctx: Context, v: Int) { p(ctx).edit().putInt("interval_hours", v).apply(); BackupScheduler.schedule(ctx) }

    fun stayReady(ctx: Context) = p(ctx).getBoolean("stay_ready", false)
    fun setStayReady(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean("stay_ready", v).apply()
        if (v) ReadyService.start(ctx) else ReadyService.stop(ctx)
    }
}

object Notifications {
    const val CHANNEL = "backup"
    const val ID_READY = 2001
    const val ID_WORKER = 2002

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while PSphere backs up or is ready to receive backups"
            setShowBadge(false)
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun build(ctx: Context, title: String, text: String): Notification {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

/** Runs a backup every few hours on Wi-Fi, even when the app isn't open. */
object BackupScheduler {
    private const val WORK = "psphere-backup"

    fun schedule(ctx: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(Prefs.chargingOnly(ctx))
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(Prefs.intervalHours(ctx).toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Engine.start(applicationContext)
        runCatching { setForeground(getForegroundInfo()) }
        // Give discovery a moment to find the other devices on this Wi-Fi.
        delay(15_000)
        Engine.backUp()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifications.build(applicationContext, "Backing up", "Looking for your devices on this Wi-Fi")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_WORKER, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_WORKER, n)
        }
    }
}

/**
 * Keeps PSphere running so other devices can back up to this one at any time. Meant for a device
 * that stays at home (a tablet). Shows an ongoing notification, as Android requires.
 */
class ReadyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Runs on the main thread: show the notification now and start the engine in the background.
        val name = if (Engine.ready.value) Engine.node.deviceName else "this device"
        val n = Notifications.build(this, "Ready to back up", "Your other devices can back up to $name on this Wi-Fi")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifications.ID_READY, n, type)
        Engine.startInBackground(applicationContext)
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, ReadyService::class.java))
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ReadyService::class.java)) }
    }
}

/** Restarts "stay ready" after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.stayReady(context)) ReadyService.start(context)
    }
}
