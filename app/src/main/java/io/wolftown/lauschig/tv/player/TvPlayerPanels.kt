package io.wolftown.lauschig.tv.player

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.SleepMode
import io.wolftown.lauschig.playback.SleepTimerUi
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tv.browse.PANEL_WIDTH
import io.wolftown.lauschig.tv.common.requestFocusWhenPlaced
import io.wolftown.lauschig.ui.detail.formatClock
import io.wolftown.lauschig.ui.player.PlayerViewModel
import io.wolftown.lauschig.ui.player.formatSpeed
import kotlin.math.ceil
import kotlin.math.floor

internal const val TAG_CONTROLS = "tvPlayerControls"
internal const val TAG_CHAPTERS_BUTTON = "tvPlayerChapters"
internal const val TAG_SLOWER = "tvPlayerSlower"
internal const val TAG_FASTER = "tvPlayerFaster"
internal const val TAG_SPEED = "tvPlayerSpeed"
internal const val TAG_SLEEP_BUTTON = "tvPlayerSleep"
internal const val TAG_SLEEP_PANEL = "tvPlayerSleepPanel"
/** An option of the sleep panel is tagged with this plus [sleepOptionKey]. */
internal const val TAG_SLEEP_OPTION = "tvPlayerSleepOption:"

/** The sleep timer's choices on the TV, as on the phone: minutes, the end of the chapter, off (null). */
internal val SLEEP_OPTIONS: List<SleepMode?> = listOf(15, 30, 45, 60).map { SleepMode.Minutes(it) } + SleepMode.EndOfChapter + null

internal fun sleepOptionKey(mode: SleepMode?): String = when (mode) {
    is SleepMode.Minutes -> mode.minutes.toString()
    SleepMode.EndOfChapter -> "chapter"
    null -> "off"
}

/**
 * One step of 0.1 from [speed], up or down, within the player's limits; null at the limit. A speed between two steps (the
 * phone offers 1.25) goes to the next step in that direction, not one further.
 */
internal fun speedStep(speed: Float, up: Boolean): Float? {
    val tenths = speed * 10
    val next = if (up) (floor(tenths + EPSILON) + 1) / 10 else (ceil(tenths - EPSILON) - 1) / 10
    val clamped = next.coerceIn(PlayerViewModel.MIN_SPEED, PlayerViewModel.MAX_SPEED)
    return clamped.takeIf { kotlin.math.abs(it - speed) > EPSILON / 10 }
}

private const val EPSILON = 0.001f

/** Which button of the bar opened a panel, so that the focus goes back to it when the panel closes. */
internal enum class TvPlayerPanel { None, Chapters, Sleep }

/**
 * The bar below the stage: chapters, speed with − and +, the sleep timer. [first] takes the focus when the bar opens; up
 * leaves it (the screen's handler).
 */
@Composable
internal fun TvControlBar(
    speed: Float,
    sleep: SleepTimerUi?,
    hasChapters: Boolean,
    first: FocusRequester,
    chaptersButton: FocusRequester,
    sleepButton: FocusRequester,
    onChapters: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slower = speedStep(speed, up = false)
    val faster = speedStep(speed, up = true)
    Row(
        modifier.focusGroup().testTag(TAG_CONTROLS),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChapters) {
            BarButton(
                stringResource(R.string.player_chapters), Icons.AutoMirrored.Rounded.List, onChapters,
                Modifier.focusRequester(chaptersButton).focusRequester(first).testTag(TAG_CHAPTERS_BUTTON),
            )
        }
        val slowerLabel = stringResource(R.string.tv_player_slower)
        // Stays in the row at the limit, so the focus does not jump; it only does nothing there.
        OutlinedButton(
            onClick = { slower?.let(onSpeed) }, enabled = slower != null,
            modifier = Modifier
                .then(if (hasChapters) Modifier else Modifier.focusRequester(first))
                .semantics { contentDescription = slowerLabel }
                .testTag(TAG_SLOWER),
        ) { Icon(Icons.Rounded.Remove, contentDescription = null, modifier = Modifier.size(20.dp)) }
        val speedText = formatSpeed(speed)
        val speedLabel = stringResource(R.string.player_speed_button, speedText)
        Text(
            speedText, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { contentDescription = speedLabel }.testTag(TAG_SPEED),
        )
        val fasterLabel = stringResource(R.string.tv_player_faster)
        OutlinedButton(
            onClick = { faster?.let(onSpeed) }, enabled = faster != null,
            modifier = Modifier.semantics { contentDescription = fasterLabel }.testTag(TAG_FASTER),
        ) { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp)) }
        BarButton(sleepLabel(sleep), Icons.Rounded.Bedtime, onSleep, Modifier.focusRequester(sleepButton).testTag(TAG_SLEEP_BUTTON))
    }
}

/** "Sleep timer", or what is left of the running one. */
@Composable
internal fun sleepLabel(sleep: SleepTimerUi?): String = when (sleep?.mode) {
    null -> stringResource(R.string.player_sleep_timer)
    is SleepMode.Minutes -> stringResource(R.string.player_sleep_active, formatClock(sleep.remainingMs / 1000.0))
    SleepMode.EndOfChapter -> stringResource(R.string.player_sleep_chapter_active, formatClock(sleep.remainingMs / 1000.0))
}

@Composable
private fun BarButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/**
 * The sleep timer's choices as a list on the right, like the chapters. It opens on the running choice (on "Off" without a
 * timer); OK sets it. The focus stays inside while it is open.
 */
@Composable
internal fun TvSleepPanel(current: SleepMode?, onSleep: (SleepMode?) -> Unit, modifier: Modifier = Modifier) {
    val start = SLEEP_OPTIONS.indexOf(current).coerceAtLeast(0)
    val target = remember { FocusRequester() }
    LaunchedEffect(Unit) { target.requestFocusWhenPlaced() }
    Surface(
        modifier.fillMaxHeight().width(PANEL_WIDTH),
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(start = 24.dp, end = TvDimens.overscanHorizontal, top = TvDimens.overscanVertical, bottom = TvDimens.overscanVertical)) {
            Text(stringResource(R.string.player_sleep_timer), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(
                Modifier
                    .testTag(TAG_SLEEP_PANEL)
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(SLEEP_OPTIONS, key = { _, mode -> sleepOptionKey(mode) }) { i, mode ->
                    ListItem(
                        selected = mode == current,
                        onClick = { onSleep(mode) },
                        headlineContent = { Text(sleepOptionText(mode)) },
                        modifier = Modifier
                            .then(if (i == start) Modifier.focusRequester(target) else Modifier)
                            .testTag(TAG_SLEEP_OPTION + sleepOptionKey(mode)),
                    )
                }
            }
        }
    }
}

@Composable
private fun sleepOptionText(mode: SleepMode?): String = when (mode) {
    is SleepMode.Minutes -> stringResource(R.string.sleep_minutes, mode.minutes)
    SleepMode.EndOfChapter -> stringResource(R.string.sleep_end_of_chapter)
    null -> stringResource(R.string.player_tile_off)
}
