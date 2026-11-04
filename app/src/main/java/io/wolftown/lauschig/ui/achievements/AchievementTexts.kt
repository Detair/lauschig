package io.wolftown.lauschig.ui.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Commute
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.stats.Achievement
import io.wolftown.lauschig.stats.AchievementMeasure
import io.wolftown.lauschig.stats.AchievementState
import io.wolftown.lauschig.stats.Achievements
import io.wolftown.lauschig.stats.ListeningStats
import io.wolftown.lauschig.ui.stats.statsDuration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.floor
import kotlin.math.roundToInt

/** A tier's threshold as the count its name says (hours for listening time). */
internal fun Achievement.tierCount(tier: Int): Int {
    val t = tiers[(tier - 1).coerceIn(0, tiers.lastIndex)]
    return if (measure == AchievementMeasure.HOURS || measure == AchievementMeasure.DURATION) (t / 3600).roundToInt() else t.roundToInt()
}

/**
 * The name of one tier ("7 Tage am Stück", "Nachteule"); [tier] 0 (locked) names the first one – the goal. The notice
 * says the same as the card.
 */
@Composable
fun achievementName(a: Achievement, tier: Int): String {
    val n = a.tierCount(tier.coerceAtLeast(1))
    return when (a) {
        Achievement.HOURS -> pluralStringResource(R.plurals.achievement_hours_name, n, n)
        Achievement.STREAK -> pluralStringResource(R.plurals.achievement_streak_name, n, n)
        Achievement.BOOKS -> pluralStringResource(R.plurals.achievement_books_name, n, n)
        Achievement.SERIES -> pluralStringResource(R.plurals.achievement_series_name, n, n)
        Achievement.MARATHON -> stringResource(R.string.achievement_marathon_name)
        Achievement.NIGHT_OWL -> stringResource(R.string.achievement_night_owl_name)
        Achievement.EARLY_BIRD -> stringResource(R.string.achievement_early_bird_name)
        Achievement.WEEKEND -> stringResource(R.string.achievement_weekend_name)
        Achievement.ONE_GO -> stringResource(R.string.achievement_one_go_name)
        Achievement.COMMUTER -> stringResource(R.string.achievement_commuter_name)
        Achievement.GENRES -> stringResource(R.string.achievement_genres_name)
    }
}

/** What it takes, in one plain line; its numbers and times come from the rule ([Achievements]), not the text. */
@Composable
fun achievementDescription(a: Achievement): String {
    @Composable fun minutes(sec: Double) = (sec / 60).roundToInt().let { pluralStringResource(R.plurals.achievement_minutes, it, it) }
    @Composable fun hours(sec: Double) = (sec / 3600).roundToInt().let { pluralStringResource(R.plurals.achievement_hours, it, it) }
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0])
    fun range(w: Pair<LocalTime, LocalTime>) = "${w.first.format(time)}–${w.second.format(time)}"
    return when (a) {
        Achievement.HOURS -> stringResource(R.string.achievement_hours_desc)
        Achievement.STREAK -> stringResource(R.string.achievement_streak_desc, minutes(ListeningStats.ACTIVE_DAY_SEC))
        Achievement.BOOKS -> stringResource(R.string.achievement_books_desc)
        Achievement.SERIES -> stringResource(R.string.achievement_series_desc)
        Achievement.MARATHON -> stringResource(R.string.achievement_marathon_desc, hours(a.tiers.single()))
        Achievement.NIGHT_OWL, Achievement.EARLY_BIRD -> {
            val window = if (a == Achievement.NIGHT_OWL) Achievements.NIGHT_WINDOW else Achievements.EARLY_WINDOW
            stringResource(R.string.achievement_window_desc, minutes(Achievements.WINDOW_SESSION_SEC), window.first.format(time), window.second.format(time))
        }
        Achievement.WEEKEND -> stringResource(R.string.achievement_weekend_desc, hours(a.tiers.single()))
        Achievement.ONE_GO -> stringResource(R.string.achievement_one_go_desc, hours(Achievements.ONE_GO_MS / 1000.0))
        Achievement.COMMUTER -> {
            val n = a.tiers.single().roundToInt()
            stringResource(
                R.string.achievement_commuter_desc, pluralStringResource(R.plurals.achievement_unit_sessions, n, n), minutes(Achievements.WINDOW_SESSION_SEC),
                Achievements.COMMUTE_WINDOWS.joinToString(" ${stringResource(R.string.achievement_or)} ") { range(it) },
            )
        }
        Achievement.GENRES -> {
            val n = a.tiers.single().roundToInt()
            stringResource(R.string.achievement_genres_desc, pluralStringResource(R.plurals.achievement_unit_genres, n, n), minutes(Achievements.GENRE_TITLE_SEC))
        }
    }
}

fun Achievement.icon(): ImageVector = when (this) {
    Achievement.HOURS -> Icons.Rounded.Schedule
    Achievement.STREAK -> Icons.Rounded.LocalFireDepartment
    Achievement.BOOKS -> Icons.Rounded.AutoStories
    Achievement.SERIES -> Icons.AutoMirrored.Rounded.LibraryBooks
    Achievement.MARATHON -> Icons.AutoMirrored.Rounded.DirectionsRun
    Achievement.NIGHT_OWL -> Icons.Rounded.NightsStay
    Achievement.EARLY_BIRD -> Icons.Rounded.WbTwilight
    Achievement.WEEKEND -> Icons.Rounded.Weekend
    Achievement.ONE_GO -> Icons.Rounded.Bolt
    Achievement.COMMUTER -> Icons.Rounded.Commute
    Achievement.GENRES -> Icons.Rounded.Category
}

/** The icon in a tonal circle: primary container once reached, else surface variant with the icon at 40 %. */
@Composable
fun AchievementBadge(a: Achievement, unlocked: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.size(size).clip(CircleShape).background(if (unlocked) scheme.primaryContainer else scheme.surfaceVariant)
            .testTag("badge:${a.id}:${if (unlocked) "unlocked" else "locked"}"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            a.icon(), contentDescription = null, modifier = Modifier.size(size / 2),
            tint = if (unlocked) scheme.onPrimaryContainer else scheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/** "Stufe 3 von 7" for a reached tier of a tiered achievement; null otherwise. */
@Composable
fun achievementTierLine(s: AchievementState): String? =
    if (s.achievement.leveled && s.unlocked) stringResource(R.string.achievement_tier, s.tier, s.achievement.tiers.size) else null

/** The progress to the next tier, shown ("37 / 50 Std.") and spoken ("37 von 50 Std."); null without one. */
@Composable
fun achievementProgress(s: AchievementState, spoken: Boolean = false): String? {
    val value = s.value ?: return null
    val next = s.nextTarget ?: return null
    val target = next.roundToInt()
    fun hours(sec: Double) = (sec / 3600).roundToInt()
    val (current, goal) = when (s.achievement.measure) {
        AchievementMeasure.HOURS ->
            // Under an hour in minutes ("20 Min. / 1 Std."), else whole hours ("37 / 50 Std.").
            (if (value < 3600) statsDuration(value) else floor(value / 3600).toInt().toString()) to stringResource(R.string.achievement_unit_hours, hours(next))
        AchievementMeasure.DURATION -> statsDuration(value) to stringResource(R.string.achievement_unit_hours, hours(next))
        // Said "12 von 30 Tagen": German wants the dative there.
        AchievementMeasure.DAYS -> count(value) to pluralStringResource(if (spoken) R.plurals.achievement_spoken_days else R.plurals.stats_days, target, target)
        AchievementMeasure.BOOKS -> count(value) to pluralStringResource(if (spoken) R.plurals.achievement_spoken_books else R.plurals.stats_books, target, target)
        AchievementMeasure.SERIES -> count(value) to pluralStringResource(if (spoken) R.plurals.achievement_spoken_series else R.plurals.stats_series, target, target)
        AchievementMeasure.SESSIONS -> count(value) to pluralStringResource(R.plurals.achievement_unit_sessions, target, target)
        AchievementMeasure.GENRES -> count(value) to pluralStringResource(R.plurals.achievement_unit_genres, target, target)
        AchievementMeasure.NONE -> return null
    }
    return stringResource(if (spoken) R.string.achievement_progress_spoken else R.string.achievement_progress, current, goal)
}

private fun count(value: Double) = floor(value).toInt().toString()

/** „Seit 12. März 2026“, „Erreicht“ (no day known) or „Noch nicht erreicht“. */
@Composable
fun achievementStatus(s: AchievementState): String {
    val day = s.unlockedAt
    return when {
        !s.unlocked -> stringResource(R.string.achievement_locked)
        day == null -> stringResource(R.string.achievement_reached)
        else -> stringResource(R.string.achievement_since, achievementDate(day))
    }
}

/** A long date in the app's locale ("12. März 2026"). */
@Composable
fun achievementDate(day: LocalDate): String =
    day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(LocalConfiguration.current.locales[0]))
