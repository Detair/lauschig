package io.wolftown.lauschig.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.KeptAccount
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.rmab.RmabConfig
import io.wolftown.lauschig.ui.LauschigTheme
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** What an entry holds, that it is masked before it is kept, the ring, the file across processes, and sharing it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticLogTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File

    @Before fun setUp() {
        // FileProvider caches its roots per authority in a static map; every Robolectric test has a new data directory.
        androidx.core.content.FileProvider::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type) }
            .forEach { f -> f.isAccessible = true; (f.get(null) as MutableMap<*, *>).clear() }
        dir = File(ctx.cacheDir, "diag-${System.nanoTime()}").apply { mkdirs() }
        DiagnosticLog.clear()
        DiagnosticLog.clock = { 1_762_330_000_000 }
    }

    @After fun tearDown() {
        DiagnosticLog.clock = System::currentTimeMillis
        DiagnosticLog.clear()
        DiagnosticLog.detach()
        LogMasker.setLiterals(emptyList())
        dir.deleteRecursively()
    }

    @Test fun anEntry_isTimeLevelTagAndTheMaskedMessage() {
        Log.i("PlaybackService", "connection: Online(baseUrl=https://abs.wolftown.io/, via=PUBLIC)")
        val e = DiagnosticLog.entries().single()
        assertTrue(e, Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} I/PlaybackService: connection: Online\(baseUrl=https://<server>/, via=PUBLIC\)$""").matches(e))
    }

    @Test fun anError_carriesItsChain_classNamesMaskedMessagesAndFrames() {
        val cause = java.net.UnknownHostException("Unable to resolve host \"abs.wolftown.io\": No address associated with hostname")
        val error = IOException("GET https://abs.wolftown.io/api/me?token=secret123 failed", cause)
        Log.w("ListeningSessionSync", "listening history not loaded", error)
        val e = DiagnosticLog.entries().single()
        assertFalse(e, e.contains("abs.wolftown") || e.contains("wolftown.io") || e.contains("secret123"))
        assertTrue(e, e.contains("W/ListeningSessionSync: listening history not loaded"))
        assertTrue(e, e.contains("    java.io.IOException: GET https://<server>/api/me?<…> failed"))
        assertTrue(e, e.contains("    Caused by: java.net.UnknownHostException: Unable to resolve host \"<server>\""))
        assertTrue(e, e.contains("        at io.wolftown.lauschig.diagnostics.DiagnosticLogTest."))
        assertTrue("frames are capped", e.lines().count { it.trimStart().startsWith("at ") } <= 8 + 4)
    }

    @Test fun debugLines_stayOut_andTheRingKeepsTheNewest() {
        Log.d("X", "debug")
        assertTrue(DiagnosticLog.entries().isEmpty())
        repeat(DiagnosticLog.CAPACITY + 7) { Log.i("Ring", "entry $it") }
        val all = DiagnosticLog.entries()
        assertEquals(DiagnosticLog.CAPACITY, all.size)
        assertTrue(all.first().endsWith("entry 7"))
        assertTrue(all.last().endsWith("entry ${DiagnosticLog.CAPACITY + 6}"))
    }

    @Test fun theFile_holdsOnlyMaskedEntries_andTheNextProcessReadsThemBack() {
        val file = File(dir, "diagnostic-log.txt")
        DiagnosticLog.attach(file)
        Log.w("AuthInterceptor", "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJlc2ln", IOException("password=hunter2"))
        assertTrue(DiagnosticLog.writeNow())
        val stored = file.readText()
        assertFalse(stored, stored.contains("eyJ") || stored.contains("hunter2"))

        // A new process: memory is empty, the file is read back, multi-line entries stay whole.
        DiagnosticLog.clear()
        file.writeText(stored)
        DiagnosticLog.attach(file)
        val back = DiagnosticLog.entries()
        assertEquals(2, back.size)
        assertTrue(back[0], back[0].contains("W/AuthInterceptor: Authorization: <redacted>") && back[0].contains("java.io.IOException: password=<redacted>"))
        assertTrue(back[1].endsWith("--- app started ---"))
    }

    @Test fun aCrash_isRecordedAndWritten_beforeThePreviousHandlerRuns() {
        val file = File(dir, "crash.txt")
        DiagnosticLog.attach(file)
        var handedOn: Throwable? = null
        var onDiskWhenHandedOn = ""
        val original = Thread.getDefaultUncaughtExceptionHandler()
        // The previous handler ends the process: what is on disk when it runs is all there will be.
        Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn = e; onDiskWhenHandedOn = file.readText() }
        try {
            DiagnosticLog.installCrashHandler()
            DiagnosticLog.installCrashHandler() // idempotent
            val boom = IllegalStateException("player released at http://192.168.1.5:13378/")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertEquals(boom, handedOn)
            val text = onDiskWhenHandedOn
            assertTrue(text, text.contains("E/Crash: uncaught exception in thread"))
            assertTrue(text, text.contains("java.lang.IllegalStateException: player released at http://<server>/"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    // --- the shared report

    private val facts = DiagnosticFacts("0.4.0", 4, "debug", "15", 35, "Google Pixel 7", "online (home network)", "SD card", 1_762_330_000_000)

    @Test fun theReport_isTheAllowlistedHeadThenTheEntries() = runBlocking {
        Log.i("FinishedDownloads", "deleted 2 finished downloads")
        val text = DiagnosticReport(dir, { facts }).text()
        val lines = text.lines()
        assertEquals("Lauschig diagnostic log", lines[0])
        assertTrue(lines[1].startsWith("Created: 2025-11-05"))
        assertEquals("App: 0.4.0 (4) debug", lines[2])
        assertEquals("Android: 15 (SDK 35)", lines[3])
        assertEquals("Device: Google Pixel 7", lines[4])
        assertEquals("Connection: online (home network)", lines[5])
        assertEquals("Downloads go to: SD card", lines[6])
        assertTrue(lines[7].startsWith("Entries: 1 "))
        assertEquals("----", lines[8])
        assertTrue(lines[9].endsWith("I/FinishedDownloads: deleted 2 finished downloads"))
    }

    @Test fun connectionFact_neverNamesTheServer() {
        val online = ConnectionState.Online("https://abs.wolftown.io/".toHttpUrl(), ConnectionState.Via.PUBLIC)
        assertEquals("online (internet)", DiagnosticReport.connectionFact(online))
        assertEquals("online (home network)", DiagnosticReport.connectionFact(online.copy(via = ConnectionState.Via.LOCAL)))
        assertEquals("offline", DiagnosticReport.connectionFact(ConnectionState.Offline))
    }

    @Test fun theSharedFile_livesInTheOneExposedDirectory_andTheIntentGrantsReadOnly() = runBlocking {
        val file = DiagnosticReport(ctx.cacheDir, { facts }).write()
        assertEquals(File(ctx.cacheDir, "diagnostics/lauschig-diagnose.txt"), file)
        val chooser = DiagnosticReport.shareIntent(ctx, file, "Lauschig diagnostic log 0.4.0", "Send to")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${ctx.packageName}.diagnostics", uri.authority)
        assertEquals("/diagnostics/lauschig-diagnose.txt", uri.path)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        val clip: ClipData = send.clipData!!
        assertEquals(uri, clip.getItemAt(0).uri)
        assertEquals("Lauschig diagnostic log 0.4.0", send.getStringExtra(Intent.EXTRA_SUBJECT))
        @Suppress("DEPRECATION") val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toList()
        assertEquals(listOf(android.content.ComponentName(ctx, io.wolftown.lauschig.MainActivity::class.java)), excluded)
    }

    @Test fun ownValues_hostsUserNamesWifiNamesAndReadMeABook() {
        val account = Account(
            "aaaaaaaaaaaaaaaa", "uid", "fabian", false,
            ServerAddresses("http://192.168.178.20:13378/".toHttpUrl(), "https://abs.wolftown.io/".toHttpUrl(), setOf("FRITZ!Box 7590 XY")), emptySet(),
        )
        val literals = DiagnosticSecrets.literals(account, listOf(KeptAccount("b", "lukas", "nas.local:13378")), RmabConfig("https://rmab.wolftown.io", "fab"))
        assertEquals(setOf("192.168.178.20", "abs.wolftown.io", "wolftown.io", "fabian", "FRITZ!Box 7590 XY", "nas.local", "lukas", "rmab.wolftown.io", "fab"), literals)
        LogMasker.setLiterals(literals)
        Log.i("Home", "lukas is on FRITZ!Box 7590 XY")
        assertTrue(DiagnosticLog.entries().single().endsWith("I/Home: <private> is on <private>"))
    }

    /** Phase 7, 1.7: a profile set aside stays masked, like a kept account. */
    @Test fun ownValues_ofASavedProfile_too() {
        val saved = Account(
            "bbbbbbbbbbbbbbbb", "uid2", "matthias", false,
            ServerAddresses("http://192.168.178.99:13378/".toHttpUrl(), "https://abs.familie.example/".toHttpUrl(), setOf("Heimnetz")), emptySet(),
        )
        val literals = DiagnosticSecrets.literals(null, emptyList(), null, listOf(saved))
        assertEquals(setOf("192.168.178.99", "abs.familie.example", "familie.example", "matthias", "Heimnetz"), literals)
    }

    @Test fun preview_isTheHeadAndTheNewestEntries() {
        val text = DiagnosticReport.render(facts, (1..100).map { "2026-11-05 10:00:00.000 I/T: entry $it" })
        val preview = DiagnosticsViewModel.previewOf(text)
        assertTrue(preview.startsWith("Lauschig diagnostic log\n"))
        assertTrue(preview.contains("… 60\n"))
        assertFalse(preview.contains("entry 60\n"))
        assertTrue(preview.trimEnd().endsWith("entry 100"))
    }

    @Test fun theRow_explainsShowsAndShares() {
        var opened = 0
        var shared = 0
        rule.setContent { LauschigTheme { Column { DiagnosticsRow("Lauschig diagnostic log\n----\nentry", { opened++ }, { shared++ }) } } }
        rule.onNodeWithText("Share diagnostic log").assertExists()
        rule.onNodeWithTag("shareDiagnostics").performClick()
        assertEquals(1, opened)
        rule.onNodeWithText("Passwords, tokens, cookies, server addresses", substring = true).assertExists()
        rule.onNodeWithTag("diagnosticsPreview").assertExists()
        rule.onNodeWithTag("diagnosticsShare").performClick()
        assertEquals(1, shared)
        rule.onNodeWithTag("diagnosticsPreview").assertDoesNotExist()
    }

    @Test fun earlyEntries_areMaskedAgain_onceTheOwnValuesAreKnown_andAtExport() = runBlocking {
        Log.w("Net", "Unable to resolve host nas-keller: timeout")
        assertTrue(DiagnosticLog.entries().single().contains("nas-keller"))
        LogMasker.setLiterals(listOf("nas-keller"))
        DiagnosticLog.remask()
        assertTrue(DiagnosticLog.entries().single().endsWith("Unable to resolve host <private>: timeout"))
        // And whatever is kept unmasked somewhere is masked once more when it is shared.
        val text = DiagnosticReport(dir, { facts }, { listOf("2026-11-05 10:00:00.000 I/X: lukas listened") }).text()
        LogMasker.setLiterals(listOf("lukas"))
        val again = DiagnosticReport(dir, { facts }, { listOf("2026-11-05 10:00:00.000 I/X: lukas listened") }).text()
        assertTrue(text.contains("lukas"))
        assertFalse(again, again.contains("lukas"))
    }

    @Test fun theFacade_neverThrowsIntoTheApp_logcatStillGetsTheLine() {
        val before = Log.recorder
        Log.recorder = { _, _, _, _ -> throw OutOfMemoryError("while building the entry") }
        try {
            Log.w("Any", "a line", IOException("x"))
            Log.i("Any", "another line")
            assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("Any").any { it.msg == "another line" })
        } finally {
            Log.recorder = before
        }
    }

    @Test fun manyWriters_atOnce_leaveAWholeFile() {
        val file = File(dir, "concurrent.txt")
        DiagnosticLog.attach(file)
        repeat(50) { Log.i("Many", "entry $it") }
        val threads = (1..8).map { Thread { repeat(30) { DiagnosticLog.writeNow() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(DiagnosticLog.writeNow())
        assertEquals(DiagnosticLog.entries().joinToString("\n", postfix = "\n"), file.readText())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test fun theSharedCopy_goesWithItsGrant_andClearRemovesTheLogFile() = runBlocking {
        val shared = DiagnosticReport(ctx.cacheDir, { facts }).write()
        assertTrue(shared.isFile)
        val revoked = mutableListOf<Pair<android.net.Uri, Int>>()
        val recording = object : android.content.ContextWrapper(ctx) {
            override fun revokeUriPermission(uri: android.net.Uri, modeFlags: Int) { revoked += uri to modeFlags }
        }
        DiagnosticReport.discardShared(recording)
        assertFalse(shared.exists())
        assertEquals(listOf(androidx.core.content.FileProvider.getUriForFile(ctx, DiagnosticReport.authority(ctx), shared) to Intent.FLAG_GRANT_READ_URI_PERMISSION), revoked)
        val file = File(dir, "persisted.txt")
        DiagnosticLog.attach(file)
        Log.i("Before", "sign-out")
        assertTrue(DiagnosticLog.writeNow())
        DiagnosticLog.clear()
        assertFalse("sign-out leaves nothing for the next account", file.exists())
        assertTrue(DiagnosticLog.entries().isEmpty())
    }

    @Test fun theProvider_exposesNothingButTheSharedCopy() {
        val elsewhere = listOf(File(ctx.noBackupFilesDir, "diagnostic-log.txt"), File(ctx.cacheDir, "image_cache/x"), File(ctx.filesDir, "downloads/x.mp3"))
        for (f in elsewhere) {
            try {
                androidx.core.content.FileProvider.getUriForFile(ctx, DiagnosticReport.authority(ctx), f)
                org.junit.Assert.fail("$f must not be reachable through the provider")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test fun parentDomains_ofOwnHosts() {
        assertEquals("example.io", DiagnosticSecrets.parentDomain("audiobookshelf.example.io"))
        assertEquals("example.co.uk", DiagnosticSecrets.parentDomain("abs.example.co.uk"))
        assertEquals(null, DiagnosticSecrets.parentDomain("example.io"))
        assertEquals(null, DiagnosticSecrets.parentDomain("nas"))
        assertEquals(null, DiagnosticSecrets.parentDomain("192.168.178.20"))
        assertEquals(null, DiagnosticSecrets.parentDomain("fd00::1"))
    }
}
