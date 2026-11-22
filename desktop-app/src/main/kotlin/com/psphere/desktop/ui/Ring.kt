package com.psphere.desktop.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.psphere.core.node.DeviceStatus
import com.psphere.desktop.engine.Engine

/** Battery and free space for one device; an older reading says when it's from. */
@Composable
internal fun DeviceStats(status: DeviceStatus?, online: Boolean, modifier: Modifier = Modifier) {
    if (status == null || (!status.hasBattery && !status.hasSpace)) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (status.hasBattery) {
                val low = status.battery <= 15 && !status.charging
                Stat(
                    if (status.charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    "${status.battery}%" + if (status.charging) " · charging" else "",
                    status.battery / 100f, low, Modifier.weight(1f),
                )
            }
            if (status.hasSpace) {
                val used = 1f - status.freeBytes.toFloat() / status.totalBytes
                Stat(Icons.Default.Storage, "${formatBytes(status.freeBytes)} free", used, used > .9f, Modifier.weight(1f))
            }
        }
        if (!online && status.at > 0) {
            Text("As of ${timeAgo(status.at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, text: String, fraction: Float, warning: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val tint = if (warning) PSphereTheme.status.warning else cs.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (warning) tint else cs.onSurface)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (warning) tint else cs.onSurfaceVariant,
            trackColor = cs.outlineVariant.copy(alpha = .5f),
            drawStopIndicator = {},
        )
    }
}

/** Shown while another device is ringing this computer. */
@Composable
fun RingingDialog(from: String?) {
    if (from == null) return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.width(380.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val pulse by rememberInfiniteTransition().animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
                Box(Modifier.size(84.dp).scale(pulse), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(84.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.NotificationsActive, null, modifier = Modifier.size(36.dp)) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("$from is looking for this computer", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("PSphere came to the front and is playing a sound.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { Engine.stopRinging() }, modifier = Modifier.height(48.dp).width(160.dp)) { Text("Stop") }
            }
        }
    }
}
