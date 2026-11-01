package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.settings.AutoDownloadMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AutoDownloadTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val enqueued = mutableListOf<String>()
    private var saved: Set<String> = emptySet()

    private val manager = object : DownloadManager {
        override suspend fun enqueue(serverId: String, itemId: String) { enqueued += itemId }
        override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
        override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = flowOf(null)
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun downloader(mode: AutoDownloadMode) =
        EpisodeAutoDownloader(flowOf(mode), db.episodeDao(), db.progressDao(), db.downloadDao(), manager, { saved }, { saved = it })

    private fun ep(item: String, id: String, at: Long, recent: Boolean = true) =
        EpisodeEntity(s, item, id, "Folge $id", publishedAt = at, recent = recent)

    private suspend fun seed() {
        db.itemDao().upsertFromList(listOf(ItemEntity(serverId = s, id = "p1", libraryId = "pod", title = "One"), ItemEntity(serverId = s, id = "p2", libraryId = "pod", title = "Two")))
        db.episodeDao().upsert(
            listOf(
                ep("p1", "a", 500), ep("p1", "b", 400), ep("p1", "c", 300), ep("p1", "d", 200), ep("p1", "old", 100, recent = false),
                ep("p2", "x", 450), ep("p2", "y", 350),
            ),
        )
    }

    @Test fun downloadsNewestN_skipsPlayedAndDownloaded() = runTest {
        seed()
        db.progressDao().mergeFromServer(listOf(ProgressEntity(s, PlayableKey.episode("p1", "a"), 600.0, 600.0, true, 1))) // heard: skipped, not counted
        db.downloadDao().upsert(DownloadEntity(s, PlayableKey.episode("p1", "b"), DownloadState.COMPLETE, 1))             // already there
        downloader(AutoDownloadMode.NEWEST_3).run(s)
        // p1: unfinished recent newest 3 = b, c, d; b is already downloaded. p2: x, y.
        assertEquals(
            setOf("p1~c", "p1~d", "p2~x", "p2~y"),
            enqueued.toSet(),
        )
        assertEquals(4, enqueued.size)
    }

    @Test fun off_downloadsNothing() = runTest {
        seed()
        downloader(AutoDownloadMode.OFF).run(s)
        assertTrue(enqueued.isEmpty())
    }

    @Test fun newest1_andNoRepeatAfterUserRemovedIt() = runTest {
        seed()
        downloader(AutoDownloadMode.NEWEST_1).run(s)
        assertEquals(setOf("p1~a", "p2~x"), enqueued.toSet())
        // The user deleted both downloads (no rows); the next sync must not bring them back.
        enqueued.clear()
        downloader(AutoDownloadMode.NEWEST_1).run(s)
        assertTrue(enqueued.isEmpty())
    }
}
