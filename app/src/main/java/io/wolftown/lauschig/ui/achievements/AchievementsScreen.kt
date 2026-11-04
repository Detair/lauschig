package io.wolftown.lauschig.ui.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.stats.AchievementState
import io.wolftown.lauschig.ui.common.LoadingState
import io.wolftown.lauschig.ui.common.ProgressLine
import io.wolftown.lauschig.ui.common.StatePage
import io.wolftown.lauschig.ui.theme.Spacing

@Composable
fun AchievementsRoute(onBack: () -> Unit, viewModel: AchievementsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AchievementsScreen(state, onBack)
}

/** The narrowest a card gets; the grid fits as many columns as the width allows (at least one). */
private val CARD_MIN_WIDTH = 160.dp

/** Tabular figures, so numbers that change keep their width. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/**
 * Every achievement as a card in a grid of [CARD_MIN_WIDTH] columns: recently unlocked first, then the closest to their
 * next tier. The cards of one row are as tall as the tallest of them. TalkBack reads each card as one item.
 */
@Composable
fun AchievementsScreen(state: AchievementsUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag("achievementsScreen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("achievementsBack")) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back))
            }
            Text(stringResource(R.string.achievements_title), style = MaterialTheme.typography.titleLarge)
        }
        if (state.loading) {
            StatePage { padding -> LoadingState(verticalPadding = padding) }
            return
        }
        // A column is never narrower than the widest word on a card (large text): fewer columns rather than a broken word.
        val cardMin = maxOf(CARD_MIN_WIDTH, widestWord(state.cards) + Spacing.m * 2)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gap = Spacing.tileGap
            val columns = ((maxWidth - Spacing.gutter * 2 + gap) / (cardMin + gap)).toInt().coerceAtLeast(1)
            val rows = state.cards.chunked(columns)
            LazyColumn(
                Modifier.fillMaxSize().testTag("achievementsGrid"),
                contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                item(key = "summary", contentType = "summary") {
                    Text(
                        stringResource(R.string.achievements_summary, state.unlockedTiers, state.totalTiers),
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.xs),
                    )
                }
                items(rows, key = { row -> row.joinToString { it.achievement.id } }, contentType = { "row" }) { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("achievementRow"), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { AchievementCard(it, Modifier.weight(1f).fillMaxHeight()) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** The widest single word of the cards' texts in their styles (what cannot wrap any further). */
@Composable
private fun widestWord(cards: List<AchievementState>): Dp {
    val type = MaterialTheme.typography
    val texts = buildList {
        for (s in cards) {
            add(achievementName(s.achievement, s.tier) to type.titleSmall)
            add(achievementDescription(s.achievement) to type.bodySmall)
            achievementTierLine(s)?.let { add(it to type.labelMedium) }
            achievementProgress(s)?.let { add(it to type.labelMedium.merge(Tabular)) }
            add(achievementStatus(s) to type.labelMedium)
        }
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(texts, density) {
        texts.maxOfOrNull { (text, style) ->
            text.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { word -> measurer.measure(word, style, softWrap = false, maxLines = 1).size.width } ?: 0
        } ?: 0
    }
    return with(density) { widest.toDp() }
}

/**
 * Icon in a tonal circle, the tier's name, what it takes; for a tier its number („Stufe 3 von 7“) and the progress to
 * the next („37 / 50 Std.“); since when it is reached, or that it is not yet.
 */
@Composable
fun AchievementCard(s: AchievementState, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        // One stop for TalkBack: its lines in order.
        modifier = modifier.semantics(mergeDescendants = true) {}.testTag("achievement:${s.achievement.id}"),
    ) {
        Column(Modifier.padding(Spacing.m)) {
            AchievementBadge(s.achievement, s.unlocked)
            Text(achievementName(s.achievement, s.tier), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.s))
            Text(achievementDescription(s.achievement), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = Spacing.xs))
            // What follows sits at the bottom, so the lines of cards side by side line up.
            Spacer(Modifier.weight(1f))
            achievementTierLine(s)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = Spacing.s))
            }
            val progress = s.progress
            val progressText = achievementProgress(s)
            val progressSpoken = achievementProgress(s, spoken = true)
            if (progress != null && progressText != null && progressSpoken != null) {
                // The polish package's line (as on the "continue" card); the text below says the numbers, so TalkBack skips it.
                ProgressLine(
                    { progress }, Modifier.padding(top = Spacing.s).clip(CircleShape).testTag("progress:${s.achievement.id}").clearAndSetSemantics {},
                    thickness = 4.dp, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                )
                // Shown "37 / 50 Std.", said "37 von 50 Std.".
                Text(
                    progressText, style = MaterialTheme.typography.labelMedium.merge(Tabular), color = muted,
                    modifier = Modifier.padding(top = Spacing.xs).clearAndSetSemantics { text = AnnotatedString(progressSpoken) },
                )
            }
            Text(achievementStatus(s), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}
