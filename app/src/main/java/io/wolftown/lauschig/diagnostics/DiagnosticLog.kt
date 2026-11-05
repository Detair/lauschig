package io.wolftown.lauschig.diagnostics

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's own diagnostic log for family support: the last [CAPACITY] entries of what the app logs ([Log]), already
 * masked ([LogMasker]) when recorded, so nothing secret is ever held, stored or shared. What an entry holds is chosen
 * field by field: time, level, tag, the (masked) message and, for an error, the exception chain – class names, masked
 * messages and the top stack frames (code names only). Library logs (ExoPlayer, OkHttp, WorkManager) never get in.
 *
 * The entries survive the process ([attach]): written to an app-private file a moment after each new entry and at once
 * when the app crashes ([installCrashHandler]), read back at the next start – a problem "this morning" is still there in
 * the evening. Thread-safe.
 */
object DiagnosticLog {
    const val CAPACITY = 500
    private const val MAX_MESSAGE = 1_000
    /** Masked before it is cut to [MAX_MESSAGE]: a secret cut in half would no longer be recognised. */
    private const val MAX_MASK_INPUT = 8_000
    private const val TOP_FRAMES = 8
    private const val CAUSE_FRAMES = 4
    private const val MAX_CAUSES = 3
    private const val WRITE_DELAY_MS = 2_000L

    private val entries = ArrayDeque<String>(CAPACITY)
    private val lock = Any()
    /** One writer at a time (attach, the delayed write and the crash handler share the file). */
    private val writeLock = Any()
    @Volatile private var file: File? = null
    private val writePending = AtomicBoolean(false)
    private val writer by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diagnostic-log").apply { isDaemon = true } }
    }
    /** Seam for tests. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    fun record(level: Char, tag: String, message: String?, error: Throwable? = null) {
        val entry = buildString {
            append(timestamp()).append(' ').append(level).append('/').append(tag.take(40)).append(": ")
            append(masked(message.orEmpty()))
            if (error != null) appendThrowable(error)
        }
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(entry)
        }
        scheduleWrite()
    }

    private fun masked(text: String): String = LogMasker.mask(text.take(MAX_MASK_INPUT)).take(MAX_MESSAGE)

    /** Every entry, oldest first. */
    fun entries(): List<String> = synchronized(lock) { entries.toList() }

    /**
     * Masks the kept entries once more with the user's own values ([LogMasker.maskLiterals]) – they become known a
     * moment after the start, after the first entries – and writes them.
     */
    fun remask() {
        synchronized(lock) {
            val again = entries.map(LogMasker::maskLiterals)
            entries.clear()
            again.forEach { entries.addLast(it) }
        }
        scheduleWrite()
    }

    /** Forgets everything, also on disk (sign-out and another account: the next one must not share these entries). */
    fun clear() {
        synchronized(lock) { entries.clear() }
        synchronized(writeLock) { file?.delete() }
    }

    /**
     * Keeps the entries in [target] from now on and reads back what an earlier process left there (marked as such).
     * Called once at app start, before the first entry of this run.
     */
    fun attach(target: File) {
        val earlier = try {
            if (target.isFile) parse(target.readText()) else emptyList()
        } catch (e: IOException) {
            emptyList()
        }
        synchronized(lock) {
            val current = entries.toList()
            entries.clear()
            (earlier + (if (earlier.isNotEmpty()) listOf("${timestamp()} I/DiagnosticLog: --- app started ---") else emptyList()) + current)
                .takeLast(CAPACITY).forEach { entries.addLast(it) }
        }
        file = target
        writeNow()
    }

    /** Stops keeping the entries in a file (tests). */
    internal fun detach() { file = null }

    /** Writes the entries to the attached file right now (blocking); false if there is none or writing failed. */
    fun writeNow(): Boolean = synchronized(writeLock) {
        val target = file ?: return false
        val text = entries().joinToString("\n", postfix = "\n")
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Records an uncaught exception and writes the entries before the previous handler ends the process. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                record('E', "Crash", "uncaught exception in thread \"${t.name}\"", e)
                writeNow()
            } catch (ignored: Throwable) {
                // Never stand in the way of the crash itself.
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun scheduleWrite() {
        if (file == null || !writePending.compareAndSet(false, true)) return
        try {
            writer.schedule({ writePending.set(false); writeNow() }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            writePending.set(false)
        }
    }

    /** Entries start with their date; continuation lines (an exception) are indented. */
    private fun parse(text: String): List<String> {
        val out = mutableListOf<String>()
        for (line in text.lines()) {
            if (line.isEmpty()) continue
            if (ENTRY_START.containsMatchIn(line) || out.isEmpty()) out += line else out[out.size - 1] = out.last() + "\n" + line
        }
        return out.takeLast(CAPACITY)
    }

    private val ENTRY_START = Regex("""^\d{4}-\d{2}-\d{2} """)

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(clock()))

    private fun StringBuilder.appendThrowable(error: Throwable) {
        var t: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (t != null && depth <= MAX_CAUSES && seen.add(t)) {
            append('\n').append(if (depth == 0) "    " else "    Caused by: ")
            append(t.javaClass.name)
            t.message?.let { append(": ").append(masked(it)) }
            val frames = t.stackTrace
            val shown = if (depth == 0) TOP_FRAMES else CAUSE_FRAMES
            frames.take(shown).forEach { f -> append("\n        at ").append(f.className).append('.').append(f.methodName).append('(').append(f.fileName ?: "?").append(':').append(f.lineNumber).append(')') }
            if (frames.size > shown) append("\n        … ").append(frames.size - shown).append(" more")
            t = t.cause
            depth++
        }
    }
}
