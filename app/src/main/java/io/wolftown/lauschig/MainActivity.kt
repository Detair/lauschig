package io.wolftown.lauschig

import android.content.Intent
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
import io.wolftown.lauschig.ui.setup.OidcCallback
import io.wolftown.lauschig.ui.setup.OidcCallbackBus
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var oidcCallbacks: OidcCallbackBus

    override fun onCreate(savedInstanceState: Bundle?) {
        // Held only until the local account read finishes; this is the sole thing the start waits for.
        installSplashScreen().setKeepOnScreenCondition { viewModel.startDestination.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpen(intent)
        setContent {
            val start by viewModel.startDestination.collectAsStateWithLifecycle()
            val open by openRequest.collectAsStateWithLifecycle()
            start?.let { AppRoot(startDestination = it, openRequest = open, onOpenHandled = { openRequest.value = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** A notification asked for a screen (e.g. "Clean up"); AppRoot navigates once and clears it. */
    private val openRequest = MutableStateFlow<String?>(null)

    private fun handleOpen(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
        intent?.removeExtra(EXTRA_OPEN)
        // SSO: lauschig://oauth?code=..&state=.. comes back from the Custom Tab.
        intent?.data?.takeIf(OidcCallback::isCallback)?.let {
            oidcCallbacks.post(OidcCallback.from(it))
            intent?.data = null // a recreation must not replay it
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_CLEANUP = "cleanup"
    }
}
