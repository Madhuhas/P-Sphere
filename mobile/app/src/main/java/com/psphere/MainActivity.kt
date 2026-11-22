package com.psphere

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.mutableStateOf
import com.psphere.backup.Engine
import com.psphere.navigation.AppNavigation
import com.psphere.ui.theme.PSphereTheme

class MainActivity : ComponentActivity() {

    // Only for the backup notification; everything else works without it.
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The engine starts in the background (PSphereApp); keep the splash up until it's ready.
        installSplashScreen().setKeepOnScreenCondition { !Engine.ready.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpen(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val ready by Engine.ready.collectAsState()
            PSphereTheme {
                // Screens read Engine.node, so they can't be composed before the engine has started.
                if (ready) AppNavigation(openRequest = open.value, onOpened = { open.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    private fun handleOpen(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN)?.let { open.value = it }
    }

    /** A screen this window was asked to open (e.g. from a notification); cleared once shown. */
    private val open = mutableStateOf<String?>(null)

    companion object {
        /** Which screen to show, e.g. from a notification. */
        const val EXTRA_OPEN = "open"
        const val OPEN_SHELF = "shelf"
    }
}
