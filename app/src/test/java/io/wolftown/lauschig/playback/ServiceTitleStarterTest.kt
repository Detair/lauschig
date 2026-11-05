package io.wolftown.lauschig.playback

import androidx.media3.common.MediaItem
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServiceTitleStarterTest {
    private val s = "srv"
    private fun file(i: Int, dur: Double, path: String?) = AudioFileEntity(s, "b", i, "ino$i", dur, 1, "mp3", path)
    private fun item(files: List<AudioFileEntity>, progress: ProgressEntity? = null) =
        ItemWithDetails(ItemEntity(s, "b", "l", "Band 2", durationSec = 200.0), emptyList(), files, progress, null)

    private val queued = mutableListOf<Triple<Int, Int, Long>>()
    private val streamed = mutableListOf<String>()

    private fun kotlinx.coroutines.test.TestScope.starter(load: () -> ItemWithDetails?, base: String?, loadDetails: suspend () -> Unit = {}) = ServiceTitleStarter(
        loadItem = { _, _ -> load() },
        factory = MediaItemFactory({ true }) { _, _ -> null },
        baseUrl = { base?.toHttpUrl() },
        loadDetails = { _, _ -> loadDetails() },
        io = UnconfinedTestDispatcher(testScheduler),
        setQueue = { items: List<MediaItem>, i, ms -> queued += Triple(items.size, i, ms) },
        onStreamStarted = { _, k -> streamed += k },
    )

    @Test fun localTitle_startsAtSavedPositionAcrossFiles_offline() = runTest {
        val it = item(listOf(file(0, 100.0, "/d/0.mp3"), file(1, 100.0, "/d/1.mp3")), ProgressEntity(s, "b", 150.0, 200.0, false, 1))
        assertTrue(starter({ it }, base = null).start(s, "b"))
        assertEquals(listOf(Triple(2, 1, 50_000L)), queued)
        assertTrue(streamed.isEmpty())
    }

    @Test fun finishedTitle_startsAtZero() = runTest {
        val it = item(listOf(file(0, 100.0, "/d/0.mp3")), ProgressEntity(s, "b", 99.0, 100.0, true, 1))
        assertTrue(starter({ it }, base = null).start(s, "b"))
        assertEquals(Triple(1, 0, 0L), queued.single())
    }

    @Test fun streamedTitle_needsTheServer() = runTest {
        val it = item(listOf(file(0, 100.0, null)))
        assertFalse(starter({ it }, base = null).start(s, "b"))
        assertTrue(queued.isEmpty())
        assertTrue(starter({ it }, base = "https://abs.example/").start(s, "b"))
        assertEquals(listOf("b"), streamed)
    }

    @Test fun resolve_withRewind_jumpsBackByThePauseSinceTheLastSave_withoutTouchingThePlayer() = runTest {
        val it = item(listOf(file(0, 100.0, "/d/0.mp3"), file(1, 100.0, "/d/1.mp3")), ProgressEntity(s, "b", 150.0, 200.0, false, 1_000))
        val pauses = mutableListOf<Long>()
        val st = ServiceTitleStarter(
            loadItem = { _, _ -> it }, factory = MediaItemFactory({ true }) { _, _ -> null }, baseUrl = { null },
            loadDetails = { _, _ -> }, io = UnconfinedTestDispatcher(testScheduler),
            setQueue = { _: List<MediaItem>, _, _ -> error("resolve never sets the queue") },
            rewindAfterPause = { pause -> pauses += pause; 60_000 }, clock = { 1_000 + 20 * 60_000L },
        )
        val r = st.resolve(s, "b", rewind = true) as ServiceTitleStarter.Resolved.Ready
        assertEquals(listOf(20 * 60_000L), pauses)
        assertEquals(2, r.items.size)
        assertEquals(0 to 90_000L, r.index to r.offsetMs) // 150 s - 60 s, in the first file
        assertFalse(r.streaming)
        // Without the rewind option (queue advance) the saved position is used as is.
        val plain = st.resolve(s, "b") as ServiceTitleStarter.Resolved.Ready
        assertEquals(1 to 50_000L, plain.index to plain.offsetMs)
    }

    @Test fun resolve_reportsWhyATitleCannotStart() = runTest {
        val streamedItem = item(listOf(file(0, 100.0, null)))
        assertEquals(ServiceTitleStarter.Failure.OFFLINE, (starter({ streamedItem }, base = null).resolve(s, "b") as ServiceTitleStarter.Resolved.Failed).reason)
        assertEquals(ServiceTitleStarter.Failure.NOT_FOUND, (starter({ null }, base = null).resolve(s, "b") as ServiceTitleStarter.Resolved.Failed).reason)
        val noFiles = item(emptyList())
        assertEquals(
            ServiceTitleStarter.Failure.NOT_PLAYABLE,
            (starter({ noFiles }, base = "https://abs.example/", loadDetails = { error("down") }).resolve(s, "b") as ServiceTitleStarter.Resolved.Failed).reason,
        )
        val ready = starter({ streamedItem }, base = "https://abs.example/").resolve(s, "b") as ServiceTitleStarter.Resolved.Ready
        assertTrue(ready.streaming)
        // Resolving never touches the player and never starts a download.
        assertTrue(queued.isEmpty())
        assertTrue(streamed.isEmpty())
    }

    @Test fun unknownFiles_loadDetailsFirst_failureMeansNotStarted() = runTest {
        var current = item(emptyList())
        assertTrue(starter({ current }, base = "https://abs.example/", loadDetails = { current = item(listOf(file(0, 100.0, null))) }).start(s, "b"))
        current = item(emptyList())
        assertFalse(starter({ current }, base = "https://abs.example/", loadDetails = { error("down") }).start(s, "b"))
        assertFalse(starter({ null }, base = "https://abs.example/").start(s, "missing"))
        assertEquals(1, queued.size)
    }

    @Test fun resumption_onAColdStart_waitsForTheFirstProbe_insteadOfCallingAStreamOffline() = runTest {
        // The first drive: process dead, mobile + NetBird. The probe tries the unreachable home address first (3 s
        // timeout), then the public one: online after 5 s. A fixed 3 s wait reported OFFLINE for the streamed title.
        val connection = kotlinx.coroutines.flow.MutableStateFlow<io.wolftown.lauschig.connection.ConnectionState>(io.wolftown.lauschig.connection.ConnectionState.Offline)
        val checking = kotlinx.coroutines.flow.MutableStateFlow(true)
        val streamed = item(listOf(file(0, 100.0, null)))
        val st = ServiceTitleStarter(
            loadItem = { _, _ -> streamed }, factory = MediaItemFactory({ true }) { _, _ -> null },
            baseUrl = { io.wolftown.lauschig.connection.awaitOnlineAnswer(connection, checking, io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS)?.baseUrl },
            loadDetails = { _, _ -> }, io = UnconfinedTestDispatcher(testScheduler), setQueue = { _: List<MediaItem>, _, _ -> },
        )
        backgroundScope.launch {
            kotlinx.coroutines.delay(5_000)
            connection.value = io.wolftown.lauschig.connection.ConnectionState.Online("https://abs.example/".toHttpUrl(), io.wolftown.lauschig.connection.ConnectionState.Via.PUBLIC)
            checking.value = false
        }
        val r = st.resolve(s, "b", rewind = true)
        assertTrue(r.toString(), r is ServiceTitleStarter.Resolved.Ready && r.streaming)
        assertEquals(5_000L, testScheduler.currentTime)

        // Truly offline (the probe answered "offline"): no waiting at all.
        val before = testScheduler.currentTime
        connection.value = io.wolftown.lauschig.connection.ConnectionState.Offline
        assertEquals(ServiceTitleStarter.Failure.OFFLINE, (st.resolve(s, "b") as ServiceTitleStarter.Resolved.Failed).reason)
        assertEquals(before, testScheduler.currentTime)
    }

    /** Review N1: the resolve can wait a round trip for the details; a switch in that time starts nothing, not even a device title. */
    @Test fun aSwitchWhileResolving_startsNothing() = runTest {
        val it = item(listOf(file(0, 100.0, null)))
        val started = starter({ it }, base = "https://abs.example/").start(s, "b") { false }
        assertFalse(started)
        assertTrue(queued.isEmpty())
        assertTrue(streamed.isEmpty())
    }

    @Test fun theAccountIsAskedAfterTheResolve_notBefore() = runTest {
        var loaded = false
        val it = item(listOf(file(0, 100.0, null)))
        val starter = starter({ loaded = true; it }, base = "https://abs.example/")
        assertTrue(starter.start(s, "b") { assertTrue("after the resolve", loaded); true })
    }
}
