package io.wolftown.lauschig.ui.collections

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.db.dao.CollectionWithCount
import io.wolftown.lauschig.db.entities.CollectionKind
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.ItemSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1600dp")
class CollectionScreenTest {
    @get:Rule val rule = createComposeRule()

    private val book = CollectionEntryUi.Book(ItemSummary("s", "b1", "Erster Band", "Autorin", null, null, null, 0f, false, false))
    private val episode = CollectionEntryUi.Episode("pod~e1", "Folge 7", "Der Podcast", 600.0, downloaded = false, finished = false)

    @Test fun page_listsBooksAndEpisodes_playAllAndRowPlay() {
        val calls = mutableListOf<String>()
        val state = CollectionUiState(true, true, CollectionKind.PLAYLIST, "Auto", "Für unterwegs", listOf(book, episode), missing = 1)
        rule.setContent {
            LauschigTheme { CollectionScreen(state, {}, onPlayAll = { calls += "all" }, onOpenItem = { calls += "open $it" }, onPlay = { calls += "play $it" }) }
        }
        rule.onNodeWithText("Auto").assertExists()
        rule.onNodeWithText("2 titles").assertExists()
        rule.onNodeWithText("Some titles are not available on this device yet.").assertExists()
        rule.onNodeWithTag("playAll").performClick()
        rule.onNodeWithText("Erster Band").performClick()
        rule.onNodeWithTag("entry:pod~e1").performClick()
        assertEquals(listOf("all", "open b1", "play pod~e1"), calls)
    }

    @Test fun offlineWithoutDownloads_playAllDisabled() {
        val state = CollectionUiState(true, true, CollectionKind.COLLECTION, "Abend", null, listOf(book))
        rule.setContent { LauschigTheme { CollectionScreen(state, {}, canPlay = { false }) } }
        rule.onNodeWithTag("playAll").assertIsNotEnabled()
    }

    @Test fun vanishedList_showsHint() {
        rule.setContent { LauschigTheme { CollectionScreen(CollectionUiState(found = false, loaded = true), {}) } }
        rule.onNodeWithText("This list is no longer available.").assertExists()
    }

    @Test fun list_emptyHint_andOpen() {
        val opened = mutableListOf<String>()
        rule.setContent {
            LauschigTheme {
                CollectionList(CollectionKind.PLAYLIST, listOf(CollectionWithCount("s", "p1", CollectionKind.PLAYLIST, "l", "Auto", null, 0, 3)), { opened += it })
            }
        }
        rule.onNodeWithText("3 titles").assertExists()
        rule.onNodeWithTag("collection:p1").performClick()
        assertEquals(listOf("p1"), opened)
    }

    @Test fun list_empty() {
        rule.setContent { LauschigTheme { CollectionList(CollectionKind.COLLECTION, emptyList(), {}) } }
        rule.onNodeWithText("No collections. Collections are created in the Audiobookshelf web app.").assertExists()
    }
}
