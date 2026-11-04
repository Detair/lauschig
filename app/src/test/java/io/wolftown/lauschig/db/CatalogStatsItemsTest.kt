package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.entities.ItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The statistics read only the columns they use of a title – no description, tags or narrators. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogStatsItemsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
        .allowMainThreadQueries().build()
    private val s = "aaaaaaaaaaaaaaaa"

    @After fun tearDown() = db.close()

    @Test fun theColumnsTheStatisticsUse_asATitle_withoutTheLongOnes() = runTest {
        val full = ItemEntity(
            s, "a", "lib", "Band Eins", subtitle = "Sub", author = "Anna", narrator = "Nina", seriesName = "Reihe", seriesSequence = "1",
            seriesSortKey = 1.0, durationSec = 3600.0, coverUpdatedAt = 7, genres = listOf("Krimi", "Humor"), tags = listOf("t"),
            description = "lang …", updatedAt = 9, addedAt = 3,
        )
        db.itemDao().upsertFromList(listOf(full, ItemEntity("other", "b", "lib", "B")))

        val items = db.catalogDao().observeForStats(s).first()

        assertEquals(1, items.size)
        val item = items.single().toEntity(s)
        assertEquals(
            ItemEntity(
                s, "a", "lib", "Band Eins", author = "Anna", seriesName = "Reihe", seriesSequence = "1", seriesSortKey = 1.0,
                durationSec = 3600.0, coverUpdatedAt = 7, genres = listOf("Krimi", "Humor"),
            ),
            item,
        )
    }
}
