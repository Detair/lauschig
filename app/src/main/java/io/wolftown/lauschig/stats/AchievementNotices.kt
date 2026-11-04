package io.wolftown.lauschig.stats

/** What one evaluation of an account's achievements leads to ([AchievementNotices.step]). */
sealed interface NoticeStep {
    /** Not the time for the first evaluation yet: the history is still arriving. */
    data object Wait : NoticeStep

    /** The first evaluation: everything reached so far is marked seen without a word (no flood for old history). */
    data class Baseline(val reached: List<AchievementKey>) : NoticeStep

    /** Reached but never told (may be empty). */
    data class Announce(val new: List<AchievementKey>) : NoticeStep
}

/**
 * When to tell about an achievement. The first evaluation of an account waits until its listening history is
 * [settled] (read in full, the progress synced once) and then marks everything reached as seen silently; afterwards
 * every tier reached and not yet seen is told once – whatever the history does then (all of it is new listening).
 */
object AchievementNotices {
    fun step(reached: List<AchievementKey>, seen: Set<AchievementKey>, hasBaseline: Boolean, settled: Boolean): NoticeStep = when {
        hasBaseline -> NoticeStep.Announce(reached.filter { it !in seen })
        settled -> NoticeStep.Baseline(reached)
        else -> NoticeStep.Wait
    }
}
