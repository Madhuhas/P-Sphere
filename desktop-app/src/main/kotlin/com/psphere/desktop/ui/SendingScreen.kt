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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.psphere.core.model.Include
import com.psphere.core.model.ItemState
import com.psphere.core.model.RuleRec
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.DesktopSystem
import kotlinx.coroutines.launch
import java.io.File

private data class SendingRow(
    val rule: RuleRec,
    /** Null if the folder can't be opened any more (deleted, or a drive that isn't plugged in). */
    val location: String?,
    val files: Int,
    val bytes: Long,
    val destinations: List<String>,
    val waiting: Int,
)

private data class Sending(val rows: List<SendingRow> = emptyList(), val loaded: Boolean = false)

@Composable
fun SendingScreen(onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val data by rememberEngineData(Sending()) {
        val node = Engine.node
        val items = node.catalog.items(node.id).filter { it.state == ItemState.PRESENT }
        Sending(node.ownRules().map { (rule, _) ->
            val mine = items.filter { it.ruleId == rule.ruleId }
            SendingRow(
                rule = rule,
                location = node.ruleLocation(rule.ruleId)?.displayName,
                files = mine.size,
                bytes = mine.sumOf { it.size },
                destinations = rule.destinations.mapNotNull { node.device(it)?.name }.sorted(),
                waiting = rule.destinations.sumOf { node.pendingCount(it, rule.ruleId) },
            )
        }, loaded = true)
    }

    Column {
        ScreenTitle("Sending")
        if (data.loaded && data.rows.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = "Back up this laptop's folders too",
                body = "Optional. This laptop already keeps backups from your other devices (see Receiving). Add a folder, like Documents, to also send it to your phone or tablet.",
            ) { Button(onClick = onAdd) { Text("Add a folder") } }
            return@Column
        }
        ScreenBody(spacing = 12.dp) {
            Text(
                "Folders on this laptop and where they're backed up. Deleting files here never deletes the backups.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            data.rows.forEach { SendingCard(it) { onEdit(it.rule.ruleId) } }
            OutlinedButton(onClick = onAdd) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a folder")
            }
        }
    }
}

@Composable
private fun SendingCard(row: SendingRow, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Default.FolderOpen, container = cs.secondaryContainer, content = cs.onSecondaryContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.rule.label, style = MaterialTheme.typography.titleMedium)
                if (row.location == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.WarningAmber, null, tint = cs.error, modifier = Modifier.size(16.dp))
                        Text("Can't open this folder. Click to choose it again.", style = MaterialTheme.typography.bodyMedium, color = cs.error)
                    }
                } else {
                    Text(row.location, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
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

/** Add a backup folder ([ruleId] null) or edit an existing one. */
@Composable
fun FolderScreen(ruleId: String?, onBack: () -> Unit, onLinkDevice: () -> Unit) {
    val node = Engine.node
    val existing = remember(ruleId) { ruleId?.let { node.catalog.rule(node.id, it) } }
    val devices = remember { Engine.linked() }
    val cs = MaterialTheme.colorScheme

    var location by remember { mutableStateOf(ruleId?.let { node.ruleLocation(it)?.displayName }) }
    var changedLocation by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var destinations by remember { mutableStateOf(existing?.destinations ?: devices.map { it.id }.toSet()) }
    var include by remember { mutableStateOf(existing?.include ?: Include.ALL) }
    var showInLibrary by remember { mutableStateOf(existing?.showInLibrary ?: true) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }
    // The name the folder itself suggests; replaced when the folder changes, unless the user renamed it.
    var suggestedLabel by remember { mutableStateOf(location?.let { File(it).name }) }

    fun pick() {
        val path = pickFolder("Choose a folder to back up", location) ?: return
        // Sending the Receiving folder (or a folder around it) would send other devices' backups back out.
        val backups = node.state.backupLocation
        if (backups != null && DesktopSystem.overlaps(path, backups)) {
            pickError = "Backups from your other devices are saved in this folder (see Receiving). Choose a different folder."
            return
        }
        if (File(path).parentFile == null) {
            pickError = "Choose a folder, not a whole drive."
            return
        }
        pickError = null
        location = path
        changedLocation = true
        val suggested = File(path).name.ifBlank { "Folder" }
        if (label.isBlank() || label == suggestedLabel) label = suggested
        suggestedLabel = suggested
    }

    val canSave = label.isNotBlank() && location != null

    fun save() {
        val loc = location ?: return
        if (existing == null) {
            node.addRule(label, loc, destinations, include, showInLibrary)
        } else {
            if (changedLocation) node.setRuleLocation(existing.ruleId, loc)
            node.updateRule(existing.ruleId, label = label, destinations = destinations, include = include, showInLibrary = showInLibrary)
        }
        Engine.bump()
        Engine.scope.launch { Engine.backUp(quiet = true) }
        onBack()
    }

    if (confirmRemove && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Stop backing up “${existing.label}”?") },
            text = { Text("New files in this folder won't be backed up. Copies already on your other devices stay there, and nothing on this laptop is deleted.") },
            confirmButton = {
                Button(onClick = { node.removeRule(existing.ruleId); Engine.bump(); onBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) { Text("Stop backing up") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }

    Column {
        ScreenTitle(if (existing == null) "Add a folder" else existing.label, onBack = onBack) {
            TextButton(onClick = ::save, enabled = canSave) { Text("Save") }
        }
        ScreenBody {
            SectionLabel("Folder")
            PanelCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.FolderOpen, null, tint = cs.primary)
                        Text(
                            location ?: if (existing != null) "Can't open this folder any more" else "No folder chosen",
                            style = if (location != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            fontFamily = if (location != null) FontFamily.Monospace else null,
                            color = if (location == null) cs.error else cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = ::pick) { Text(if (location == null) "Choose folder…" else "Change…") }
                    }
                    pickError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error) }
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(60) },
                        label = { Text("Name") },
                        supportingText = { Text("Your other devices keep it in a folder with this name.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionLabel("Back up to")
            PanelCard {
                if (devices.isEmpty()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Link your other devices first, then choose where this folder goes.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        OutlinedButton(onClick = onLinkDevice) { Text("Link a device") }
                    }
                }
                devices.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                    val checked = d.id in destinations
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(checked, role = Role.Checkbox) { destinations = if (it) destinations + d.id else destinations - d.id }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            SectionLabel("What to include")
            PanelCard {
                IncludeOption("Photos and videos", "Skips other files, like documents", include == Include.MEDIA) { include = Include.MEDIA }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                IncludeOption("All files", "Everything in the folder and its subfolders", include == Include.ALL) { include = Include.ALL }
            }

            SectionLabel("Photos")
            PanelCard {
                Row(
                    Modifier.fillMaxWidth().toggleable(showInLibrary, role = Role.Switch) { showInLibrary = it }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show on my other devices", style = MaterialTheme.typography.bodyLarge)
                        Text("Photos from this folder appear in Photos on your linked devices. Turn off for private folders.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = showInLibrary, onCheckedChange = null)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::save, enabled = canSave, modifier = Modifier.height(48.dp)) {
                    Text(if (existing == null) "Start backing up" else "Save")
                }
                if (existing != null) {
                    TextButton(onClick = { confirmRemove = true }, colors = ButtonDefaults.textButtonColors(contentColor = cs.error), modifier = Modifier.height(48.dp)) {
                        Text("Stop backing up this folder")
                    }
                }
            }
        }
    }
}

@Composable
private fun IncludeOption(title: String, sub: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
