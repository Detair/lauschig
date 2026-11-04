package io.wolftown.lauschig.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementNoticesTest {
    private val hours1 = AchievementKey("hours", 1)
    private val hours2 = AchievementKey("hours", 2)
    private val owl = AchievementKey("night_owl", 1)

    @Test fun beforeTheFirstEvaluation_waitsUntilTheHistoryIsSettled() {
        assertEquals(NoticeStep.Wait, AchievementNotices.step(listOf(hours1, owl), emptySet(), hasBaseline = false, settled = false))
    }

    @Test fun theFirstSettledEvaluation_marksEverythingReachedSilently() {
        assertEquals(NoticeStep.Baseline(listOf(hours1, owl)), AchievementNotices.step(listOf(hours1, owl), emptySet(), hasBaseline = false, settled = true))
        // A new listener reaches nothing yet: the baseline is taken all the same, so the first unlock is told.
        assertEquals(NoticeStep.Baseline(emptyList()), AchievementNotices.step(emptyList(), emptySet(), hasBaseline = false, settled = true))
    }

    @Test fun afterTheBaseline_whatWasNotToldYet_inCatalogueOrder_whateverTheHistory() {
        assertEquals(
            NoticeStep.Announce(listOf(hours2, owl)),
            AchievementNotices.step(listOf(hours1, hours2, owl), setOf(hours1), hasBaseline = true, settled = false),
        )
        assertEquals(NoticeStep.Announce(emptyList()), AchievementNotices.step(listOf(hours1), setOf(hours1, owl), hasBaseline = true, settled = true))
    }
}
