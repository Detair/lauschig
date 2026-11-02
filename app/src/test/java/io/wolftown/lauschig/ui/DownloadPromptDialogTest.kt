package io.wolftown.lauschig.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.work.NetworkType
import io.wolftown.lauschig.download.DownloadPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadPromptDialogTest {
    @get:Rule val rule = createComposeRule()
    private val answers = mutableListOf<String>()

    private fun show(prompt: DownloadPrompt) = rule.setContent {
        LauschigTheme {
            DownloadPromptDialog(
                prompt,
                onMetered = { _, now, remember -> answers += "metered now=$now remember=$remember" },
                onContention = { _, download -> answers += "contention download=$download" },
                onDismiss = { answers += "dismiss" },
            )
        }
    }

    @Test fun metered_rememberAndDownloadNow() {
        show(DownloadPrompt.Metered("s", "it", "The Book", 120_000_000))
        rule.onNodeWithText("“The Book” is streaming", substring = true).assertExists()
        rule.onNodeWithTag("promptRemember").performClick()
        rule.onNodeWithTag("promptMeteredNow").performClick()
        assertEquals(listOf("metered now=true remember=true"), answers)
    }

    @Test fun metered_waitForWifi() {
        show(DownloadPrompt.Metered("s", "it", "The Book", 1))
        rule.onNodeWithText("Download on Wi-Fi").performClick()
        assertEquals(listOf("metered now=false remember=false"), answers)
    }

    @Test fun contention_showsEstimate_andBothAnswers() {
        show(DownloadPrompt.Contention("s", "it", "The Book", etaSec = 1_500, network = NetworkType.CONNECTED))
        rule.onNodeWithText("about 25 min", substring = true).assertExists()
        rule.onNodeWithTag("promptKeepStreaming").performClick()
        rule.onNodeWithTag("promptDownload").performClick()
        assertEquals(listOf("contention download=false", "contention download=true"), answers)
    }

    @Test fun contention_withoutEstimate() {
        show(DownloadPrompt.Contention("s", "it", "The Book", etaSec = null, network = null))
        rule.onNodeWithText("The download of “The Book” is paused so the stream keeps playing.").assertExists()
    }
}
