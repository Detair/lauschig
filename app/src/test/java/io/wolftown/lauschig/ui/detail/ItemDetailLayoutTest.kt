package io.wolftown.lauschig.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the header puts the cover: above the texts, or beside them when the window is too short for that. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ItemDetailLayoutTest {
    @get:Rule val rule = createComposeRule()

    // A title with everything a header can have: two-line title, subtitle, four chips, a download button, a description.
    private val state = ItemDetailUiState(
        found = true, itemId = "b1", title = "Die drei ??? und der Fluch des Rubins", subtitle = "Folge 17",
        author = "Alfred Hitchcock", narrator = "Oliver Rohrbeck", seriesName = "Die drei ???", seriesSequence = "17",
        genres = listOf("Krimi"), description = "Ein Fall, so lang, dass die Beschreibung nach vier Zeilen eingeklappt wird. ".repeat(12),
        primary = PrimaryAction.Continue(2), detailsLoaded = true, online = true,
        chapters = listOf(ChapterRow(0, "One", 100.0, false), ChapterRow(1, "Two", 100.0, true, 30.0)),
    )

    private fun show() = rule.setContent { LauschigTheme { ItemDetailScreen(state, {}, {}, {}, {}, {}, {}) } }

    private fun assertEverythingAboveTheFold() {
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        for (label in listOf("Continue · Ch. 2", "Download")) {
            rule.onNodeWithText(label).assertIsDisplayed()
            val b = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label below the fold: ${b.bottom} > ${root.bottom}", b.bottom <= root.bottom)
        }
    }

    // The right pane of a 1006×601 dp tablet in landscape (list share 0.45) with the mini player under it.
    @Config(qualifiers = "w455dp-h470dp")
    @Test fun landscapePane_coverBesideTheTexts_primaryActionWithoutScrolling() {
        show()
        assertEverythingAboveTheFold()
        val cover = rule.onNodeWithTag("cover").getUnclippedBoundsInRoot()
        val primary = rule.onNodeWithText("Continue · Ch. 2").getUnclippedBoundsInRoot()
        val title = rule.onNodeWithText("Die drei ??? und der Fluch des Rubins").getUnclippedBoundsInRoot()
        assertTrue("button not beside the cover", primary.left >= cover.right)
        assertTrue("title not beside the cover", title.left >= cover.right)
        assertTrue("cover not smaller: ${cover.width}", cover.width in 120.dp..200.dp)
    }

    @Config(qualifiers = "w800dp-h360dp")
    @Test fun phoneLandscape_primaryActionWithoutScrolling() {
        show()
        assertEverythingAboveTheFold()
        val cover = rule.onNodeWithTag("cover").getUnclippedBoundsInRoot()
        val primary = rule.onNodeWithText("Continue · Ch. 2").getUnclippedBoundsInRoot()
        assertTrue("button not beside the cover", primary.left >= cover.right)
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test fun portraitPhone_keepsTheColumn() = assertColumn()

    // A tablet in portrait: wide enough for a row, but tall, so the cover stays on top at full size.
    @Config(qualifiers = "w601dp-h1006dp")
    @Test fun portraitTablet_keepsTheColumn() {
        assertColumn()
        assertTrue("cover shrank", rule.onNodeWithTag("cover").getUnclippedBoundsInRoot().width >= 280.dp)
    }

    // A narrow window (split screen, a phone with a large display size) never gets the row.
    @Config(qualifiers = "w360dp-h300dp")
    @Test fun narrowAndShort_keepsTheColumn() = assertColumn()

    private fun assertColumn() {
        show()
        val cover = rule.onNodeWithTag("cover").getUnclippedBoundsInRoot()
        val title = rule.onNodeWithText("Die drei ??? und der Fluch des Rubins").getUnclippedBoundsInRoot()
        val primary = rule.onNodeWithText("Continue · Ch. 2").getUnclippedBoundsInRoot()
        assertTrue("title not under the cover", title.top >= cover.bottom)
        assertTrue("button not under the cover", primary.top >= cover.bottom)
    }

    @Test fun sideCover_followsTheRoom_within120And200dp() {
        assertEquals(200.dp, sideCoverSize(800.dp, 360.dp))
        assertEquals(200.dp, sideCoverSize(2000.dp, 2000.dp))
        assertEquals(((455 - 32) * 0.4f).dp, sideCoverSize(455.dp, 470.dp))
        assertEquals("never below the minimum", 120.dp, sideCoverSize(400.dp, 150.dp))
    }

    @Test fun headerBesideCover_needsWidthForTwoColumns_andShortHeight() {
        assertTrue(headerBesideCover(550.dp, 520.dp))
        assertTrue(headerBesideCover(800.dp, 360.dp))
        assertTrue(headerBesideCover(455.dp, 470.dp))
        assertTrue(headerBesideCover(400.dp, 599.dp))
        assertFalse("tall enough for the column", headerBesideCover(550.dp, 600.dp))
        assertFalse("too narrow", headerBesideCover(399.dp, 300.dp))
        assertFalse(headerBesideCover(360.dp, 800.dp))
        assertFalse(headerBesideCover(601.dp, 1006.dp))
    }
}
