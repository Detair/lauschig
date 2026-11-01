package io.wolftown.lauschig.ui.search

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.library.FilterPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class SearchViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val viewModels = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private val catalog = Catalog()

    private fun store(name: String) = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("$name${System.nanoTime()}.preferences_pb") }

    private val searchCalls = mutableListOf<String>()

    private fun vm(recent: RecentSearches = RecentSearches(store("r"))): SearchViewModel {
        val accounts = FakeAccountStore()
        accounts.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        val covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false }
        val factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    accounts, db.catalogDao(), db.itemDao(), db.libraryDao(), db.progressDao(), db.downloadDao(),
                    covers, FilterPreferences(store("f")), recent, UnconfinedTestDispatcher(),
                    { items, q -> searchCalls += q; catalog.search(items, q) },
                )
            }
        }
        return ViewModelProvider.create(viewModels, factory)["vm${System.nanoTime()}", SearchViewModel::class]
    }

    private fun item(id: String, title: String, author: String? = null, narrator: String? = null, series: String? = null) =
        ItemEntity(serverId = sid, id = id, libraryId = "lib1", title = title, author = author, narrator = narrator, seriesName = series, durationSec = 100.0)

    private suspend fun seed(vararg items: ItemEntity) {
        db.libraryDao().upsertFromServer(listOf(LibraryEntity(sid, "lib1", "Books", "book", 0)))
        db.itemDao().upsertFromList(items.toList())
    }

    private suspend fun SearchViewModel.await(p: (SearchUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    @Test fun search_offline_findsServerOnlyTitles() = runTest {
        // Nothing downloaded and no network involved: the title is found from Room alone.
        seed(item("1", "Der Schatz im Silbersee", author = "Karl May"))
        val v = vm()
        v.setQuery("silber")
        advanceTimeBy(250)
        val s = v.await { it.titles.isNotEmpty() }
        assertEquals(listOf("Der Schatz im Silbersee"), s.titles.map { it.title })
        assertTrue(!s.titles.single().isDownloaded)
    }

    @Test fun search_debounces_andGroupsSections() = runTest {
        seed(
            item("1", "Anna Karenina", author = "Leo Tolstoi"),
            item("2", "Krieg und Frieden", author = "Anne Writer", narrator = "Anna Sprecher", series = "Annas Reihe"),
        )
        val v = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.state.collect {} }
        v.setQuery("a")
        advanceTimeBy(100)
        v.setQuery("ann")
        advanceTimeBy(100)
        // Only 100 ms since the last keystroke: nothing was searched yet.
        assertEquals(emptyList<String>(), searchCalls.toList())
        advanceTimeBy(150)
        val s = v.await { it.searched }
        assertEquals(listOf("ann"), searchCalls.toList()) // one search, for the final text only
        assertEquals("ann", s.resultsFor)
        assertEquals(setOf("Anna Karenina", "Krieg und Frieden"), s.titles.map { it.title }.toSet())
        assertEquals("Anna Karenina", s.titles.first().title) // title hits rank first
        assertEquals(listOf("Anne Writer"), s.authors.map { it.name })
        assertEquals(listOf("Anna Sprecher"), s.narrators.map { it.name })
        assertEquals(listOf("Annas Reihe"), s.series.map { it.name })
    }

    @Test fun search_progressChange_doesNotRerunSearch() = runTest {
        seed(item("1", "Anna Karenina"))
        val v = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.state.collect {} }
        v.setQuery("ann")
        advanceTimeBy(250)
        v.await { it.searched && it.titles.isNotEmpty() }
        db.progressDao().mergeFromServer(listOf(ProgressEntity(sid, "1", 50.0, 100.0, false, 1L)))
        val s = v.await { it.titles.singleOrNull()?.progress?.let { p -> p > 0f } == true }
        assertEquals(0.5f, s.titles.single().progress, 0.01f)
        assertEquals(listOf("ann"), searchCalls.toList())
    }

    @Test fun search_recentQueries_storedAndCapped() = runTest {
        val v = vm()
        for (i in 1..10) { v.setQuery("query $i"); v.submit() }
        v.setQuery("QUERY 5"); v.submit() // de-duplicated case-insensitively, moves to the front
        v.setQuery("   "); v.submit()     // blank is never stored
        val s = v.await { it.recent.size == 8 && it.recent.first() == "QUERY 5" }
        assertEquals(listOf("QUERY 5", "query 10", "query 9", "query 8", "query 7", "query 6", "query 4", "query 3"), s.recent)
    }

    @Test fun search_noMatch_isSearchedAndEmpty() = runTest {
        seed(item("1", "Alpha"))
        val v = vm()
        v.setQuery("zzz")
        advanceTimeBy(250)
        val s = v.await { it.searched }
        assertTrue(s.titles.isEmpty() && s.authors.isEmpty())
    }
}
