package io.wolftown.lauschig.ui.tvsetup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class TvHandoverScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()
    private val typed = mutableListOf<String>()

    private val confirm = TvHandoverStep.Confirm(
        number = "4821",
        servers = listOf("http://192.168.1.20:13378", "https://abs.example.org"),
        username = "fabian",
    )

    private fun show(step: TvHandoverStep) = rule.setContent {
        LauschigTheme {
            TvHandoverScreen(
                step,
                onBack = { calls += "back" },
                onScan = { calls += "scan" },
                onPassword = { typed += it },
                onSend = { calls += "send" },
                onDone = { calls += "done" },
                onAgain = { calls += "again" },
            )
        }
    }

    private fun announced(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString()

    @Test fun intro_explainsInThreeSentences_andScans() {
        show(TvHandoverStep.Intro())
        rule.onNodeWithText("Set up TV").assertExists()
        rule.onNodeWithText("Scan the code the TV shows.").assertExists()
        rule.onNodeWithText("Then enter your password here.").assertExists()
        rule.onNodeWithText("The TV gets its own sign-in. Your password goes only to your server, never to the TV.").assertExists()
        rule.onNodeWithTag("tvSetupScan").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("scan", "back"), calls)
    }

    @Test fun intro_saysWhyTheLastScanWasNotTaken() {
        val expected = mapOf(
            TvHandoverNote.CAMERA_REFUSED to "Without the camera the code cannot be scanned. You can also set up the TV with the remote.",
            TvHandoverNote.NOT_A_TV_CODE to "That is not a code from a TV.",
            TvHandoverNote.SERVER_CODE to "That is the code of a server, not of a TV.",
            TvHandoverNote.OTHER_VERSION to "This code is from another version of Lauschig. Update the app on both devices.",
            TvHandoverNote.NOT_HOME_NETWORK to "The TV's address is not in a home network. Lauschig only sends within your home network.",
        )
        assertEquals(TvHandoverNote.entries.toSet(), expected.keys)
        var step by mutableStateOf<TvHandoverStep>(TvHandoverStep.Intro())
        rule.setContent { LauschigTheme { TvHandoverScreen(step, {}, {}, {}, {}, {}, {}) } }
        assertEquals(null, announced("tvSetupNote"))
        for ((note, text) in expected) {
            step = TvHandoverStep.Intro(note)
            rule.waitForIdle()
            assertEquals(text, announced("tvSetupNote"))
        }
    }

    @Test fun confirm_namesNumberInTwoGroups_serverAndAccount_andTheAddressesLocalFirst() {
        show(confirm)
        rule.onNodeWithText("Set up this TV (number 48 21) with the server abs.example.org and the account fabian?").assertExists()
        rule.onNodeWithTag("tvSetupNumber").assertExists()
        rule.onNodeWithText("48 21").assertExists()
        rule.onNodeWithText("Check that the TV shows the same number. If it does not, stop here.").assertExists()
        rule.onNodeWithText("http://192.168.1.20:13378").assertExists()
        rule.onNodeWithText("https://abs.example.org").assertExists()
        rule.onNodeWithText("Accounts that only sign in with SSO cannot set up a TV yet.").assertExists()
        rule.onNodeWithContentDescription("Show password").assertExists()
    }

    @Test fun confirm_withOnlyALocalAddress_namesThatServer() {
        show(confirm.copy(servers = listOf("http://192.168.1.20:13378")))
        rule.onNodeWithText("Set up this TV (number 48 21) with the server 192.168.1.20 and the account fabian?").assertExists()
    }

    @Test fun theButton_needsAPassword() {
        show(confirm)
        rule.onNodeWithTag("tvSetupSend").assertIsNotEnabled()
        rule.onNodeWithTag("tvSetupPassword").performTextInput("x")
        assertEquals(listOf("x"), typed)
    }

    @Test fun withAPassword_theButtonSends() {
        show(confirm.copy(password = "geheim"))
        rule.onNodeWithTag("tvSetupSend").assertIsEnabled().performClick()
        assertEquals(listOf("send"), calls)
    }

    @Test fun whileWorking_theButtonWaits() {
        show(confirm.copy(working = true))
        rule.onNodeWithTag("tvSetupSend").assertIsNotEnabled()
        rule.onNodeWithText("Setting up the TV …").assertExists()
        rule.onNodeWithText("Now confirm the setup on the TV.").assertExists()
    }

    @Test fun beforeSending_thereIsNoWordAboutTheTvsQuestion() {
        show(confirm)
        rule.onNodeWithTag("tvSetupConfirmOnTv").assertDoesNotExist()
    }

    @Test fun failed_withASessionThatMayBeOpen_saysSoCalmly() {
        show(TvHandoverStep.Failed(TvHandoverError.NO_REPLY, sessionMayBeOpen = true))
        rule.onNodeWithText("The app could not check whether the sign-in for the TV has ended.").assertExists()
    }

    @Test fun failed_withTheSessionEnded_saysNothingMore() {
        show(TvHandoverStep.Failed(TvHandoverError.NO_REPLY))
        rule.onNodeWithTag("tvSetupSessionMayBeOpen").assertDoesNotExist()
    }

    @Test fun aWrongPassword_isAnnounced() {
        show(confirm.copy(error = TvHandoverError.WRONG_PASSWORD))
        assertEquals("The password is wrong.", announced("tvSetupError"))
    }

    @Test fun done_saysSo_andGoesBack() {
        show(TvHandoverStep.Done)
        rule.onNodeWithText("The TV is set up.").assertExists()
        rule.onNodeWithTag("tvSetupDone").performClick()
        assertEquals(listOf("done"), calls)
    }

    @Test fun failed_saysWhy_andOffersANewScan() {
        val expected = mapOf(
            TvHandoverError.WRONG_PASSWORD to "The password is wrong.",
            TvHandoverError.SERVER_UNREACHABLE to "The app is not connected to the server right now. Check the connection and try again.",
            TvHandoverError.NO_HOME_NETWORK to "This phone is not on a home network by Wi-Fi or cable. Mobile data and VPN do not count.",
            TvHandoverError.TV_UNREACHABLE to "The phone cannot reach the TV. Phone and TV must be on the same home network. If the TV shows a new code, scan that one.",
            TvHandoverError.CODE_EXPIRED to "The TV did not take it. The code may have expired. Scan the new code.",
            TvHandoverError.TV_CANNOT_REACH_SERVER to "The TV cannot reach the server.",
            TvHandoverError.TV_REFUSED to "The server refused the TV's sign-in.",
            TvHandoverError.DECLINED_ON_TV to "The setup was cancelled or not confirmed on the TV.",
            TvHandoverError.NO_REPLY to "The TV did not answer. Look at the TV to see whether it is signed in.",
            TvHandoverError.FAILED to "That did not work.",
        )
        assertEquals(TvHandoverError.entries.toSet(), expected.keys)
        var step by mutableStateOf<TvHandoverStep>(TvHandoverStep.Failed(TvHandoverError.FAILED))
        rule.setContent { LauschigTheme { TvHandoverScreen(step, {}, {}, {}, {}, {}, onAgain = { calls += "again" }) } }
        for ((error, text) in expected) {
            step = TvHandoverStep.Failed(error)
            rule.waitForIdle()
            assertEquals(error.name, text, announced("tvSetupFailed"))
        }
        rule.onNodeWithText("Scan again").performClick()
        assertEquals(listOf("again"), calls)
    }
}
