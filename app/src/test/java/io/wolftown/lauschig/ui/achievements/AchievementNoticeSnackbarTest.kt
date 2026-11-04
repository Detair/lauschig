package io.wolftown.lauschig.ui.achievements

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.stats.AchievementKey
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The notice on Start: one unlock by its name, several by their number; „Ansehen“ opens the achievements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AchievementNoticeSnackbarTest {
    @get:Rule val rule = createComposeRule()

    private val shown = mutableListOf<AchievementNotice>()
    private var opened = 0

    private fun show(initial: AchievementNotice?, onOpen: (() -> Unit)? = { opened++ }): (AchievementNotice?) -> Unit {
        var set: (AchievementNotice?) -> Unit = {}
        rule.setContent {
            LauschigTheme {
                var notice by remember { mutableStateOf(initial) }
                set = { notice = it }
                val host = remember { SnackbarHostState() }
                SnackbarHost(host)
                AchievementNoticeSnackbar(notice, host, onShown = { shown += it }, onOpen = onOpen)
            }
        }
        return { rule.runOnIdle { set(it) } }
    }

    @Test fun oneUnlock_isNamed_andViewOpensTheAchievements() {
        val notice = AchievementNotice("s", listOf(AchievementKey("streak", 2)))
        show(notice)
        rule.onNodeWithText("New achievement: 7 days in a row").assertExists()
        rule.onNodeWithText("View").performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
        assertEquals(listOf(notice), shown)
        rule.onNodeWithText("New achievement: 7 days in a row").assertDoesNotExist()
    }

    @Test fun severalAtOnce_areCounted_andDismissingCountsAsShown() {
        val notice = AchievementNotice("s", listOf(AchievementKey("hours", 1), AchievementKey("books", 1), AchievementKey("night_owl", 1)))
        show(notice)
        rule.onNodeWithText("3 new achievements").assertExists()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        rule.waitForIdle()
        assertEquals(0, opened)
        assertEquals(listOf(notice), shown)
    }

    @Test fun aSingleAchievementByItsTitle_noActionWithoutAPlaceToOpen_nothingWithoutANotice() {
        val set = show(null, onOpen = null)
        rule.onNodeWithText("View").assertDoesNotExist()
        set(AchievementNotice("s", listOf(AchievementKey("night_owl", 1))))
        rule.onNodeWithText("New achievement: Night owl").assertExists()
        rule.onNodeWithText("View").assertDoesNotExist()
    }

    @Test fun aNewerNotice_replacesTheOneOnScreen_whichCountsAsNotShown() {
        val first = AchievementNotice("s", listOf(AchievementKey("hours", 1)))
        val set = show(first)
        rule.onNodeWithText("New achievement: 1 hour listened").assertExists()
        set(AchievementNotice("s", listOf(AchievementKey("hours", 1), AchievementKey("hours", 2))))
        rule.onNodeWithText("2 new achievements").assertExists()
        rule.onNodeWithText("New achievement: 1 hour listened").assertDoesNotExist()
        assertEquals(emptyList<AchievementNotice>(), shown)
    }
}
