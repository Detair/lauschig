package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.StubLibraryApi
import io.wolftown.lauschig.absapi.library.AudioFileDto
import io.wolftown.lauschig.absapi.library.AuthorsResponse
import io.wolftown.lauschig.absapi.library.CollectionDto
import io.wolftown.lauschig.absapi.library.CollectionsPage
import io.wolftown.lauschig.absapi.library.EpisodeDto
import io.wolftown.lauschig.absapi.library.FileMetadataDto
import io.wolftown.lauschig.absapi.library.IdOnlyDto
import io.wolftown.lauschig.absapi.library.LibrariesResponse
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import io.wolftown.lauschig.absapi.library.LibraryItemsPage
import io.wolftown.lauschig.absapi.library.PlaylistDto
import io.wolftown.lauschig.absapi.library.PlaylistItemDto
import io.wolftown.lauschig.absapi.library.PlaylistsResponse
import io.wolftown.lauschig.absapi.library.RecentEpisodesResponse
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.CollectionKind
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class CollectionSyncTest {
    private lateinit var db: LauschigDatabase
    private val s = "srv"
    private val asked = mutableListOf<String>()
    private var collections = mapOf<String, List<CollectionDto>>()
    private var failing = setOf<String>()
    private var playlists: List<PlaylistDto>? = emptyList()

    private val api = object : StubLibraryApi() {
        override suspend fun libraries() = LibrariesResponse(emptyList())
        override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int): LibraryItemsPage = error("unused")
        override suspend fun authors(id: String): AuthorsResponse = error("unused")
        override suspend fun recentEpisodes(id: String, limit: Int, page: Int): RecentEpisodesResponse = error("unused")
        override suspend fun item(id: String, expanded: Int): LibraryItemDto = error("unused")
        override suspend fun collections(libraryId: String): CollectionsPage {
            asked += libraryId
            if (libraryId in failing) throw IOException("down")
            return CollectionsPage(collections[libraryId].orEmpty())
        }
        override suspend fun playlists() = PlaylistsResponse(playlists ?: throw IOException("down"))
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private suspend fun libs() = db.libraryDao().upsertFromServer(
        listOf(LibraryEntity(s, "books", "Bücher", "book", 0), LibraryEntity(s, "more", "Hörspiele", "book", 1), LibraryEntity(s, "pods", "Podcasts", "podcast", 2)),
    )

    private fun sync() = CollectionSync(flowOf(api), db.libraryDao(), db.collectionDao(), db.episodeDao(), db.audioFileDao()) { it() }

    @Test fun collections_onlyForBookLibraries_inServerOrder_failureKeepsThatLibrary() = runTest {
        libs()
        collections = mapOf(
            "books" to listOf(CollectionDto("c1", "books", "Abend", books = listOf(IdOnlyDto("b2"), IdOnlyDto("b1")))),
            "more" to listOf(CollectionDto("c2", "more", "Kinder", books = listOf(IdOnlyDto("b3")))),
        )
        sync().sync(s)
        assertEquals(listOf("books", "more"), asked)
        assertEquals(listOf("b2", "b1"), db.collectionDao().entries(s, "c1").map { it.itemKey })

        collections = mapOf("books" to emptyList())
        failing = setOf("more")
        assertTrue(runCatching { sync().sync(s) }.isFailure)
        val left = db.collectionDao().observeByKind(s, CollectionKind.COLLECTION).first()
        assertEquals(listOf("c2"), left.map { it.id })
    }

    @Test fun playlists_storeKeys_andAddUnknownEpisodesWithTheirFile() = runTest {
        libs()
        db.episodeDao().upsert(listOf(EpisodeEntity(s, "pod", "known", "Bekannt", recent = true)))
        val file = AudioFileDto(1, "ino5", 600.0, FileMetadataDto("e.mp3", ".mp3", 10))
        playlists = listOf(
            PlaylistDto(
                "p1", "pods", "Auto",
                items = listOf(
                    PlaylistItemDto("b1"),
                    PlaylistItemDto("pod", "new", EpisodeDto("new", title = "Neu", duration = 600.0, audioFile = file)),
                    PlaylistItemDto("pod", "known", EpisodeDto("known", title = "Anders")),
                ),
            ),
        )
        sync().sync(s)
        assertEquals(listOf("b1", "pod~new", "pod~known"), db.collectionDao().entries(s, "p1").map { it.itemKey })
        assertEquals("Neu", db.episodeDao().get(s, "pod", "new")!!.title)
        assertEquals("ino5", db.audioFileDao().forItem(s, "pod~new").single().ino)
        val known = db.episodeDao().get(s, "pod", "known")!!
        assertEquals("Bekannt", known.title) // existing rows are left alone
        assertTrue(known.recent)
    }

    @Test fun playlistFailure_keepsCachedPlaylists() = runTest {
        playlists = listOf(PlaylistDto("p1", "books", "Auto", items = listOf(PlaylistItemDto("b1"))))
        sync().sync(s)
        playlists = null
        assertTrue(runCatching { sync().sync(s) }.isFailure)
        assertEquals(1, db.collectionDao().observeByKind(s, CollectionKind.PLAYLIST).first().size)
    }

    @Test fun syncIfDue_atMostOncePer6h_afterASuccess_failuresRetry() = runTest {
        libs()
        var now = 1_000_000L
        val stamps = InMemorySyncStamps()
        fun throttled() = CollectionSync(flowOf(api), db.libraryDao(), db.collectionDao(), db.episodeDao(), db.audioFileDao(), stamps, { now }) { it() }
        throttled().syncIfDue(s)
        assertEquals(2, asked.size)
        now += CollectionSync.MIN_INTERVAL_MS - 1
        throttled().syncIfDue(s)
        assertEquals(2, asked.size)
        now += 1
        failing = setOf("more")
        assertTrue(runCatching { throttled().syncIfDue(s) }.isFailure)
        assertEquals(4, asked.size)
        failing = emptySet()
        throttled().syncIfDue(s) // the failed run did not count
        assertEquals(6, asked.size)
        throttled().sync(s) // opening the UI is never throttled
        assertEquals(8, asked.size)
    }

    @Test fun offline_isNoop() = runTest {
        CollectionSync(flowOf(null), db.libraryDao(), db.collectionDao(), db.episodeDao(), db.audioFileDao()) { it() }.sync(s)
        assertTrue(asked.isEmpty())
    }
}
