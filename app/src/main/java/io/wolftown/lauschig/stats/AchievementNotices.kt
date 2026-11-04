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
 * every tier reached and not yet seen is told once, whatever the history does then. Mostly that is new listening, but
 * not always: a reconcile run, another device's late upload, a series edited on the server or a change of the device
 * zone (time windows are read in it) can reach a tier with older data – it is told all the same, once. An account whose
 * history never completes (metered only, or every full read failed) never gets its baseline, and so no notice.
 */
object AchievementNotices {
    fun step(reached: List<AchievementKey>, seen: Set<AchievementKey>, hasBaseline: Boolean, settled: Boolean): NoticeStep = when {
        hasBaseline -> NoticeStep.Announce(reached.filter { it !in seen })
        settled -> NoticeStep.Baseline(reached)
        else -> NoticeStep.Wait
    }
}
