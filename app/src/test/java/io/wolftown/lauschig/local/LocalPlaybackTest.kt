package io.wolftown.lauschig.local

import androidx.media3.common.MediaItem
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.playback.MediaItemFactory
import io.wolftown.lauschig.playback.PlaybackUnavailableException
import io.wolftown.lauschig.playback.PlayerControllerImpl
import io.wolftown.lauschig.playback.PlayerFacade
import io.wolftown.lauschig.playback.PlayerSnapshot
import io.wolftown.lauschig.playback.ServiceTitleStarter
import io.wolftown.lauschig.ui.common.coverModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Local titles play from their `content://` documents and never get anything from a server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalPlaybackTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dev = DeviceLibrary.SERVER_ID
    private val id = DeviceLibrary.titleId("content://tree/x", "dir:a")
    private val base = "https://abs.example/".toHttpUrl()

    private fun file(i: Int, dur: Double, path: String? = "content://fake/doc$i") = AudioFileEntity(dev, id, i, "doc$i", dur, 10, "mp3", path, 10)
    private fun item(files: List<AudioFileEntity>, progress: ProgressEntity? = null) =
        ItemWithDetails(ItemEntity(dev, id, DeviceLibrary.LIBRARY_ID, "Momo", author = "Ende", detailsLoaded = true), emptyList(), files, progress, null)

    @Test fun factory_usesContentUris_offlineAndOnline() {
        val f = MediaItemFactory(fileExists = { false }, contentExists = { true }, coverFile = { _, _ -> null })
        for (b in listOf(null, base)) {
            val items = f.build(item(listOf(file(1, 60.0), file(2, 30.0))), b)
            assertEquals(listOf("content", "content"), items.map { it.localConfiguration!!.uri.scheme })
            assertEquals("content://fake/doc1", items[0].localConfiguration!!.uri.toString())
        }
        assertTrue(f.isFullyLocal(item(listOf(file(1, 60.0)))))
    }

    @Test fun factory_missingDocument_neverStreamsALocalTitle_evenWithABaseUrl() {
        val f = MediaItemFactory(fileExists = { false }, contentExists = { false }, coverFile = { _, _ -> null })
        assertFalse(f.isFullyLocal(item(listOf(file(1, 60.0)))))
        assertThrows { f.build(item(listOf(file(1, 60.0))), base) }
        // Even a row without a path (never written by the scanner) cannot become a stream URL.
        assertThrows { MediaItemFactory({ true }, { true }) { _, _ -> null }.build(item(listOf(file(1, 60.0, path = null))), base) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected PlaybackUnavailableException")
        } catch (_: PlaybackUnavailableException) {
        }
    }

    @Test fun factory_serverTitle_keepsFileUris() {
        val s = "aaaaaaaaaaaaaaaa"
        val server = ItemWithDetails(ItemEntity(s, "li", "lib", "T"), emptyList(), listOf(AudioFileEntity(s, "li", 1, "ino", 10.0, 1, "mp3", "/d/1.mp3")), null, null)
        val items = MediaItemFactory(fileExists = { true }, contentExists = { false }, coverFile = { _, _ -> null }).build(server, null)
        assertEquals("file", items.single().localConfiguration!!.uri.scheme)
    }

    private class Facade : PlayerFacade {
        var items: List<MediaItem> = emptyList()
        private var snap = PlayerSnapshot(false, 0, 0, 1f)
        override suspend fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) { this.items = items; snap = snap.copy(mediaIndex = startIndex, positionMs = startPositionMs) }
        override fun replaceMediaItem(index: Int, item: MediaItem) {}
        override fun play() { snap = snap.copy(isPlaying = true) }
        override fun pause() { snap = snap.copy(isPlaying = false) }
        override fun seekTo(mediaIndex: Int, positionMs: Long) { snap = snap.copy(mediaIndex = mediaIndex, positionMs = positionMs) }
        override fun setSpeed(speed: Float) {}
        override fun snapshot() = snap
        override fun hasMedia() = items.isNotEmpty()
        override fun setOnError(onError: (Int) -> Unit) {}
        override fun setOnDisconnected(onDisconnected: () -> Unit) {}
        override fun setOnChange(onChange: () -> Unit) {}
    }

    @Test fun controller_localTitle_neverAsksForTheServer_loadsNoDetails_reportsNoStream() = runTest {
        var baseAsked = 0
        val details = mutableListOf<String>()
        val streams = mutableListOf<String>()
        val facade = Facade()
        val present = MediaItemFactory(fileExists = { false }, contentExists = { true }, coverFile = { _, _ -> null })
        val c = PlayerControllerImpl(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            facade = facade,
            factory = present,
            loadItem = { _, _ -> item(listOf(file(1, 60.0), file(2, 60.0)), ProgressEntity(dev, id, 90.0, 120.0, false, 0)) },
            onlineBaseUrl = { baseAsked++; base },
            coverFile = { _, _ -> null },
            loadDetails = { _, k -> details += k },
            io = UnconfinedTestDispatcher(testScheduler),
            onStreamStarted = { _, k -> streams += k },
        )
        c.play(dev, id)
        assertEquals(listOf("content", "content"), facade.items.map { it.localConfiguration!!.uri.scheme })
        assertEquals(1, facade.snapshot().mediaIndex)
        assertEquals(0, baseAsked)
        assertTrue(details.isEmpty() && streams.isEmpty())
        assertFalse(c.state.value.isStreaming)
    }

    @Test fun controller_localTitleWithMissingDocuments_showsAnErrorInsteadOfStreaming() = runTest {
        var baseAsked = 0
        val details = mutableListOf<String>()
        val facade = Facade()
        val c = PlayerControllerImpl(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            facade = facade,
            factory = MediaItemFactory(fileExists = { false }, contentExists = { false }, coverFile = { _, _ -> null }),
            loadItem = { _, _ -> item(listOf(file(1, 60.0))) },
            onlineBaseUrl = { baseAsked++; base },
            coverFile = { _, _ -> null },
            loadDetails = { _, k -> details += k },
            io = UnconfinedTestDispatcher(testScheduler),
        )
        c.play(dev, id)
        assertTrue(facade.items.isEmpty())
        assertEquals(R.string.error_local_unavailable, c.state.value.error)
        assertEquals(0, baseAsked)
        assertTrue(details.isEmpty())
        // No files at all (a folder that lost them): no details request either.
        val empty = PlayerControllerImpl(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            facade = Facade(), factory = MediaItemFactory({ false }, { true }) { _, _ -> null },
            loadItem = { _, _ -> item(emptyList()) }, onlineBaseUrl = { baseAsked++; base }, coverFile = { _, _ -> null },
            loadDetails = { _, k -> details += k }, io = UnconfinedTestDispatcher(testScheduler),
        )
        empty.play(dev, id)
        assertEquals(R.string.error_local_unavailable, empty.state.value.error)
        assertEquals(0, baseAsked)
        assertTrue(details.isEmpty())
    }

    @Test fun serviceStarter_localTitle_playsWithoutServer_andNeverStreams() = runTest {
        val queued = mutableListOf<Int>()
        val details = mutableListOf<String>()
        val streams = mutableListOf<String>()
        fun starter(exists: Boolean, files: List<AudioFileEntity>) = ServiceTitleStarter(
            loadItem = { _, _ -> item(files) },
            factory = MediaItemFactory(fileExists = { false }, contentExists = { exists }, coverFile = { _, _ -> null }),
            baseUrl = { base },
            loadDetails = { _, k -> details += k },
            io = UnconfinedTestDispatcher(testScheduler),
            setQueue = { items, _, _ -> queued += items.size },
            onStreamStarted = { _, k -> streams += k },
        )
        assertTrue(starter(true, listOf(file(1, 60.0))).start(dev, id))
        assertEquals(listOf(1), queued)
        assertFalse(starter(false, listOf(file(1, 60.0))).start(dev, id))
        assertFalse(starter(true, emptyList()).start(dev, id))
        assertEquals(listOf(1), queued)
        assertTrue(details.isEmpty() && streams.isEmpty())
    }

    @Test fun covers_ofLocalTitles_comeFromTheDeviceCoverFolder_neverFromTheServer() {
        val root = tmp.newFolder("files")
        val db = androidx.room.Room.inMemoryDatabaseBuilder(androidx.test.core.app.ApplicationProvider.getApplicationContext(), io.wolftown.lauschig.db.LauschigDatabase::class.java).allowMainThreadQueries().build()
        val files = DownloadFiles(File(root, "downloads"), audioFiles = db.audioFileDao(), deviceCovers = File(root, "device_covers"))
        val cover = files.coverFile(dev, id)
        assertEquals(File(File(root, "device_covers"), id.removePrefix("local:") + ".jpg"), cover)
        assertEquals(cover, files.playableCover(dev, id))
        // A local id never builds a server cover URL, with or without a connection.
        assertNull(coverModel(base, id, null, 5))
        assertEquals(File("/x.jpg"), coverModel(base, id, "/x.jpg", 5))
        assertTrue(coverModel(base, "li_1", null, 0).toString().startsWith("https://abs.example/"))
        db.close()
    }
    @Test fun factory_resolvesEachFileOnce_soAFlipBetweenChecksCannotCrash() {
        // The provider answers "present" only for the first query: a second pass over the files would see them gone.
        var calls = 0
        val flaky = MediaItemFactory(fileExists = { false }, contentExists = { calls++ == 0 }, coverFile = { _, _ -> null })
        val items = flaky.build(item(listOf(file(1, 60.0), file(2, 30.0))), null)
        assertEquals(listOf("content", "content"), items.map { it.localConfiguration!!.uri.scheme })
        // A server title whose downloaded file vanishes between the checks: never a NullPointerException.
        val s = "aaaaaaaaaaaaaaaa"
        var fileCalls = 0
        val server = ItemWithDetails(ItemEntity(s, "li", "lib", "T"), emptyList(), listOf(AudioFileEntity(s, "li", 1, "ino", 10.0, 1, "mp3", "/d/1.mp3")), null, null)
        val vanishing = MediaItemFactory(fileExists = { fileCalls++ == 0 }, contentExists = { true }, coverFile = { _, _ -> null })
        assertEquals("file", vanishing.build(server, null).single().localConfiguration!!.uri.scheme)
    }

    @Test fun factory_asksTheProviderOncePerCall_notOncePerFile() {
        var calls = 0
        val f = MediaItemFactory(fileExists = { false }, contentExists = { calls++; true }, coverFile = { _, _ -> null })
        val many = item((1..20).map { file(it, 10.0) })
        assertTrue(f.isFullyLocal(many))
        assertEquals(1, calls)
        f.build(many, null)
        assertEquals(2, calls)
    }
    @Test fun controller_localDocumentsVanishWhileLoaded_pausesWithTheLocalError() = runTest {
        var present = true
        val count = kotlinx.coroutines.flow.MutableStateFlow(2)
        val facade = Facade()
        val c = PlayerControllerImpl(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            facade = facade,
            factory = MediaItemFactory(fileExists = { false }, contentExists = { present }, coverFile = { _, _ -> null }),
            loadItem = { _, _ -> item(listOf(file(1, 60.0), file(2, 60.0))) },
            onlineBaseUrl = { base },
            coverFile = { _, _ -> null },
            io = UnconfinedTestDispatcher(testScheduler),
            localCount = { _, _ -> count },
        )
        c.play(dev, id)
        assertTrue(facade.snapshot().isPlaying)
        present = false
        count.value = 1 // a rescan changed the files
        assertFalse(facade.snapshot().isPlaying)
        assertEquals(R.string.error_local_unavailable, c.state.value.error)
    }
}
