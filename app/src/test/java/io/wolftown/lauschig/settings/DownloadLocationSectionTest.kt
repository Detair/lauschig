package io.wolftown.lauschig.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.download.DownloadVolume
import io.wolftown.lauschig.download.VolumeKind
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class DownloadLocationSectionTest {
    @get:Rule val rule = createComposeRule()

    private val internal = DownloadVolume(File("/data/user/0/pkg/files"), VolumeKind.INTERNAL, null, writable = true)
    private val sd = DownloadVolume(File("/storage/1A2B-3C4D/Android/data/pkg/files"), VolumeKind.SD_CARD, null, writable = true)
    private val gb = 1_000_000_000L

    private fun ui(volumes: List<DownloadVolume>, chosen: String?) =
        DownloadLocationUi.of(volumes, { if (it.kind == VolumeKind.INTERNAL) 2 * gb else 50 * gb }, chosen)

    @Test fun options_internalFirst_aMissingChoiceStaysVisibleButUnusable() {
        val two = ui(listOf(internal, sd), null)
        assertEquals(listOf(null, sd.path.absolutePath), two.options.map { it.path })
        assertTrue(two.canChoose)
        assertFalse(two.chosenMissing)
        assertNull(two.active!!.path)

        val onCard = ui(listOf(internal, sd), sd.path.absolutePath)
        assertEquals(sd.path.absolutePath, onCard.active!!.path)

        val gone = ui(listOf(internal), sd.path.absolutePath)
        assertTrue(gone.chosenMissing)
        assertTrue("a card that is gone can still be given up", gone.canChoose)
        assertNull("new downloads go inside meanwhile", gone.active!!.path)
        assertFalse(gone.options.last().usable)

        val readOnly = ui(listOf(internal, sd.copy(writable = false)), sd.path.absolutePath)
        assertTrue(readOnly.chosenMissing)
        assertEquals(1, readOnly.options.count { it.usable })

        assertFalse("one volume: nothing to choose", ui(listOf(internal), null).canChoose)
    }

    @Test fun oneVolume_showsWhereAndHowMuchIsFree_withoutAChoice() {
        rule.setContent { LauschigTheme { Column { DownloadLocationRow(ui(listOf(internal), null), {}) } } }
        rule.onNodeWithText("Internal storage · 2.0\u00A0GB free").assertExists()
        rule.onNodeWithTag("downloadLocation").assertHasNoClickAction()
    }

    @Test fun twoVolumes_theDialogChoosesTheCard() {
        val chosen = mutableListOf<String?>()
        rule.setContent { LauschigTheme { Column { DownloadLocationRow(ui(listOf(internal, sd), null), { chosen += it }) } } }
        rule.onNodeWithTag("downloadLocation").assertHasClickAction().performClick()
        rule.onNodeWithTag("location:internal").assertIsSelected()
        rule.onNodeWithText("50\u00A0GB free").assertExists()
        rule.onNodeWithText("Earlier downloads stay where they are", substring = true).assertExists()
        rule.onNodeWithTag("location:${sd.path.absolutePath}").performClick()
        assertEquals(listOf<String?>(sd.path.absolutePath), chosen)
        rule.onNodeWithTag("location:internal").assertDoesNotExist() // closed
    }

    @Test fun aMissingCard_isSaid_andInternalCanBeChosen() {
        val chosen = mutableListOf<String?>()
        rule.setContent { LauschigTheme { Column { DownloadLocationRow(ui(listOf(internal), sd.path.absolutePath), { chosen += it }) } } }
        rule.onNodeWithText("SD card not found – new downloads go to the internal storage for now.").assertExists()
        rule.onNodeWithText("Internal storage · 2.0\u00A0GB free").assertExists()
        rule.onNodeWithTag("downloadLocation").performClick()
        rule.onNodeWithTag("location:${sd.path.absolutePath}").assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithText("Not available").assertExists()
        rule.onNodeWithTag("location:internal").performClick()
        assertEquals(listOf<String?>(null), chosen)
    }
}
