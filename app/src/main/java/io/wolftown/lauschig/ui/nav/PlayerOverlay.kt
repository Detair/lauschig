package io.wolftown.lauschig.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.ui.ConnectionBanner
import io.wolftown.lauschig.ui.FrameBanners
import io.wolftown.lauschig.ui.common.LocalBackdropReach
import io.wolftown.lauschig.ui.common.backdropReach
import io.wolftown.lauschig.ui.player.PlayerRoute
import kotlin.coroutines.cancellation.CancellationException

/** The player's slide: 300 ms with Material's standard easing (the system's "remove animations" makes it instant). */
const val PLAYER_SLIDE_MS = 300
private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal fun <T> playerSlide() = tween<T>(PLAYER_SLIDE_MS, easing = StandardEasing)

/** How far the player follows a predictive back gesture before it is released. */
private val PREDICTIVE_BACK_SHIFT = 96.dp

/**
 * True while the player covers the whole frame: it is the current destination and its slide-in has finished. Only then
 * may the frame drop its bars, rail and mini player (underneath, unseen); as soon as the player is left they are back
 * before it starts to slide away, so nothing under a moving player changes size.
 */
fun playerCoversFrame(onPlayer: Boolean, slide: MutableTransitionState<Boolean>): Boolean =
    onPlayer && slide.isIdle && slide.currentState

/**
 * The full player as a layer above the whole frame – bars, rail, mini player – instead of inside the page area: it
 * slides up when the Player destination becomes current ([slide]'s target) and back down when it is left, at the
 * window's size whatever the frame shows underneath. It shows the frame's banners itself (its backdrop reaches over
 * the status bar only without one) and the snackbars while it is up. The Player destination stays in the back stack
 * (back, links, widget and "details replace the player" work as before) but draws nothing; see [AppNavHost].
 */
@Composable
fun PlayerOverlay(
    slide: MutableTransitionState<Boolean>,
    banner: ConnectionBanner?,
    showReauthBanner: Boolean,
    onReauth: () -> Unit,
    onOpenConnection: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onClose: () -> Unit,
    onOpenDetails: (itemId: String) -> Unit,
) {
    AnimatedVisibility(slide, enter = slideInVertically(playerSlide()) { it }, exit = slideOutVertically(playerSlide()) { it }) {
        val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
        val reach = backdropReach(insets, LocalLayoutDirection.current, bannerShown = showReauthBanner || banner != null)
        // Predictive back: the player follows the gesture a little and slides away when it is released.
        var backProgress by remember { mutableFloatStateOf(0f) }
        PredictiveBackHandler(enabled = slide.isIdle && slide.currentState) { events ->
            try {
                events.collect { backProgress = it.progress }
                onClose()
            } catch (e: CancellationException) {
                backProgress = 0f
                throw e
            }
        }
        // A surface of its own (outside the frame's Scaffold): the text colour is set here, and touches stop here.
        Surface(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = backProgress * PREDICTIVE_BACK_SHIFT.toPx() }
                .testTag("playerOverlay"),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(insets)) {
                    FrameBanners(banner, showReauthBanner, onReauth, onOpenConnection)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(LocalBackdropReach provides reach) {
                            PlayerRoute(onClose = onClose, onOpenDetails = onOpenDetails)
                        }
                    }
                }
                SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(insets))
            }
        }
    }
}
