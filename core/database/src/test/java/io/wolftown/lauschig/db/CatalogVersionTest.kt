package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.entities.ItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogVersionTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
        .allowMainThreadQueries().build()
    private val s = "aaaaaaaaaaaaaaaa"

    @After fun tearDown() = db.close()

    private suspend fun version() = db.catalogDao().observeVersion(s).first()

    @Test fun changesWithEveryTitleChange_withoutLoadingTheTitles() = runTest {
        val empty = version()
        assertEquals(0, empty.count)
        db.itemDao().upsertFromList(listOf(ItemEntity(s, "a", "l", "A", updatedAt = 10, addedAt = 1)))
        val one = version()
        assertEquals(1, one.count)
        assertNotEquals(empty, one)
        db.itemDao().upsertFromList(listOf(ItemEntity(s, "a", "l", "A", updatedAt = 20, addedAt = 1)))
        val updated = version()
        assertNotEquals(one, updated)
        db.itemDao().upsertFromList(listOf(ItemEntity(s, "a", "l", "A", updatedAt = 20, addedAt = 1, coverUpdatedAt = 5)))
        assertNotEquals(updated, version())
        // Another server's titles do not count.
        db.itemDao().upsertFromList(listOf(ItemEntity("bbbbbbbbbbbbbbbb", "x", "l", "X")))
        assertEquals(1, version().count)
    }
}
