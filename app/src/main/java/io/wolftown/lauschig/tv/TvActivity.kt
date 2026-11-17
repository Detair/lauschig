package io.wolftown.lauschig.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.wolftown.lauschig.MainViewModel
import io.wolftown.lauschig.auto.VoicePlayer
import io.wolftown.lauschig.isLauncherStart
import io.wolftown.lauschig.links.IncomingIntents
import io.wolftown.lauschig.ui.profiles.AndroidDeviceLockPrompter
import io.wolftown.lauschig.ui.profiles.LocalDeviceLockPrompter
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

/**
 * The app on a television, started from the TV launcher. It shares everything below the interface with the phone's
 * [io.wolftown.lauschig.MainActivity]: accounts, library, playback.
 *
 * Unlike the phone it neither applies the in-app text size (a TV sets its own for the viewing distance) nor follows the
 * appearance settings: it is always dark. A FragmentActivity because the device lock prompt (BiometricPrompt) needs one.
 */
@AndroidEntryPoint
class TvActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var voicePlayer: VoicePlayer

    /** A request to show a page (the media session's "open the player"); TvRoot takes it once and clears it. */
    private val openRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Here and not later: the prompt's answer can come right after a recreation (see AndroidDeviceLockPrompter).
        val deviceLock = AndroidDeviceLockPrompter(this)
        // Before handleOpen strips the extras it consumes.
        if (isLauncherStart(intent, recreated = savedInstanceState != null)) viewModel.continueOnLauncherStart()
        handleOpen(intent, replay = IncomingIntents.isReplay(intent, recreated = savedInstanceState != null))
        setContent {
            // Not with the lifecycle: the first frame after a pause must already belong to the account that is active now.
            val pages by viewModel.pages.collectAsState()
            val open by openRequest.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalDeviceLockPrompter provides deviceLock) {
                pages?.let { TvRoot(pages = it, openRequest = open, onOpenHandled = { openRequest.value = null }) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /**
     * As in MainActivity, which hands its intents on to this one on a TV. Web links and the SSO redirect are let go: the TV
     * has no browser to sign in with and no page to open a link on.
     */
    private fun handleOpen(intent: Intent?, replay: Boolean = false) {
        val incoming = IncomingIntents.consume(intent, replay)
        incoming.open?.let { openRequest.value = it }
        // "Spiel X in Lauschig" (Assistant): plays without a page of its own.
        incoming.voice?.let { voicePlayer.playFromSearch(it.query, it.extras) }
    }
}
