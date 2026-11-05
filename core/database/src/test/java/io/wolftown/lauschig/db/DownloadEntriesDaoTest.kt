package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadEntriesDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
    private val s = "aaaaaaaaaaaaaaaa"

    @After fun tearDown() = db.close()

    @Test fun entries_haveTitles_bytesOnDisk_andListeningState_forBooksAndEpisodes() = runTest {
        db.itemDao().upsertDetails(ItemEntity(s, "b1", "l", "Momo", author = "Ende"))
        db.itemDao().upsertDetails(ItemEntity(s, "pod", "lp", "Der Podcast", numEpisodes = 1))
        db.episodeDao().insertIgnore(listOf(EpisodeEntity(s, "pod", "ep1", "Folge 1")))
        val ep = PlayableKey.episode("pod", "ep1")
        db.audioFileDao().apply {
            replaceFromServer(s, "b1", listOf(AudioFileEntity(s, "b1", 1, "i1", 10.0, 100, "mp3"), AudioFileEntity(s, "b1", 2, "i2", 10.0, 200, "mp3")))
            replaceFromServer(s, ep, listOf(AudioFileEntity(s, ep, 1, "e1", 10.0, 50, "mp3")))
        }
        db.downloadDao().upsert(DownloadEntity(s, "b1", DownloadState.COMPLETE, 7))
        db.downloadDao().upsert(DownloadEntity(s, ep, DownloadState.RUNNING, 9))
        db.audioFileDao().setLocal(s, "b1", 1, "/d/1", 100)
        db.audioFileDao().setLocal(s, "b1", 2, "/d/2", 200)
        db.audioFileDao().setProgress(s, ep, 1, 20)
        db.progressDao().saveLocal(s, "b1", 20.0, 20.0, true, 1234)
        // Another server's download is not listed.
        db.downloadDao().upsert(DownloadEntity("bbbbbbbbbbbbbbbb", "x", DownloadState.COMPLETE, 1))

        val rows = db.downloadDao().observeEntries(s).first().associateBy { it.itemKey }
        assertEquals(setOf("b1", ep), rows.keys)
        val book = rows.getValue("b1")
        assertEquals("Momo", book.title)
        assertEquals("Ende", book.subtitle)
        assertEquals(300L, book.bytesDone)
        assertEquals(300L, book.bytesTotal)
        assertEquals(1234L, book.lastPlayedAt)
        assertTrue(book.isFinished)
        assertEquals(7L, book.requestedAt)
        val episode = rows.getValue(ep)
        assertEquals("Folge 1", episode.title)
        assertEquals("Der Podcast", episode.subtitle)
        assertEquals(20L, episode.bytesDone)
        assertEquals(50L, episode.bytesTotal)
        assertNull(episode.lastPlayedAt)
        assertFalse(episode.isFinished)
        assertEquals(DownloadState.RUNNING, episode.state)
    }
}
