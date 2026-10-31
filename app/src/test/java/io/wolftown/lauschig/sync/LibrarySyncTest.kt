package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.absapi.library.BookMediaDto
import io.wolftown.lauschig.absapi.library.BookMetadataDto
import io.wolftown.lauschig.absapi.library.LibrariesResponse
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.LibraryDto
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import io.wolftown.lauschig.absapi.library.LibraryItemsPage
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

private class FakeLibrary : LibraryApi {
    var items: List<LibraryItemDto> = emptyList()
    var failOnPage: Int? = null
    var reportedTotal: Int? = null
    var detail: LibraryItemDto? = null
    var ignorePage = false
    val pageSizes = mutableListOf<Int>()

    override suspend fun libraries() = LibrariesResponse(listOf(LibraryDto("l1", "Bücher", "book", 0)))
    override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int): LibraryItemsPage {
        pageSizes += limit
        if (failOnPage == page) throw IOException("boom")
        val chunk = items.drop((if (ignorePage) 0 else page) * limit).take(limit)
        return LibraryItemsPage(chunk, reportedTotal ?: items.size, limit, page)
    }
    override suspend fun item(id: String, expanded: Int) = detail!!
}

@RunWith(RobolectricTestRunner::class)
class LibrarySyncTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val api = FakeLibrary()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun sync(a: LibraryApi? = api) = LibrarySync(
        flowOf(a), db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(),
        { block -> db.withTransaction { block() } },
    )

    private fun dto(id: String, series: String? = null) =
        LibraryItemDto(id, "l1", "book", 1, BookMediaDto(BookMetadataDto(title = "T$id", seriesName = series), duration = 10.0))

    @Test fun pagesThroughAllItems_andParsesSeries() = runTest {
        api.items = (1..450).map { dto("i$it", if (it == 1) "Saga #3, Other #1" else null) }
        sync().sync_()
        assertEquals(450, db.itemDao().idsInLibrary(s, "l1").size)
        assertTrue(api.pageSizes.all { it == 200 })
        val first = db.itemDao().get(s, "i1")!!
        assertEquals("Saga", first.seriesName)
        assertEquals("3", first.seriesSequence)
    }

    @Test fun serverRemovedItem_withDownload_isKeptAsOrphan() = runTest {
        api.items = listOf(dto("a"), dto("b"), dto("c"), dto("d"))
        sync().sync_()
        db.downloadDao().upsert(DownloadEntity(s, "b", DownloadState.COMPLETE, 1))
        db.progressDao().saveLocal(s, "c", 5.0, 10.0, false, 1)
        api.items = listOf(dto("a"))

        sync().sync_()

        assertNotNull(db.itemDao().get(s, "a"))
        assertTrue(db.itemDao().get(s, "b")!!.orphaned)
        assertTrue(db.itemDao().get(s, "c")!!.orphaned)
        assertNull(db.itemDao().get(s, "d"))
    }

    @Test fun partialFetch_deletesNothing() = runTest {
        api.items = (1..300).map { dto("i$it") }
        sync().sync_()
        api.items = (1..100).map { dto("i$it") }
        api.reportedTotal = 300 // server says 300 but delivers 100
        sync().sync_()
        assertEquals(300, db.itemDao().idsInLibrary(s, "l1").size)

        api.reportedTotal = null
        api.items = (1..300).map { dto("i$it") }
        api.failOnPage = 1
        try { sync().sync_(); fail() } catch (_: IOException) {}
        api.failOnPage = null
        api.items = listOf(dto("i1"))
        api.failOnPage = 0
        try { sync().sync_(); fail() } catch (_: IOException) {}
        assertEquals(300, db.itemDao().idsInLibrary(s, "l1").size)
    }

    @Test fun seriesNameChange_invalidatesDetails() = runTest {
        api.items = listOf(dto("a", "Old #1"), dto("b", "Same #1"))
        sync().sync_()
        api.detail = dto("a", "Old #1")
        sync().loadDetails(s, "a")
        api.detail = dto("b", "Same #1")
        sync().loadDetails(s, "b")
        assertTrue(db.itemDao().get(s, "a")!!.detailsLoaded)

        api.items = listOf(dto("a", "New #1"), dto("b", "Same #1"))
        sync().sync_()

        assertFalse(db.itemDao().get(s, "a")!!.detailsLoaded)
        assertTrue(db.itemDao().get(s, "b")!!.detailsLoaded)
    }

    @Test fun pageWithoutNewIds_stopsAndDeletesNothing() = runTest {
        api.items = (1..450).map { dto("i$it") }
        sync().sync_()
        api.ignorePage = true
        sync().sync_() // would loop forever / delete on a naive implementation
        assertEquals(450, db.itemDao().idsInLibrary(s, "l1").size)
    }

    @Test fun detailsFailure_leavesDetailsUnloaded() = runTest {
        api.items = listOf(dto("a"))
        sync().sync_()
        api.detail = dto("a")
        val failing = LibrarySync(
            flowOf(api), db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(),
            { block -> db.withTransaction { block(); throw IOException("late failure") } },
        )
        try { failing.loadDetails(s, "a"); fail() } catch (_: IOException) {}
        assertFalse(db.itemDao().get(s, "a")!!.detailsLoaded)
    }

    @Test fun offline_isNoOp() = runTest {
        sync(null).sync_()
        assertTrue(db.libraryDao().getAll(s).isEmpty())
    }

    private suspend fun LibrarySync.sync_() = syncAll(s)
}
