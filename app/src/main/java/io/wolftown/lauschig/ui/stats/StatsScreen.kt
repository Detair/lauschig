package io.wolftown.lauschig.ui.stats

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.stats.AuthorTime
import io.wolftown.lauschig.stats.BarUnit
import io.wolftown.lauschig.stats.FinishedSeries
import io.wolftown.lauschig.stats.PeriodRange
import io.wolftown.lauschig.stats.PeriodSummary
import io.wolftown.lauschig.stats.StatsPeriod
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.EmptyState
import io.wolftown.lauschig.ui.common.LoadingState
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.SectionHeader
import io.wolftown.lauschig.ui.common.StatePage
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.theme.Spacing
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle
import java.util.Formatter
import java.util.Locale

@Composable
fun StatsRoute(onBack: () -> Unit, onOpenItem: (itemId: String) -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatsScreen(state, onBack, onPeriod = viewModel::setPeriod, onPrevious = viewModel::previous, onNext = viewModel::next, onOpenItem = onOpenItem)
}

/** Tabular figures, so numbers that change keep their width. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/**
 * The listening statistics: a period (week, month, year, all time) with previous/next, its total, a bar per day, month
 * or year, streaks and finished books, then the finished books, the most heard titles and authors (and in "all time"
 * the finished series). Everything comes from the device; without any listening the page is its empty state.
 */
@Composable
fun StatsScreen(
    state: StatsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPeriod: (StatsPeriod) -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOpenItem: (itemId: String) -> Unit = {},
) {
    Column(modifier.fillMaxSize().testTag("statsScreen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleLarge)
        }
        val summary = state.summary
        if (!state.hasData || summary == null) {
            StatePage { padding ->
                if (state.loading || state.refreshing) {
                    LoadingState(verticalPadding = padding)
                } else {
                    EmptyState(
                        Icons.Rounded.BarChart, stringResource(R.string.stats_empty),
                        supporting = stringResource(if (state.unreached) R.string.stats_unreached else R.string.stats_empty_hint),
                        verticalPadding = padding,
                    )
                }
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize().testTag("statsList"), contentPadding = PaddingValues(bottom = Spacing.l)) {
            item(key = "periods", contentType = "periods") { PeriodChoice(state.period, onPeriod, Modifier.padding(horizontal = Spacing.gutter)) }
            item(key = "head", contentType = "head") {
                PeriodHead(state, summary, onPrevious, onNext, Modifier.padding(horizontal = Spacing.xs).padding(top = Spacing.m))
            }
            item(key = "chart", contentType = "chart") {
                PeriodChart(summary, Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.l).testTag("statsChart"))
            }
            item(key = "tiles", contentType = "tiles") {
                Tiles(state, Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.xl))
            }
            titles("finished", R.string.stats_section_finished, state.finished, onOpenItem)
            titles("top", R.string.stats_section_top_titles, state.topTitles, onOpenItem)
            authors(state.topAuthors)
            series(state.finishedSeries)
        }
    }
}

/** Week, month, year, all time: one choice, as one row (the labels shrink a little rather than wrap at large text). */
@Composable
private fun PeriodChoice(current: StatsPeriod, onPeriod: (StatsPeriod) -> Unit, modifier: Modifier = Modifier) {
    val periods = StatsPeriod.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        periods.forEachIndexed { i, p ->
            val style = MaterialTheme.typography.labelLarge
            SegmentedButton(
                selected = p == current,
                onClick = { onPeriod(p) },
                shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                icon = {},
                label = {
                    Text(
                        stringResource(p.label()),
                        maxLines = 1,
                        style = style,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
                    )
                },
                modifier = Modifier.testTag("period:$p"),
            )
        }
    }
}

private fun StatsPeriod.label(): Int = when (this) {
    StatsPeriod.WEEK -> R.string.stats_period_week
    StatsPeriod.MONTH -> R.string.stats_period_month
    StatsPeriod.YEAR -> R.string.stats_period_year
    StatsPeriod.ALL -> R.string.stats_period_all
}

/** The period's name between previous/next, its total big, and on how many days. */
@Composable
private fun PeriodHead(state: StatsUiState, summary: PeriodSummary, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val navigable = state.period != StatsPeriod.ALL
            if (navigable) {
                IconButton(onClick = onPrevious, enabled = state.hasPrevious, modifier = Modifier.testTag("statsPrevious")) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.stats_previous))
                }
            }
            Text(
                periodLabel(summary.range),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.m).semantics { heading() }.testTag("statsPeriodLabel"),
            )
            if (navigable) {
                IconButton(onClick = onNext, enabled = state.hasNext, modifier = Modifier.testTag("statsNext")) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.stats_next))
                }
            }
        }
        Text(
            statsDuration(summary.totalSec),
            style = MaterialTheme.typography.displaySmall.merge(Tabular),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.m).testTag("statsTotal"),
        )
        Text(
            if (summary.activeDays > 0) pluralStringResource(R.plurals.stats_active_days, summary.activeDays, summary.activeDays)
            else stringResource(R.string.stats_period_nothing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs).testTag("statsActiveDays"),
        )
    }
}

/** Week "2.–8. Nov. 2026", month "November 2026", year "2026", all time "Since 3 Mar 2024", in the app's locale. */
@Composable
private fun periodLabel(range: PeriodRange): String {
    val context = LocalContext.current
    val locale = appLocale()
    return when (range.period) {
        StatsPeriod.WEEK -> {
            // Calendar days, formatted in UTC so no zone shifts them; the end is the last day's noon (inclusive).
            val start = range.start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val end = range.end.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
            DateUtils.formatDateRange(context, Formatter(StringBuilder(), locale), start, end, flags, "UTC").toString()
        }
        StatsPeriod.MONTH -> range.start.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMy"), locale))
        StatsPeriod.YEAR -> range.start.year.toString()
        StatsPeriod.ALL -> stringResource(R.string.stats_since, range.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
    }
}

/** The locale the app's texts are in (observable: a change recomposes). */
@Composable
private fun appLocale(): Locale = LocalConfiguration.current.locales[0]

/** "5 h 12 min", "40 min", "30 s", and "0 min" for nothing. */
@Composable
fun statsDuration(sec: Double): String {
    val total = sec.coerceAtLeast(0.0).toLong()
    val h = (total / 3600).toInt()
    val m = (total % 3600 / 60).toInt()
    return when {
        h > 0 -> stringResource(R.string.history_duration_h_m, h, m)
        m > 0 || total == 0L -> stringResource(R.string.history_duration_m, m)
        else -> stringResource(R.string.history_duration_s, total.toInt())
    }
}

@Composable
private fun PeriodChart(summary: PeriodSummary, modifier: Modifier = Modifier) {
    val locale = appLocale()
    val bars = summary.bars
    val labels: List<String?> = when (summary.unit) {
        BarUnit.DAY -> if (summary.range.period == StatsPeriod.WEEK) bars.map { it.start.dayOfWeek.getDisplayName(DateTextStyle.SHORT, locale) }
        else bars.map { b -> b.start.dayOfMonth.takeIf { it % 7 == 1 }?.toString() }
        BarUnit.MONTH -> bars.map { it.start.month.getDisplayName(DateTextStyle.NARROW_STANDALONE, locale) }
        BarUnit.YEAR -> {
            val every = (bars.size + 7) / 8
            bars.mapIndexed { i, b -> if ((bars.size - 1 - i) % every == 0) b.start.year.toString() else null }
        }
    }
    val dayName = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale)
    // Spoken: every bar, but a month's days only where something was heard.
    val spoken = bars.filter { summary.unit != BarUnit.DAY || summary.range.period == StatsPeriod.WEEK || it.seconds > 0 }
    val entries = spoken.map { b ->
        val name = when (summary.unit) {
            BarUnit.DAY -> if (summary.range.period == StatsPeriod.WEEK) b.start.dayOfWeek.getDisplayName(DateTextStyle.FULL, locale) else b.start.format(dayName)
            BarUnit.MONTH -> b.start.month.getDisplayName(DateTextStyle.FULL_STANDALONE, locale)
            BarUnit.YEAR -> b.start.year.toString()
        }
        stringResource(R.string.stats_chart_entry, name, statsDuration(b.seconds))
    }
    val description = stringResource(
        when (summary.unit) {
            BarUnit.DAY -> R.string.stats_chart_days
            BarUnit.MONTH -> R.string.stats_chart_months
            BarUnit.YEAR -> R.string.stats_chart_years
        },
        entries.joinToString(", "),
    )
    BarChart(bars.map { it.seconds }, bars.indexOfFirst { it.current }.takeIf { it >= 0 }, description, modifier, labels = labels)
}

/** The streak now, the longest one and the books finished in the period. */
@Composable
private fun Tiles(state: StatsUiState, modifier: Modifier = Modifier) {
    TileLine(modifier.fillMaxWidth()) {
        val books = state.finished.size
        Tile(R.string.stats_tile_streak, pluralStringResource(R.plurals.stats_days, state.streaks.current, state.streaks.current), Modifier.testTag("tileStreak"))
        Tile(R.string.stats_tile_longest, pluralStringResource(R.plurals.stats_days, state.streaks.longest, state.streaks.longest), Modifier.testTag("tileLongest"))
        // All time also counts the finished series (they have no date, so no other period does).
        val series = state.finishedSeries.size.takeIf { state.period == StatsPeriod.ALL }
        Tile(
            R.string.stats_tile_finished, pluralStringResource(R.plurals.stats_books, books, books), Modifier.testTag("tileFinished"),
            extra = series?.let { pluralStringResource(R.plurals.stats_series, it, it) },
        )
    }
}

/**
 * The tiles side by side in equal widths and one height – unless a tile would have to break a word there (large text,
 * narrow window): then each takes the full width, one below the other.
 */
@Composable
private fun TileLine(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = Spacing.s.roundToPx()
        val width = constraints.maxWidth
        val share = ((width - gap * (measurables.size - 1)) / measurables.size.coerceAtLeast(1)).coerceAtLeast(0)
        // The min intrinsic width is the widest word (plus padding): what cannot wrap any further.
        val sideBySide = measurables.all { it.minIntrinsicWidth(Constraints.Infinity) <= share }
        if (sideBySide) {
            val height = measurables.maxOf { it.minIntrinsicHeight(share) }
            val placeables = measurables.map { it.measure(Constraints.fixed(share, height)) }
            layout(width, height) { placeables.forEachIndexed { i, p -> p.placeRelative(i * (share + gap), 0) } }
        } else {
            val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                placeables.forEach { p -> p.placeRelative(0, y); y += p.height + gap }
            }
        }
    }
}

@Composable
private fun Tile(label: Int, value: String, modifier: Modifier = Modifier, extra: String? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp),
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(Spacing.m)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge.merge(Tabular), modifier = Modifier.padding(top = Spacing.xs))
            extra?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun LazyListScope.sectionHeader(key: String, title: Int) {
    item(key = "h-$key", contentType = "header") {
        SectionHeader(stringResource(title), Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.section))
    }
}

/** A list of titles with cover; a title the catalog knows opens its details. */
private fun LazyListScope.titles(key: String, title: Int, rows: List<StatsTitle>, onOpenItem: (String) -> Unit) {
    if (rows.isEmpty()) return
    sectionHeader(key, title)
    items(rows, key = { "$key:${it.itemId}" }, contentType = { "title" }) { row -> TitleRow(row, onOpenItem) }
}

@Composable
private fun TitleRow(row: StatsTitle, onOpenItem: (String) -> Unit) {
    val name = row.title ?: stringResource(R.string.stats_unknown_title)
    val trailing = when {
        row.seconds != null -> statsDuration(row.seconds)
        row.day != null -> row.day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(appLocale(), "dMMM")))
        else -> stringResource(R.string.stats_finished_earlier)
    }
    ListItem(
        headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = row.author?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            CoverArt(coverModel(LocalServerBaseUrl.current, row.itemId, row.coverPath, row.coverVersion), name, Modifier.size(48.dp), initialsStyle = MaterialTheme.typography.labelLarge)
        },
        trailingContent = { Text(trailing, style = MaterialTheme.typography.labelLarge.merge(Tabular)) },
        modifier = Modifier.fillMaxWidth().then(if (row.known) Modifier.clickable { onOpenItem(row.itemId) } else Modifier),
    )
}

private fun LazyListScope.authors(rows: List<AuthorTime>) {
    if (rows.isEmpty()) return
    sectionHeader("authors", R.string.stats_section_top_authors)
    items(rows, key = { "author:${it.name}" }, contentType = { "author" }) { a ->
        ListItem(
            headlineContent = { Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            trailingContent = { Text(statsDuration(a.seconds), style = MaterialTheme.typography.labelLarge.merge(Tabular)) },
            )
    }
}

private fun LazyListScope.series(rows: List<FinishedSeries>) {
    if (rows.isEmpty()) return
    sectionHeader("series", R.string.stats_section_series)
    items(rows, key = { "series:${it.key}" }, contentType = { "series" }) { s ->
        ListItem(
            headlineContent = { Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(pluralStringResource(R.plurals.stats_series_volumes, s.volumes, s.volumes)) },
            leadingContent = {
                CoverArt(coverModel(LocalServerBaseUrl.current, s.coverItemId, null, 0), s.name, Modifier.size(48.dp), initialsStyle = MaterialTheme.typography.labelLarge)
            },
            )
    }
}
