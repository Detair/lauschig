package io.wolftown.lauschig.tv.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.discover.SeriesTile

/** A card's tag is this plus its key ([ItemSummary.key], or the series name). */
internal const val TAG_CARD = "tvCard:"
internal const val TAG_MESSAGE = "tvMessage"
internal const val TAG_MESSAGE_ACTION = "tvMessageAction"

private val CardShape = RoundedCornerShape(12.dp)

/** A title as a card: cover, title, author and, while it is being heard, how far. Focused, it grows and gets a frame. */
@Composable
fun TvCoverCard(item: ItemSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvCard(
        title = item.title,
        subtitle = item.author,
        cover = coverModel(LocalServerBaseUrl.current, item.id, item.coverPath, item.coverVersion),
        progress = item.progress.takeIf { !item.isFinished && it > 0f },
        onClick = onClick,
        modifier = modifier.testTag(TAG_CARD + item.key),
    )
}

/** A series as a card: the cover of one of its titles, its name and how many volumes there are. */
@Composable
internal fun TvSeriesCard(tile: SeriesTile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvCard(
        title = tile.name,
        subtitle = pluralStringResource(R.plurals.discover_volumes, tile.count, tile.count),
        cover = tile.cover?.let { coverModel(LocalServerBaseUrl.current, it.id, it.coverPath, it.coverVersion) },
        progress = null,
        onClick = onClick,
        modifier = modifier.testTag(TAG_CARD + tile.name),
    )
}

@Composable
private fun TvCard(title: String, subtitle: String?, cover: Any?, progress: Float?, onClick: () -> Unit, modifier: Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.width(TvDimens.cardWidth),
        shape = CardDefaults.shape(CardShape),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        // Seen from the sofa: the card grows, and a light frame marks it even on a light cover.
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), shape = CardShape)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            CoverArt(cover, title, Modifier.fillMaxSize(), shape = RectangleShape)
            progress?.let { p ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // Two lines always: the cards of a row are then all the same height.
            Text(title, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A row of titles under a heading. It keeps the card that had the focus, also across a visit to another page. */
@Composable
fun TvItemRow(title: String, items: List<ItemSummary>, onOpen: (ItemSummary) -> Unit, modifier: Modifier = Modifier) {
    TvCardRow(title, items, key = { it.key }, modifier = modifier) { item, cardModifier -> TvCoverCard(item, { onOpen(item) }, cardModifier) }
}

/**
 * A row of cards that remembers which one had the focus: coming back into the row (from above, below or another page) lands
 * on it, by its key, else at its place. When that card goes while it has the focus (the row reloads, or gets shorter), the
 * focus moves to the card now at its place, or the last one, and does not get lost. [focusRequester] reaches the remembered card.
 */
@Composable
internal fun <T> TvCardRow(
    title: String,
    items: List<T>,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    card: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    var rememberedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var rememberedIndex by rememberSaveable { mutableIntStateOf(0) }
    var holdsFocus by remember { mutableStateOf(false) }
    val keys = items.map(key)
    val target = keys.indexOf(rememberedKey).takeIf { it >= 0 } ?: rememberedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    // Decided while composing the new items, before the old card leaves: after that the focus is already gone.
    val lostFocus = remember { Flag() }
    if (holdsFocus && rememberedKey != null && rememberedKey !in keys) lostFocus.set = true
    val listState = rememberLazyListState()
    LaunchedEffect(keys) {
        if (lostFocus.set && items.isNotEmpty()) {
            lostFocus.set = false
            // A lazy list drops the card that went when it lays itself out, and the focus with it: ask after that.
            withFrameNanos { }
            listState.bringIntoReach(target)
            focusRequester.requestFocusWhenPlaced()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        LazyRow(
            Modifier
                .onFocusChanged { holdsFocus = it.hasFocus }
                // Into the row from above or below: to the remembered card, not to the one nearest on screen.
                .focusProperties { onEnter = { focusRequester.requestFocus(FocusDirection.Enter) } },
            state = listState,
            // Room for a focused card to grow without being cut off.
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.gap),
        ) {
            itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                val cardKey = key(item)
                card(
                    item,
                    Modifier
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
}

/** A plain flag, read and written outside of snapshots. */
private class Flag {
    var set = false
}

/**
 * Asks for the focus, also for an element a lazy list composes only when it lays itself out: then it waits a few frames for
 * it. False if it never came.
 */
internal suspend fun FocusRequester.requestFocusWhenPlaced(): Boolean {
    repeat(PLACEMENT_FRAMES) {
        if (requestFocus(FocusDirection.Enter)) return true
        withFrameNanos { }
    }
    return requestFocus(FocusDirection.Enter)
}

private const val PLACEMENT_FRAMES = 5

/** Composes the item at [index] if it is not, so that a focus request can reach it. */
internal suspend fun LazyListState.bringIntoReach(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        withFrameNanos { }
    }
}

/**
 * A page's message: loading, empty, offline, or a page still to come. It takes the focus when it appears, so a page with only
 * a message has one thing in focus too: the [action], or else the text.
 */
@Composable
fun TvMessage(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val withAction = action != null && onAction != null
    Column(
        Modifier.fillMaxSize().padding(horizontal = TvDimens.overscanHorizontal, vertical = TvDimens.overscanVertical),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .then(if (withAction) Modifier else Modifier.focusRequester(focus).focusable())
                .testTag(TAG_MESSAGE),
        )
        if (action != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.focusRequester(focus).testTag(TAG_MESSAGE_ACTION)) { Text(action) }
        }
    }
}
