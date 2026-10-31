package io.wolftown.lauschig.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CoverTileTest {
    @get:Rule val rule = createComposeRule()

    private fun item(downloaded: Boolean, seq: String? = "3", progress: Float = 0.5f) =
        ItemSummary("s", "i1", "Das Buch", "Autor", "Saga", seq, null, progress, downloaded, false)

    @Test fun coverTile_showsSeriesBadgeAndOfflineIcon() {
        rule.setContent { LauschigTheme { CoverTile(item(downloaded = true)) } }
        rule.onNodeWithContentDescription("Book 3", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("#3", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Available offline", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("coverProgress", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("DB", useUnmergedTree = true).assertIsDisplayed() // placeholder initials
    }

    @Test @Config(sdk = [35], application = android.app.Application::class, qualifiers = "de")
    fun coverTile_german_usesBandAndOfflineVerfuegbar() {
        rule.setContent { LauschigTheme { CoverTile(item(downloaded = true)) } }
        rule.onNodeWithContentDescription("Band 3", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Offline verfügbar", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun coverTile_withoutSeriesOrDownload_hasNoBadgeNorIcon() {
        rule.setContent { LauschigTheme { CoverTile(item(downloaded = false, seq = null)) } }
        rule.onNodeWithContentDescription("Available offline", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("#3", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun offline_nonDownloadedTile_isMarkedUnavailable_butClickable() {
        var clicks = 0
        rule.setContent { LauschigTheme { CoverTile(item(downloaded = false), onClick = { clicks++ }) } }
        rule.onNode(hasStateDescription("Not available offline")).performClick()
        assertEquals(1, clicks)
    }

    @Test fun online_orDownloaded_tileIsNotMarkedUnavailable() {
        rule.setContent {
            LauschigTheme {
                CompositionLocalProvider(LocalServerBaseUrl provides "https://abs.example.org/".toHttpUrl()) {
                    CoverTile(item(downloaded = false))
                }
            }
        }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertDoesNotExist()
    }

}
