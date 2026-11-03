package io.wolftown.lauschig.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/** Medium and expanded widths (tablets, unfolded foldables, phones in landscape): a navigation rail instead of the bottom bar. */
@Composable
fun rememberNavigationRail(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * The pane directive of the window: two horizontal partitions from the expanded width on; on a foldable in book
 * posture the hinge is kept out of the panes.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberPaneDirective(): PaneScaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())

/** List and detail side by side. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
val PaneScaffoldDirective.isTwoPane: Boolean get() = maxHorizontalPartitions > 1

/** Text pages (settings, details opened full screen, …) never grow wider than this; wide windows centre them. */
val READABLE_MAX_WIDTH = 840.dp

/** Centres [content] with at most [READABLE_MAX_WIDTH] on wide windows; full width on phones. */
@Composable
fun ReadableWidth(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = READABLE_MAX_WIDTH).fillMaxSize()) { content() }
    }
}
