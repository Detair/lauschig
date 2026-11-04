package io.wolftown.lauschig.ui.start

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.EmptyState
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.LoadingState
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.ProgressLine
import io.wolftown.lauschig.ui.common.SectionHeader
import io.wolftown.lauschig.ui.common.SeeAllButton
import io.wolftown.lauschig.ui.common.StatePage
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.extras.listenedText
import io.wolftown.lauschig.ui.theme.Spacing
import io.wolftown.lauschig.stats.StatsCard
import io.wolftown.lauschig.ui.stats.BarChart
import io.wolftown.lauschig.ui.stats.statsDuration
import io.wolftown.lauschig.ui.theme.TINT_FADE_MS
import io.wolftown.lauschig.ui.theme.controlColorOn
import io.wolftown.lauschig.ui.theme.coverTintTarget
import kotlin.math.roundToInt

@androidx.compose.runtime.Immutable
data class StartUiState(
    val continueItems: List<ItemSummary> = emptyList(),
    val nextInSeries: List<ItemSummary> = emptyList(),
    val recentlyAdded: List<ItemSummary> = emptyList(),
    val downloaded: List<ItemSummary> = emptyList(),
    /** Episode tiles: podcast cover, episode title, id = episode key. Empty without a podcast library. */
    val newEpisodes: List<ItemSummary> = emptyList(),
    /** The first data is not there yet (show neither sections nor "nothing listened to"). */
    val loading: Boolean = false,
)

@Composable
fun StartRoute(
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    viewModel: StartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    val stats by viewModel.statsCard.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    // An episode tile opens its podcast.
    val onOpen: (String) -> Unit = { onOpenItem(PlayableKey.parse(it).libraryItemId) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Spacing.gutter).weight(1f))
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("openSettings")) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title))
            }
        }
        StartScreen(
            state, onOpen = onOpen, onPlay = viewModel::play, onMenu = { menuFor = it }, onOpenLibrary = onOpenLibrary,
            onOpenDiscover = onOpenDiscover, modifier = Modifier.weight(1f), playingId = playingId, onPlayPause = viewModel::playOrPause,
            stats = stats, onOpenStats = onOpenStats,
        )
    }
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpen) }
}

/**
 * Tap opens the details, the play button plays, long-press opens the actions menu. The first "continue" title is a
 * large card with its own play/pause button ([onPlayPause]; pause while it is [playingId]); the others stay in the row.
 * Below them a small [stats] card (this week, the streak) opens the statistics; it is null while nothing was heard lately.
 */
@Composable
fun StartScreen(
    state: StartUiState,
    onOpen: (itemId: String) -> Unit,
    onPlay: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (ItemSummary) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    playingId: String? = null,
    onPlayPause: (itemId: String) -> Unit = onPlay,
    stats: StatsCard? = null,
    onOpenStats: () -> Unit = {},
) {
    if (state.continueItems.isEmpty() && state.nextInSeries.isEmpty() && state.recentlyAdded.isEmpty() && state.downloaded.isEmpty() && state.newEpisodes.isEmpty()) {
        StatePage(modifier) { padding ->
            if (state.loading) {
                LoadingState(verticalPadding = padding)
            } else {
                EmptyState(
                    Icons.Rounded.Headphones, stringResource(R.string.start_empty),
                    supporting = stringResource(R.string.start_empty_hint),
                    action = {
                        Button(onClick = onOpenLibrary, modifier = Modifier.testTag("openLibrary")) { Text(stringResource(R.string.start_open_library)) }
                    },
                    verticalPadding = padding,
                )
            }
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.l)) {
        val hero = state.continueItems.firstOrNull()
        if (hero != null) {
            item(key = "h-continue", contentType = "header") { StartSectionHeader(R.string.start_continue, onOpenLibrary, "seeAll-continue") }
            item(key = "hero", contentType = "hero") {
                ContinueHero(
                    hero, playing = hero.id == playingId, onOpen = onOpen, onPlayPause = onPlayPause, onMenu = onMenu,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            }
            val rest = state.continueItems.drop(1)
            if (rest.isNotEmpty()) item(key = "continue", contentType = "row") { TileRow(rest, onOpen, onPlay, onMenu, Modifier.padding(top = Spacing.m)) }
        }
        if (stats != null) {
            item(key = "stats", contentType = "stats") {
                StartStatsCard(stats, onOpenStats, Modifier.padding(horizontal = Spacing.gutter).padding(top = if (hero != null) Spacing.l else Spacing.section))
            }
        }
        section("episodes", R.string.start_new_episodes, state.newEpisodes, null, onOpen, onPlay, onMenu)
        section("next", R.string.start_next_in_series, state.nextInSeries, null, onOpen, onPlay, onMenu)
        section("recent", R.string.start_recently_added, state.recentlyAdded, onOpenDiscover, onOpen, onPlay, onMenu)
        section("downloaded", R.string.start_downloaded, state.downloaded, null, onOpen, onPlay, onMenu)
    }
}

/** A header (with an optional "See all" link) plus a tile row; empty sections are left out. */
private fun LazyListScope.section(
    key: String, titleRes: Int, items: List<ItemSummary>, onSeeAll: (() -> Unit)?,
    onOpen: (String) -> Unit, onPlay: (String) -> Unit, onMenu: (ItemSummary) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "h-$key", contentType = "header") { StartSectionHeader(titleRes, onSeeAll, "seeAll-$key") }
    item(key = key, contentType = "row") { TileRow(items, onOpen, onPlay, onMenu) }
}

@Composable
private fun StartSectionHeader(textRes: Int, onSeeAll: (() -> Unit)?, seeAllTag: String) {
    SectionHeader(
        stringResource(textRes),
        Modifier.padding(start = Spacing.gutter, end = Spacing.xs, top = Spacing.section),
        action = onSeeAll?.let { { SeeAllButton(it, Modifier.testTag(seeAllTag)) } },
    )
}

@Composable
private fun TileRow(items: List<ItemSummary>, onOpen: (String) -> Unit, onPlay: (String) -> Unit, onMenu: (ItemSummary) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier,
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.tileGap),
    ) {
        items(items, key = ItemSummary::key, contentType = { "tile" }) { item ->
            CoverTile(
                item, Modifier.width(140.dp),
                onClick = { onOpen(item.id) }, onLongClick = { onMenu(item) }, onPlay = { onPlay(item.id) },
            )
        }
    }
}

/** The widest the "continue" card gets (tablets); phones give it the full width. */
private val HERO_MAX_WIDTH = 560.dp

/**
 * The title to get back into: a card in the cover's tint with the cover, title, author, what is left, the progress
 * and a large play/pause button. Tapping the card opens the details, a long press the actions menu.
 */
@Composable
private fun ContinueHero(
    item: ItemSummary,
    playing: Boolean,
    onOpen: (String) -> Unit,
    onPlayPause: (String) -> Unit,
    onMenu: (ItemSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = coverModel(LocalServerBaseUrl.current, item.id, item.coverPath, item.coverVersion)
    val scheme = MaterialTheme.colorScheme
    val target = coverTintTarget(model, item.coverVersion, scheme.surfaceContainerHigh)
    val container by animateColorAsState(target, tween(TINT_FADE_MS), label = "heroTint")
    // Primary only where it keeps 3:1 on this card's tint, else the text colour (decided on the tint the card fades to).
    val control = scheme.controlColorOn(target)
    val onControl = if (control == scheme.primary) scheme.onPrimary else scheme.surface
    val unavailable = LocalOfflineDimming.current && !item.isDownloaded
    val unavailableText = stringResource(R.string.cover_not_available_offline)
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = container,
        contentColor = scheme.onSurface,
        shape = shape,
        modifier = modifier.widthIn(max = HERO_MAX_WIDTH).fillMaxWidth().testTag("continueHero"),
    ) {
        Row(
            Modifier
                .combinedClickable(
                    onClick = { onOpen(item.id) }, onLongClick = { onMenu(item) },
                    onLongClickLabel = stringResource(R.string.cover_actions),
                )
                .semantics(mergeDescendants = true) { if (unavailable) stateDescription = unavailableText }
                .padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(model, item.title, Modifier.size(112.dp).alpha(if (unavailable) 0.38f else 1f))
            Column(Modifier.weight(1f).padding(start = Spacing.l)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.author?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val left = item.remainingSec?.let { stringResource(R.string.start_continue_left, listenedText(it)) }
                            ?: stringResource(R.string.start_progress_percent, (item.progress * 100).roundToInt())
                        Text(left, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("heroLeft"))
                        ProgressLine(
                            { item.progress }, Modifier.padding(top = 6.dp, end = Spacing.s).clip(CircleShape).testTag("heroProgress"),
                            thickness = 4.dp, color = control, trackColor = scheme.onSurface.copy(alpha = 0.12f),
                        )
                    }
                    val label = if (playing) stringResource(R.string.player_pause) else stringResource(R.string.cover_play, item.title)
                    FilledIconButton(
                        onClick = { onPlayPause(item.id) },
                        enabled = !unavailable || playing,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = control, contentColor = onControl),
                        modifier = Modifier.size(56.dp).testTag("heroPlay"),
                    ) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = label, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

/**
 * This week at a glance: the listening time, the streak and the week's days as small bars (today in primary). Tapping it
 * opens the statistics. Said as one sentence; the bars are left out of it (the statistics page lists the values).
 */
@Composable
private fun StartStatsCard(card: StatsCard, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.widthIn(max = HERO_MAX_WIDTH).fillMaxWidth().testTag("statsCard"),
    ) {
        Row(
            Modifier
                .clickable(onClickLabel = stringResource(R.string.stats_card_open), onClick = onOpen)
                .semantics(mergeDescendants = true) {}
                .padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_card_week), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(statsDuration(card.weekSec), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                if (card.streak > 0) {
                    Text(
                        pluralStringResource(R.plurals.stats_card_streak, card.streak, card.streak),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BarChart(
                card.days.map { it.seconds }, card.days.indexOfFirst { it.current }.takeIf { it >= 0 }, description = null,
                modifier = Modifier.padding(start = Spacing.l).width(112.dp), height = 40.dp,
            )
        }
    }
}
