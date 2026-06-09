package com.psphere

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.psphere.navigation.AppNavigation
import com.psphere.ui.theme.PSphereTheme

class MainActivity : ComponentActivity() {

    // Request media-read permission as soon as the activity is created.
    // The app shows photos from MediaStore (P2P mode) and needs this at runtime on API 33+.
    private val requestMediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op: UI handles the empty state if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestMediaPermission.launch(permissions)

        setContent {
            PSphereTheme {
                AppNavigation()
            }
        }
    }
}
