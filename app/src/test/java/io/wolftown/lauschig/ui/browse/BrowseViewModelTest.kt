package io.wolftown.lauschig.ui.browse

import io.wolftown.lauschig.TestViewModels
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.absapi.library.AuthorDto
import io.wolftown.lauschig.absapi.library.AuthorsResponse
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.library.FilterPreferences
import io.wolftown.lauschig.ui.nav.BrowseKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.Collections
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.wolftown.lauschig.external.AudibleVolume
import io.wolftown.lauschig.external.MissingVolume
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
class BrowseViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val viewModels = TestViewModels()

    /** The open screens: one collector per ViewModel that stays subscribed for the whole test, see [vm]. */
    private val screens = CoroutineScope(Dispatchers.Default + Job())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        runBlocking { screens.coroutineContext[Job]!!.cancelAndJoin() }
        viewModels.clear()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private val authors = AuthorInfoCache(flowOf(null)) { emptyList() }

    private fun vm(
        kind: BrowseKind, name: String,
        prefs: FilterPreferences = FilterPreferences(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("p${System.nanoTime()}.preferences_pb") }),
        info: AuthorInfoCache = authors,
        missing: MissingVolumesSource = MissingVolumesSource(flowOf(false), flowOf(true)) { _, _, _, _ -> null },
        signedIn: Boolean = true,
    ): BrowseViewModel {
        val store = FakeAccountStore()
        if (signedIn) store.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        val covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false }
        val factory = viewModelFactory {
            initializer {
                BrowseViewModel(kind, name, store, db.catalogDao(), db.itemDao(), db.libraryDao(), db.progressDao(), db.downloadDao(), covers, prefs, Catalog(), info, missing)
            }
        }
        // Like the real screen, keep one collector on `state` for the whole test. With only the short-lived subscription
        // of each await(), the stateIn(WhileSubscribed(5_000)) pipeline stops whenever the last one ends: runTest
        // fast-forwards virtual time while the body waits on another thread, so that 5 s timeout fires at once and the
        // next await restarts the whole pipeline (a second, identical Audible lookup).
        return viewModels.get(factory, BrowseViewModel::class).also { screens.launch { it.state.collect { } } }
    }

    private fun item(id: String, lib: String = "lib1", title: String = id, author: String? = null, series: String? = null, key: Double? = null) =
        ItemEntity(serverId = sid, id = id, libraryId = lib, title = title, author = author, seriesName = series, seriesSequence = key?.toInt()?.toString(), seriesSortKey = key, durationSec = 100.0)

    private suspend fun libs() = db.libraryDao().upsertFromServer(
        listOf(LibraryEntity(sid, "lib1", "Books", "book", 0), LibraryEntity(sid, "lib2", "Hörspiele", "book", 1)),
    )

    private suspend fun BrowseViewModel.await(p: (BrowseUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    @Test fun signedOut_isLoaded_notLoadingForever() = runTest {
        assertTrue(vm(BrowseKind.SERIES, "Saga", signedIn = false).await { it.loaded }.entries.isEmpty())
    }

    @Test fun authorPage_offline_showsBooksWithoutBio() = runTest {
        libs()
        db.itemDao().upsertFromList(
            listOf(
                item("a", title = "Alpha", author = "Lucy Hawking, Stephen Hawking", series = "Saga", key = 1.0),
                item("b", title = "Beta", author = "Lucy Hawking"),
                item("c", title = "Other", author = "Lucy Hawkins"),
            ),
        )
        val s = vm(BrowseKind.AUTHOR, "lucy hawking").await { it.loaded }
        assertEquals(listOf("Alpha", "Beta"), s.entries.map { (it as BrowseEntry.Tile).item.title })
        assertEquals(2, s.count)
        assertEquals(listOf("Saga"), s.series.map { it.name })
        assertNull(s.author)
    }

    @Test fun authorPage_bioAppearsOnceCached() = runTest {
        libs()
        db.itemDao().upsertFromList(listOf(item("a", author = "Ann")))
        val api = object : io.wolftown.lauschig.StubLibraryApi() {
            override suspend fun libraries() = error("unused")
            override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int) = error("unused")
            override suspend fun authors(id: String) = AuthorsResponse(listOf(AuthorDto("a1", "ANN", "Bio", "/x.jpg")))
            override suspend fun recentEpisodes(id: String, limit: Int, page: Int): io.wolftown.lauschig.absapi.library.RecentEpisodesResponse = error("unused")
            override suspend fun item(id: String, expanded: Int) = error("unused")
        }
        val s = vm(BrowseKind.AUTHOR, "Ann", info = AuthorInfoCache(flowOf(api)) { listOf("lib1") }).await { it.author != null }
        assertEquals("Bio", s.author?.description)
    }

    @Test fun authorInfo_failureLeavesCacheEmpty_neverThrows() = runTest {
        val api = object : io.wolftown.lauschig.StubLibraryApi() {
            override suspend fun libraries() = error("unused")
            override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int) = error("unused")
            override suspend fun authors(id: String): AuthorsResponse = throw java.io.IOException("down")
            override suspend fun recentEpisodes(id: String, limit: Int, page: Int): io.wolftown.lauschig.absapi.library.RecentEpisodesResponse = error("unused")
            override suspend fun item(id: String, expanded: Int) = error("unused")
        }
        val cache = AuthorInfoCache(flowOf(api)) { listOf("lib1") }
        cache.ensureLoaded(sid)
        assertEquals(emptyMap<String, AuthorDto>(), cache.authors.value)
    }

    @Test fun seriesPage_ordersBySequence_andShowsGaps() = runTest {
        libs()
        db.itemDao().upsertFromList(
            listOf(
                item("v4", title = "D", series = "Saga", key = 4.0),
                item("v1", title = "A", series = "saga", key = 1.0),
                item("v2", title = "B", series = "Saga", key = 2.0),
                item("other", series = "Another", key = 1.0),
            ),
        )
        val s = vm(BrowseKind.SERIES, "Saga").await { it.loaded }
        assertEquals(
            listOf("A", "B", "missing:3", "D"),
            s.entries.map { if (it is BrowseEntry.Tile) it.item.title else "missing:" + (it as BrowseEntry.Missing).sequence },
        )
        assertEquals(3, s.count)
    }

    @Test fun genrePage_listsMatchingTitles() = runTest {
        libs()
        db.itemDao().upsertFromList(listOf(item("a").copy(genres = listOf("Horror")), item("b").copy(genres = listOf("Krimi"))))
        assertEquals(listOf("a"), vm(BrowseKind.GENRE, "horror").await { it.loaded }.entries.map { (it as BrowseEntry.Tile).item.id })
    }

    @Test fun browse_kindFilter_appliesAndIsSaved() = runTest {
        libs()
        db.itemDao().upsertFromList(listOf(item("book", "lib1", author = "Ann"), item("play", "lib2", author = "Ann")))
        val prefs = FilterPreferences(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("pf.preferences_pb") })
        val vm = vm(BrowseKind.AUTHOR, "Ann", prefs)
        val all = vm.await { it.count == 2 }
        assertEquals(setOf(LibraryKind.AUDIOBOOK, LibraryKind.HOERSPIEL), all.availableKinds)
        vm.setKinds(setOf(LibraryKind.HOERSPIEL))
        val s = vm.await { it.count == 1 }
        assertEquals(listOf("play"), s.entries.map { (it as BrowseEntry.Tile).item.id })
        assertEquals(setOf(LibraryKind.HOERSPIEL), prefs.kinds("browse").first { it.isNotEmpty() })
    }

    private fun mv(seq: String, title: String = "Audible $seq") =
        MissingVolume(seq, seq.toDouble(), AudibleVolume("B$seq", title, sequence = seq), upcoming = false)

    private fun BrowseEntry.label() = when (this) {
        is BrowseEntry.Tile -> item.title
        is BrowseEntry.Missing -> "missing:$sequence" + (volume?.let { "=" + it.volume.title } ?: "")
    }

    private suspend fun ownSaga() {
        libs()
        db.itemDao().upsertFromList(
            listOf(
                item("v1", title = "A", series = "Saga", key = 1.0),
                item("v2", title = "B", series = "Saga", key = 2.0),
                item("v4", title = "D", series = "Saga", key = 4.0),
            ),
        )
    }

    private suspend fun ownSagaWithAsin() {
        libs()
        db.itemDao().upsertFromList(
            listOf(
                item("v1", title = "A", author = "Ann", series = "Saga", key = 1.0).copy(asin = "A1"),
                item("v2", title = "B", author = "Ann", series = "Saga", key = 2.0),
                item("v4", title = "D", author = "Ann", series = "Saga", key = 4.0),
            ),
        )
    }

    @Test fun series_online_mergesAudibleVolumesInOrder() = runTest {
        ownSaga()
        val calls = mutableListOf<Triple<String, String, List<String>>>()
        val source = MissingVolumesSource(flowOf(true), flowOf(true)) { _, lib, series, owned ->
            calls += Triple(lib, series, owned.map { it.id })
            listOf(mv("3"), mv("5"), mv("12"))
        }
        val s = vm(BrowseKind.SERIES, "Saga", missing = source).await { s -> s.entries.any { it is BrowseEntry.Missing && it.volume != null } }
        assertEquals(
            listOf("A", "B", "missing:3=Audible 3", "D", "missing:5=Audible 5", "missing:12=Audible 12"),
            s.entries.map { it.label() },
        )
        assertEquals(3, s.count)
        assertEquals(listOf(Triple("lib1", "Saga", listOf("v1", "v2", "v4"))), calls.map { Triple(it.first, it.second, it.third.sorted()) })
    }

    @Test fun series_online_ignoresVolumesOwnedInAnotherLibrary() = runTest {
        ownSaga()
        db.itemDao().upsertFromList(listOf(item("h3", lib = "lib2", title = "C", series = "Saga", key = 3.0)))
        val source = MissingVolumesSource(flowOf(true), flowOf(true)) { _, lib, _, _ -> if (lib == "lib1") listOf(mv("3"), mv("5")) else listOf(mv("5")) }
        val s = vm(BrowseKind.SERIES, "Saga", missing = source).await { s -> s.entries.any { it is BrowseEntry.Missing } }
        assertEquals(listOf("A", "B", "C", "D", "missing:5=Audible 5"), s.entries.map { it.label() })
    }

    /** Starts with [gate] closed: the plain page is checked, then the gate opens and the lookup result is the positive control. */
    private suspend fun gatedRun(online: MutableStateFlow<Boolean>, enabled: MutableStateFlow<Boolean>, open: () -> Unit) {
        ownSaga()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val source = MissingVolumesSource(online, enabled) { _, lib, _, _ -> calls += lib; listOf(mv("3")) }
        val vm = vm(BrowseKind.SERIES, "Saga", missing = source)
        val plain = vm.await { it.loaded }
        assertEquals(listOf("A", "B", "missing:3", "D"), plain.entries.map { it.label() })
        open()
        val merged = vm.await { s -> s.entries.any { it is BrowseEntry.Missing && it.volume != null } }
        assertEquals(listOf("A", "B", "missing:3=Audible 3", "D"), merged.entries.map { it.label() })
        // Exactly the one lookup after opening: nothing was asked while the gate was closed.
        assertEquals(listOf("lib1"), calls.toList())
    }

    private fun consent(undecided: MutableStateFlow<Boolean>, enabled: MutableStateFlow<Boolean>, calls: MutableList<String>) =
        MissingVolumesSource(MutableStateFlow(true), enabled, undecided, { allow -> enabled.value = allow; undecided.value = false }) { _, lib, _, _ ->
            calls += lib
            listOf(mv("3"))
        }

    @Test fun series_undecided_asks_andLooksNothingUp() = runTest {
        ownSagaWithAsin()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val vm = vm(BrowseKind.SERIES, "Saga", missing = consent(MutableStateFlow(true), MutableStateFlow(false), calls))
        assertTrue(vm.await { it.loaded && it.askMissingVolumes }.askMissingVolumes)
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test fun series_allow_hidesTheQuestion_andLooksUp() = runTest {
        ownSagaWithAsin()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val vm = vm(BrowseKind.SERIES, "Saga", missing = consent(MutableStateFlow(true), MutableStateFlow(false), calls))
        vm.await { it.askMissingVolumes }
        vm.answerMissingVolumes(true)
        val merged = vm.await { s -> !s.askMissingVolumes && s.entries.any { it is BrowseEntry.Missing && it.volume != null } }
        assertEquals(listOf("A", "B", "missing:3=Audible 3", "D"), merged.entries.map { it.label() })
        assertEquals(listOf("lib1"), calls.toList())
    }

    @Test fun series_decline_hidesTheQuestion_andLooksNothingUp() = runTest {
        ownSagaWithAsin()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val vm = vm(BrowseKind.SERIES, "Saga", missing = consent(MutableStateFlow(true), MutableStateFlow(false), calls))
        vm.await { it.askMissingVolumes }
        vm.answerMissingVolumes(false)
        vm.await { it.loaded && !it.askMissingVolumes }
        assertEquals(emptyList<String>(), calls.toList())
    }

    @Test fun series_withoutAnyAsin_doesNotAsk() = runTest {
        ownSaga()
        val vm = vm(BrowseKind.SERIES, "Saga", missing = consent(MutableStateFlow(true), MutableStateFlow(false), mutableListOf()))
        assertFalse(vm.await { it.loaded }.askMissingVolumes)
    }

    @Test fun otherPages_doNotAsk() = runTest {
        ownSagaWithAsin()
        val vm = vm(BrowseKind.AUTHOR, "Ann", missing = consent(MutableStateFlow(true), MutableStateFlow(false), mutableListOf()))
        assertFalse(vm.await { it.loaded }.askMissingVolumes)
    }

    @Test fun series_offline_keepsPlainGaps() = runTest {
        val online = MutableStateFlow(false)
        gatedRun(online, MutableStateFlow(true)) { online.value = true }
    }

    @Test fun series_disabled_noLookup() = runTest {
        val enabled = MutableStateFlow(false)
        gatedRun(MutableStateFlow(true), enabled) { enabled.value = true }
    }

    @Test fun series_lookupReturnsNull_keepsPlainGaps() = runTest {
        ownSaga()
        val source = MissingVolumesSource(flowOf(true), flowOf(true)) { _, _, _, _ -> null }
        val s = vm(BrowseKind.SERIES, "Saga", missing = source).await { it.loaded }
        assertEquals(listOf("A", "B", "missing:3", "D"), s.entries.map { it.label() })
    }

    @Test fun authorPage_neverLooksUpVolumes() = runTest {
        ownSaga()
        db.itemDao().upsertFromList(listOf(item("w1", title = "W", author = "Ann")))
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val source = MissingVolumesSource(flowOf(true), flowOf(true)) { _, lib, series, _ -> calls += "$lib/$series"; listOf(mv("3")) }
        val author = vm(BrowseKind.AUTHOR, "Ann", missing = source).await { it.loaded && it.count == 1 }
        assertEquals(listOf("W"), author.entries.map { it.label() })
        // Positive control: a series page on the same source does look up, so the pipeline was live.
        vm(BrowseKind.SERIES, "Saga", missing = source).await { s -> s.entries.any { it is BrowseEntry.Missing && it.volume != null } }
        assertEquals(listOf("lib1/Saga"), calls.toList())
    }
}
