package io.wolftown.lauschig.serverqr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.settings.Appearance
import io.wolftown.lauschig.settings.ThemeMode
import io.wolftown.lauschig.ui.LauschigTheme
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The code as the screen really draws it, read back from the pixels: an account's addresses → [ServerQr.code] →
 * [QrCodeImage] on a page of the light, the dark and the black design → [QrFrameDecoder] → [ServerQr.read]. A dark design
 * must not make the code unreadable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h700dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real pixels
class ServerQrPictureTest {
    @get:Rule val rule = createComposeRule()

    private val account = ServerAddresses("http://192.168.1.20:13378/".toHttpUrl(), "https://abs.example.org/audiobookshelf/".toHttpUrl(), setOf("Heimnetz"))

    /** A photo of the page, as grey values. */
    private fun photographed(appearance: Appearance): QrFrame {
        val code = ServerQr.code(account)!!
        rule.setContent {
            LauschigTheme(appearance) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(24.dp)) { QrCodeImage(QrCode.modules(code.text), "code", Modifier.size(300.dp)) }
                }
            }
        }
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val grey = ByteArray(pixels.width * pixels.height) {
            val c = pixels[it % pixels.width, it / pixels.width]
            ((c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f) * 255).toInt().coerceIn(0, 255).toByte()
        }
        return QrFrame(grey, pixels.width, pixels.width, pixels.height)
    }

    private fun readBack(appearance: Appearance) {
        val frame = photographed(appearance)
        val seen = QrFrameDecoder().decode(frame)
        assertEquals(ServerQr.code(account)!!.text, seen)
        assertEquals(ServerQrResult.Addresses("http://192.168.1.20:13378", "https://abs.example.org/audiobookshelf"), ServerQr.read(seen!!))
    }

    @Test fun inTheLightDesign_thePictureIsRead() = readBack(Appearance(theme = ThemeMode.LIGHT, dynamicColor = false))

    @Test fun inTheDarkDesign_thePictureIsRead() = readBack(Appearance(theme = ThemeMode.DARK, dynamicColor = false))

    @Test fun inTheBlackDesign_thePictureIsRead() = readBack(Appearance(theme = ThemeMode.DARK, pureBlack = true, dynamicColor = false))

    /** Dark on light with its light border, also on a black page: the page's corner is dark, the code's is white. */
    @Test fun onABlackPage_theCodeStaysDarkOnLight() {
        val frame = photographed(Appearance(theme = ThemeMode.DARK, pureBlack = true, dynamicColor = false))
        fun grey(x: Int, y: Int) = frame.bytes[y * frame.stride + x].toInt() and 0xFF
        assertTrue("the page is dark", grey(4, 4) < 40)
        val inset = (24 * 2) + 6 // just inside the picture: its light border
        assertTrue("the code's border is light", grey(inset, inset) > 215)
    }
}
