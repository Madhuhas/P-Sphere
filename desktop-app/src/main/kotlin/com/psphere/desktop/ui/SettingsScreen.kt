package com.psphere.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.psphere.desktop.engine.Engine
import com.psphere.desktop.engine.DesktopSystem

const val APP_VERSION = "2.3.0"

@Composable
fun SettingsScreen(onOpenDevice: (String) -> Unit, onLink: () -> Unit) {
    val state by Engine.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    var renaming by remember { mutableStateOf(false) }
    val installed = remember { DesktopSystem.installedExe() != null }
    var startup by remember { mutableStateOf(installed && DesktopSystem.startsAtLogin()) }
    var keepInTray by remember { mutableStateOf(Engine.prefs.keepInTray) }
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

    Column {
        ScreenTitle("Settings")
        ScreenBody {
            SectionLabel("This device")
            PanelCard { NavRow(Icons.Default.Edit, "Name", name) { renaming = true } }

            SectionLabel("When to back up")
            PanelCard {
                ToggleRow(
                    Icons.Default.PowerSettingsNew, DesktopSystem.startAtLoginLabel,
                    if (installed) "It opens ${if (DesktopSystem.hasTray) "in the tray" else "in the background"}, so your devices can back up here without you opening it."
                    else "Available in the installed app.",
                    startup, enabled = installed,
                ) { on -> if (DesktopSystem.setStartsAtLogin(on)) startup = on }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                ToggleRow(
                    Icons.Default.WebAsset, "Keep running in the tray when closed",
                    if (DesktopSystem.hasTray) "Closing the window leaves PSphere in the tray, still receiving backups. Quit it from the tray icon."
                    else "This desktop has no tray, so closing the window quits PSphere.",
                    keepInTray && DesktopSystem.hasTray, enabled = DesktopSystem.hasTray,
                ) { keepInTray = it; Engine.prefs.keepInTray = it }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                InfoBlock(Icons.Default.Wifi, "Backs up automatically", "Whenever a linked device is on the same Wi-Fi and PSphere is running. Use “Back up now” to start it yourself.")
            }

            SectionLabel("Linked devices")
            PanelCard {
                devices.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                    NavRow(Icons.Default.Devices, d.name, if (Engine.isOnline(d.id)) "On this Wi-Fi" else "Not nearby") { onOpenDevice(d.id) }
                }
                if (devices.isNotEmpty()) HorizontalDivider(color = cs.outlineVariant)
                NavRow(Icons.Default.QrCode2, "Link a device", "Show a code to scan") { onLink() }
            }

            AboutLinks()

            SectionLabel("About")
            PanelCard {
                InfoRow(Icons.Default.Info, "Version") { Text(APP_VERSION, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                InfoRow(Icons.Default.Devices, "Device ID") { Text(Engine.node.id, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant) }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                InfoRow(Icons.Default.Folder, "PSphere's own data") { OpenFolderButton(Engine.dataDir.path) }
                HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
                InfoBlock(Icons.Default.Lock, "Local only", "PSphere talks only to your linked devices on your own network, encrypted. Nothing goes to the internet.")
            }
            AboutFooter()
        }
    }
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
private fun ToggleRow(icon: ImageVector, title: String, sub: String, checked: Boolean, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun InfoBlock(icon: ImageVector, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** First start: name this laptop. */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    var name by remember { mutableStateOf(Engine.node.deviceName) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 40.dp).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("PSphere", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Back up folders between your own devices over your home Wi-Fi. This laptop can keep backups of your phone and tablet, and back up its own folders to them.",
            style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant,
        )
        Point(Icons.Default.Devices, "Link your devices", "Your phone or tablet scans a code shown here.")
        Point(Icons.Default.Folder, "Choose where backups go", "Any folder or drive on this laptop.")
        Point(Icons.Default.Lock, "Private", "Only your linked devices can connect, and everything is encrypted. Nothing goes to the internet.")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Name for this device") },
            supportingText = { Text("Your other devices see this name, e.g. “Laptop” or “Home PC”.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { Engine.rename(name); Engine.prefs.onboarded = true; onDone() },
            enabled = name.isNotBlank(),
            modifier = Modifier.height(48.dp),
        ) { Text("Continue") }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
