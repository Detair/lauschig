package io.wolftown.lauschig.serverqr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** The dark and light modules of a QR code, without the light border around it. */
class QrModules(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/** Makes QR codes on the device (ZXing, Apache-2.0): no web service draws them. */
object QrCode {
    /** Light modules to leave around the code on every side; the standard asks for four. */
    const val QUIET_ZONE = 4

    fun modules(text: String): QrModules {
        val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
        val size = matrix.width
        return QrModules(size, BooleanArray(size * size) { matrix.get(it % size, it / size).toInt() == 1 })
    }
}

/**
 * Looks for a QR code in one camera frame (ZXing, on the device: no Play services, no ML Kit). The frame is read and
 * forgotten: nothing of it is stored, sent or logged, and neither is the text found.
 */
class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")

    /**
     * [luminance]: the frame's grey values row by row, [stride] bytes a row, of which the first [width] are the image.
     * The code's text, or null when the frame shows none (or is no frame at all).
     */
    fun decode(luminance: ByteArray, stride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || stride < width || luminance.size < stride.toLong() * (height - 1) + width) return null
        return try {
            val source = PlanarYUVLuminanceSource(luminance, stride, height, 0, 0, width, height, false)
            // Dark on light first, as the app draws it; then light on dark, as other tools may.
            read(BinaryBitmap(HybridBinarizer(source))) ?: read(BinaryBitmap(HybridBinarizer(source.invert())))
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun read(bitmap: BinaryBitmap): String? = try {
        reader.decode(bitmap, hints).text
    } catch (_: Exception) {
        // NotFound, Checksum, Format: this frame has no readable code.
        null
    } finally {
        reader.reset()
    }
}
