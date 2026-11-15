package io.wolftown.lauschig.serverqr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drawing a QR code and finding one in a camera frame: [QrCode] and [QrFrameDecoder], without a camera or a screen. */
class QrCodeTest {
    private val text = "lauschig://server?v=1&local=http%3A%2F%2F192.168.1.20%3A13378&public=https%3A%2F%2Fabs.example.org%2Faudiobookshelf"

    private fun frame(modules: QrModules, cell: Int = 6, pad: Int = 0, dark: Int = 20, light: Int = 230, turned: Boolean = false) =
        qrFrame(modules, cell = cell, pad = pad, dark = dark, light = light, turned = turned)

    private fun read(f: QrFrame) = QrFrameDecoder().decode(f)

    @Test fun modules_areASquareQrCode() {
        val m = QrCode.modules(text)
        assertTrue("a QR code has 21 + 4n modules a side: ${m.size}", m.size >= 21 && (m.size - 21) % 4 == 0)
        // The finder pattern in the top left corner: a dark frame, a light ring, a dark core.
        assertTrue(m[0, 0] && m[6, 0] && m[0, 6] && m[6, 6] && m[3, 3])
        assertFalse(m[1, 1] || m[5, 1] || m[1, 5] || m[5, 5])
        assertFalse("the module after the finder pattern is its light separator", m[7, 0] || m[0, 7])
    }

    @Test fun aFrameShowingTheCode_isRead() {
        assertEquals(text, read(frame(QrCode.modules(text))))
    }

    @Test fun aFrameWithPaddedRows_isRead() {
        assertEquals(text, read(frame(QrCode.modules(text), pad = 24)))
    }

    @Test fun aCodeTurnedOnItsSide_isRead() {
        assertEquals(text, read(frame(QrCode.modules(text), turned = true)))
    }

    @Test fun aLightCodeOnDark_isReadToo() {
        assertEquals(text, read(frame(QrCode.modules(text), dark = 230, light = 20)))
    }

    @Test fun aFrameWithoutACode_isNothing() {
        assertNull(read(QrFrame(ByteArray(320 * 240) { ((it * 31) % 251).toByte() }, 320, 320, 240)))
        assertNull(read(QrFrame(ByteArray(320 * 240) { 128.toByte() }, 320, 320, 240)))
    }

    @Test fun aFrameThatIsNoImage_isNothing_notACrash() {
        assertNull(QrFrameDecoder().decode(ByteArray(10), 320, 320, 240))
        assertNull(QrFrameDecoder().decode(ByteArray(0), 0, 0, 0))
    }

    @Test fun otherCodes_areReadAsTheirText_theReaderDecidesWhatTheyAre() {
        val other = "https://example.org/"
        assertEquals(other, read(frame(QrCode.modules(other))))
        assertEquals(ServerQrResult.Rejected(ServerQrError.NOT_A_SERVER_CODE), ServerQr.read(other))
    }

    @Test fun theLongestCodeTheReaderTakes_stillFitsAQrCode() {
        val longest = "lauschig://server?v=1&public=https%3A%2F%2Fabs.example.org&x=".let { it + "a".repeat(ServerQr.MAX_LENGTH - it.length) }
        assertEquals(longest, read(frame(QrCode.modules(longest), cell = 4)))
    }
}
