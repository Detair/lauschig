package io.wolftown.lauschig.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.wolftown.lauschig.ui.ConnectionBanner
import io.wolftown.lauschig.ui.FrameBanners
import io.wolftown.lauschig.ui.common.LocalBackdropReach
import io.wolftown.lauschig.ui.common.backdropReach
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

/** The player layer for one composition: its slide and whether the Player destination is current ([onPlayer]). */
class PlayerLayer(val slide: MutableTransitionState<Boolean>, val onPlayer: Boolean) {
    /** See [playerCoversFrame]. */
    val coversFrame: Boolean get() = playerCoversFrame(onPlayer, slide)

    /** The layer is on screen or on its way (it shows the snackbars meanwhile). */
    val up: Boolean get() = onPlayer || slide.currentState || slide.targetState
}

/**
 * The player layer's state for [navController]. It survives the activity being recreated (rotation, theme, font or
 * language change, process restore): a player that was open is shown at once in the new activity, in place – no
 * slide-up replayed over a frame that has nothing beneath it yet. Until the navigation host has (re)built its back
 * stack the slide is left as it is; after that it follows the Player destination and slides for the user's navigations.
 */
@Composable
fun rememberPlayerLayer(navController: NavHostController): PlayerLayer {
    val entry = navController.currentBackStackEntryAsState().value
    var wasOpen by rememberSaveable { mutableStateOf(false) }
    val slide = remember { MutableTransitionState(wasOpen) }
    val onPlayer = entry?.destination?.hasRoute<Player>() == true
    if (entry != null && slide.targetState != onPlayer) slide.targetState = onPlayer
    if (entry != null) SideEffect { wasOpen = onPlayer }
    return PlayerLayer(slide, onPlayer)
}

/**
 * The frame under a [covered][PlayerLayer.coversFrame] player: hidden from TalkBack, and keyboard / D-pad focus does not
 * move into it (its tabs and controls lie behind the opaque player). Always a focus group, so the node stays the same.
 */
fun Modifier.underPlayer(covered: Boolean): Modifier =
    if (covered) clearAndSetSemantics {}.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup() else focusGroup()

/**
 * The full player as a layer above the whole frame – bars, rail, mini player – instead of inside the page area: it
 * slides up when the Player destination becomes current ([slide]'s target) and back down when it is left, at the
 * window's size whatever the frame shows underneath. It shows the frame's banners itself (its backdrop reaches over
 * the status bar only without one) and the snackbars while it is up. The Player destination stays in the back stack
 * (back, links, widget and "details replace the player" work as before) but draws nothing; see [AppNavHost]. [slide]
 * comes from [rememberPlayerLayer].
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
    /** The player itself (`PlayerRoute`). */
    content: @Composable () -> Unit,
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
                        CompositionLocalProvider(LocalBackdropReach provides reach, content = content)
                    }
                }
                SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(insets))
            }
        }
    }
}
