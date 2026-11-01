package io.wolftown.lauschig.playback

import androidx.media3.common.MediaItem
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private class FakeFacade : PlayerFacade {
    var items: List<MediaItem> = emptyList()
    var snap = PlayerSnapshot(false, 0, 0, 1f)
    var started: Pair<Int, Long>? = null
    private var cb: () -> Unit = {}
    override suspend fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        this.items = items; started = startIndex to startPositionMs; snap = snap.copy(mediaIndex = startIndex, positionMs = startPositionMs)
    }
    override fun play() { snap = snap.copy(isPlaying = true) }
    override fun pause() { snap = snap.copy(isPlaying = false) }
    override fun seekTo(mediaIndex: Int, positionMs: Long) { snap = snap.copy(mediaIndex = mediaIndex, positionMs = positionMs) }
    override fun setSpeed(speed: Float) { snap = snap.copy(speed = speed) }
    override fun snapshot() = snap
    override fun setOnChange(onChange: () -> Unit) { cb = onChange }
    var errorCb: () -> Unit = {}
    var disconnectedCb: () -> Unit = {}
    override fun hasMedia() = items.isNotEmpty()
    override fun setOnError(onError: () -> Unit) { errorCb = onError }
    override fun setOnDisconnected(onDisconnected: () -> Unit) { disconnectedCb = onDisconnected }
    fun disconnect() { items = emptyList(); snap = PlayerSnapshot(false, 0, 0, 1f); disconnectedCb() }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerControllerTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private fun file(i: Int, dur: Double, path: String? = "/d/$i.mp3") = AudioFileEntity(s, "it", i, "ino$i", dur, 1, "mp3", path)
    private fun chapter(i: Int, st: Double, en: Double) = ChapterEntity(s, "it", i, st, en, "Chapter $i")
    private fun item(files: List<AudioFileEntity>, progress: ProgressEntity? = null) = ItemWithDetails(
        ItemEntity(s, "it", "lib", "Title", author = "Author", durationSec = 1800.0),
        listOf(chapter(0, 0.0, 500.0), chapter(1, 500.0, 1000.0), chapter(2, 1000.0, 1800.0)), files, progress, null,
    )

    private val threeFiles get() = listOf(file(0, 600.0), file(1, 300.0), file(2, 900.0))

    private fun TestScope.controller(facade: FakeFacade, item: ItemWithDetails?, base: String? = null) = PlayerControllerImpl(
        scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        facade = facade,
        factory = MediaItemFactory({ true }) { _, _ -> null },
        loadItem = { _, _ -> item },
        onlineBaseUrl = { base?.toHttpUrl() },
        coverFile = { _, _ -> null },
    )

    @Test fun play_withExplicitStart_seeksIntoRightFile() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it", 750.0)
        assertEquals(3, f.items.size)
        assertEquals(1 to 150_000L, f.started)
        assertTrue(c.state.value.isPlaying)
        assertEquals(750.0, c.state.value.positionSec, 0.001)
        assertEquals(1800.0, c.state.value.durationSec, 0.001)
        assertEquals(1, c.state.value.chapterIndex)
        assertEquals(500.0, c.state.value.chapterStartSec, 0.0)
        assertEquals(1000.0, c.state.value.chapterEndSec, 0.0)
    }

    @Test fun play_withoutStart_usesSavedProgress() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles, ProgressEntity(s, "it", 950.0, 1800.0, false, 1)))
        c.play(s, "it")
        assertEquals(2 to 50_000L, f.started)
    }

    @Test fun play_finishedOrNoProgress_startsAtZero() = runTest {
        val f = FakeFacade()
        controller(f, item(threeFiles, ProgressEntity(s, "it", 1790.0, 1800.0, true, 1))).play(s, "it")
        assertEquals(0 to 0L, f.started)
        val f2 = FakeFacade()
        controller(f2, item(threeFiles)).play(s, "it")
        assertEquals(0 to 0L, f2.started)
    }

    @Test fun play_offlineNotDownloaded_setsError() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(listOf(file(0, 600.0), file(1, 300.0, path = null))), base = null)
        c.play(s, "it")
        assertEquals(R.string.error_not_downloaded_offline, c.state.value.error)
        assertTrue(f.items.isEmpty())
    }

    @Test fun play_onlinePartiallyLocal_streamsMissing() = runTest {
        val f = FakeFacade()
        controller(f, item(listOf(file(0, 600.0), file(1, 300.0, path = null))), base = "https://abs.example/").play(s, "it")
        assertEquals("file", f.items[0].localConfiguration!!.uri.scheme)
        assertEquals("https", f.items[1].localConfiguration!!.uri.scheme)
    }

    @Test fun seekBy_crossesFileBoundary_andClamps() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it", 590.0)
        c.seekBy(30)
        assertEquals(1, f.snap.mediaIndex); assertEquals(20_000L, f.snap.positionMs)
        c.seekBy(-30)
        assertEquals(0, f.snap.mediaIndex); assertEquals(590_000L, f.snap.positionMs)
        c.seekBy(-5000)
        assertEquals(0, f.snap.mediaIndex); assertEquals(0L, f.snap.positionMs)
    }

    @Test fun seekTo_mapsAbsoluteAcrossFileBoundary() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it", 10.0)
        c.seekTo(750.0)
        assertEquals(1 to 150_000L, f.snap.mediaIndex to f.snap.positionMs)
        c.seekTo(-5.0)
        assertEquals(0 to 0L, f.snap.mediaIndex to f.snap.positionMs)
    }

    @Test fun seekToChapter_andToggleAndSpeed() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it")
        c.seekToChapter(2)
        assertEquals(2, f.snap.mediaIndex); assertEquals(100_000L, f.snap.positionMs)
        assertEquals(2, c.state.value.chapterIndex)
        c.togglePlayPause(); assertTrue(!c.state.value.isPlaying)
        c.togglePlayPause(); assertTrue(c.state.value.isPlaying)
        c.setSpeed(1.5f); assertEquals(1.5f, c.state.value.speed, 0f)
    }

    @Test fun playSameItemWithoutStart_justResumes() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it", 100.0)
        c.togglePlayPause()
        f.started = null
        c.play(s, "it")
        assertNull(f.started)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun playAfterServiceDisconnect_rebuildsAndPlays() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles, ProgressEntity(s, "it", 100.0, 1800.0, false, 1)))
        c.play(s, "it", 700.0)
        f.disconnect()
        assertTrue(!c.state.value.isPlaying)
        c.play(s, "it")
        assertEquals(3, f.items.size)
        assertEquals(0 to 100_000L, f.started)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun playerError_surfacesInUiState() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it")
        f.errorCb()
        assertEquals(R.string.error_playback_failed, c.state.value.error)
        assertTrue(!c.state.value.isPlaying)
    }
}
