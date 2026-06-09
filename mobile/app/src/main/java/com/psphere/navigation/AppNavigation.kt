package com.psphere.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.psphere.data.TokenDataStore
import com.psphere.ui.albums.AlbumsScreen
import com.psphere.ui.albums.AlbumsViewModel
import com.psphere.ui.gallery.GalleryScreen
import com.psphere.ui.gallery.GalleryViewModel
import com.psphere.ui.home.HomeScreen
import com.psphere.ui.photos.PhotoViewModel
import com.psphere.ui.photos.PhotoViewerScreen
import com.psphere.ui.register.RegisterDeviceScreen
import com.psphere.ui.search.SearchScreen
import com.psphere.ui.search.SearchViewModel
import com.psphere.ui.settings.SyncSettingsScreen
import kotlinx.coroutines.flow.first

// ── Route constants ───────────────────────────────────────────────────────────

private const val ROUTE_REGISTER = "register"
private const val ROUTE_HOME     = "home"
private const val ROUTE_GALLERY  = "gallery"
private const val ROUTE_ALBUMS   = "albums"
private const val ROUTE_SEARCH   = "search"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_VIEWER   = "viewer?startIndex={startIndex}"

private fun viewerRoute(index: Int) = "viewer?startIndex=$index"

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Sync    (ROUTE_HOME,     "Sync",     Icons.Default.Sync),
    Gallery (ROUTE_GALLERY,  "Gallery",  Icons.Default.PhotoLibrary),
    Albums  (ROUTE_ALBUMS,   "Albums",   Icons.Default.VideoLibrary),
    Search  (ROUTE_SEARCH,   "Search",   Icons.Default.Search),
    Settings(ROUTE_SETTINGS, "Settings", Icons.Default.Settings),
}

private val TAB_ROUTES = Tab.values().map { it.route }.toSet()

// ── Root composable ───────────────────────────────────────────────────────────

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current

    var startDest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val store   = TokenDataStore(context)
        val jwt     = store.jwtToken.first()
        val p2pMode = store.p2pMode.first()
        // Restore server URL into ApiClient on launch
        store.serverUrl.first()?.let { com.psphere.network.ApiClient.setBaseUrl(it) }
        startDest = if (jwt != null || p2pMode) ROUTE_HOME else ROUTE_REGISTER
    }
    val dest = startDest ?: return

    // Shared ViewModels — scoped to NavHost so Gallery and Viewer share state
    val photoViewModel:   PhotoViewModel   = viewModel()
    val galleryViewModel: GalleryViewModel = viewModel()
    val albumsViewModel:  AlbumsViewModel  = viewModel()
    val searchViewModel:  SearchViewModel  = viewModel()

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter   = slideInVertically { it },
                exit    = slideOutVertically { it },
            ) {
                BottomBar(navController)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController    = navController,
            startDestination = dest,
            modifier         = Modifier.padding(innerPadding),
        ) {

            // ── Register ──────────────────────────────────────────────────────
            composable(ROUTE_REGISTER) {
                RegisterDeviceScreen(
                    onRegistered = {
                        navController.navigate(ROUTE_HOME) {
                            popUpTo(ROUTE_REGISTER) { inclusive = true }
                        }
                    }
                )
            }

            // ── Home (Sync) ───────────────────────────────────────────────────
            composable(ROUTE_HOME) {
                HomeScreen()
            }

            // ── Gallery ───────────────────────────────────────────────────────
            composable(ROUTE_GALLERY) {
                LaunchedEffect(Unit) {
                    galleryViewModel.load(context)
                    photoViewModel.load(context)
                }
                GalleryScreen(
                    viewModel    = galleryViewModel,
                    onPhotoClick = { index -> navController.navigate(viewerRoute(index)) },
                    onGoToSync   = { navController.navigateTab(ROUTE_HOME) },
                )
            }

            // ── Albums ────────────────────────────────────────────────────────
            composable(ROUTE_ALBUMS) {
                AlbumsScreen(viewModel = albumsViewModel)
            }

            // ── AI Search ─────────────────────────────────────────────────────
            composable(ROUTE_SEARCH) {
                SearchScreen(viewModel = searchViewModel)
            }

            // ── Settings ──────────────────────────────────────────────────────
            composable(ROUTE_SETTINGS) {
                SyncSettingsScreen(
                    onBack      = { navController.popBackStack() },
                    onSignedOut = {
                        navController.navigate(ROUTE_REGISTER) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            // ── Photo viewer (no bottom bar) ──────────────────────────────────
            composable(
                route     = ROUTE_VIEWER,
                arguments = listOf(navArgument("startIndex") {
                    type = NavType.IntType; defaultValue = 0
                })
            ) { back ->
                val startIndex = back.arguments?.getInt("startIndex") ?: 0
                PhotoViewerScreen(
                    startIndex = startIndex,
                    onBack     = { navController.popBackStack() },
                    viewModel  = photoViewModel,
                )
            }
        }
    }
}

// ── Bottom navigation bar ─────────────────────────────────────────────────────

@Composable
private fun BottomBar(navController: NavController) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    NavigationBar {
        Tab.values().forEach { tab ->
            NavigationBarItem(
                selected        = currentRoute == tab.route,
                onClick         = { navController.navigateTab(tab.route) },
                icon            = { Icon(tab.icon, contentDescription = tab.label) },
                label           = { Text(tab.label) },
                alwaysShowLabel = true,
            )
        }
    }
}

private fun NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState    = true
    }
}
