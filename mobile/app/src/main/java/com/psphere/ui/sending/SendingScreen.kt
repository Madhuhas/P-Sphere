package com.psphere.ui.sending

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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.psphere.backup.Engine
import com.psphere.core.model.Include
import com.psphere.core.model.ItemState
import com.psphere.core.model.RuleRec
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.EmptyState
import com.psphere.ui.components.IconBadge
import com.psphere.ui.components.formatBytes
import com.psphere.ui.components.plural
import com.psphere.ui.components.rememberEngineData

internal data class FolderRow(
    val rule: RuleRec,
    val location: String?,
    val files: Int,
    val bytes: Long,
    val destinations: List<String>,
    val waiting: Int,
)

private data class Sending(val rows: List<FolderRow> = emptyList(), val loaded: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendingScreen(onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val data by rememberEngineData(Sending()) {
        val node = Engine.node
        val items = node.catalog.items(node.id).filter { it.state == ItemState.PRESENT }
        Sending(node.ownRules().map { (rule, _) ->
            val mine = items.filter { it.ruleId == rule.ruleId }
            FolderRow(
                rule = rule,
                location = node.ruleLocation(rule.ruleId)?.displayName,
                files = mine.size,
                bytes = mine.sumOf { it.size },
                destinations = rule.destinations.mapNotNull { node.device(it)?.name }.sorted(),
                waiting = rule.destinations.sumOf { node.pendingCount(it, rule.ruleId) },
            )
        }, loaded = true)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Sending") }) }) { padding ->
        if (data.loaded && data.rows.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = "Choose folders to back up",
                body = "Pick a folder on this device, like Camera, and the devices it should go to.",
                modifier = Modifier.padding(padding),
            ) { Button(onClick = onAdd) { Text("Add a folder") } }
            return@Scaffold
        }
        ContentColumn(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Folders on this device and where they're backed up. Deleting files here never deletes the backups.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                data.rows.forEach { FolderCard(it) { onEdit(it.rule.ruleId) } }
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add a folder")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FolderCard(row: FolderRow, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Default.FolderOpen, container = cs.secondaryContainer, content = cs.onSecondaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.rule.label, style = MaterialTheme.typography.titleMedium)
                if (row.location == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.WarningAmber, null, tint = cs.error, modifier = Modifier.size(16.dp))
                        Text("Can't open this folder. Tap to choose it again.", style = MaterialTheme.typography.bodyMedium, color = cs.error)
                    }
                } else {
                    Text(row.location, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Text(
                    if (row.destinations.isEmpty()) "Not going anywhere yet" else "To ${row.destinations.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    buildString {
                        append(plural(row.files, "file")).append(" · ").append(formatBytes(row.bytes))
                        if (row.rule.include == Include.MEDIA) append(" · photos and videos")
                        if (row.waiting > 0) append(" · ").append(row.waiting).append(" waiting")
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
        }
    }
}
