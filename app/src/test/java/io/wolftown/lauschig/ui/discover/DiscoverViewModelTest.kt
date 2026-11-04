package io.wolftown.lauschig.ui.discover

import io.wolftown.lauschig.TestViewModels
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.browse.AuthorInfoCache
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.library.FilterPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DiscoverViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val viewModels = TestViewModels()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        viewModels.clear()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun store(name: String) = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("$name${System.nanoTime()}.preferences_pb") }

    private val filterStore = store("f")
    private val discoverStore = store("d")

    private fun vm(): DiscoverViewModel {
        val accounts = FakeAccountStore()
        accounts.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        val covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false }
        val factory = viewModelFactory {
            initializer {
                DiscoverViewModel(
                    accounts, db.catalogDao(), db.itemDao(), db.libraryDao(), db.progressDao(), db.downloadDao(), covers,
                    FilterPreferences(filterStore), DiscoverPreferences(discoverStore), AuthorInfoCache(flowOf(null)) { emptyList() },
                    Catalog(), UnconfinedTestDispatcher(),
                )
            }
        }
        return viewModels.get(factory, DiscoverViewModel::class)
    }

    private fun item(
        id: String, title: String, lib: String = "lib1", author: String? = null, series: String? = null, genres: List<String> = emptyList(),
        added: Long = 0, duration: Double = 100.0, tags: List<String> = emptyList(),
    ) = ItemEntity(
        serverId = sid, id = id, libraryId = lib, title = title, author = author, seriesName = series, seriesSortKey = if (series != null) 1.0 else null,
        genres = genres, addedAt = added, durationSec = duration, tags = tags,
    )

    private suspend fun seed(vararg items: ItemEntity) {
        db.libraryDao().upsertFromServer(
            listOf(LibraryEntity(sid, "lib1", "Books", "book", 0), LibraryEntity(sid, "lib2", "Plays", "book", 1)),
        )
        db.itemDao().upsertFromList(items.toList())
    }

    private suspend fun DiscoverViewModel.await(p: (DiscoverUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    @Test fun discover_sectionsFromCatalog_respectKindFilter() = runTest {
        db.libraryDao().upsertFromServer(
            listOf(LibraryEntity(sid, "lib1", "Books", "book", 0), LibraryEntity(sid, "lib2", "Hörspiele", "book", 1)),
        )
        db.itemDao().upsertFromList(
            listOf(
                item("1", "Buch Eins", "lib1", "Ann", "Reihe A", listOf("Krimi"), added = 10, tags = listOf("Gute Nacht")),
                item("2", "Buch Zwei", "lib1", "Ann", "Reihe A", listOf("Krimi"), added = 20),
                item("3", "Spiel", "lib2", "Bob", "Reihe B", listOf("Kinder"), added = 30, tags = listOf("Auto", "gute nacht")),
            ),
        )
        val v = vm()
        val all = v.await { it.all.size == 3 }
        assertEquals(listOf("Spiel", "Buch Zwei", "Buch Eins"), all.recent.map { it.title })
        assertEquals(setOf("Reihe A" to 2, "Reihe B" to 1), all.series.map { it.name to it.count }.toSet())
        assertEquals(setOf("Krimi", "Kinder"), all.genres.map { it.name }.toSet())
        assertEquals(listOf("Gute Nacht" to 2, "Auto" to 1), all.tags.map { it.name to it.count })
        assertEquals(setOf("Ann", "Bob"), all.authors.map { it.name }.toSet())

        v.setKinds(setOf(LibraryKind.AUDIOBOOK))
        val books = v.await { it.all.size == 2 }
        assertEquals(listOf("Buch Zwei", "Buch Eins"), books.recent.map { it.title })
        assertEquals(listOf("Reihe A"), books.series.map { it.name })
        assertEquals(listOf("Krimi"), books.genres.map { it.name })
        assertEquals(listOf("Gute Nacht"), books.tags.map { it.name })
        assertEquals(listOf("Ann"), books.authors.map { it.name })
    }

    @Test fun discover_sortAllTitles_persists() = runTest {
        seed(
            item("1", "Zebra", author = "Anna", added = 5, duration = 300.0),
            item("2", "apfel", author = "Zora", added = 9, duration = 100.0),
            item("3", "Mitte", author = null, added = 1, duration = 200.0),
        )
        val v = vm()
        assertEquals(listOf("apfel", "Mitte", "Zebra"), v.await { it.all.size == 3 }.all.map { it.title })
        v.setSort(DiscoverSort.AUTHOR)
        assertEquals(listOf("Zebra", "apfel", "Mitte"), v.await { it.sort == DiscoverSort.AUTHOR }.all.map { it.title })
        v.setSort(DiscoverSort.ADDED)
        assertEquals(listOf("apfel", "Zebra", "Mitte"), v.await { it.sort == DiscoverSort.ADDED }.all.map { it.title })
        v.setSort(DiscoverSort.DURATION)
        assertEquals(listOf("Zebra", "Mitte", "apfel"), v.await { it.sort == DiscoverSort.DURATION }.all.map { it.title })

        // A fresh view model (new app start) over the same stored preference starts with the saved sort.
        assertEquals(DiscoverSort.DURATION, vm().await { it.all.size == 3 }.sort)
    }

    @Test fun discover_isLoading_untilTheFirstCatalogIsRead_thenEmpty() = runTest {
        val v = vm()
        assertTrue("starts loading, not empty", v.state.value.loading)
        val empty = v.await { !it.loading }
        assertTrue(empty.all.isEmpty() && empty.recent.isEmpty())
    }

    @Test fun discover_itemsWithoutMetadata_listedOnlyInAllTitles() = runTest {
        seed(item("1", "Nackt", added = 0))
        val s = vm().await { it.all.isNotEmpty() }
        assertEquals(listOf("Nackt"), s.all.map { it.title })
        assertTrue(s.recent.isEmpty() && s.series.isEmpty() && s.genres.isEmpty() && s.authors.isEmpty())
    }
}
