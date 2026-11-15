package io.wolftown.lauschig.serverqr

/** A camera frame's grey plane for tests: [stride] bytes a row, of which [width] are the picture. */
class QrFrame(val bytes: ByteArray, val stride: Int, val width: Int, val height: Int)

/** A frame showing [modules]: [cell] pixels a module, a plain border around it, rows padded by [pad] bytes. */
fun qrFrame(modules: QrModules, cell: Int = 6, border: Int = 40, pad: Int = 0, dark: Int = 20, light: Int = 230, turned: Boolean = false): QrFrame {
    val side = modules.size * cell + 2 * border
    val stride = side + pad
    val bytes = ByteArray(stride * side) { light.toByte() }
    for (y in 0 until modules.size) for (x in 0 until modules.size) {
        val on = if (turned) modules[y, modules.size - 1 - x] else modules[x, y]
        if (!on) continue
        for (dy in 0 until cell) for (dx in 0 until cell) bytes[(border + y * cell + dy) * stride + border + x * cell + dx] = dark.toByte()
    }
    return QrFrame(bytes, stride, side, side)
}

fun QrFrameDecoder.decode(frame: QrFrame): String? = decode(frame.bytes, frame.stride, frame.width, frame.height)
