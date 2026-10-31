package io.wolftown.lauschig

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.wolftown.lauschig.ui.AppRoot

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Held only until the local account read finishes; this is the sole thing the start waits for.
        installSplashScreen().setKeepOnScreenCondition { viewModel.startDestination.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val start by viewModel.startDestination.collectAsStateWithLifecycle()
            start?.let { AppRoot(startDestination = it, miniPlayer = {}) }
        }
    }
}
