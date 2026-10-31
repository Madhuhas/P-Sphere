package com.psphere.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.psphere.desktop.engine.Engine

/** Where the app is. A small back stack: tabs at the bottom, detail screens on top. */
sealed class Screen {
    object Welcome : Screen()
    object Devices : Screen()
    data class Device(val id: String) : Screen()
    object Link : Screen()
    object Sending : Screen()
    data class Folder(val ruleId: String?) : Screen()
    object Receiving : Screen()
    object Settings : Screen()
}

private enum class Tab(val label: String, val on: ImageVector, val off: ImageVector, val root: Screen) {
    DEVICES("Devices", Icons.Filled.Devices, Icons.Outlined.Devices, Screen.Devices),
    SENDING("Sending", Icons.Filled.CloudUpload, Icons.Outlined.CloudUpload, Screen.Sending),
    RECEIVING("Receiving", Icons.Filled.CloudDownload, Icons.Outlined.CloudDownload, Screen.Receiving),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, Screen.Settings),
}

private fun tabOf(s: Screen): Tab? = when (s) {
    Screen.Welcome -> null
    Screen.Devices, is Screen.Device, Screen.Link -> Tab.DEVICES
    Screen.Sending, is Screen.Folder -> Tab.SENDING
    Screen.Receiving -> Tab.RECEIVING
    Screen.Settings -> Tab.SETTINGS
}

@Composable
fun App() {
    val stack = remember { mutableStateListOf<Screen>(if (Engine.prefs.onboarded) Screen.Devices else Screen.Welcome) }
    val screen = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(t: Tab) { stack.clear(); stack.add(t.root) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val compact = maxWidth < 860.dp
        Row(Modifier.fillMaxSize()) {
            tabOf(screen)?.let { current ->
                if (compact) Rail(current, ::tab) else Drawer(current, ::tab)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when (val s = screen) {
                    Screen.Welcome -> WelcomeScreen(onDone = { tab(Tab.DEVICES) })
                    Screen.Devices -> DevicesScreen(onOpenDevice = { go(Screen.Device(it)) }, onLink = { go(Screen.Link) }, onOpenReceiving = { tab(Tab.RECEIVING) })
                    is Screen.Device -> DeviceScreen(s.id, onBack = ::back)
                    Screen.Link -> LinkScreen(onBack = ::back)
                    Screen.Sending -> SendingScreen(onAdd = { go(Screen.Folder(null)) }, onEdit = { go(Screen.Folder(it)) })
                    is Screen.Folder -> FolderScreen(s.ruleId, onBack = ::back, onLinkDevice = { go(Screen.Link) })
                    Screen.Receiving -> ReceivingScreen()
                    Screen.Settings -> SettingsScreen(onOpenDevice = { go(Screen.Device(it)) }, onLink = { go(Screen.Link) })
                }
            }
        }
        CodeDialog()
    }
}

@Composable
private fun Drawer(current: Tab, onTab: (Tab) -> Unit) {
    val state by Engine.state.collectAsState()
    val name = remember(state.revision) { Engine.node.deviceName }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.width(232.dp).fillMaxHeight().background(cs.surfaceContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 16.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text("PSphere", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Tab.entries.forEach { t ->
            NavigationDrawerItem(
                label = { Text(t.label) },
                icon = { Icon(if (t == current) t.on else t.off, null) },
                selected = t == current,
                onClick = { onTab(t) },
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = cs.surfaceContainer),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Lock, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text("Local only · encrypted", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Rail(current: Tab, onTab: (Tab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Spacer(Modifier.height(12.dp))
        Tab.entries.forEach { t ->
            NavigationRailItem(
                selected = t == current,
                onClick = { onTab(t) },
                icon = { Icon(if (t == current) t.on else t.off, t.label) },
                label = { Text(t.label) },
            )
        }
    }
}

/** Shown while the engine starts (loading the catalog can take a few seconds on a big library). */
@Composable
fun Starting() {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("PSphere", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Starting…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
