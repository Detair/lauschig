package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import io.wolftown.lauschig.db.dao.ProgressChange
import io.wolftown.lauschig.db.entities.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val other = "bbbbbbbbbbbbbbbb"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun item(id: String, lib: String = "lib1", server: String = s, title: String = id) =
        ItemEntity(serverId = server, id = id, libraryId = lib, title = title)

    private fun file(id: String, i: Int, path: String?, server: String = s) =
        AudioFileEntity(server, id, i, "ino$i", 10.0, 100, "mp3", path, if (path != null) 100 else 0)

    private fun progress(id: String, t: Double, fin: Boolean = false, last: Long = 1, server: String = s) =
        ProgressEntity(server, id, t, 100.0, fin, last, false)

    private suspend fun seedFiles(id: String, vararg paths: String?, server: String = s) {
        db.audioFileDao().replaceFromServer(server, id, paths.mapIndexed { i, _ -> file(id, i, null, server) })
        paths.forEachIndexed { i, p -> if (p != null) db.audioFileDao().setLocal(server, id, i, p, 100) }
    }

    private suspend fun seedProgress(vararg rows: ProgressEntity) = db.progressDao().mergeFromServer(rows.toList())

    @Test fun inProgress_excludesFinishedAndUnstarted() = runTest {
        db.itemDao().upsertFromList(listOf(item("a"), item("b"), item("c"), item("d")))
        seedProgress(progress("a", 10.0, last = 1), progress("b", 99.0, fin = true), progress("c", 0.0), progress("d", 5.0, last = 9))
        assertEquals(listOf("d", "a"), db.itemDao().observeInProgress(s).first().map { it.id })
        assertEquals(listOf("b"), db.itemDao().observeFinished(s).first().map { it.id })
    }

    @Test fun downloaded_onlyWhenAllAudioFilesComplete() = runTest {
        db.itemDao().upsertFromList(listOf(item("full"), item("partial"), item("nofiles"), item("queued")))
        for (id in listOf("full", "partial", "nofiles")) {
            db.downloadDao().upsert(DownloadEntity(s, id, DownloadState.COMPLETE, 1))
        }
        db.downloadDao().upsert(DownloadEntity(s, "queued", DownloadState.RUNNING, 1))
        seedFiles("full", "/p/0", "/p/1")
        seedFiles("partial", "/p/0", null)
        seedFiles("queued", "/p/0")
        assertEquals(listOf("full"), db.itemDao().observeDownloaded(s).first().map { it.id })
    }

    @Test fun seriesSortKey_parsesSequences() {
        assertEquals(3.0, seriesSortKey("3")!!, 0.0)
        assertEquals(3.5, seriesSortKey("3.5")!!, 0.0)
        assertEquals(1.0, seriesSortKey("1-2")!!, 0.0)
        assertNull(seriesSortKey("Band X"))
        assertNull(seriesSortKey(null))
    }

    @Test fun parseMinifiedSeries_firstSeriesAndSequence() {
        assertEquals("Saga" to "1", parseMinifiedSeries("Saga #1"))
        assertEquals("Die drei ???" to "126", parseMinifiedSeries("Die drei ??? #126, Kids #3"))
        assertEquals("No Number" to null, parseMinifiedSeries("No Number"))
        assertNull(parseMinifiedSeries(null))
        assertNull(parseMinifiedSeries("  "))
        assertEquals("Name" to "1.5", parseMinifiedSeries("Name #1.5"))
    }

    @Test fun libraryKind_defaultMapping() {
        assertEquals(LibraryKind.PODCAST, defaultLibraryKind("podcast", "Hörspiele"))
        assertEquals(LibraryKind.HOERSPIEL, defaultLibraryKind("book", "Meine Hörspiele"))
        assertEquals(LibraryKind.HOERSPIEL, defaultLibraryKind("book", "HOERSPIEL"))
        assertEquals(LibraryKind.AUDIOBOOK, defaultLibraryKind("book", "Hörbücher"))
    }

    @Test fun kindsFilter_returnsOnlySelectedKinds() = runTest {
        db.libraryDao().upsertFromServer(
            listOf(
                LibraryEntity(s, "books", "Hörbücher", "book", 0),
                LibraryEntity(s, "hsp", "Hörspiele", "book", 1),
                LibraryEntity(s, "pod", "Pods", "podcast", 2),
                LibraryEntity(s, "over", "Odd", "book", 3, kindOverride = LibraryKind.HOERSPIEL),
            ),
        )
        db.itemDao().upsertFromList(listOf(item("b", "books"), item("h", "hsp"), item("p", "pod"), item("o", "over")))
        val dao = db.itemDao()
        assertEquals(listOf("b"), dao.observeByLibraryKinds(s, listOf(LibraryKind.AUDIOBOOK)).first().map { it.id })
        assertEquals(setOf("h", "o"), dao.observeByLibraryKinds(s, listOf(LibraryKind.HOERSPIEL)).first().map { it.id }.toSet())
        assertEquals(
            setOf("b", "p"),
            dao.observeByLibraryKinds(s, listOf(LibraryKind.AUDIOBOOK, LibraryKind.PODCAST)).first().map { it.id }.toSet(),
        )
    }

    @Test fun observeItem_scopesByServerId() = runTest {
        db.itemDao().upsertFromList(listOf(item("x"), item("x", server = other, title = "other")))
        db.chapterDao().replaceFromServer(s, "x", listOf(ChapterEntity(s, "x", 1, 10.0, 20.0, "two"), ChapterEntity(s, "x", 0, 0.0, 10.0, "one")))
        db.chapterDao().replaceFromServer(other, "x", listOf(ChapterEntity(other, "x", 0, 0.0, 5.0, "foreign")))
        db.audioFileDao().replaceFromServer(s, "x", listOf(file("x", 1, null), file("x", 0, null)))
        db.audioFileDao().replaceFromServer(other, "x", listOf(file("x", 0, null, server = other)))
        seedProgress(progress("x", 3.0))
        db.itemDao().observeItem(s, "x").test {
            val d = awaitItem()!!
            assertEquals("x", d.item.title)
            assertEquals(listOf("one", "two"), d.chapters.map { it.title })
            assertEquals(listOf(0, 1), d.audioFiles.map { it.index })
            assertEquals(3.0, d.progress!!.currentTimeSec, 0.0)
            assertNull(d.download)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(db.itemDao().observeItem("cccccccccccccccc", "x").first())
    }

    @Test fun dirtyProgressAndUnsyncedSessions_scopedByServer() = runTest {
        db.progressDao().saveLocal(s, "a", 1.0, 100.0, false, 1)
        seedProgress(progress("b", 1.0))
        db.progressDao().saveLocal(other, "a", 1.0, 100.0, false, 1)
        assertEquals(listOf("a"), db.progressDao().dirty(s).map { it.itemId })
        db.sessionDao().upsert(LocalSessionEntity("u1", s, "a", 0.0, 5.0, 5.0, 1, 2))
        db.sessionDao().upsert(LocalSessionEntity("u2", s, "a", 0.0, 5.0, 5.0, 1, 2, synced = true))
        db.sessionDao().upsert(LocalSessionEntity("u3", other, "a", 0.0, 5.0, 5.0, 1, 2))
        assertEquals(listOf("u1"), db.sessionDao().unsynced(s).map { it.id })
    }

    @Test fun genresAndTags_roundTrip() = runTest {
        db.itemDao().upsertFromList(listOf(item("g").copy(genres = listOf("Krimi", "A,B"), tags = listOf("x"))))
        val got = db.itemDao().get(s, "g")!!
        assertEquals(listOf("Krimi", "A,B"), got.genres)
        assertEquals(listOf("x"), got.tags)
    }

    @Test fun replaceFromServer_keepsLocalPathAndCompletedBytes() = runTest {
        seedFiles("k", "/p/0", "/p/1")
        // server reorders files: ino1 now first
        val fresh = listOf(file("k", 0, null).copy(ino = "ino1", sizeBytes = 200), file("k", 1, null).copy(ino = "ino0"))
        db.audioFileDao().replaceFromServer(s, "k", fresh)
        val got = db.audioFileDao().forItem(s, "k")
        assertEquals(listOf("/p/1", "/p/0"), got.map { it.localPath })
        assertEquals(100L, got[0].completedBytes)
        assertEquals(200L, got[0].sizeBytes)
    }

    @Test fun replaceFromServer_removesVanishedFiles() = runTest {
        seedFiles("v", "/p/0", "/p/1")
        db.audioFileDao().replaceFromServer(s, "v", listOf(file("v", 0, null)))
        assertEquals(listOf("ino0"), db.audioFileDao().forItem(s, "v").map { it.ino })
    }

    @Test fun mergeFromServer_skipsNewerLocalDirtyRow() = runTest {
        db.progressDao().saveLocal(s, "m", 50.0, 100.0, false, 10)
        val changes = db.progressDao().mergeFromServer(listOf(progress("m", 5.0, last = 9)))
        assertEquals(emptyList<ProgressChange>(), changes)
        val p = db.progressDao().get(s, "m")!!
        assertEquals(50.0, p.currentTimeSec, 0.0)
        assertEquals(true, p.dirty)
    }

    @Test fun mergeFromServer_appliesNewerServer_andReportsChange() = runTest {
        db.progressDao().saveLocal(s, "m", 50.0, 100.0, false, 10)
        val changes = db.progressDao().mergeFromServer(listOf(progress("m", 80.0, last = 11), progress("n", 1.0, last = 1)))
        assertEquals(listOf(ProgressChange("m", 50.0, 80.0)), changes)
        val p = db.progressDao().get(s, "m")!!
        assertEquals(80.0, p.currentTimeSec, 0.0)
        assertEquals(false, p.dirty)
    }

    @Test fun markSynced_ignoresIfNewerLocalWrite() = runTest {
        db.progressDao().saveLocal(s, "m", 1.0, 100.0, false, 10)
        db.progressDao().saveLocal(s, "m", 2.0, 100.0, false, 20)
        db.progressDao().markSynced(s, "m", 10)
        assertEquals(true, db.progressDao().get(s, "m")!!.dirty)
        db.progressDao().markSynced(s, "m", 20)
        assertEquals(false, db.progressDao().get(s, "m")!!.dirty)
    }

    @Test fun librarySync_preservesKindOverride() = runTest {
        db.libraryDao().upsertFromServer(listOf(LibraryEntity(s, "l", "Old", "book", 0)))
        db.libraryDao().setKindOverride(s, "l", LibraryKind.HOERSPIEL)
        db.libraryDao().upsertFromServer(listOf(LibraryEntity(s, "l", "Meine Hörspiele", "book", 3)))
        val l = db.libraryDao().getAll(s).single()
        assertEquals(LibraryKind.HOERSPIEL, l.kindOverride)
        assertEquals("Meine Hörspiele", l.name)
        assertEquals(3, l.displayOrder)
    }

    @Test fun upsertFromList_preservesDetailsAndSeriesId_resetsOrphaned() = runTest {
        db.itemDao().upsertDetails(item("d").copy(description = "desc", seriesId = "ser1", title = "Old"))
        db.itemDao().setOrphaned(s, listOf("d"), true)
        db.itemDao().upsertFromList(listOf(item("d").copy(title = "New", seriesName = "S", seriesSequence = "2", seriesSortKey = 2.0)))
        val got = db.itemDao().get(s, "d")!!
        assertEquals("New", got.title)
        assertEquals("desc", got.description)
        assertEquals("ser1", got.seriesId)
        assertEquals(true, got.detailsLoaded)
        assertEquals(false, got.orphaned)
        assertEquals("S", got.seriesName)
    }

    @Test fun deleteByIds_handlesMoreThan999() = runTest {
        val items = (1..1500).map { item("i$it") }
        db.itemDao().upsertFromList(items)
        db.itemDao().setOrphaned(s, items.map { it.id }, true)
        assertEquals(1500, db.itemDao().idsInLibrary(s, "lib1").size)
        db.itemDao().deleteByIds(s, items.map { it.id })
        assertEquals(0, db.itemDao().idsInLibrary(s, "lib1").size)
    }
}
