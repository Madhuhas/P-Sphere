package com.psphere

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val ready by Engine.ready.collectAsState()
            PSphereTheme {
                // Screens read Engine.node, so they can't be composed before the engine has started.
                if (ready) AppNavigation()
            }
        }
    }
}
