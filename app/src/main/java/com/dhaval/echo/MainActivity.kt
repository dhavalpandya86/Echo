package com.dhaval.echo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dhaval.echo.ui.EchoApp
import com.dhaval.echo.ui.auth.FacebookAuthHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The main entry point of the application.
 * Following Clean Architecture, this activity is kept minimal,
 * delegating UI and logic to the EchoApp composable and subsequent layers.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var facebookAuthHelper: FacebookAuthHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EchoApp()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        facebookAuthHelper.onActivityResult(requestCode, resultCode, data)
    }
}
