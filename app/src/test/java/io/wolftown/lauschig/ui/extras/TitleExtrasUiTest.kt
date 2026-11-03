package io.wolftown.lauschig.ui.extras

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.history.History
import io.wolftown.lauschig.history.HistoryEntry
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.player.PlayerExtrasRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h2000dp")
class TitleExtrasUiTest {
    @get:Rule val rule = createComposeRule()

    private val bookmark = BookmarkEntity("s", "b1", 125.0, "Wichtige Stelle")
    private fun entry(i: Int) = HistoryEntry("h$i", 1_700_000_000_000L - i * 86_400_000L, 10.0, 130.0 + i, 3_900.0, "Pixel", thisDevice = i == 0)

    @Test fun detailSections_showBookmarksAndHistory_tapJumps_moreExpands() {
        val jumps = mutableListOf<Double>()
        val deleted = mutableListOf<BookmarkEntity>()
        var all = false
        val history = History((0 until 7).map(::entry), 7 * 3_900.0)
        rule.setContent {
            LauschigTheme {
                LazyColumn {
                    titleExtrasSections(
                        "b1", listOf(bookmark), history, all, online = true,
                        onShowAllHistory = { all = true }, onJump = { jumps += it }, onEditBookmark = {}, onDeleteBookmark = { deleted += it },
                    )
                }
            }
        }
        rule.onNodeWithText("Bookmarks").assertExists()
        rule.onNodeWithText("Wichtige Stelle").assertExists()
        rule.onNodeWithText("02:05").performClick()
        rule.onNodeWithText("7 h 35 min listened in total").assertExists()
        rule.onNodeWithTag("history:h0").performClick()
        rule.onNodeWithTag("history:h6").assertDoesNotExist()
        rule.onNodeWithText("Show more").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("bookmarkDelete").performClick()
        assertEquals(listOf(125.0, 130.0), jumps)
        assertEquals(listOf(bookmark), deleted)
        assertEquals(true, all)
    }

    @Test fun playerExtrasRow_buttons() {
        val calls = mutableListOf<String>()
        rule.setContent { LauschigTheme { PlayerExtrasRow({ calls += "b" }, { calls += "q" }, { calls += "h" }) } }
        rule.onNodeWithText("Bookmarks").performClick()
        rule.onNodeWithText("Queue").performClick()
        rule.onNodeWithText("History").performClick()
        assertEquals(listOf("b", "q", "h"), calls)
    }
}
