package io.wolftown.lauschig.diagnostics

/**
 * The app's logger: the same calls as [android.util.Log], each line also goes (masked) into the [DiagnosticLog] that
 * "Diagnose-Log teilen" sends. Use it instead of `android.util.Log` in app code. Logcat keeps the unmasked line (it
 * never leaves the device without adb). Debug lines stay out of the diagnostic log.
 */
object Log {
    fun d(tag: String, msg: String): Int = android.util.Log.d(tag, msg)

    fun i(tag: String, msg: String): Int = android.util.Log.i(tag, msg).also { DiagnosticLog.record('I', tag, msg) }

    fun w(tag: String, msg: String): Int = android.util.Log.w(tag, msg).also { DiagnosticLog.record('W', tag, msg) }

    fun w(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.w(tag, msg, tr).also { DiagnosticLog.record('W', tag, msg, tr) }

    fun e(tag: String, msg: String): Int = android.util.Log.e(tag, msg).also { DiagnosticLog.record('E', tag, msg) }

    fun e(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.e(tag, msg, tr).also { DiagnosticLog.record('E', tag, msg, tr) }
}
