package com.psphere.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.psphere.backup.Engine
import com.psphere.core.node.DeviceStatus
import com.psphere.ui.components.formatBytes
import com.psphere.ui.components.timeAgo
import com.psphere.ui.theme.PSphereTheme

/** Battery and free space for one device; an older reading says when it's from. */
@Composable
internal fun DeviceStats(status: DeviceStatus?, online: Boolean, modifier: Modifier = Modifier) {
    if (status == null || (!status.hasBattery && !status.hasSpace)) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (status.hasBattery) {
                Stat(
                    if (status.charging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    "${status.battery}%" + if (status.charging) " · charging" else "",
                    status.battery / 100f, status.battery <= 15 && !status.charging, Modifier.weight(1f),
                )
            }
            if (status.hasSpace) {
                val used = 1f - status.freeBytes.toFloat() / status.totalBytes
                val drive = if (status.driveName.isNotEmpty()) "${status.driveName} " else ""
                Stat(Icons.Default.Storage, "$drive${formatBytes(status.freeBytes)} free", used, used > .9f, Modifier.weight(1f))
            }
        }
        if (status.otherDrives.isNotEmpty()) {
            Text(
                "Other drives: " + status.otherDrives.joinToString(" · ") { "${it.name} ${formatBytes(it.freeBytes)} free" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (warning) tint else cs.onSurface, maxLines = 1)
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

@Composable
internal fun RingButton(deviceId: String, online: Boolean, ringing: Boolean, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = { Engine.ring(deviceId, on = !ringing) }, enabled = online || ringing, modifier = modifier) {
        Icon(if (ringing) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (ringing) "Stop ringing" else "Ring")
    }
}

@Composable
internal fun RingNoteText(note: Engine.RingNote?, modifier: Modifier = Modifier) {
    if (note == null || note.text.isEmpty()) return
    Text(
        note.text, style = MaterialTheme.typography.bodySmall, modifier = modifier,
        color = if (note.problem) PSphereTheme.status.warning else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
