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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.psphere.backup.Activity
import com.psphere.backup.Engine
import com.psphere.core.node.KeptGroup
import com.psphere.core.model.RuleRec
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.NotesCard
import com.psphere.ui.components.SectionLabel
import com.psphere.ui.components.describeActivity
import com.psphere.ui.components.formatBytes
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData
import kotlinx.coroutines.launch

private data class Detail(
    val row: DeviceRow? = null,
    val sending: List<Pair<RuleRec, Int>> = emptyList(),
    val keeping: List<KeptGroup> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
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
        )
    }
    val row = detail.row
    val name = row?.device?.name ?: Engine.node.device(deviceId)?.name ?: "Device"
    val cs = MaterialTheme.colorScheme

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink $name?") },
            text = { Text("This device stops backing up to $name and won't accept connections from it. Files already copied stay where they are. Unlink on $name too.") },
            confirmButton = {
                Button(onClick = { Engine.unlink(deviceId); confirmUnlink = false; onBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) { Text("Unlink") }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") } },
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        ContentColumn(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row != null) OnlineLine(row)
                val busy = state.activity != Activity.Idle
                Button(
                    onClick = { Engine.scope.launch { Engine.backUp(onlyDevice = deviceId) } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
                ) {
                    Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) describeActivity(state.activity) ?: "Backing up…" else "Back up with $name now")
                }
                state.results[deviceId]?.let { r -> NotesCard(listOfNotNull(r.error) + r.notes) }

                SectionLabel("Sending to $name")
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    if (detail.sending.isEmpty()) {
                        Text(
                            "None of this device's folders go to $name. Choose destinations in Sending.",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                        )
                    }
                    detail.sending.forEachIndexed { i, (rule, waiting) ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                        FolderRow(Icons.Default.FolderOpen, rule.label, if (waiting > 0) "${plural(waiting, "file")} waiting" else "Up to date")
                    }
                }

                SectionLabel("Keeping from $name")
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    if (detail.keeping.isEmpty()) {
                        Text(
                            "Nothing from $name is stored here yet.",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                        )
                    }
                    detail.keeping.forEachIndexed { i, g ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                        FolderRow(Icons.Default.Folder, g.label, "${plural(g.files, "file")} · ${formatBytes(g.bytes)} · ${g.path}")
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { confirmUnlink = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Unlink $name")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
