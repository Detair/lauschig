package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * How far a page's full-bleed backdrop (the cover tint behind the player and the detail header) may reach beyond the
 * page's own top, left and right edges: over the status bar and the side insets, and only where the app's frame draws
 * nothing else. Whenever the offline, connecting or sign-in banner sits between the status bar and the page, [top] is 0,
 * so a backdrop never paints over a banner.
 */
@Immutable
data class BackdropReach(val top: Dp = 0.dp, val left: Dp = 0.dp, val right: Dp = 0.dp)

/** Provided by the frame (`AppScaffold`, the player overlay); a page outside them may not reach anywhere. */
val LocalBackdropReach = staticCompositionLocalOf { BackdropReach() }

/** The reach for a page laid out inside [insets] (the frame's padding for the system bars), below [bannerShown] or not. */
fun backdropReach(insets: PaddingValues, direction: LayoutDirection, bannerShown: Boolean): BackdropReach = BackdropReach(
    top = if (bannerShown) 0.dp else insets.calculateTopPadding(),
    left = insets.calculateLeftPadding(direction),
    right = insets.calculateRightPadding(direction),
)
