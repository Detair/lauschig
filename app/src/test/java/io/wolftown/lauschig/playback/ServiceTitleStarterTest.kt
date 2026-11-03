package io.wolftown.lauschig.playback

import androidx.media3.common.MediaItem
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
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
}
