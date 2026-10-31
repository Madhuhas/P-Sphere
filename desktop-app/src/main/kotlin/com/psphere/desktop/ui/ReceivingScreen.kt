package com.psphere.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.psphere.core.node.KeptGroup
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.WindowsSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private data class Receiving(
    val location: String? = null,
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
    val reserveBytes: Long = 0,
    val paused: Boolean = false,
    val groups: List<KeptGroup> = emptyList(),
    val previewBytes: Long = 0,
)

@Composable
fun ReceivingScreen() {
    val scope = rememberCoroutineScope()
    val data by rememberEngineData(Receiving()) {
        val node = Engine.node
        val folder = node.backupFolder()
        Receiving(
            location = folder?.displayName,
            freeBytes = folder?.freeSpace() ?: 0,
            totalBytes = folder?.displayName?.let { File(it).totalSpace } ?: 0,
            reserveBytes = node.state.reserveBytes,
            paused = node.state.receivingPaused,
            groups = node.keptGroups(),
            previewBytes = node.previews.totalBytes(),
        )
    }
    var pickError by remember { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme

    fun pick() {
        val path = pickFolder("Choose where backups go", data.location) ?: return
        // Backups saved inside a folder this laptop sends would be sent back out to other devices.
        val clash = Engine.node.ownRules().firstOrNull { (_, local) -> WindowsSystem.overlaps(path, local.location) }
        if (clash != null) {
            pickError = "You back up “${clash.first.label}” from this folder, so backups can't be saved here. Choose a different folder."
            return
        }
        pickError = null
        scope.launch(Dispatchers.IO) { Engine.node.setBackupLocation(path); Engine.bump() }
    }

    Column {
        ScreenTitle("Receiving")
        ScreenBody {
            Text("Backups your other devices send to ${Engine.node.deviceName}.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)

            SectionLabel("Backup location")
            PanelCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.SaveAlt, null, tint = cs.primary)
                        Column(Modifier.weight(1f)) {
                            if (data.location == null) Text("Not chosen", style = MaterialTheme.typography.bodyLarge, color = cs.error)
                            else Text(data.location!!, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            Text(
                                if (data.location == null) "Choose a folder or drive, such as D:\\PSphere Backups, before your devices can back up here."
                                else "Inside it, each device gets its own folder, like Phone\\Camera.",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    pickError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::pick) { Text(if (data.location == null) "Choose folder…" else "Change…") }
                        data.location?.let { OpenFolderButton(it) }
                    }
                    if (data.location != null) {
                        Text(
                            "Changing it doesn't move backups already saved. New backups go to the new folder.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                }
            }

            if (data.location != null) {
                SectionLabel("Space")
                PanelCard {
                    val low = data.freeBytes < data.reserveBytes * 2
                    val drive = data.location!!.take(2).takeIf { it.endsWith(":") }
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.Storage, null, tint = if (low) cs.error else cs.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column {
                                Text("${formatBytes(data.freeBytes)} free${drive?.let { " on drive $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (low) "Nearly full. PSphere stops receiving when ${formatBytes(data.reserveBytes)} is left."
                                    else "PSphere always leaves ${formatBytes(data.reserveBytes)} free. Previews use ${formatBytes(data.previewBytes)}.",
                                    style = MaterialTheme.typography.bodyMedium, color = if (low) cs.error else cs.onSurfaceVariant,
                                )
                            }
                            if (data.totalBytes > 0) {
                                val used = 1f - data.freeBytes.toFloat() / data.totalBytes
                                LinearProgressIndicator(
                                    progress = { used.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                                    color = if (low) cs.error else cs.primary, trackColor = cs.surfaceContainerHighest,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(data.paused, role = Role.Switch) { v -> scope.launch(Dispatchers.IO) { Engine.node.setReceivingPaused(v); Engine.bump() } }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Pause receiving", style = MaterialTheme.typography.bodyLarge)
                            Text("Your devices wait until you turn this off.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                        Switch(checked = data.paused, onCheckedChange = null)
                    }
                }
            }

            SectionLabel("Kept here")
            PanelCard {
                if (data.groups.isEmpty()) {
                    Text(
                        "Nothing yet. Backups appear here once another device sends folders to ${Engine.node.deviceName}.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                    )
                }
                data.groups.groupBy { it.originName }.entries.forEachIndexed { i, (device, groups) ->
                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                    Text("From $device", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 14.dp))
                    groups.forEach { g ->
                        val full = data.location?.let { File(it, g.path.replace('/', File.separatorChar)).path }
                        FolderRow(Icons.Default.Folder, g.label, "${plural(g.files, "file")} · ${formatBytes(g.bytes)}") {
                            if (full != null) OpenFolderButton(full)
                        }
                    }
                }
            }
            if (data.paused) {
                Text("Receiving is paused.", style = MaterialTheme.typography.bodyMedium, color = PSphereTheme.status.warning)
            }
        }
    }
}
