package com.psphere.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.psphere.BuildConfig
import com.psphere.backup.Engine
import com.psphere.backup.Prefs
import com.psphere.ui.components.ContentColumn
import com.psphere.ui.components.SectionLabel

private val INTERVALS = listOf(1, 3, 6, 12, 24)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenDevice: (String) -> Unit, onLink: () -> Unit) {
    val context = LocalContext.current
    val state by Engine.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    var renaming by remember { mutableStateOf(false) }
    var charging by remember { mutableStateOf(Prefs.chargingOnly(context)) }
    var interval by remember { mutableStateOf(Prefs.intervalHours(context)) }
    var stayReady by remember { mutableStateOf(Prefs.stayReady(context)) }
    val name = remember(state.revision) { Engine.node.deviceName }
    val devices = remember(state.revision) { Engine.linked() }

    if (renaming) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name this device") },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { Button(onClick = { Engine.rename(text); renaming = false }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        ContentColumn(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("This device")
                SettingsCard {
                    NavRow(Icons.Default.Edit, "Name", name) { renaming = true }
                }

                SectionLabel("When to back up")
                SettingsCard {
                    ToggleRow(Icons.Default.BatteryChargingFull, "Only while charging", "For backups in the background. “Back up now” always works.", charging) {
                        charging = it; Prefs.setChargingOnly(context, it)
                    }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.Schedule, null, tint = cs.primary)
                        Column {
                            Text("Back up in the background every", style = MaterialTheme.typography.bodyLarge)
                            Text("On Wi-Fi only. Also whenever a linked device shows up while PSphere is open.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                INTERVALS.forEach { h ->
                                    FilterChip(
                                        selected = interval == h,
                                        onClick = { interval = h; Prefs.setIntervalHours(context, h) },
                                        label = { Text(if (h == 1) "1 hour" else "$h hours") },
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                    ToggleRow(
                        Icons.Default.NotificationsActive, "Stay ready to receive",
                        "Keeps PSphere running so your other devices can back up here any time. Shows a notification. Best on a device that stays at home.",
                        stayReady,
                    ) { stayReady = it; Prefs.setStayReady(context, it) }
                }

                SectionLabel("Linked devices")
                SettingsCard {
                    devices.forEachIndexed { i, d ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                        NavRow(Icons.Default.Devices, d.name, if (Engine.isOnline(d.id)) "On this Wi-Fi" else "Not nearby") { onOpenDevice(d.id) }
                    }
                    if (devices.isNotEmpty()) HorizontalDivider(color = cs.outlineVariant)
                    NavRow(Icons.Default.Devices, "Link a device", "Scan or show a code") { onLink() }
                }

                AboutLinks()

                SectionLabel("About")
                SettingsCard {
                    InfoRow(Icons.Default.Info, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                    InfoRow(Icons.Default.Devices, "Device ID", Engine.node.id)
                }
                AboutFooter()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column { content() } }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, sub: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onToggle).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
