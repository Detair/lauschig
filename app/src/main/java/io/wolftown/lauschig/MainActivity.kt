package io.wolftown.lauschig

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.wolftown.lauschig.auto.VoicePlayer
import io.wolftown.lauschig.links.IncomingIntents
import io.wolftown.lauschig.settings.Appearance
import io.wolftown.lauschig.settings.FontScaleBoot
import io.wolftown.lauschig.ui.AppRoot
import io.wolftown.lauschig.ui.isDarkTheme
import io.wolftown.lauschig.ui.setup.OidcCallback
import io.wolftown.lauschig.ui.setup.OidcCallbackBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var oidcCallbacks: OidcCallbackBus
    @Inject lateinit var voicePlayer: VoicePlayer

    /** The app text size this activity was created with (see [FontScaleBoot]). */
    private var appliedFontScale = 100

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        appliedFontScale = FontScaleBoot.read(newBase)
        FontScaleBoot.overrideFor(newBase.resources.configuration, appliedFontScale)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Held only until the local account and appearance reads finish; these are the only things the start waits for.
        installSplashScreen().setKeepOnScreenCondition { viewModel.startDestination.value == null || viewModel.appearance.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A new text size in the settings: remember it for attachBaseContext and rebuild the activity with it.
        lifecycleScope.launch {
            viewModel.appearance.filterNotNull().map { it.fontScalePercent }.distinctUntilChanged().collect { percent ->
                if (percent != appliedFontScale) {
                    FontScaleBoot.write(this@MainActivity, percent)
                    recreate()
                }
            }
        }
        // Before handleOpen strips the extras it consumes.
        if (isLauncherStart(intent, recreated = savedInstanceState != null)) viewModel.continueOnLauncherStart()
        // A recreation (rotation, process restart) hands us the old intent again; its SSO link was already used.
        handleOpen(intent, replay = savedInstanceState != null)
        setContent {
            val appearance = viewModel.appearance.collectAsStateWithLifecycle().value ?: Appearance()
            val dark = isDarkTheme(appearance)
            // Status and navigation bar icons follow the app's theme, not the system's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            val start by viewModel.startDestination.collectAsStateWithLifecycle()
            val tabs by viewModel.tabLayout.collectAsStateWithLifecycle()
            val open by openRequest.collectAsStateWithLifecycle()
            val link by linkRequest.collectAsStateWithLifecycle()
            start?.let {
                AppRoot(
                    startDestination = it, appearance = appearance, tabs = tabs.visible, openRequest = open, onOpenHandled = { openRequest.value = null },
                    linkRequest = link, onLinkHandled = { linkRequest.value = null },
                )
            }
        }
        keepContentRootEdgeToEdge()
    }

    /**
     * After [recreate] (a new text size) Android keeps the old window, and the freshly inflated content root then pads
     * itself by the system bars (legacy `fitsSystemWindows`) although the app draws edge to edge – white bars on top
     * and bottom in a dark theme. Compose handles all insets, so the root never fits them.
     */
    private fun keepContentRootEdgeToEdge() {
        (findViewById<View>(android.R.id.content)?.parent as? View)?.apply {
            fitsSystemWindows = false
            setPadding(0, 0, 0, 0)
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

    /** Routing in [IncomingIntents.consume]; a recreation must not repeat voice, links or the SSO redirect. */
    private fun handleOpen(intent: Intent?, replay: Boolean = false) {
        val incoming = IncomingIntents.consume(intent, replay)
        incoming.open?.let { openRequest.value = it }
        // "Spiel X in Lauschig" (Assistant).
        incoming.voice?.let { voicePlayer.playFromSearch(it.query, it.extras) }
        // A web link of the server (VIEW) or a shared one (SEND).
        incoming.link?.let { linkRequest.value = it }
        // SSO: lauschig://oauth?code=..&state=.. comes back from the Custom Tab.
        incoming.oidc?.let { oidcCallbacks.post(OidcCallback.from(it)) }
    }

    companion object {
        // The scrims enableEdgeToEdge() uses by default for 3-button navigation.
        private val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        const val EXTRA_OPEN = "open"
        const val OPEN_CLEANUP = "cleanup"
        /** The player, if a title is loaded (the widget's cover and title). */
        const val OPEN_PLAYER = "player"
    }
}
