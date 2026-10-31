package com.psphere.ui.sending

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.psphere.backup.Engine
import com.psphere.backup.SafFolder
import com.psphere.core.model.Include
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.SectionLabel
import com.psphere.ui.components.folderLabelFromUri
import com.psphere.ui.components.rememberFolderPicker
import kotlinx.coroutines.launch

/** Add a backup folder ([ruleId] null) or edit an existing one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(ruleId: String?, onBack: () -> Unit, onLinkDevice: () -> Unit) {
    val node = Engine.node
    val scope = rememberCoroutineScope()
    val existing = remember(ruleId) { ruleId?.let { node.catalog.rule(node.id, it) } }
    val devices = remember { Engine.linked() }

    var location by remember { mutableStateOf<Uri?>(null) }
    var locationText by remember { mutableStateOf(ruleId?.let { node.ruleLocation(it)?.displayName }) }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var destinations by remember { mutableStateOf(existing?.destinations ?: devices.map { it.id }.toSet()) }
    var include by remember { mutableStateOf(existing?.include ?: Include.MEDIA) }
    var showInLibrary by remember { mutableStateOf(existing?.showInLibrary ?: true) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }
    // The name the folder itself suggests; replaced when the folder changes, unless the user renamed it.
    var suggestedLabel by remember { mutableStateOf(locationText?.substringAfterLast('/')) }

    val pick = rememberFolderPicker { uri ->
        // Sending the Receiving folder (or a folder around it) would send other devices' backups back out.
        val backups = node.state.backupLocation
        if (backups != null && SafFolder.overlaps(uri.toString(), backups)) {
            pickError = "Backups from your other devices are saved in this folder (see Receiving). Choose a different folder."
            return@rememberFolderPicker
        }
        pickError = null
        location = uri
        locationText = SafFolder.describe(uri)
        val suggested = folderLabelFromUri(uri)
        if (label.isBlank() || label == suggestedLabel) label = suggested
        suggestedLabel = suggested
    }
    val canSave = label.isNotBlank() && (existing != null || location != null)

    fun save() {
        if (existing == null) {
            node.addRule(label, location.toString(), destinations, include, showInLibrary)
        } else {
            location?.let { node.setRuleLocation(existing.ruleId, it.toString()) }
            node.updateRule(existing.ruleId, label = label, destinations = destinations, include = include, showInLibrary = showInLibrary)
        }
        Engine.bump()
        scope.launch { Engine.backUp(quiet = true) }
        onBack()
    }

    if (confirmRemove && existing != null) {
        val cs = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Stop backing up “${existing.label}”?") },
            text = { Text("New files in this folder won't be backed up. Copies already on your other devices stay there, and nothing on this device is deleted.") },
            confirmButton = {
                Button(onClick = { node.removeRule(existing.ruleId); Engine.bump(); onBack() },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)) { Text("Stop backing up") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (existing == null) "Add a folder" else existing.label) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = ::save, enabled = canSave) { Text("Save") } },
        )
    }) { padding ->
        ContentColumn(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val cs = MaterialTheme.colorScheme

                SectionLabel("Folder")
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.FolderOpen, null, tint = cs.primary)
                            Text(
                                locationText ?: if (existing != null) "Can't open this folder any more" else "No folder chosen",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (locationText == null) cs.error else cs.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        pickError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error) }
                        OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
                            Text(if (locationText == null) "Choose folder" else "Change folder")
                        }
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(60) },
                            label = { Text("Name") },
                            supportingText = { Text("Other devices keep it in a folder with this name.") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                SectionLabel("Back up to")
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
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
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    IncludeOption("Photos and videos", "Skips other files, like documents", include == Include.MEDIA) { include = Include.MEDIA }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                    IncludeOption("All files", "Everything in the folder and its subfolders", include == Include.ALL) { include = Include.ALL }
                }

                SectionLabel("Photos")
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
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
                Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(if (existing == null) "Start backing up" else "Save")
                }
                if (existing != null) {
                    TextButton(onClick = { confirmRemove = true }, colors = ButtonDefaults.textButtonColors(contentColor = cs.error), modifier = Modifier.fillMaxWidth()) {
                        Text("Stop backing up this folder")
                    }
                }
                Spacer(Modifier.height(24.dp))
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
