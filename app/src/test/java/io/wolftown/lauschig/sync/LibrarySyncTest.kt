package io.wolftown.lauschig.sync

import io.wolftown.lauschig.StubLibraryApi

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.absapi.library.BookMediaDto
import io.wolftown.lauschig.absapi.library.BookMetadataDto
import io.wolftown.lauschig.absapi.library.AudioFileDto
import io.wolftown.lauschig.absapi.library.ChapterDto
import io.wolftown.lauschig.absapi.library.EpisodeDto
import io.wolftown.lauschig.absapi.library.FileMetadataDto
import io.wolftown.lauschig.absapi.library.LibrariesResponse
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.LibraryDto
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import io.wolftown.lauschig.absapi.library.LibraryItemsPage
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.flow.first
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

private class FakeLibrary : StubLibraryApi() {
    override suspend fun authors(id: String): io.wolftown.lauschig.absapi.library.AuthorsResponse = throw UnsupportedOperationException()
    var items: List<LibraryItemDto> = emptyList()
    var failOnPage: Int? = null
    var reportedTotal: Int? = null
    var detail: LibraryItemDto? = null
    var ignorePage = false
    val pageSizes = mutableListOf<Int>()

    var libraryType = "book"
    var recent: List<EpisodeDto>? = null
    var recentFails = false
    override suspend fun libraries() = LibrariesResponse(listOf(LibraryDto("l1", "Bücher", libraryType, 0)))
    override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int): LibraryItemsPage {
        pageSizes += limit
        if (failOnPage == page) throw IOException("boom")
        val chunk = items.drop((if (ignorePage) 0 else page) * limit).take(limit)
        return LibraryItemsPage(chunk, reportedTotal ?: items.size, limit, page)
    }
    override suspend fun recentEpisodes(id: String, limit: Int, page: Int): io.wolftown.lauschig.absapi.library.RecentEpisodesResponse {
        if (recentFails) throw IOException("404")
        return io.wolftown.lauschig.absapi.library.RecentEpisodesResponse(recent ?: error("unused"))
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
        flowOf(a), db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(), db.episodeDao(),
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

    @Test fun sync_writesAndUpdatesAddedAtAndPublishedYear() = runTest {
        db.itemDao().upsertFromList(listOf(ItemEntity(serverId = s, id = "old", libraryId = "l1", title = "Told", durationSec = 10.0, addedAt = 0)))
        api.items = listOf(
            LibraryItemDto("old", "l1", "book", 1, BookMediaDto(BookMetadataDto(title = "Told", publishedYear = "1999"), duration = 10.0), addedAt = 1234L),
            LibraryItemDto("new", "l1", "book", 1, BookMediaDto(BookMetadataDto(title = "Tnew", publishedYear = " "), duration = 10.0), addedAt = 5678L),
        )
        sync().sync_()
        val old = db.itemDao().get(s, "old")!!
        assertEquals(1234L, old.addedAt)
        assertEquals("1999", old.publishedYear)
        val new = db.itemDao().get(s, "new")!!
        assertEquals(5678L, new.addedAt)
        assertNull(new.publishedYear)
    }

    @Test fun sync_mapsAsinLanguageNumEpisodes_andPodcastAuthor() = runTest {
        api.items = listOf(
            LibraryItemDto("b", "l1", "book", 1, BookMediaDto(BookMetadataDto(title = "Book", authorName = "Ann", asin = "B0ABC", language = "German"), duration = 10.0)),
            LibraryItemDto("p", "l1", "podcast", 1, BookMediaDto(BookMetadataDto(title = "Pod", author = "Host", language = "de"), numEpisodes = 7)),
        )
        sync().sync_()
        sync().sync_() // second run goes through the update path
        val book = db.itemDao().get(s, "b")!!
        assertEquals("B0ABC", book.asin)
        assertEquals("German", book.language)
        assertEquals("Ann", book.author)
        assertEquals(0, book.numEpisodes)
        val pod = db.itemDao().get(s, "p")!!
        assertEquals("Host", pod.author)
        assertEquals(7, pod.numEpisodes)
        assertNull(pod.asin)
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

    private fun listPod(episodes: Int, updatedAt: Long) =
        LibraryItemDto("pod", "l1", "podcast", updatedAt, BookMediaDto(BookMetadataDto(title = "Pod"), numEpisodes = episodes))

    @Test fun removedPodcast_withDownloadedEpisode_isKeptAsOrphan() = runTest {
        api.items = listOf(listPod(3, 1), dto("a"), dto("pod2"))
        sync().sync_()
        val key = PlayableKey.episode("pod", "e1")
        db.episodeDao().upsert(listOf(io.wolftown.lauschig.db.entities.EpisodeEntity(s, "pod", "e1", "Folge", publishedAt = 1)))
        db.downloadDao().upsert(DownloadEntity(s, key, DownloadState.COMPLETE, 1))
        api.items = listOf(dto("a"))

        sync().sync_()

        assertTrue(db.itemDao().get(s, "pod")!!.orphaned)
        assertNotNull(db.episodeDao().get(s, "pod", "e1"))
        assertNull(db.itemDao().get(s, "pod2")) // a prefix-sharing id without rows is still deleted
    }

    @Test fun podcast_changedEpisodeCountOrUpdatedAt_invalidatesDetails_unchangedDoesNot() = runTest {
        api.items = listOf(listPod(3, 1))
        sync().sync_()
        api.detail = podcast(ep("e1"), ep("e2"), ep("e3")) // same count and updatedAt (1) as the list item
        sync().loadDetails(s, "pod")
        assertTrue(db.itemDao().get(s, "pod")!!.detailsLoaded)

        api.items = listOf(listPod(3, 1))
        sync().sync_()
        assertTrue(db.itemDao().get(s, "pod")!!.detailsLoaded)

        api.items = listOf(listPod(4, 1))
        sync().sync_()
        assertFalse(db.itemDao().get(s, "pod")!!.detailsLoaded)

        sync().loadDetails(s, "pod")
        api.items = listOf(listPod(4, 2))
        sync().sync_()
        assertFalse(db.itemDao().get(s, "pod")!!.detailsLoaded)
    }

    @Test fun book_updatedAtChange_doesNotInvalidateDetails() = runTest {
        api.items = listOf(dto("a"))
        sync().sync_()
        api.detail = dto("a")
        sync().loadDetails(s, "a")
        api.items = listOf(dto("a").copy(updatedAt = 99))
        sync().sync_()
        assertTrue(db.itemDao().get(s, "a")!!.detailsLoaded)
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
            flowOf(api), db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(), db.episodeDao(),
            { block -> db.withTransaction { block(); throw IOException("late failure") } },
        )
        try { failing.loadDetails(s, "a"); fail() } catch (_: IOException) {}
        assertFalse(db.itemDao().get(s, "a")!!.detailsLoaded)
    }

    private fun ep(id: String, item: String? = null, ino: String = "ino-$id") = EpisodeDto(
        id = id, title = "Folge $id", publishedAt = 1_000, duration = 600.0, size = 5_000, libraryItemId = item,
        audioFile = AudioFileDto(1, ino, 600.0, FileMetadataDto("$id.mp3", ".mp3", 5_000)),
        chapters = listOf(ChapterDto(0, 0.0, 600.0, "Kap")),
    )

    private fun podcast(vararg eps: EpisodeDto) = LibraryItemDto(
        "pod", "l1", "podcast", 1, BookMediaDto(BookMetadataDto(title = "Pod", author = "Host"), numEpisodes = eps.size, episodes = eps.toList()),
    )

    @Test fun podcastDetails_storeEpisodesAndFilesUnderKeys() = runTest {
        api.detail = podcast(ep("e1"), ep("e2"))
        sync().loadEpisodes(s, "pod")

        assertEquals(listOf("e2", "e1"), db.episodeDao().observeForItem(s, "pod").first().map { it.id }.sortedDescending())
        assertEquals(600.0, db.episodeDao().get(s, "pod", "e1")!!.durationSec, 0.0)
        val key = PlayableKey.episode("pod", "e1")
        val f = db.audioFileDao().forItem(s, key).single()
        assertEquals(1, f.index)
        assertEquals("ino-e1", f.ino)
        assertEquals("mp3", f.ext)
        assertEquals(1, db.chapterDao().forItem(s, key).size)
        assertTrue(db.audioFileDao().forItem(s, "pod").isEmpty())
        assertTrue(db.itemDao().get(s, "pod")!!.detailsLoaded)
    }

    @Test fun loadDetailsForPlayable_episodeKey_loadsThePodcastsEpisodes() = runTest {
        api.detail = podcast(ep("e1"), ep("e2"))
        sync().loadDetailsForPlayable(s, PlayableKey.episode("pod", "e1"))
        assertEquals(setOf("e1", "e2"), db.episodeDao().observeForItem(s, "pod").first().map { it.id }.toSet())
    }

    @Test fun replaceForItem_keepsDownloadedEpisodes() = runTest {
        api.detail = podcast(ep("e1"), ep("e2"))
        sync().loadDetails(s, "pod")
        val k1 = PlayableKey.episode("pod", "e1")
        val k2 = PlayableKey.episode("pod", "e2")
        db.audioFileDao().setLocal(s, k1, 1, "/x/e1.mp3", 5_000)
        db.downloadDao().upsert(DownloadEntity(s, k1, DownloadState.COMPLETE, 1))

        api.detail = podcast(ep("e3")) // e1 downloaded -> kept, e2 dropped
        sync().loadDetails(s, "pod")

        assertEquals(setOf("e1", "e3"), db.episodeDao().observeForItem(s, "pod").first().map { it.id }.toSet())
        assertEquals("/x/e1.mp3", db.audioFileDao().forItem(s, k1).single().localPath)
        assertTrue(db.audioFileDao().forItem(s, k2).isEmpty())
        assertTrue(db.chapterDao().forItem(s, k2).isEmpty())

        // Refetching the same episode keeps its local file.
        api.detail = podcast(ep("e1"), ep("e3"))
        sync().loadDetails(s, "pod")
        assertEquals("/x/e1.mp3", db.audioFileDao().forItem(s, k1).single().localPath)
    }

    @Test fun recentEpisodes_flagged_resetPerLibrary_andInsertMissing() = runTest {
        api.libraryType = "podcast"
        api.items = listOf(podcast())
        api.detail = podcast(ep("e1"), ep("e2"))
        sync().loadDetails(s, "pod")
        api.recent = listOf(ep("e1", "pod"), ep("e9", "pod"))
        sync().sync_()
        assertEquals(setOf("e1", "e9"), db.episodeDao().observeRecent(s, 10).first().map { it.id }.toSet())
        assertEquals("ino-e9", db.audioFileDao().forItem(s, PlayableKey.episode("pod", "e9")).single().ino)

        // Re-opening the podcast keeps the flags; the next recent sync clears stale ones.
        api.detail = podcast(ep("e1"), ep("e2"), ep("e9"))
        sync().loadDetails(s, "pod")
        assertEquals(setOf("e1", "e9"), db.episodeDao().observeRecent(s, 10).first().map { it.id }.toSet())
        api.recent = listOf(ep("e2", "pod"))
        sync().sync_()
        assertEquals(listOf("e2"), db.episodeDao().observeRecent(s, 10).first().map { it.id })
    }

    @Test fun recentEpisodes_failure_doesNotFailSync() = runTest {
        api.libraryType = "podcast"
        api.items = listOf(podcast())
        api.detail = podcast(ep("e1"))
        sync().loadDetails(s, "pod")
        api.recent = listOf(ep("e1", "pod"))
        sync().sync_()
        assertEquals(listOf("e1"), db.episodeDao().observeRecent(s, 10).first().map { it.id })

        api.recentFails = true
        sync().sync_() // must not throw
        assertNotNull(db.itemDao().get(s, "pod"))
        // A failed recent call must not wipe the flags of the last good one.
        assertEquals(listOf("e1"), db.episodeDao().observeRecent(s, 10).first().map { it.id })
    }

    @Test fun offline_isNoOp() = runTest {
        sync(null).sync_()
        assertTrue(db.libraryDao().getAll(s).isEmpty())
    }

    private suspend fun LibrarySync.sync_() = syncAll(s)
}
