package io.wolftown.lauschig.ui.connection

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.wolftown.lauschig.connection.ConnectionStatusUi
import io.wolftown.lauschig.serverqr.ServerQrCode
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Server-Adressen als QR-Code" on the connection page: only with a server, and the page shows nothing but the addresses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp")
class ConnectionStatusScreenTest {
    @get:Rule val rule = createComposeRule()

    private val code = ServerQrCode(
        "lauschig://server?v=1&local=http%3A%2F%2F192.168.1.20%3A13378&public=https%3A%2F%2Fabs.example.org%2Faudiobookshelf",
        listOf("http://192.168.1.20:13378", "https://abs.example.org/audiobookshelf"),
    )

    private fun show(state: ConnectionStatusUi) = rule.setContent { LauschigTheme { ConnectionStatusScreen(state, {}, {}, {}) } }

    @Test fun withAServer_theEntryOpensThePage_withTheCodeAndWhatIsInIt() {
        show(ConnectionStatusUi(signedIn = true, username = "fabian", homeSsids = setOf("Heimnetz"), serverQr = code))
        rule.onNodeWithTag("serverQrDialog").assertDoesNotExist()
        rule.onNodeWithText("Server addresses as QR code").assertExists()
        rule.onNodeWithTag("showServerQr").performScrollTo().performClick()
        rule.onNodeWithTag("serverQrCode").assertIsDisplayed()
        rule.onNodeWithContentDescription("QR code with the server addresses").assertExists()
        rule.onNodeWithText("Contains only the server addresses, no account and no password.").assertIsDisplayed()
        rule.onAllNodesWithTag("serverQrAddress").assertCountEquals(2)
        rule.onNodeWithText("http://192.168.1.20:13378").assertExists()
        rule.onNodeWithText("https://abs.example.org/audiobookshelf").assertExists()
        // The page itself names neither the account nor its Wi-Fi.
        val onThePage = hasAnyAncestor(hasTestTag("serverQrDialog"))
        rule.onAllNodes(onThePage and hasText("fabian", substring = true, ignoreCase = true)).assertCountEquals(0)
        rule.onAllNodes(onThePage and hasText("Heimnetz", substring = true)).assertCountEquals(0)
        rule.onNodeWithTag("serverQrClose").performClick()
        rule.onNodeWithTag("serverQrDialog").assertDoesNotExist()
    }

    @Test fun withoutAServer_thereIsNoEntry() {
        show(ConnectionStatusUi(signedIn = false))
        rule.onNodeWithTag("connectionScreen").assertExists()
        rule.onNodeWithTag("showServerQr").assertDoesNotExist()
        rule.onNodeWithText("Server addresses as QR code").assertDoesNotExist()
    }
}
