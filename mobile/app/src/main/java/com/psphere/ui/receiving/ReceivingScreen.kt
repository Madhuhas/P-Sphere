package com.psphere.ui.receiving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.psphere.backup.Engine
import com.psphere.core.node.KeptGroup
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.SectionLabel
import com.psphere.ui.components.formatBytes
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData
import com.psphere.ui.components.rememberFolderPicker
import com.psphere.ui.theme.PSphereTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class Receiving(
    val location: String? = null,
    val freeBytes: Long = 0,
    val reserveBytes: Long = 0,
    val paused: Boolean = false,
    val groups: List<KeptGroup> = emptyList(),
    val previewBytes: Long = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivingScreen() {
    val scope = rememberCoroutineScope()
    val data by rememberEngineData(Receiving()) {
        val node = Engine.node
        val folder = node.backupFolder()
        Receiving(
            location = folder?.displayName,
            freeBytes = folder?.freeSpace() ?: 0,
            reserveBytes = node.state.reserveBytes,
            paused = node.state.receivingPaused,
            groups = node.keptGroups(),
            previewBytes = node.previews.totalBytes(),
        )
    }
    val pick = rememberFolderPicker { uri ->
        scope.launch(Dispatchers.IO) { Engine.node.setBackupLocation(uri.toString()); Engine.bump() }
    }
    val cs = MaterialTheme.colorScheme

    Scaffold(topBar = { TopAppBar(title = { Text("Receiving") }) }) { padding ->
        ContentColumn(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Backups your other devices send to ${Engine.node.deviceName}.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )

                SectionLabel("Backup location")
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.SaveAlt, null, tint = cs.primary)
                            Column(Modifier.weight(1f)) {
                                Text(data.location ?: "Not chosen", style = MaterialTheme.typography.bodyLarge, color = if (data.location == null) cs.error else cs.onSurface)
                                Text(
                                    if (data.location == null) "Choose a folder, such as Pictures/PSphere, before other devices can back up here."
                                    else "Inside it, each device gets its own folder, like Phone/Camera.",
                                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
                            Text(if (data.location == null) "Choose folder" else "Change folder")
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
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                        val low = data.freeBytes < data.reserveBytes * 2
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.Storage, null, tint = if (low) cs.error else cs.primary)
                            Column(Modifier.weight(1f)) {
                                Text("${formatBytes(data.freeBytes)} free", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (low) "Nearly full. PSphere stops receiving when ${formatBytes(data.reserveBytes)} is left."
                                    else "PSphere always leaves ${formatBytes(data.reserveBytes)} free. Previews use ${formatBytes(data.previewBytes)}.",
                                    style = MaterialTheme.typography.bodyMedium, color = if (low) cs.error else cs.onSurfaceVariant,
                                )
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
                                Text("Other devices wait until you turn this off.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            }
                            Switch(checked = data.paused, onCheckedChange = null)
                        }
                    }
                }

                SectionLabel("Kept here")
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    if (data.groups.isEmpty()) {
                        Text("Nothing yet. Backups appear here once another device sends folders to ${Engine.node.deviceName}.",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                    data.groups.groupBy { it.originName }.entries.forEachIndexed { i, (device, groups) ->
                        if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                        Text("From $device", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 14.dp))
                        groups.forEach { g ->
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(Icons.Default.Folder, null, tint = cs.primary, modifier = Modifier.size(22.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(g.label, style = MaterialTheme.typography.bodyLarge)
                                    Text("${plural(g.files, "file")} · ${formatBytes(g.bytes)} · ${g.path}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (data.paused) {
                    Text("Receiving is paused.", style = MaterialTheme.typography.bodyMedium, color = PSphereTheme.status.warning)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
