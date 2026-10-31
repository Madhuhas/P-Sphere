package com.psphere.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.psphere.backup.Activity
import com.psphere.backup.Engine
import com.psphere.core.node.LinkedDevice
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.EmptyState
import com.psphere.ui.components.IconBadge
import com.psphere.ui.components.NotesCard
import com.psphere.ui.components.SectionLabel
import com.psphere.ui.components.activityFraction
import com.psphere.ui.components.describeActivity
import com.psphere.ui.components.formatBytes
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData
import com.psphere.ui.components.timeAgo
import com.psphere.ui.theme.PSphereTheme
import kotlinx.coroutines.launch

internal data class DeviceRow(
    val device: LinkedDevice,
    val online: Boolean,
    /** Files this device should still send to it. */
    val waiting: Int,
    /** At least one of this device's folders goes to it. */
    val isDestination: Boolean,
    val keptFiles: Int,
    val keptBytes: Long,
)

private data class Overview(val rows: List<DeviceRow> = emptyList(), val hasFolders: Boolean = true, val loaded: Boolean = false)

internal fun deviceRows(): List<DeviceRow> {
    val node = Engine.node
    val rules = node.ownRules().map { it.first }
    val kept = node.keptGroups()
    return Engine.linked().map { d ->
        DeviceRow(
            device = d,
            online = Engine.isOnline(d.id),
            waiting = node.pendingCount(d.id),
            isDestination = rules.any { d.id in it.destinations },
            keptFiles = kept.filter { it.origin == d.id }.sumOf { it.files },
            keptBytes = kept.filter { it.origin == d.id }.sumOf { it.bytes },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onOpenDevice: (String) -> Unit, onLink: () -> Unit, onOpenSending: () -> Unit) {
    val state by Engine.state.collectAsState()
    val overview by rememberEngineData(Overview()) {
        Overview(deviceRows(), Engine.node.ownRules().isNotEmpty(), loaded = true)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(Engine.node.deviceName, fontWeight = FontWeight.SemiBold) }) }) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { Engine.searchAgain() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (overview.loaded && overview.rows.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Devices,
                    title = "Link your other devices",
                    body = "Open PSphere on your tablet or laptop, then link it here. Linked devices back up to each other on your Wi-Fi.",
                ) { Button(onClick = onLink) { Text("Link a device") } }
                return@PullToRefreshBox
            }
            ContentColumn(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusCard(state.activity, overview, onBackUp = { Engine.scope.launch { Engine.backUp() } }, onOpenSending = onOpenSending)

                    val notes = state.results.values.flatMap { r -> listOfNotNull(r.error) + r.notes }.distinct() + state.scanErrors
                    NotesCard(notes)

                    SectionLabel("My devices")
                    overview.rows.forEach { row -> DeviceCard(row) { onOpenDevice(row.device.id) } }
                    OutlinedButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Link a device")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(activity: Activity, overview: Overview, onBackUp: () -> Unit, onOpenSending: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ok = PSphereTheme.status
    val busy = activity != Activity.Idle
    val waitingOffline = overview.rows.filter { it.waiting > 0 && !it.online }
    val waitingOnline = overview.rows.filter { it.waiting > 0 && it.online }

    data class Look(val title: String, val detail: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val bg: Color, val fg: Color)
    val look = when {
        busy -> Look("Backing up", describeActivity(activity).orEmpty(), Icons.Default.Sync, cs.primaryContainer, cs.onPrimaryContainer)
        !overview.hasFolders -> Look("Choose what to back up", "Pick folders on this device and the devices they go to.", Icons.Default.FolderOpen, ok.warningContainer, ok.onWarningContainer)
        waitingOnline.isNotEmpty() -> Look("Ready to back up", waitingOnline.joinToString(" · ") { "${plural(it.waiting, "file")} for ${it.device.name}" }, Icons.Default.CloudUpload, cs.surfaceContainerHigh, cs.onSurface)
        waitingOffline.isNotEmpty() -> Look(
            "Waiting for ${waitingOffline.joinToString(", ") { it.device.name }}",
            waitingOffline.joinToString(" · ") { "${plural(it.waiting, "file")} for ${it.device.name}" } + ". Backs up when it's on this Wi-Fi.",
            Icons.Default.HourglassBottom, cs.surfaceContainerHigh, cs.onSurface,
        )
        else -> Look("Everything is backed up", lastBackupText(overview.rows), Icons.Default.CheckCircle, ok.successContainer, ok.onSuccessContainer)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = look.bg, contentColor = look.fg),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (busy) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = look.fg.copy(alpha = .08f), modifier = Modifier.size(48.dp)) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.5.dp, color = look.fg)
                    }
                } else {
                    IconBadge(look.icon, container = look.fg.copy(alpha = .10f), content = look.fg, size = 48.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(look.title, style = MaterialTheme.typography.titleLarge)
                    if (look.detail.isNotBlank()) Text(look.detail, style = MaterialTheme.typography.bodyMedium, color = look.fg.copy(alpha = .8f))
                }
            }
            if (busy) {
                Spacer(Modifier.height(16.dp))
                val f = activityFraction(activity)
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = look.fg, trackColor = look.fg.copy(alpha = .15f))
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = look.fg, trackColor = look.fg.copy(alpha = .15f))
            }
            Spacer(Modifier.height(18.dp))
            if (!overview.hasFolders && !busy) {
                Button(onClick = onOpenSending, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Choose folders", style = MaterialTheme.typography.titleSmall) }
            } else {
                Button(onClick = onBackUp, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Sync, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Backing up…" else "Back up now", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

private fun lastBackupText(rows: List<DeviceRow>): String {
    val last = rows.maxOfOrNull { it.device.lastSyncAt } ?: 0
    return if (last > 0) "Last backup ${timeAgo(last)}" else ""
}

@Composable
internal fun DeviceCard(row: DeviceRow, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Default.Devices, container = cs.primaryContainer, content = cs.onPrimaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.device.name, style = MaterialTheme.typography.titleMedium)
                OnlineLine(row)
                if (row.isDestination) {
                    Text(
                        if (row.waiting > 0) "Sending: ${plural(row.waiting, "file")} waiting" else "Sending: up to date",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                }
                if (row.keptFiles > 0) {
                    Text("Keeping: ${plural(row.keptFiles, "file")} · ${formatBytes(row.keptBytes)}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
internal fun OnlineLine(row: DeviceRow) {
    val text = when {
        row.online -> "On this Wi-Fi"
        row.device.lastSyncAt > 0 -> "Not nearby · last backup ${timeAgo(row.device.lastSyncAt)}"
        else -> "Not nearby · never backed up"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (row.online) PSphereTheme.status.success else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
