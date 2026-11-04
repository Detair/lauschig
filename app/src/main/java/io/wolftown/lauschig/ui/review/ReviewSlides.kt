package io.wolftown.lauschig.ui.review

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Commute
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.stats.Achievements
import io.wolftown.lauschig.stats.FinishedBook
import io.wolftown.lauschig.stats.ListenerType
import io.wolftown.lauschig.stats.ListeningTime
import io.wolftown.lauschig.stats.ReviewSlide
import io.wolftown.lauschig.stats.YearReview
import io.wolftown.lauschig.ui.achievements.AchievementBadge
import io.wolftown.lauschig.ui.achievements.achievementName
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.stats.BarChart
import io.wolftown.lauschig.ui.stats.statsDuration
import io.wolftown.lauschig.ui.theme.Spacing
import io.wolftown.lauschig.ui.theme.MIN_CONTROL_CONTRAST
import io.wolftown.lauschig.ui.theme.contrastRatio
import io.wolftown.lauschig.ui.theme.coverTintTarget
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** A title's cover as the slides draw it: the local file where there is one, else the server's, and its version. */
@Immutable
data class ReviewCover(val path: String?, val version: Long = 0)

/** The slide's lead title, whose cover tints it: the most heard title, the author's, the last finished book. */
val ReviewSlide.leadItemId: String?
    get() = when (this) {
        is ReviewSlide.TopTitles -> titles.firstOrNull()?.itemId
        is ReviewSlide.TopPeople -> authorItemId
        is ReviewSlide.Finished -> last.itemId
        else -> null
    }

@Composable
fun reviewCoverModel(itemId: String, covers: Map<String, ReviewCover>): Any? =
    covers[itemId].let { coverModel(LocalServerBaseUrl.current, itemId, it?.path, it?.version ?: 0) }

/**
 * A slide that leads with one title is the cover's tint over the raised surface (texts keep their contrast, see
 * `coverTintOver`); the others are the theme's containers – primary for the opening and the closing, secondary and
 * tertiary in turn in between.
 */
@Composable
fun reviewSlideColors(slide: ReviewSlide, covers: Map<String, ReviewCover>): SlideColors {
    val scheme = MaterialTheme.colorScheme
    val lead = slide.leadItemId
    return when (slide) {
        is ReviewSlide.Opening, is ReviewSlide.Closing -> SlideColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        is ReviewSlide.Days, is ReviewSlide.Achieved -> SlideColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        is ReviewSlide.Rhythm, is ReviewSlide.Personality -> SlideColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        is ReviewSlide.TopTitles, is ReviewSlide.TopPeople, is ReviewSlide.Finished -> {
            val model = lead?.let { reviewCoverModel(it, covers) }
            SlideColors(coverTintTarget(model, lead?.let { covers[it]?.version } ?: 0, scheme.surfaceContainerHigh), scheme.onSurface)
        }
    }
}

/** Tabular figures, so a number that counts up keeps its width per digit. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** The count-up of a slide's big number. */
private const val COUNT_UP_MS = 900

/** The fade-in of a slide's big name. */
private const val FADE_IN_MS = 500

@Composable
private fun appLocale(): Locale = LocalConfiguration.current.locales[0]

/** A slide is wide when it has more room across than down (phones in landscape). */
@Composable
private fun wide(): Boolean = LocalStoryViewport.current.let { it.width > it.height && it.width >= 480.dp }

/** Whole hours, never rounded up: 1 h 50 min is one hour listened. */
private fun hours(sec: Double): Int = floor(sec / 3600).toInt()

@Composable
private fun shortDate(day: LocalDate): String {
    val locale = appLocale()
    return day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMM"), locale))
}

@Composable
private fun percent(share: Double): String = NumberFormat.getPercentInstance(appLocale()).format(share)

@Composable
private fun timeFormat(): DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(appLocale())

// --- the words of each slide (shown and spoken) -----------------------------------------------------------------------

@Composable
private fun hoursUnit(sec: Double): String = hours(sec).let { pluralStringResource(R.plurals.review_hours_listened, it, it) }

@Composable
private fun streakLine(s: ReviewSlide.Days): String? =
    s.streakEnd?.let { pluralStringResource(R.plurals.review_streak, s.longestStreak, s.longestStreak, shortDate(it)) }

@Composable
private fun monthName(s: ReviewSlide.Rhythm): String = s.topMonth.getDisplayName(DateTextStyle.FULL_STANDALONE, appLocale())

@Composable
private fun weekdayLine(s: ReviewSlide.Rhythm): String? =
    s.weekday?.let { stringResource(R.string.review_weekday, it.getDisplayName(DateTextStyle.FULL, appLocale())) }

@Composable
private fun timeLine(t: ListeningTime?): String? = t?.let {
    stringResource(
        when (it) {
            ListeningTime.MORNING -> R.string.review_time_morning
            ListeningTime.AFTERNOON -> R.string.review_time_afternoon
            ListeningTime.EVENING -> R.string.review_time_evening
            ListeningTime.NIGHT -> R.string.review_time_night
            ListeningTime.COMMUTE_MORNING -> R.string.review_time_commute_morning
            ListeningTime.COMMUTE_EVENING -> R.string.review_time_commute_evening
        },
    )
}

@Composable
private fun titleName(title: String?): String = title ?: stringResource(R.string.stats_unknown_title)

@Composable
private fun narratorLine(s: ReviewSlide.TopPeople): String? =
    s.narrator?.let { stringResource(R.string.review_narrator, it.name, statsDuration(it.seconds)) }

@Composable
private fun seriesLine(s: ReviewSlide.Finished): String? =
    s.series.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.review_series_completed, it, it) }

@Composable
private fun bookLine(label: Int?, book: FinishedBook): String {
    val name = titleName(book.title)
    val day = book.day?.let { shortDate(it) }.orEmpty()
    return if (label == null) stringResource(R.string.review_book_only, name, day) else stringResource(label, name, day)
}

/** The first and the last finished book, labelled; one line for a single book. */
@Composable
private fun bookLines(s: ReviewSlide.Finished): List<Pair<FinishedBook, String>> =
    if (s.first.itemId == s.last.itemId) listOf(s.first to bookLine(null, s.first))
    else listOf(s.first to bookLine(R.string.review_book_first, s.first), s.last to bookLine(R.string.review_book_last, s.last))

@Composable
fun listenerTypeName(type: ListenerType): String = stringResource(
    when (type) {
        ListenerType.NIGHT_OWL -> R.string.review_type_night_owl
        ListenerType.EARLY_BIRD -> R.string.review_type_early_bird
        ListenerType.COMMUTER -> R.string.review_type_commuter
        ListenerType.WEEKEND -> R.string.review_type_weekend
        ListenerType.MARATHON -> R.string.review_type_marathon
    },
)

private fun ListenerType.icon(): ImageVector = when (this) {
    ListenerType.NIGHT_OWL -> Icons.Rounded.NightsStay
    ListenerType.EARLY_BIRD -> Icons.Rounded.WbTwilight
    ListenerType.COMMUTER -> Icons.Rounded.Commute
    ListenerType.WEEKEND -> Icons.Rounded.Weekend
    ListenerType.MARATHON -> Icons.AutoMirrored.Rounded.DirectionsRun
}

/** Why this type, in one line; its numbers and windows come from the rule ([YearReview]). */
@Composable
private fun whyLine(s: ReviewSlide.Personality): String {
    val share = percent(s.share)
    val format = timeFormat()
    fun time(t: LocalTime) = t.format(format)
    val or = stringResource(R.string.achievement_or)
    return when (s.type) {
        ListenerType.NIGHT_OWL -> stringResource(R.string.review_why_window, share, time(YearReview.NIGHT.first), time(YearReview.NIGHT.second))
        ListenerType.EARLY_BIRD -> stringResource(R.string.review_why_window, share, time(YearReview.EARLY.first), time(YearReview.EARLY.second))
        ListenerType.COMMUTER -> stringResource(
            R.string.review_why_commuter, share,
            Achievements.COMMUTE_WINDOWS.joinToString(" $or ") { "${time(it.first)}–${time(it.second)}" },
        )
        ListenerType.WEEKEND -> stringResource(R.string.review_why_weekend, share)
        ListenerType.MARATHON -> {
            val h = (YearReview.MARATHON_DAY_SEC / 3600).roundToInt()
            pluralStringResource(R.plurals.review_why_marathon, s.days, s.days, pluralStringResource(R.plurals.achievement_hours, h, h))
        }
    }
}

/** The closing card's facts: label and value, only those with data. */
@Composable
private fun closingFacts(s: ReviewSlide.Closing): List<Pair<String, String>> = buildList {
    add(stringResource(R.string.review_fact_time) to statsDuration(s.totalSec))
    add(stringResource(R.string.review_fact_days) to pluralStringResource(R.plurals.stats_days, s.activeDays, s.activeDays))
    if (s.finished > 0) add(stringResource(R.string.review_fact_finished) to pluralStringResource(R.plurals.stats_books, s.finished, s.finished))
    s.topTitle?.let { add(stringResource(R.string.review_fact_title) to titleName(it.title)) }
    s.topAuthor?.let { add(stringResource(R.string.review_fact_author) to it) }
    s.type?.let { add(stringResource(R.string.review_fact_type) to listenerTypeName(it)) }
}

/** Joins a slide's lines into sentences for TalkBack (no doubled full stops). */
private fun sentences(parts: List<String?>): String =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ") { p -> p.trim().let { if (it.last() in ".!?…") it else "$it." } }

/** What TalkBack says for a slide (the pager adds its position): the same words the slide shows, in reading order. */
@Composable
fun reviewSlideDescription(slide: ReviewSlide): String = when (slide) {
    is ReviewSlide.Opening -> sentences(
        listOf(
            stringResource(R.string.review_opening_title, slide.year),
            "${hours(slide.totalSec)} ${hoursUnit(slide.totalSec)}, ${pluralStringResource(R.plurals.review_titles, slide.titles, slide.titles)}",
            if (slide.incomplete) stringResource(R.string.review_incomplete) else null,
        ),
    )
    is ReviewSlide.Days -> sentences(
        listOf(
            stringResource(R.string.review_days_title),
            "${slide.activeDays} ${pluralStringResource(R.plurals.review_days_unit, slide.activeDays)}",
            streakLine(slide),
        ),
    )
    is ReviewSlide.Rhythm -> sentences(
        listOf(
            stringResource(R.string.review_rhythm_title),
            "${monthName(slide)}, ${stringResource(R.string.review_top_month, statsDuration(slide.topMonthSec))}",
            weekdayLine(slide),
            timeLine(slide.time),
        ),
    )
    is ReviewSlide.TopTitles -> sentences(
        listOf(stringResource(R.string.review_top_titles_title)) +
            slide.titles.mapIndexed { i, t -> stringResource(R.string.review_rank_spoken, i + 1, titleName(t.title), statsDuration(t.seconds)) },
    )
    is ReviewSlide.TopPeople -> sentences(
        listOf(stringResource(R.string.review_author_title), "${slide.author.name}, ${statsDuration(slide.author.seconds)}", narratorLine(slide)),
    )
    is ReviewSlide.Finished -> sentences(
        listOf(
            stringResource(R.string.review_finished_title),
            "${slide.books} ${pluralStringResource(R.plurals.review_books_unit, slide.books)}" + (seriesLine(slide)?.let { " $it" } ?: ""),
        ) + bookLines(slide).map { it.second },
    )
    is ReviewSlide.Achieved -> sentences(
        listOf(
            stringResource(R.string.review_achievements_title, slide.year),
            "${slide.tiers} ${pluralStringResource(R.plurals.review_achievements_unit, slide.tiers)}",
            slide.shown.map { achievementName(it.achievement, it.tier) }.joinToString(", "),
        ),
    )
    is ReviewSlide.Personality -> sentences(listOf(stringResource(R.string.review_type_title), listenerTypeName(slide.type), whyLine(slide)))
    is ReviewSlide.Closing -> sentences(
        listOf(stringResource(R.string.review_closing_title, slide.year)) + closingFacts(slide).map { (label, value) -> "$label: $value" },
    )
}

// --- the slides ---------------------------------------------------------------------------------------------------------

/** One slide of the year in review: one memorable thing (the big number, name or cover), a few calm lines around it. */
@Composable
fun ReviewSlideContent(slide: ReviewSlide, covers: Map<String, ReviewCover>, entrance: Boolean) {
    when (slide) {
        is ReviewSlide.Opening -> Opening(slide, entrance)
        is ReviewSlide.Days -> Days(slide, entrance)
        is ReviewSlide.Rhythm -> Rhythm(slide, entrance)
        is ReviewSlide.TopTitles -> TopTitles(slide, covers)
        is ReviewSlide.TopPeople -> TopPeople(slide, covers, entrance)
        is ReviewSlide.Finished -> Finished(slide, covers, entrance)
        is ReviewSlide.Achieved -> Achieved(slide, entrance)
        is ReviewSlide.Personality -> Personality(slide, entrance)
        is ReviewSlide.Closing -> Closing(slide, covers)
    }
}

@Composable
private fun Overline(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = Spacing.xl))
}

@Composable
private fun Line(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    Text(text, style = style, textAlign = TextAlign.Center, modifier = modifier.padding(top = Spacing.s))
}

/** The slide's number, big; it counts up once from zero when [entrance]. */
@Composable
private fun BigNumber(value: Int, entrance: Boolean) {
    val shown = remember { Animatable(if (entrance) 0f else value.toFloat()) }
    LaunchedEffect(value) {
        if (entrance) shown.animateTo(value.toFloat(), tween(COUNT_UP_MS, easing = FastOutSlowInEasing)) else shown.snapTo(value.toFloat())
    }
    val locale = appLocale()
    val format = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val style = MaterialTheme.typography.displayLarge.merge(Tabular).copy(fontWeight = FontWeight.SemiBold)
    Text(
        format.format(shown.value.roundToInt()),
        style = style,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = style.fontSize * 1.4f, stepSize = 2.sp),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("reviewNumber"),
    )
}

/** The slide's name (a month, a person, a type), big; it fades in once when [entrance]. */
@Composable
private fun BigText(text: String, entrance: Boolean) {
    val alpha = remember { Animatable(if (entrance) 0f else 1f) }
    LaunchedEffect(Unit) { if (entrance) alpha.animateTo(1f, tween(FADE_IN_MS)) }
    val style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold)
    Text(
        text,
        style = style,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = style.fontSize, stepSize = 2.sp),
        modifier = Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value }.testTag("reviewName"),
    )
}

@Composable
private fun Opening(s: ReviewSlide.Opening, entrance: Boolean) {
    Overline(stringResource(R.string.review_opening_title, s.year))
    BigNumber(hours(s.totalSec), entrance)
    Text(hoursUnit(s.totalSec), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Line(pluralStringResource(R.plurals.review_titles, s.titles, s.titles), Modifier.padding(top = Spacing.s))
    if (s.incomplete) {
        // Said plainly on the first slide: the numbers may still grow.
        Surface(
            color = LocalContentColor.current.copy(alpha = 0.1f),
            contentColor = LocalContentColor.current,
            shape = RoundedCornerShape(50),
            modifier = Modifier.padding(top = Spacing.xl).testTag("reviewIncomplete"),
        ) {
            Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.HourglassTop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.review_incomplete), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Spacing.s))
            }
        }
    }
}

@Composable
private fun Days(s: ReviewSlide.Days, entrance: Boolean) {
    Overline(stringResource(R.string.review_days_title))
    BigNumber(s.activeDays, entrance)
    Text(pluralStringResource(R.plurals.review_days_unit, s.activeDays), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    streakLine(s)?.let { Line(it, Modifier.padding(top = Spacing.l)) }
}

/**
 * The plain bars on a slide: its content colour blended into the background only as far as they still keep WCAG's 3:1
 * for graphics against it – quieter than the strongest month (the content colour itself, with its dot and bold label).
 */
internal fun quietBarColor(background: Color, content: Color): Color {
    if (background.isUnspecified) return content
    var fraction = 0.5f
    while (fraction < 1f) {
        val c = lerp(background, content, fraction)
        if (contrastRatio(c, background) >= MIN_CONTROL_CONTRAST) return c
        fraction += 0.05f
    }
    return content
}

@Composable
private fun Rhythm(s: ReviewSlide.Rhythm, entrance: Boolean) {
    val locale = appLocale()
    val background = LocalSlideBackground.current
    val content = LocalContentColor.current
    Overline(stringResource(R.string.review_rhythm_title))
    BigText(monthName(s), entrance)
    Line(stringResource(R.string.review_top_month, statsDuration(s.topMonthSec)))
    BarChart(
        s.months, current = s.topMonth.value - 1, description = null,
        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = Spacing.xl).testTag("reviewMonths"),
        labels = (1..12).map { java.time.Month.of(it).getDisplayName(DateTextStyle.NARROW_STANDALONE, locale) },
        height = 96.dp,
        // In the slide's own colours (a container's "on" colour may be light while the theme's neutrals are dark).
        barColor = quietBarColor(background, content),
        currentColor = content,
        baselineColor = content.copy(alpha = 0.3f),
        labelColor = content,
        currentLabelColor = content,
    )
    weekdayLine(s)?.let { Line(it, Modifier.padding(top = Spacing.l)) }
    timeLine(s.time)?.let { Line(it) }
}

@Composable
private fun TopTitles(s: ReviewSlide.TopTitles, covers: Map<String, ReviewCover>) {
    Overline(stringResource(R.string.review_top_titles_title))
    val list: @Composable () -> Unit = {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            s.titles.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium.merge(Tabular), modifier = Modifier.width(28.dp))
                    Text(titleName(t.title), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(statsDuration(t.seconds), style = MaterialTheme.typography.labelLarge.merge(Tabular), modifier = Modifier.padding(start = Spacing.m))
                }
            }
        }
    }
    if (wide()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Collage(s, covers, lead = 128.dp)
            Box(Modifier.weight(1f)) { list() }
        }
    } else {
        Collage(s, covers, lead = 168.dp)
        Spacer(Modifier.height(Spacing.xl))
        list()
    }
}

/** The covers of the top titles: the first one large, the others small beneath it. */
@Composable
private fun Collage(s: ReviewSlide.TopTitles, covers: Map<String, ReviewCover>, lead: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag("reviewCollage")) {
        val first = s.titles.first()
        CoverArt(reviewCoverModel(first.itemId, covers), titleName(first.title), Modifier.size(lead))
        val rest = s.titles.drop(1)
        if (rest.isNotEmpty()) {
            Row(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                val small = (lead - Spacing.s * 3) / 4
                rest.forEach { t ->
                    CoverArt(reviewCoverModel(t.itemId, covers), titleName(t.title), Modifier.size(small), initialsStyle = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun TopPeople(s: ReviewSlide.TopPeople, covers: Map<String, ReviewCover>, entrance: Boolean) {
    Overline(stringResource(R.string.review_author_title))
    s.authorItemId?.let { id ->
        CoverArt(reviewCoverModel(id, covers), s.author.name, Modifier.padding(bottom = Spacing.l).size(if (wide()) 72.dp else 112.dp))
    }
    BigText(s.author.name, entrance)
    Line(statsDuration(s.author.seconds), style = MaterialTheme.typography.titleMedium.merge(Tabular))
    narratorLine(s)?.let { Line(it, Modifier.padding(top = Spacing.l)) }
}

@Composable
private fun Finished(s: ReviewSlide.Finished, covers: Map<String, ReviewCover>, entrance: Boolean) {
    Overline(stringResource(R.string.review_finished_title))
    BigNumber(s.books, entrance)
    Text(pluralStringResource(R.plurals.review_books_unit, s.books), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    seriesLine(s)?.let { Line(it) }
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        bookLines(s).forEach { (book, line) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(reviewCoverModel(book.itemId, covers), titleName(book.title), Modifier.size(56.dp), initialsStyle = MaterialTheme.typography.labelLarge)
                Text(line, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Spacing.m))
            }
        }
    }
}

@Composable
private fun Achieved(s: ReviewSlide.Achieved, entrance: Boolean) {
    Overline(stringResource(R.string.review_achievements_title, s.year))
    BigNumber(s.tiers, entrance)
    Text(pluralStringResource(R.plurals.review_achievements_unit, s.tiers), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    val columns = if (wide()) 3 else 2
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        s.shown.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                row.forEach { t ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        AchievementBadge(t.achievement, unlocked = true, size = 40.dp)
                        Text(
                            achievementName(t.achievement, t.tier), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = Spacing.s),
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Personality(s: ReviewSlide.Personality, entrance: Boolean) {
    Overline(stringResource(R.string.review_type_title))
    Box(
        Modifier.padding(bottom = Spacing.l).size(72.dp).clip(CircleShape).background(LocalContentColor.current.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center,
    ) { Icon(s.type.icon(), contentDescription = null, modifier = Modifier.size(36.dp)) }
    BigText(listenerTypeName(s.type), entrance)
    Line(whyLine(s), Modifier.padding(top = Spacing.m))
}

@Composable
private fun Closing(s: ReviewSlide.Closing, covers: Map<String, ReviewCover>) {
    Overline(stringResource(R.string.review_closing_title, s.year))
    s.topTitle?.let { t ->
        CoverArt(reviewCoverModel(t.itemId, covers), titleName(t.title), Modifier.padding(bottom = Spacing.xl).size(if (wide()) 64.dp else 96.dp))
    }
    val facts = closingFacts(s)
    val columns = if (wide()) 3 else 2
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().testTag("reviewFacts"), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        facts.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text(value, style = MaterialTheme.typography.titleMedium.merge(Tabular), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The current slide's background (the month bars pick colours that keep their contrast on it). */
val LocalSlideBackground = androidx.compose.runtime.staticCompositionLocalOf { Color.Unspecified }
