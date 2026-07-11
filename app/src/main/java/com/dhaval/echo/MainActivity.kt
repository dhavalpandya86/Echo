package com.dhaval.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dhaval.echo.ui.EchoApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * The main entry point of the application.
 * Following Clean Architecture, this activity is kept minimal,
 * delegating UI and logic to the EchoApp composable and subsequent layers.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoApp()
        }
    }
}
