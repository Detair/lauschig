package io.wolftown.lauschig.diagnostics

/**
 * The app's logger: the same calls as [android.util.Log], each line also goes (masked) into the [DiagnosticLog] that
 * "Diagnose-Log teilen" sends. Use it instead of `android.util.Log` in app code. Logcat keeps the unmasked line (it
 * never leaves the device without adb). Debug lines stay out of the diagnostic log. Recording never throws into the
 * calling code: a log call cannot fail because of the diagnostic log.
 */
object Log {
    fun d(tag: String, msg: String): Int = android.util.Log.d(tag, msg)

    fun i(tag: String, msg: String): Int = android.util.Log.i(tag, msg).also { record('I', tag, msg, null) }

    fun w(tag: String, msg: String): Int = android.util.Log.w(tag, msg).also { record('W', tag, msg, null) }

    fun w(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.w(tag, msg, tr).also { record('W', tag, msg, tr) }

    fun e(tag: String, msg: String): Int = android.util.Log.e(tag, msg).also { record('E', tag, msg, null) }

    fun e(tag: String, msg: String, tr: Throwable?): Int = android.util.Log.e(tag, msg, tr).also { record('E', tag, msg, tr) }

    /** Seam for tests: what records an entry. */
    @Volatile internal var recorder: (Char, String, String, Throwable?) -> Unit = { level, tag, msg, tr -> DiagnosticLog.record(level, tag, msg, tr) }

    private fun record(level: Char, tag: String, msg: String, tr: Throwable?) {
        try {
            recorder(level, tag, msg, tr)
        } catch (t: Throwable) {
            // The diagnostic log is never worth an exception in the app.
        }
    }
}
