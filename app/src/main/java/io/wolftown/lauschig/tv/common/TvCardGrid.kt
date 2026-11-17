package io.wolftown.lauschig.tv.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tv.nav.LocalRailHasFocus

/**
 * A grid of cards that remembers which one had the focus, as [TvCardRow] does: coming back into the grid (from above, or
 * from another page) lands on it, by its key, else at its place. When that card goes while it has the focus, the focus
 * moves to the card now at its place, or the last one, and does not get lost.
 *
 * With [takeFocus] the grid takes the focus when it appears (unless the rail has it): on entering a page, but not when the
 * person switches what the grid shows with a control above it.
 */
@Composable
internal fun <T> TvCardGrid(
    items: List<T>,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    state: LazyGridState = rememberLazyGridState(),
    card: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    var rememberedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var rememberedIndex by rememberSaveable { mutableIntStateOf(0) }
    var holdsFocus by remember { mutableStateOf(false) }
    // Once per list, not per move: every move of the focus recomposes the grid (the remembered card is state).
    val keys = remember(items) { items.map(key) }
    val indexOfKey = remember(keys) { HashMap<String, Int>(keys.size * 2).apply { keys.forEachIndexed { i, k -> put(k, i) } } }
    val target = rememberedKey?.let { indexOfKey[it] } ?: rememberedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    // Decided while composing the new items, before the old card leaves: after that the focus is already gone.
    val lostFocus = remember { GridFlag() }
    if (holdsFocus && rememberedKey != null && rememberedKey !in indexOfKey) lostFocus.set = true
    val railHasFocus = LocalRailHasFocus.current
    LaunchedEffect(Unit) {
        if (takeFocus && items.isNotEmpty() && !railHasFocus()) {
            state.bringIntoReach(target)
            focusRequester.requestFocusWhenPlaced()
        }
    }
    // Keyed on the remembered list itself: the same instance on a move, compared by reference.
    LaunchedEffect(keys) {
        if (lostFocus.set && items.isNotEmpty()) {
            lostFocus.set = false
            // A lazy grid drops the card that went when it lays itself out, and the focus with it: ask after that.
            withFrameNanos { }
            state.bringIntoReach(target)
            focusRequester.requestFocusWhenPlaced()
        }
    }
    LazyVerticalGrid(
        // As many columns as fit at the cards' width, then the cards widen to fill the row (no empty strip on the right).
        columns = GridCells.Adaptive(TvDimens.cardWidth),
        modifier = modifier
            .onFocusChanged { holdsFocus = it.hasFocus }
            // Into the grid from above: to the remembered card, not to the one nearest on screen.
            .focusProperties { onEnter = { focusRequester.requestFocus(FocusDirection.Enter) } },
        state = state,
        // Room for a focused card to grow without being cut off.
        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.gap),
        verticalArrangement = Arrangement.spacedBy(TvDimens.gap),
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            val cardKey = keys[index]
            card(
                item,
                Modifier
                    .fillMaxWidth()
                    .then(if (index == target) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused) {
                            rememberedKey = cardKey
                            rememberedIndex = index
                        }
                    },
            )
        }
    }
}

/** A plain flag, read and written outside of snapshots. */
private class GridFlag {
    var set = false
}

/** Composes the item at [index] if it is not, so that a focus request can reach it. */
internal suspend fun LazyGridState.bringIntoReach(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        withFrameNanos { }
    }
}
