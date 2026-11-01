package io.wolftown.lauschig.ui.podcast

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.QuickDownload
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpisodeRowTest {
    @get:Rule val rule = createComposeRule()

    private val episode = EpisodeUi("pod~e1", "Folge 1", 1_700_000_000_000, 600.0, remainingSec = 450.0, progress = 0.25f, finished = false)

    @Test fun episodeRow_playAndDownloadButtons() {
        val calls = mutableListOf<String>()
        rule.setContent {
            LauschigTheme {
                EpisodeRow(episode, QuickDownload.None, { calls += "play" }, { calls += "download" }, { calls += "delete" }, onLongClick = { calls += "menu" })
            }
        }
        rule.onNodeWithTag("episodeProgress").assertIsDisplayed()
        rule.onNodeWithTag("episodePlay").performClick()
        rule.onNodeWithTag("downloadButton").performClick()
        rule.onNodeWithTag("episode:pod~e1").performTouchInput { longClick() }
        assertEquals(listOf("play", "download", "menu"), calls)
    }

    @Test fun finishedEpisode_showsHeardTick_noProgressBar() {
        rule.setContent {
            LauschigTheme {
                EpisodeRow(episode.copy(finished = true, progress = 0f, remainingSec = null), QuickDownload.Complete, {}, {}, {})
            }
        }
        rule.onNodeWithTag("episodeHeard", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("episodeProgress", useUnmergedTree = true).assertDoesNotExist()
    }
}
