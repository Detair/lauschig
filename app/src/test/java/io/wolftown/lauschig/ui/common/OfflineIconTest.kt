package io.wolftown.lauschig.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.player.PlaySource
import io.wolftown.lauschig.ui.player.sourceIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** One symbol for "on this device" everywhere: the arrow in a circle, never the tick on a bar (read as "done"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OfflineIconTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theOfflineSymbol_isTheArrowInACircle() = assertSame(Icons.Rounded.ArrowCircleDown, OfflineIcon)

    @Test fun thePlayersSource_downloaded_usesIt() = assertSame(OfflineIcon, sourceIcon(PlaySource.DOWNLOAD))

    @Test fun noScreen_usesDownloadDoneAnyMore() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("sources not found from ${File(".").absolutePath}", sources.size > 100)
        // Any style of the icon (import or use); a comment naming it does not count.
        val icon = Regex("""(?i)(rounded|filled|outlined|sharp|twotone|default)\.DownloadDone\b""")
        val stale = sources.filter { icon.containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("still DownloadDone: $stale", stale.isEmpty())
    }

    @Test fun downloadButton_complete_saysOnThisDevice_andAsksBeforeDeleting() {
        var deleted = 0
        rule.setContent { LauschigTheme { DownloadIconButton(QuickDownload.Complete, onDownload = {}, onDelete = { deleted++ }) } }
        rule.onNodeWithContentDescription("Available on this device").assertIsDisplayed()
        rule.onNodeWithTag("downloadButton").performClick()
        rule.onNodeWithText("Delete download?").assertIsDisplayed()
        assertEquals(0, deleted)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test @Config(sdk = [35], application = android.app.Application::class, qualifiers = "de")
    fun downloadButton_complete_german() {
        rule.setContent { LauschigTheme { DownloadIconButton(QuickDownload.Complete, onDownload = {}, onDelete = {}) } }
        rule.onNodeWithContentDescription("Auf diesem Gerät verfügbar").assertIsDisplayed()
    }
}
