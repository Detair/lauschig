package io.wolftown.lauschig.auto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.auto.CarTestData.DEV
import io.wolftown.lauschig.auto.CarTestData.LOCAL_A
import io.wolftown.lauschig.auto.CarTestData.LOCAL_B
import io.wolftown.lauschig.auto.CarTestData.S
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarLibraryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
        .allowMainThreadQueries().build()
    private val lib = CarLibrary(
        db.itemDao(), db.libraryDao(), db.progressDao(), db.episodeDao(), db.downloadDao(), db.catalogDao(), Catalog(), TestCarTexts,
    )
    private val online = CarContext(account = S, online = true)
    private val offline = CarContext(account = S, online = false)

    @Before fun seed() = runTest { CarTestData.seed(db) }

    @After fun tearDown() = db.close()

    private suspend fun titles(node: CarNode, ctx: CarContext = online) = lib.children(node, ctx)!!.map { it.title }

    @Test fun root_hasFourBrowsableNodes() = runTest {
        val root = lib.children(CarNode.Root, online)!!
        assertEquals(listOf(CarNode.Continue, CarNode.Downloads, CarNode.SeriesList, CarNode.Libraries), root.map { it.node })
        assertEquals(listOf("Weiterhören", "Heruntergeladen", "Serien", "Bibliotheken"), root.map { it.title })
        assertTrue(root.all { it.browsable && !it.playable })
    }

    @Test fun continue_mixesAccountEpisodesAndDeviceByRecency_withoutHiddenOrFinished() = runTest {
        val entries = lib.children(CarNode.Continue, online)!!
        assertEquals(listOf(CarNode.Title(DEV, LOCAL_A), CarNode.Title(S, "v2"), CarNode.Title(S, "pod~e1")), entries.map { it.node })
        assertEquals(listOf("Lokal A", "Band 2", "Erste Folge"), entries.map { it.title })
        assertEquals(listOf("noch 5 min", "noch 50 min", "noch 29 min"), entries.map { it.subtitle })
        assertEquals(CarEntry.Type.EPISODE, entries[2].type)
        assertEquals(0.5f, entries[0].progress, 0.001f)
        assertTrue(entries[0].downloaded)
        assertFalse(entries[1].downloaded)
        assertTrue(entries.all { it.playable })
    }

    @Test fun offline_listsOnlyWhatPlaysWithoutTheServer() = runTest {
        assertEquals(listOf("Lokal A"), titles(CarNode.Continue, offline))
        assertEquals(listOf("Band 1", "Mehrteilig"), titles(CarNode.Library("books"), offline))
        assertEquals(listOf("Band 1"), titles(CarNode.Series("Testreihe"), offline))
        assertEquals(listOf("Zweite Folge"), titles(CarNode.Podcast("pod"), offline))
        assertEquals(listOf("Der Podcast"), titles(CarNode.Library("pods"), offline)) // has a downloaded episode
        assertEquals(listOf("Lokal A", "Lokal B"), titles(CarNode.Library("device"), offline))
        assertEquals(listOf("Testreihe"), titles(CarNode.SeriesList, offline))
    }

    @Test fun downloads_inNaturalOrder() = runTest {
        val entries = lib.children(CarNode.Downloads, online)!!
        assertEquals(listOf("Band 1", "Mehrteilig"), entries.map { it.title })
        assertTrue(entries.all { it.downloaded })
        assertTrue(entries[0].finished)
    }

    @Test fun series_activeFirst_thenByName_volumesInSequence() = runTest {
        db.itemDao().upsertFromList(
            listOf(
                ItemEntity(S, "a1", "books", "Alpha 1", seriesName = "Alpha", seriesSequence = "1", seriesSortKey = 1.0),
                ItemEntity(S, "z1", "hsp", "Zeta 1", seriesName = "Zeta", seriesSequence = "1", seriesSortKey = 1.0),
            ),
        )
        db.progressDao().saveLocal(S, "z1", 10.0, 100.0, false, 5_000)
        val series = lib.children(CarNode.SeriesList, online)!!
        assertEquals(listOf("Zeta", "Testreihe", "Alpha"), series.map { it.title })
        assertEquals("3 Titel", series[1].subtitle)
        assertEquals(CarEntry.Type.SERIES, series[1].type)
        assertEquals(CoverRef(S, "v1", 7), series[1].cover)

        val volumes = lib.children(CarNode.Series("testreihe "), online)!!
        assertEquals(listOf("Band 1", "Band 2", "Band 3"), volumes.map { it.title })
        assertEquals(listOf("Band 1", "Band 2 · noch 50 min", "Band 3"), volumes.map { it.subtitle })
        assertEquals(CoverRef(S, "v3", 7), volumes[2].cover)
    }

    @Test fun libraries_includeDeviceLibrary_titlesInNaturalOrder_podcastsAsFolders() = runTest {
        val libs = lib.children(CarNode.Libraries, online)!!
        assertEquals(listOf("Hörbücher", "Hörspiele", "Podcasts", "Auf dem Gerät"), libs.map { it.title })
        assertEquals(CarNode.Library("device"), libs[3].node)
        assertEquals(listOf("Folge 1", "Folge 2", "Folge 10"), titles(CarNode.Library("hsp")))
        val pods = lib.children(CarNode.Library("pods"), online)!!
        assertEquals(listOf(CarNode.Podcast("pod")), pods.map { it.node })
        assertTrue(pods.single().browsable)
        assertEquals(listOf("Zweite Folge", "Erste Folge"), titles(CarNode.Podcast("pod")))
        assertEquals(listOf("Lokal A", "Lokal B"), titles(CarNode.Library("device")))
        assertEquals(CarNode.Title(DEV, LOCAL_B), lib.children(CarNode.Library("device"), online)!![1].node)
    }

    @Test fun bigLibrary_pages() = runTest {
        db.itemDao().upsertFromList((1..241).map { ItemEntity(S, "h$it", "hsp", "Hörspiel $it") })
        val all = lib.children(CarNode.Library("hsp"), online)!!
        assertEquals(244, all.size)
        // "Folge 1, 2, 10" come first; natural order keeps "Hörspiel 2" before "Hörspiel 10".
        assertEquals(listOf("Folge 10", "Hörspiel 1", "Hörspiel 2"), all.page(0, 100).subList(2, 5).map { it.title })
        assertEquals(44, all.page(2, 100).size)
        assertEquals("Hörspiel 241", all.page(2, 100)[43].title)
    }

    @Test fun signedOut_onlyTheSignInHint_nothingPlayable() = runTest {
        val none = CarContext(account = null, online = true)
        val root = lib.children(CarNode.Root, none)!!
        assertEquals(listOf("In Lauschig auf dem Handy anmelden"), root.map { it.title })
        assertEquals(CarNode.SignIn, root.single().node)
        assertFalse(root.single().playable)
        assertFalse(root.single().browsable)
        // A car that cached the tree finds every folder empty, no device title either.
        listOf(CarNode.Continue, CarNode.Downloads, CarNode.SeriesList, CarNode.Libraries, CarNode.Library(DeviceLibrary.LIBRARY_ID), CarNode.Series("Testreihe"))
            .forEach { assertEquals("$it", emptyList<String>(), titles(it, none)) }
        assertNull(lib.item(CarNode.Title(DEV, LOCAL_A), none))
        assertNull(lib.item(CarNode.Continue, none))
        assertEquals("In Lauschig auf dem Handy anmelden", lib.item(CarNode.SignIn, none)!!.title)
        // Signed in, the hint is unknown.
        assertNull(lib.item(CarNode.SignIn, online))
        assertNull(lib.children(CarNode.SignIn, online))
    }

    @Test fun item_forTitlesAndFolders_refusesForeignServers() = runTest {
        assertEquals("Band 2", lib.item(CarNode.Title(S, "v2"), online)!!.title)
        assertEquals("Lokal A", lib.item(CarNode.Title(DEV, LOCAL_A), online)!!.title)
        assertEquals("Erste Folge", lib.item(CarNode.Title(S, "pod~e1"), online)!!.title)
        assertNull(lib.item(CarNode.Title("bbbbbbbbbbbbbbbb", "v2"), online))
        assertNull(lib.item(CarNode.Title(S, "missing"), online))
        assertEquals("Weiterhören", lib.item(CarNode.Continue, online)!!.title)
        assertEquals("Testreihe", lib.item(CarNode.Series("Testreihe"), online)!!.title)
        assertEquals("Hörspiele", lib.item(CarNode.Library("hsp"), online)!!.title)
        assertNull(lib.children(CarNode.Title(S, "v2"), online))
        assertNull(lib.children(CarNode.Library("nope"), online))
    }
}
