package com.psphere.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.psphere.MainActivity
import com.psphere.R
import com.psphere.core.shelf.ShelfEntry
import com.psphere.core.shelf.shelfNotice

/** The notification shown when another device puts something on the Shelf. */
object ShelfNotify {
    private const val CHANNEL = "shelf"
    const val ID = 2101

    fun show(ctx: Context, added: List<ShelfEntry>) {
        val (title, text) = shelfNotice(added) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Shelf", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When another device puts something on the Shelf"
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SHELF)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        // One piece of text or a link: copy it straight from the notification.
        added.singleOrNull()?.item?.takeIf { !it.isFile }?.let { item ->
            val copy = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, ShelfCopyReceiver::class.java).putExtra(ShelfCopyReceiver.EXTRA_TEXT, item.text),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Copy", copy)
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, builder.build()) }
    }
}

/** The notification's Copy button. */
class ShelfCopyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PSphere", text))
        NotificationManagerCompat.from(ctx).cancel(ShelfNotify.ID)
        // Android 13 and later show their own "Copied" message.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    companion object { const val EXTRA_TEXT = "text" }
}
