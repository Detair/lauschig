package io.wolftown.lauschig.serverqr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The camera page without a camera it may use: it never touches the camera, says that typing works and can be closed.
 * (The camera itself cannot run in a unit test; frames are read in [QrCodeTest], the pictures the app draws in
 * [ServerQrPictureTest].)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class ServerQrScannerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun withoutTheCameraPermission_itSaysSo_andCanBeClosed() {
        var closed = 0
        val codes = mutableListOf<String>()
        rule.setContent { LauschigTheme { ServerQrScanner(onCode = { codes += it }, onClose = { closed++ }) } }
        rule.onNodeWithText("Scan QR code").assertIsDisplayed()
        rule.onNodeWithText("The camera cannot be used. Close this page and type the addresses.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close camera").assertExists()
        rule.onNodeWithTag("qrScannerClose").performClick()
        assertEquals(1, closed)
        assertEquals(emptyList<String>(), codes)
    }
}
