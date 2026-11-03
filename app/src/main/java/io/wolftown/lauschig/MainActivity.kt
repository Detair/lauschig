package io.wolftown.lauschig

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.wolftown.lauschig.auto.VoicePlayer
import io.wolftown.lauschig.links.IncomingIntents
import io.wolftown.lauschig.ui.AppRoot
import io.wolftown.lauschig.ui.setup.OidcCallback
import io.wolftown.lauschig.ui.setup.OidcCallbackBus
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var oidcCallbacks: OidcCallbackBus
    @Inject lateinit var voicePlayer: VoicePlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        // Held only until the local account read finishes; this is the sole thing the start waits for.
        installSplashScreen().setKeepOnScreenCondition { viewModel.startDestination.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before handleOpen strips the extras it consumes.
        if (isLauncherStart(intent, recreated = savedInstanceState != null)) viewModel.continueOnLauncherStart()
        // A recreation (rotation, process restart) hands us the old intent again; its SSO link was already used.
        handleOpen(intent, replay = savedInstanceState != null)
        setContent {
            val start by viewModel.startDestination.collectAsStateWithLifecycle()
            val open by openRequest.collectAsStateWithLifecycle()
            val link by linkRequest.collectAsStateWithLifecycle()
            start?.let {
                AppRoot(
                    startDestination = it, openRequest = open, onOpenHandled = { openRequest.value = null },
                    linkRequest = link, onLinkHandled = { linkRequest.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** A notification asked for a screen (e.g. "Clean up"); AppRoot navigates once and clears it. */
    private val openRequest = MutableStateFlow<String?>(null)

    /** An Audiobookshelf web link (opened or shared); AppRoot opens its title once and clears it. */
    private val linkRequest = MutableStateFlow<String?>(null)

    private fun handleOpen(intent: Intent?, replay: Boolean = false) {
        intent?.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
        intent?.removeExtra(EXTRA_OPEN)
        // "Spiel X in Lauschig" (Assistant); a recreation must not start it again.
        if (!replay && intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            voicePlayer.playFromSearch(intent.getStringExtra(SearchManager.QUERY), intent.extras)
            intent.action = null
        }
        // A web link of the server (VIEW) or a shared one (SEND); a recreation must not open it again.
        if (!replay) IncomingIntents.link(intent)?.let { url ->
            linkRequest.value = url
            intent?.action = null
            intent?.data = null
            intent?.removeExtra(Intent.EXTRA_TEXT)
        }
        // SSO: lauschig://oauth?code=..&state=.. comes back from the Custom Tab.
        if (!replay) intent?.data?.takeIf(OidcCallback::isCallback)?.let {
            oidcCallbacks.post(OidcCallback.from(it))
            intent?.data = null // a recreation must not replay it
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_CLEANUP = "cleanup"
        /** The player, if a title is loaded (the widget's cover and title). */
        const val OPEN_PLAYER = "player"
    }
}
