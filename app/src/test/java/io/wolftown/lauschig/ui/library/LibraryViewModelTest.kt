package io.wolftown.lauschig.ui.library

import io.wolftown.lauschig.TestViewModels
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.*
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LibraryViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val statChecks = java.util.concurrent.atomic.AtomicInteger()
    private val dlRoot by lazy { tmp.newFolder("dl") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    /** Owns every VM of a test so their Room observers are finished before the database closes. */
    private val viewModels = TestViewModels()

    @After fun tearDown() {
        viewModels.clear()
        scope.cancel()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun vm(prefs: FilterPreferences = FilterPreferences(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("p${System.nanoTime()}.preferences_pb") })): LibraryViewModel {
        val store = FakeAccountStore()
        store.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        return viewModels.own(LibraryViewModel(store, db.itemDao(), db.libraryDao(), db.progressDao(), CoverPathCache(DownloadFiles(dlRoot, db.audioFileDao())) { statChecks.incrementAndGet(); it.isFile && it.length() > 0 }, prefs, db.downloadDao()))
    }

    private fun item(id: String, lib: String = "lib1", title: String = id, series: String? = null, seq: String? = null, key: Double? = null) =
        ItemEntity(serverId = sid, id = id, libraryId = lib, title = title, seriesName = series, seriesSequence = seq, seriesSortKey = key, durationSec = 100.0)

    private suspend fun libs() = db.libraryDao().upsertFromServer(
        listOf(
            LibraryEntity(sid, "lib1", "Books", "book", 0),
            LibraryEntity(sid, "lib2", "Hörspiele", "book", 1),
        ),
    )

    private suspend fun listen(id: String, t: Double = 10.0, fin: Boolean = false, last: Long = 1) =
        db.progressDao().mergeFromServer(listOf(ProgressEntity(sid, id, t, 100.0, fin, last, false)))

    private suspend fun LibraryViewModel.await(p: (LibraryUiState) -> Boolean) = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    @Test fun seriesTab_ordersBySequence_unknownLast() = runTest {
        libs()
        db.itemDao().upsertFromList(
            listOf(
                item("a3", title = "Zeta", series = "Saga", seq = "3", key = 3.0),
                item("a1", title = "Alpha", series = "saga", seq = "1", key = 1.0),
                item("ax", title = "Mid", series = "Saga"),
                item("ay", title = "Beta", series = "SAGA"),
                item("b1", title = "Other", series = "Another", seq = "1", key = 1.0),
                item("solo", title = "Solo"),
            ),
        )
        val vm = vm()
        vm.selectTab(LibraryTab.Series)
        val s = vm.await { it.tab == LibraryTab.Series && it.series.isNotEmpty() }
        assertEquals(listOf("another", "saga"), s.series.map { it.name.lowercase() })
        assertEquals(listOf("Alpha", "Zeta", "Beta", "Mid"), s.series.last().items.map { it.title })
        assertFalse(s.series.flatMap { it.items }.any { it.title == "Solo" })
    }

    @Test fun kindFilter_hoerspieleOnly_hidesAudiobooks() = runTest {
        libs()
        db.itemDao().upsertFromList(listOf(item("book", "lib1"), item("play", "lib2")))
        listen("book"); listen("play")
        val prefs = FilterPreferences(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("pf.preferences_pb") })
        val vm = vm(prefs)
        val all = vm.await { it.items.size == 2 }
        assertEquals(setOf(LibraryKind.AUDIOBOOK, LibraryKind.HOERSPIEL), all.availableKinds)
        vm.setKinds(setOf(LibraryKind.HOERSPIEL))
        val s = vm.await { it.items.size == 1 }
        assertEquals(listOf("play"), s.items.map { it.id })
        assertEquals(setOf(LibraryKind.HOERSPIEL), prefs.kinds("library").first { it.isNotEmpty() })
    }

    @Test fun offline_nonDownloadedItems_areMarkedUnavailable() = runTest {
        libs()
        db.itemDao().upsertFromList(listOf(item("dl"), item("remote")))
        listen("dl"); listen("remote")
        db.downloadDao().upsert(DownloadEntity(sid, "dl", DownloadState.COMPLETE, 1))
        db.audioFileDao().replaceFromServer(sid, "dl", listOf(AudioFileEntity(sid, "dl", 0, "i", 10.0, 1, "mp3", null, 0)))
        db.audioFileDao().setLocal(sid, "dl", 0, "/p/0", 1)
        val vm = vm()
        val s = vm.await { it.items.size == 2 }
        assertEquals(mapOf("dl" to true, "remote" to false), s.items.associate { it.id to it.isDownloaded })
    }

    @Test fun localCover_isUsedOnlyWhenFileExists() = runTest {
        libs()
        db.itemDao().upsertFromList(listOf(item("withcover"), item("nocover")))
        for (id in listOf("withcover", "nocover")) {
            listen(id)
            db.downloadDao().upsert(DownloadEntity(sid, id, DownloadState.COMPLETE, 1))
            db.audioFileDao().replaceFromServer(sid, id, listOf(AudioFileEntity(sid, id, 0, "i", 10.0, 1, "mp3", null, 0)))
            db.audioFileDao().setLocal(sid, id, 0, "/p/0", 1)
        }
        File(dlRoot, "$sid/withcover").mkdirs()
        File(dlRoot, "$sid/withcover/cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val items = vm().await { it.items.size == 2 }.items.associateBy { it.id }
        assertEquals(File(dlRoot, "$sid/withcover/cover.jpg").path, items.getValue("withcover").coverPath)
        assertNull(items.getValue("nocover").coverPath)
    }

    @Test fun progressTick_doesNotRestatCoverFiles() = runTest {
        libs()
        db.itemDao().upsertFromList(listOf(item("dl")))
        listen("dl", t = 10.0, last = 1)
        db.downloadDao().upsert(DownloadEntity(sid, "dl", DownloadState.COMPLETE, 1))
        db.audioFileDao().replaceFromServer(sid, "dl", listOf(AudioFileEntity(sid, "dl", 0, "i", 10.0, 1, "mp3", null, 0)))
        db.audioFileDao().setLocal(sid, "dl", 0, "/p/0", 1)
        val vm = vm()
        vm.await { it.items.size == 1 && it.items.single().progress == 0.1f }
        val before = statChecks.get()
        assertEquals(1, before)
        listen("dl", t = 50.0, last = 2)
        assertEquals(0.5f, vm.await { it.items.singleOrNull()?.progress == 0.5f }.items.single().progress)
        assertEquals(before, statChecks.get())
    }
}
