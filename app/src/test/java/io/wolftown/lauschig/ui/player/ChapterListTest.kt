package io.wolftown.lauschig.ui.player

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.detail.ChapterRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chapter tab of the player's contents sheet: the search field over long chapter lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1200dp")
class ChapterListTest {
    @get:Rule val rule = createComposeRule()

    /** „Ort A“ … „Ort AD“: titles without numbers; chapter 4 is „Mädchen im Moor“, chapter 21 is playing. */
    private val long = List(30) { i ->
        val title = if (i == 3) "Mädchen im Moor" else "Ort " + (if (i < 26) "${'A' + i}" else "A${'A' + i - 26}")
        ChapterRow(i, title, 60.0, isCurrent = i == 20, positionInChapterSec = if (i == 20) 10.0 else null)
    }

    private val picked = mutableListOf<Int>()

    private fun show(chapters: List<ChapterRow> = long) = rule.setContent {
        LauschigTheme {
            ContentsSheet(
                ContentsTab.Chapters, chapters, onChapter = { picked += it },
                contents = PlayerContents(queue = { Text("queue content") }), onDismiss = {},
            )
        }
    }

    @Test fun aNumberFindsThatChapter_numberedAndPlayable() {
        show()
        rule.onNodeWithTag("chapterSearch").performTextInput("12")
        rule.onNodeWithText("Ort L").assertExists()
        rule.onNodeWithText("Ort C").assertDoesNotExist()
        rule.onAllNodesWithTag("chapterNumber", useUnmergedTree = true).let { numbers ->
            assertEquals(1, numbers.fetchSemanticsNodes().size)
            numbers[0].assertTextContains("12")
        }
        rule.onNodeWithText("Ort L").performClick()
        assertEquals(listOf(11), picked)
    }

    @Test fun umlautsDoNotMatter() {
        show()
        rule.onNodeWithTag("chapterSearch").performTextInput("madchen")
        rule.onNodeWithText("Mädchen im Moor").assertExists()
        rule.onNodeWithText("Ort A").assertDoesNotExist()
    }

    @Test fun noHit_saysSo_andTheClearButtonBringsTheListBack() {
        show()
        rule.onNodeWithTag("chapterSearch").performTextInput("xyz")
        rule.onNodeWithTag("chapterSearchEmpty").assertExists()
        rule.onNodeWithText("No chapter for “xyz”").assertExists()
        rule.onNodeWithTag("chapterSearchClear").performClick()
        rule.onNodeWithTag("chapterSearchEmpty").assertDoesNotExist()
        rule.onNodeWithTag("chapterSearchClear").assertDoesNotExist()
        // Back to the whole list at the playing chapter, without numbers.
        rule.onNodeWithTag("currentChapter").assertExists()
        rule.onNodeWithTag("chapterNumber", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theQuerySurvivesALookAtTheQueue() {
        show()
        rule.onNodeWithTag("chapterSearch").performTextInput("ort b")
        rule.onNodeWithTag("contentsTab:Queue").performClick()
        rule.onNodeWithText("queue content").assertExists()
        rule.onNodeWithTag("contentsTab:Chapters").performClick()
        rule.onNodeWithTag("chapterSearch").assertTextContains("ort b")
        rule.onNodeWithText("Ort B").assertExists()
        rule.onNodeWithText("Ort C").assertDoesNotExist()
    }

    @Test fun shortLists_haveNoSearchField() {
        show(long.take(CHAPTER_SEARCH_FROM - 1))
        rule.onNodeWithTag("chapterSearch").assertDoesNotExist()
        rule.onNodeWithText("Ort A").assertExists()
    }
}
