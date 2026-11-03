package io.wolftown.lauschig.ui.collections

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.CollectionEntryEntity
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CollectionEntriesTest {
    private lateinit var db: LauschigDatabase
    private val s = "srv"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun keepsOrder_mapsBooksAndEpisodes_countsUnknown() = runTest {
        db.itemDao().upsertFromList(listOf(ItemEntity(s, "b1", "l", "Band 1"), ItemEntity(s, "pod", "p", "Podcast")))
        db.episodeDao().upsert(listOf(EpisodeEntity(s, "pod", "e1", "Folge", durationSec = 60.0)))
        val entries = listOf("pod~e1", "gone", "b1", "pod~gone").mapIndexed { i, k -> CollectionEntryEntity(s, "c", i, k) }
        val covers = CoverPathCache(DownloadFiles(File(System.getProperty("java.io.tmpdir"), "ce-${System.nanoTime()}"), db.audioFileDao())) { false }
        val progress = mapOf("b1" to ProgressEntity(s, "b1", 50.0, 100.0, false, 1), "pod~e1" to ProgressEntity(s, "pod~e1", 60.0, 60.0, true, 1))

        val (ui, missing) = collectionEntries(s, entries, db.itemDao(), db.episodeDao(), progress, setOf("pod~e1"), covers)

        assertEquals(listOf("pod~e1", "b1"), ui.map { it.key })
        val ep = ui[0] as CollectionEntryUi.Episode
        assertEquals("Podcast", ep.podcast)
        assertTrue(ep.downloaded && ep.finished)
        assertEquals(0.5f, (ui[1] as CollectionEntryUi.Book).summary.progress, 0f)
        assertEquals(2, missing)
    }
}
