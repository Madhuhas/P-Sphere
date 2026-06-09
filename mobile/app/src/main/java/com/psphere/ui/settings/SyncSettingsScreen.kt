package com.psphere.ui.settings

import android.os.Environment
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    viewModel: SyncSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load(context) }

    LaunchedEffect(state.justFreed) { if (state.justFreed) viewModel.clearJustFreed() }

    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    // ── Free-space confirm dialog ─────────────────────────────────────────────
    if (state.showFreeSpaceConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissFreeSpaceConfirm() },
            title = { Text("Free up space?") },
            text  = {
                Text(
                    "This will permanently delete ${state.freeableCount} local " +
                    "file${if (state.freeableCount != 1) "s" else ""} " +
                    "(${formatBytes(state.freeableBytes)}) that have been synced to a peer device. " +
                    "Your copies on other devices are not affected."
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.freeUpSpace(context) },
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissFreeSpaceConfirm() }) { Text("Cancel") }
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Sync conditions ───────────────────────────────────────────────
            SectionHeader("Sync conditions")
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
                Column {
                    ToggleRow(Icons.Default.Wifi, "Sync on Wi-Fi only", "Don't use mobile data for transfers",
                        state.wifiOnly) { viewModel.setWifiOnly(context, it) }
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow(Icons.Default.BatteryChargingFull, "Sync while charging only", "Preserve battery on large libraries",
                        state.chargingOnly) { viewModel.setChargingOnly(context, it) }
                }
            }

            // ── Sync interval ─────────────────────────────────────────────────
            SectionHeader("Background sync interval")
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text("Auto-sync every", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SYNC_INTERVAL_OPTIONS.forEach { hours ->
                            FilterChip(
                                selected = state.syncIntervalHours == hours,
                                onClick  = { viewModel.setSyncInterval(context, hours) },
                                label    = { Text(if (hours == 1L) "1 hr" else "${hours} hrs") },
                            )
                        }
                    }
                }
            }

            // ── Folders ───────────────────────────────────────────────────────
            SectionHeader("Folders to sync")
            if (state.folders.isEmpty()) {
                Text("No photo folders found on this device.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))
            } else {
                Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
                    Column {
                        state.folders.forEachIndexed { i, folder ->
                            FolderRow(folder.displayName, folder.path, folder.selected) { viewModel.toggleFolder(context, folder.path) }
                            if (i < state.folders.lastIndex) Divider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }

            // ── Storage ───────────────────────────────────────────────────────
            SectionHeader("Storage")
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.CleaningServices, null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Free up space", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    state.freeableCount > 0 -> "${state.freeableCount} synced files · ${formatBytes(state.freeableBytes)} can be freed"
                                    state.justFreed         -> "Space freed successfully"
                                    else                    -> "Sync with a peer first to track what's safely stored elsewhere"
                                },
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            )
                        }
                    }
                    if (state.freeableCount > 0) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick  = { viewModel.showFreeSpaceConfirm() },
                            enabled  = !state.isDeleting,
                            colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.isDeleting) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.onError, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Delete ${state.freeableCount} local copies  (${formatBytes(state.freeableBytes)})")
                            }
                        }
                        Text("These files exist on at least one peer device. Your originals on other phones are unaffected.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            // ── Account ───────────────────────────────────────────────────────
            SectionHeader("Account")
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(1.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedButton(
                        onClick  = { viewModel.signOut(context) },
                        enabled  = !state.isSigningOut,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        if (state.isSigningOut) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sign Out / Switch Server")
                        }
                    }
                    Text(
                        "Signs you out and returns to the setup screen. Your photos on the server are not deleted.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, sub: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun FolderRow(name: String, path: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = if (selected) "Deselect" else "Select",
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                path.removePrefix(Environment.getExternalStorageDirectory().path + "/"),
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}
