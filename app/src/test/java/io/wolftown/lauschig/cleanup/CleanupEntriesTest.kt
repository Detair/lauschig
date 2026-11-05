package io.wolftown.lauschig.cleanup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.FinishedEntry
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The clean-up page lists finished episodes too (with their own title), so they can be kept from the clean-up. */
@RunWith(RobolectricTestRunner::class)
class CleanupEntriesTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private lateinit var db: LauschigDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun finishedBooksAndEpisodes_withTheirTitles_onlyCompleteAndFinished(): Unit = runBlocking {
        db.itemDao().upsertDetails(ItemEntity(s, "book", "l", "Zebra book"))
        db.itemDao().upsertDetails(ItemEntity(s, "pod", "l", "The podcast"))
        db.episodeDao().upsert(listOf(EpisodeEntity(s, "pod", "ep1", "Anfang der Folge")))
        db.itemDao().upsertDetails(ItemEntity(s, "unheard", "l", "Unheard"))
        db.itemDao().upsertDetails(ItemEntity(s, "partial", "l", "Partial"))
        for (k in listOf("book", "pod~ep1", "unheard", "partial")) {
            db.downloadDao().upsert(DownloadEntity(s, k, if (k == "partial") DownloadState.PAUSED else DownloadState.COMPLETE, 1))
            db.progressDao().replaceFromServer(ProgressEntity(s, k, 1.0, 1.0, k != "unheard", 1))
        }
        assertEquals(
            listOf(FinishedEntry("pod~ep1", "Anfang der Folge"), FinishedEntry("book", "Zebra book")),
            db.downloadDao().observeFinishedEntries(s).first(),
        )
    }
}
