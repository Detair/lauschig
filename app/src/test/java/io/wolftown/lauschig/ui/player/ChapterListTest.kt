package io.wolftown.lauschig.ui.player

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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

    private val loaded = mutableStateOf("b1" to long)

    private fun show(chapters: List<ChapterRow> = long) {
        loaded.value = "b1" to chapters
        rule.setContent {
            LauschigTheme {
                ContentsSheet(
                    ContentsTab.Chapters, loaded.value.second, onChapter = { picked += it },
                    contents = PlayerContents(queue = { Text("queue content") }), onDismiss = {}, titleKey = loaded.value.first,
                )
            }
        }
    }

    /** Types [text] and waits for the answer (the chapter words are prepared off the main thread). */
    private fun search(text: String) {
        rule.onNodeWithTag("chapterSearch").performTextInput(text)
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("chapterSearchHits", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("chapterSearchEmpty").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun aNumberFindsThatChapter_numberedAndPlayable() {
        show()
        search("12")
        rule.onNodeWithText("Ort L").assertExists()
        rule.onNodeWithText("Ort C").assertDoesNotExist()
        rule.onAllNodesWithTag("chapterNumber", useUnmergedTree = true).let { numbers ->
            assertEquals(1, numbers.fetchSemanticsNodes().size)
            numbers[0].assertTextEquals("Chapter 12")
        }
        rule.onNodeWithText("Ort L").performClick()
        assertEquals(listOf(11), picked)
    }

    @Test fun umlautsDoNotMatter() {
        show()
        search("madchen")
        rule.onNodeWithText("Mädchen im Moor").assertExists()
        rule.onNodeWithText("Ort A").assertDoesNotExist()
    }

    @Test fun noHit_saysSo_andTheClearButtonBringsTheListBack() {
        show()
        search("xyz")
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
        search("ort b")
        rule.onNodeWithTag("contentsTab:Queue").performClick()
        rule.onNodeWithText("queue content").assertExists()
        rule.onNodeWithTag("contentsTab:Chapters").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("chapterSearchHits", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("chapterSearch").assertTextContains("ort b")
        rule.onNodeWithText("Ort B").assertExists()
        rule.onNodeWithText("Ort C").assertDoesNotExist()
    }

    @Test fun shortLists_haveNoSearchField() {
        show(long.take(CHAPTER_SEARCH_FROM - 1))
        rule.onNodeWithTag("chapterSearch").assertDoesNotExist()
        rule.onNodeWithText("Ort A").assertExists()
    }

    // Fix round 1, E6: TalkBack hears how many chapters match (or that none does), keeps the field's name, and a hit's
    // number as „Chapter 12“.
    @Test fun talkBack_hearsTheCount_theFieldsName_andTheChapterNumber() {
        show()
        search("12")
        rule.onNodeWithTag("chapterSearchHits", useUnmergedTree = true)
            .assertTextEquals("1 chapter")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithTag("chapterSearch").assert(hasText("Search chapters", substring = true))
        rule.onNode(hasText("Chapter 12", substring = true) and hasText("Ort L", substring = true)).assertExists()
        rule.onNodeWithTag("chapterSearchClear").performClick()
        search("xyz")
        rule.onNodeWithTag("chapterSearchEmpty").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithTag("chapterSearchHits", useUnmergedTree = true).assertDoesNotExist()
    }

    // Fix round 1, E5: the queue moves on to another title while the sheet is open – its chapters start unfiltered.
    @Test fun anotherTitle_startsTheChapterTabAfresh() {
        show()
        search("12")
        rule.onNodeWithText("Ort C").assertDoesNotExist()
        rule.runOnIdle { loaded.value = "b2" to long.map { it.copy(title = it.title.replace("Ort", "Platz")) } }
        rule.onNodeWithTag("chapterSearchClear").assertDoesNotExist()
        rule.onNodeWithTag("chapterNumber", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("currentChapter").assertExists()
        rule.onNodeWithText("Platz C").assertDoesNotExist() // the list is at the playing chapter again, not at the top
    }

    // Fix round 1, E4: punctuation alone is no search – no numbers, no jump away from the playing chapter.
    @Test fun punctuationAlone_isNoSearch() {
        show()
        search("12") // the words are ready: a "?" would now be filtered if it counted
        rule.onNodeWithTag("chapterNumber", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("chapterSearch").performTextReplacement("?")
        rule.waitForIdle()
        rule.onNodeWithTag("chapterNumber", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("chapterSearchHits", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("currentChapter").assertExists() // back at the playing chapter
    }
}
