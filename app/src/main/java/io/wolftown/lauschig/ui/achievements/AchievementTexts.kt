package io.wolftown.lauschig.ui.achievements

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.wolftown.lauschig.R
import io.wolftown.lauschig.stats.Achievement
import kotlin.math.roundToInt

/** A tier's threshold as the count its name says (hours for listening time). */
internal fun Achievement.tierCount(tier: Int): Int {
    val t = tiers[(tier - 1).coerceIn(0, tiers.lastIndex)]
    return if (this == Achievement.HOURS) (t / 3600).roundToInt() else t.roundToInt()
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
