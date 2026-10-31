package com.psphere.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.psphere.backup.Prefs
import com.psphere.ui.components.CodeDialog
import com.psphere.ui.devices.DeviceScreen
import com.psphere.ui.devices.DevicesScreen
import com.psphere.ui.link.LinkScreen
import com.psphere.ui.receiving.ReceivingScreen
import com.psphere.ui.sending.FolderScreen
import com.psphere.ui.sending.SendingScreen
import com.psphere.ui.settings.SettingsScreen
import com.psphere.ui.setup.WelcomeScreen

private const val ROUTE_WELCOME   = "welcome"
private const val ROUTE_DEVICES   = "devices"
private const val ROUTE_DEVICE    = "device/{id}"
private const val ROUTE_LINK      = "link"
private const val ROUTE_SENDING   = "sending"
private const val ROUTE_FOLDER    = "folder?id={id}"
private const val ROUTE_RECEIVING = "receiving"
private const val ROUTE_SETTINGS  = "settings"

private fun deviceRoute(id: String) = "device/$id"
private fun folderRoute(id: String?) = if (id == null) "folder" else "folder?id=$id"

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Devices  (ROUTE_DEVICES,   "Devices",   Icons.Outlined.Devices,       Icons.Default.Devices),
    Sending  (ROUTE_SENDING,   "Sending",   Icons.Outlined.CloudUpload,   Icons.Default.CloudUpload),
    Receiving(ROUTE_RECEIVING, "Receiving", Icons.Outlined.CloudDownload, Icons.Default.CloudDownload),
    Settings (ROUTE_SETTINGS,  "Settings",  Icons.Outlined.Settings,      Icons.Default.Settings),
}

private val TAB_ROUTES = Tab.entries.map { it.route }.toSet()

/** Width at which the bottom bar becomes a side rail (tablets, phones in landscape). */
private val RAIL_BREAKPOINT = 600.dp

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val start = remember { if (Prefs.onboarded(context)) ROUTE_DEVICES else ROUTE_WELCOME }
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showNav = currentRoute in TAB_ROUTES

    CodeDialog()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= RAIL_BREAKPOINT
        Scaffold(
            bottomBar = {
                AnimatedVisibility(visible = showNav && !useRail, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateTab(tab.route) },
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(Modifier.fillMaxSize().padding(innerPadding)) {
                if (showNav && useRail) {
                    NavigationRail {
                        Tab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navController.navigateTab(tab.route) },
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                NavHost(navController, startDestination = start, modifier = Modifier.weight(1f)) {
                    composable(ROUTE_WELCOME) {
                        WelcomeScreen(onDone = {
                            navController.navigate(ROUTE_DEVICES) { popUpTo(ROUTE_WELCOME) { inclusive = true } }
                        })
                    }
                    composable(ROUTE_DEVICES) {
                        DevicesScreen(
                            onOpenDevice = { navController.navigate(deviceRoute(it)) },
                            onLink = { navController.navigate(ROUTE_LINK) },
                            onOpenSending = { navController.navigateTab(ROUTE_SENDING) },
                        )
                    }
                    composable(ROUTE_DEVICE) { entry ->
                        DeviceScreen(deviceId = entry.arguments?.getString("id").orEmpty(), onBack = { navController.popBackStack() })
                    }
                    composable(ROUTE_LINK) { LinkScreen(onBack = { navController.popBackStack() }) }
                    composable(ROUTE_SENDING) {
                        SendingScreen(
                            onAdd = { navController.navigate(folderRoute(null)) },
                            onEdit = { navController.navigate(folderRoute(it)) },
                        )
                    }
                    composable(ROUTE_FOLDER) { entry ->
                        FolderScreen(
                            ruleId = entry.arguments?.getString("id"),
                            onBack = { navController.popBackStack() },
                            onLinkDevice = { navController.navigate(ROUTE_LINK) },
                        )
                    }
                    composable(ROUTE_RECEIVING) { ReceivingScreen() }
                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            onOpenDevice = { navController.navigate(deviceRoute(it)) },
                            onLink = { navController.navigate(ROUTE_LINK) },
                        )
                    }
                }
            }
        }
    }
}

private fun NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
