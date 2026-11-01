package com.psphere.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.psphere.core.model.RuleRec
import com.psphere.core.node.KeptGroup
import com.psphere.core.node.LinkedDevice
import com.psphere.desktop.engine.Activity
import com.psphere.desktop.engine.Engine
import kotlinx.coroutines.launch
import java.io.File

internal data class DeviceRow(
    val device: LinkedDevice,
    val online: Boolean,
    /** Files this laptop should still send to it. */
    val waiting: Int,
    /** At least one of this laptop's folders goes to it. */
    val isDestination: Boolean,
    val keptFiles: Int,
    val keptBytes: Long,
)

private data class Overview(
    val rows: List<DeviceRow> = emptyList(),
    val hasFolders: Boolean = true,
    val hasLocation: Boolean = true,
    val paused: Boolean = false,
    val keptFiles: Int = 0,
    val keptBytes: Long = 0,
    val loaded: Boolean = false,
)

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

@Composable
fun DevicesScreen(onOpenDevice: (String) -> Unit, onLink: () -> Unit, onOpenReceiving: () -> Unit) {
    val state by Engine.state.collectAsState()
    val overview by rememberEngineData(Overview()) {
        val node = Engine.node
        val rows = deviceRows()
        Overview(
            rows = rows,
            hasFolders = node.ownRules().isNotEmpty(),
            hasLocation = node.backupFolder() != null,
            paused = node.state.receivingPaused,
            keptFiles = rows.sumOf { it.keptFiles },
            keptBytes = rows.sumOf { it.keptBytes },
            loaded = true,
        )
    }

    Column {
        ScreenTitle(Engine.node.deviceName)
        if (overview.loaded && overview.rows.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Devices,
                title = "Link your phone or tablet",
                body = "Open PSphere on your phone or tablet, go to Link a device, and scan the code this laptop shows. Linked devices back up to each other on your Wi-Fi.",
            ) { Button(onClick = onLink) { Text("Show the code") } }
            return@Column
        }
        ScreenBody(spacing = 12.dp) {
            StatusCard(state.activity, overview, onBackUp = { Engine.scope.launch { Engine.backUp() } }, onOpenReceiving = onOpenReceiving)
            val notes = state.results.values.flatMap { r -> listOfNotNull(r.error) + r.notes }.distinct() + state.scanErrors
            // The status card is about this laptop's own folders; still say why nothing can come in.
            val noLocation = if (!overview.hasLocation && overview.hasFolders)
                listOf("Backups from your other devices can't be saved on this laptop until you choose a folder in Receiving.") else emptyList()
            NotesCard(noLocation + notes)

            SectionLabel("My devices")
            overview.rows.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { row -> DeviceCard(row, Modifier.weight(1f)) { onOpenDevice(row.device.id) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            OutlinedButton(onClick = onLink) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Link a device")
            }
        }
    }
}

@Composable
private fun StatusCard(activity: Activity, overview: Overview, onBackUp: () -> Unit, onOpenReceiving: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ok = PSphereTheme.status
    val busy = activity != Activity.Idle
    val waitingOffline = overview.rows.filter { it.waiting > 0 && !it.online }
    val waitingOnline = overview.rows.filter { it.waiting > 0 && it.online }

    data class Look(val title: String, val detail: String, val icon: ImageVector, val bg: Color, val fg: Color, val accent: Color = fg, val chooseLocation: Boolean = false)
    // Files waiting to go out come first: a laptop that only sends doesn't need a backup location.
    val look = when {
        busy -> {
            val title = when (activity) {
                is Activity.Serving -> "Backing up with ${activity.deviceName}"
                is Activity.Syncing -> "Backing up with ${activity.deviceName}"
                else -> "Backing up"
            }
            Look(title, describeActivity(activity).orEmpty(), Icons.Default.Sync, cs.primaryContainer, cs.onPrimaryContainer)
        }
        waitingOnline.isNotEmpty() -> Look("Ready to back up", waitingOnline.joinToString(" · ") { "${plural(it.waiting, "file")} for ${it.device.name}" }, Icons.Default.CloudUpload, cs.surfaceContainerHigh, cs.onSurface)
        waitingOffline.isNotEmpty() -> Look(
            "Waiting for ${waitingOffline.joinToString(", ") { it.device.name }}",
            waitingOffline.joinToString(" · ") { "${plural(it.waiting, "file")} for ${it.device.name}" } + ". Backs up when it's on this Wi-Fi.",
            Icons.Default.HourglassBottom, cs.surfaceContainerHigh, cs.onSurface,
        )
        // Only a laptop that sends nothing is held up by having nowhere to keep backups.
        !overview.hasLocation && !overview.hasFolders -> Look(
            "Choose where backups go",
            "Pick a folder on this laptop, such as D:\\PSphere Backups, before your devices can back up here.",
            Icons.Default.SaveAlt, cs.surfaceContainerLow, cs.onSurface, accent = ok.warning, chooseLocation = true,
        )
        overview.paused -> Look("Receiving is paused", "Your devices wait until you turn it back on in Receiving.", Icons.Default.PauseCircle, cs.surfaceContainerHigh, cs.onSurface)
        // A laptop that only keeps other devices' backups isn't asked to add folders of its own.
        !overview.hasFolders -> Look(
            "Ready to receive",
            if (overview.keptFiles > 0) "Keeping ${plural(overview.keptFiles, "file")} (${formatBytes(overview.keptBytes)}) from your devices" else "Your devices can back up here when they're on this Wi-Fi.",
            Icons.Default.CloudDownload, cs.surfaceContainerLow, cs.onSurface, accent = ok.success,
        )
        else -> Look("Everything is backed up", lastBackupText(overview.rows), Icons.Default.CheckCircle, cs.surfaceContainerLow, cs.onSurface, accent = ok.success)
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
                    IconBadge(look.icon, container = look.accent.copy(alpha = .12f), content = look.accent, size = 48.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(look.title, style = MaterialTheme.typography.titleLarge)
                    if (look.detail.isNotBlank()) Text(look.detail, style = MaterialTheme.typography.bodyMedium, color = look.fg.copy(alpha = .85f))
                }
                if (look.chooseLocation) {
                    Button(onClick = onOpenReceiving) { Text("Choose a folder") }
                } else {
                    Button(onClick = onBackUp, enabled = !busy) {
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Backing up…" else "Back up now")
                    }
                }
            }
            if (busy) {
                Spacer(Modifier.height(16.dp))
                val f = activityFraction(activity)
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = look.fg, trackColor = look.fg.copy(alpha = .15f))
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = look.fg, trackColor = look.fg.copy(alpha = .15f))
            }
        }
    }
}

private fun lastBackupText(rows: List<DeviceRow>): String {
    val last = rows.maxOfOrNull { it.device.lastSyncAt } ?: 0
    return if (last > 0) "Last backup ${timeAgo(last)}" else ""
}

@Composable
private fun DeviceCard(row: DeviceRow, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Default.Devices, container = cs.primaryContainer, content = cs.onPrimaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.device.name, style = MaterialTheme.typography.titleMedium)
                OnlineLine(row)
                if (row.keptFiles > 0) {
                    Text("Keeping: ${plural(row.keptFiles, "file")} · ${formatBytes(row.keptBytes)}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                if (row.isDestination) {
                    Text(
                        if (row.waiting > 0) "Sending: ${plural(row.waiting, "file")} waiting" else "Sending: up to date",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
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

// ── One device ───────────────────────────────────────────────────────────────

private data class Detail(
    val row: DeviceRow? = null,
    val sending: List<Pair<RuleRec, Int>> = emptyList(),
    val keeping: List<KeptGroup> = emptyList(),
    val location: String? = null,
)

@Composable
fun DeviceScreen(deviceId: String, onBack: () -> Unit) {
    val state by Engine.state.collectAsState()
    var confirmUnlink by remember { mutableStateOf(false) }
    val detail by rememberEngineData(Detail()) {
        val node = Engine.node
        Detail(
            row = deviceRows().firstOrNull { it.device.id == deviceId },
            sending = node.ownRules().map { it.first }.filter { deviceId in it.destinations }.map { it to node.pendingCount(deviceId, it.ruleId) },
            keeping = node.keptGroups().filter { it.origin == deviceId },
            location = node.backupFolder()?.displayName,
        )
    }
    val row = detail.row
    val name = row?.device?.name ?: Engine.node.device(deviceId)?.name ?: "Device"
    val cs = MaterialTheme.colorScheme

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink $name?") },
            text = { Text("This laptop stops backing up with $name and won't accept connections from it. Backups already saved here stay where they are. Unlink on $name too.") },
            confirmButton = {
                Button(onClick = { Engine.unlink(deviceId); confirmUnlink = false; onBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) { Text("Unlink") }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") } },
        )
    }

    Column {
        ScreenTitle(name, onBack = onBack)
        ScreenBody {
            if (row != null) OnlineLine(row)
            val busy = state.activity != Activity.Idle
            Button(
                onClick = { Engine.scope.launch { Engine.backUp(onlyDevice = deviceId) } },
                enabled = !busy,
                modifier = Modifier.padding(top = 8.dp).height(48.dp),
            ) {
                Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (busy) describeActivity(state.activity) ?: "Backing up…" else "Back up with $name now")
            }
            if (row != null && !row.online) {
                Text("Backs up by itself when $name is on this Wi-Fi with PSphere open.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            state.results[deviceId]?.let { r -> NotesCard(listOfNotNull(r.error) + r.notes) }

            SectionLabel("Keeping from $name")
            PanelCard {
                if (detail.keeping.isEmpty()) {
                    Text(
                        "Nothing from $name is stored here yet. Choose folders to send here in PSphere on $name.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                    )
                }
                detail.keeping.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                    val full = detail.location?.let { File(it, g.path.replace('/', File.separatorChar)).path }
                    FolderRow(Icons.Default.Folder, g.label, "${plural(g.files, "file")} · ${formatBytes(g.bytes)}${full?.let { " · $it" } ?: ""}") {
                        if (full != null) OpenFolderButton(full)
                    }
                }
            }

            SectionLabel("Sending to $name")
            PanelCard {
                if (detail.sending.isEmpty()) {
                    Text(
                        "None of this laptop's folders go to $name. Choose destinations in Sending.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                    )
                }
                detail.sending.forEachIndexed { i, (rule, waiting) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                    FolderRow(Icons.Default.FolderOpen, rule.label, if (waiting > 0) "${plural(waiting, "file")} waiting" else "Up to date")
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { confirmUnlink = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error)) {
                Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Unlink $name")
            }
        }
    }
}

@Composable
internal fun FolderRow(icon: ImageVector, title: String, detail: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}
