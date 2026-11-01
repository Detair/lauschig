package io.wolftown.lauschig.ui.player

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.detail.ChapterRow
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1200dp")
class PlayerScreenTest {
    @get:Rule val rule = createComposeRule()

    private val state = PlayerScreenState(
        itemId = "b1", title = "The Book", chapterTitle = "Two", chapterElapsedText = "00:25", chapterTotalText = "01:40",
        bookElapsedText = "02:05", bookTotalText = "05:00", speed = 1.25f,
        chapters = listOf(ChapterRow(0, "One", 100.0, false), ChapterRow(1, "Two", 100.0, true, 25.0)),
    )

    @Test fun controls_callbacks() {
        val calls = mutableListOf<String>()
        rule.setContent {
            LauschigTheme {
                PlayerScreen(state, {}, onToggle = { calls += "t" }, onSeekBy = { calls += "s$it" }, onPrevious = { calls += "p" }, onNext = { calls += "n" })
            }
        }
        rule.onNodeWithContentDescription("Play").performClick()
        rule.onNodeWithContentDescription("Back 30 seconds").performClick()
        rule.onNodeWithContentDescription("Forward 30 seconds").performClick()
        rule.onNodeWithContentDescription("Previous chapter").performClick()
        rule.onNodeWithContentDescription("Next chapter").performClick()
        assertEquals(listOf("t", "s-30", "s30", "p", "n"), calls)
        rule.onNodeWithText("02:05 / 05:00").assertExists()
    }

    @Test fun speedSheet_presetApplies_andChapterSheetSeeks() {
        val speeds = mutableListOf<Float>()
        val chapters = mutableListOf<Int>()
        rule.setContent { LauschigTheme { PlayerScreen(state, {}, onSpeed = { speeds += it }, onChapter = { chapters += it }) } }
        rule.onNodeWithTag("speedButton").performClick()
        rule.onNodeWithTag("preset1.5").performClick()
        assertEquals(listOf(1.5f), speeds)
    }

    @Test fun controlsRow_fitsAt360dp_withLargeFont() {
        rule.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.5f),
            ) {
                LauschigTheme { PlayerScreen(state, {}) }
            }
        }
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        for (d in listOf("Previous chapter", "Next chapter", "Play")) {
            val b = rule.onNodeWithContentDescription(d).getUnclippedBoundsInRoot()
            assertTrue("$d clipped", b.left >= 0.dp && b.right <= root.right)
        }
        rule.onNodeWithContentDescription("Playback speed 1.25×").assertExists()
    }
}
