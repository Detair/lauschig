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
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> handedOn = e }
        try {
            DiagnosticLog.installCrashHandler()
            DiagnosticLog.installCrashHandler() // idempotent
            val boom = IllegalStateException("player released at http://192.168.1.5:13378/")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertEquals(boom, handedOn)
            val text = file.readText()
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
        assertEquals(setOf("192.168.178.20", "abs.wolftown.io", "fabian", "FRITZ!Box 7590 XY", "nas.local", "lukas", "rmab.wolftown.io", "fab"), literals)
        LogMasker.setLiterals(literals)
        Log.i("Home", "lukas is on FRITZ!Box 7590 XY")
        assertTrue(DiagnosticLog.entries().single().endsWith("I/Home: <private> is on <private>"))
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
}
