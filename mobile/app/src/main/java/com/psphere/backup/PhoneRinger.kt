package com.psphere.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.psphere.R
import com.psphere.core.node.DeviceStatus
import com.psphere.ui.RingActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** This phone's battery, and free space on its internal storage. */
fun phoneStatus(ctx: Context): DeviceStatus {
    val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val state = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
    val fs = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
    return DeviceStatus(percent, charging, fs?.availableBytes ?: -1, fs?.totalBytes ?: -1)
}

/**
 * Rings this phone when a linked device is looking for it: the alarm sound at full volume (the
 * alarm stream plays even on silent), vibration, and a full-screen "… is looking for this phone"
 * with a Stop button. Stops after a minute by itself.
 */
object PhoneRinger {
    private const val CHANNEL = "ring"
    private const val NOTIFICATION_ID = 2201
    private const val RING_MS = 60_000L

    private val _from = MutableStateFlow<String?>(null)
    /** The device ringing this phone, or null. */
    val from: StateFlow<String?> = _from

    private var player: MediaPlayer? = null
    private var previousVolume = -1
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val autoStop = Runnable { appContext?.let(::stop) }

    fun start(ctx: Context, deviceName: String) = main.post { startHere(ctx.applicationContext, deviceName) }

    fun stop(ctx: Context) = main.post { stopHere(ctx.applicationContext) }

    private fun startHere(ctx: Context, deviceName: String) {
        appContext = ctx
        _from.value = deviceName
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, RING_MS)
        if (player == null) playSound(ctx)
        vibrator(ctx)?.let { v ->
            val pattern = longArrayOf(0, 600, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else @Suppress("DEPRECATION") v.vibrate(pattern, 0)
        }
        showScreen(ctx, deviceName)
    }

    private fun stopHere(ctx: Context) {
        main.removeCallbacks(autoStop)
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        val audio = ctx.getSystemService(AudioManager::class.java)
        if (previousVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0) }
        previousVolume = -1
        vibrator(ctx)?.cancel()
        NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID)
        _from.value = null
    }

    private fun playSound(ctx: Context) {
        val audio = ctx.getSystemService(AudioManager::class.java)
        runCatching {
            previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    /**
     * Opens the ringing screen: straight away when PSphere is in front, otherwise through a
     * full-screen notification (shown over the lock screen, or as a banner while the phone is in use).
     */
    private fun showScreen(ctx: Context, deviceName: String) {
        val open = Intent(ctx, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Find this phone", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When one of your devices rings this phone"
                setSound(null, null) // PSphere plays the sound itself, at full volume.
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val full = PendingIntent.getActivity(ctx, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getBroadcast(ctx, 4, Intent(ctx, RingStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("$deviceName is looking for this phone")
            .setContentText("Tap Stop when you've found it.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(0, "Stop", stop)
            .setTimeoutAfter(RING_MS)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n) }
        runCatching { ctx.startActivity(open) }
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
}

/** The Stop button on the ringing notification. */
class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { PhoneRinger.stop(context) }
}
