package io.wolftown.lauschig.auto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.auto.CarTestData.DEV
import io.wolftown.lauschig.auto.CarTestData.LOCAL_A
import io.wolftown.lauschig.auto.CarTestData.S
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarSearchTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
        .allowMainThreadQueries().build()
    private val lib = CarLibrary(
        db.itemDao(), db.libraryDao(), db.progressDao(), db.episodeDao(), db.downloadDao(), db.catalogDao(), Catalog(), TestCarTexts,
    )
    private val search = CarSearch(lib)
    private val online = CarContext(S, online = true)
    private val offline = CarContext(S, online = false)
    private var last: CarNode.Title? = null

    @Before fun seed() = runTest {
        CarTestData.seed(db)
        db.itemDao().upsertFromList(
            listOf(
                ItemEntity(S, "d1", "hsp", "001/Die drei ??? und der Super-Papagei", seriesName = "Die drei ???", seriesSequence = "1", seriesSortKey = 1.0),
                ItemEntity(S, "d2", "hsp", "002/Die drei ??? und der Phantomsee", seriesName = "Die drei ???", seriesSequence = "2", seriesSortKey = 2.0),
                ItemEntity(S, "x1", "hsp", "Die drei !!! – Fall 1", seriesName = "Die drei !!!", seriesSequence = "1", seriesSortKey = 1.0),
                ItemEntity(S, "a1", "books", "Alpha eins", seriesName = "Alpha", seriesSequence = "1", seriesSortKey = 1.0),
                ItemEntity(S, "a2", "books", "Alpha zwei", seriesName = "Alpha", seriesSequence = "2", seriesSortKey = 2.0),
            ),
        )
        db.progressDao().saveLocal(S, "a1", 100.0, 100.0, isFinished = true, lastUpdate = 10)
    }

    @After fun tearDown() = db.close()

    private suspend fun play(query: String, ctx: CarContext = online, hints: SearchHints = SearchHints()) =
        search.resolve(query, hints, ctx) { last }

    @Test fun search_seriesFolderFirst_thenTitles_byTitleAuthorAndDevice() = runTest {
        val r = search.search("testreihe", online)
        assertEquals(CarNode.Series("Testreihe"), r.first().node)
        assertEquals(listOf("Band 1", "Band 2", "Band 3"), r.drop(1).map { it.title })
        assertEquals(listOf("Band 1", "Band 2", "Band 3"), search.search("anna", online).map { it.title })
        assertEquals(listOf("Lokal A", "Lokal B"), search.search("lokal", online).map { it.title })
        assertEquals(emptyList<CarEntry>(), search.search("zzz", online))
        assertEquals(emptyList<CarEntry>(), search.search("  ", online))
    }

    @Test fun signedOut_findsNothing_andVoicePlaysNothing() = runTest {
        val none = CarContext(null, online = true)
        assertEquals(emptyList<CarEntry>(), search.search("Lokal", none))
        assertNull(search.resolve("Lokal A", SearchHints(), none) { CarNode.Title(CarTestData.DEV, CarTestData.LOCAL_A) })
        assertNull(search.resolve("", SearchHints(), none) { CarNode.Title(CarTestData.DEV, CarTestData.LOCAL_A) })
    }

    @Test fun search_offline_onlyPlayableTitles() = runTest {
        assertEquals(listOf("Band 1"), search.search("band", offline).map { it.title })
    }

    @Test fun emptyQuery_playsTheLastTitle_elseTheFirstToContinue() = runTest {
        last = CarNode.Title(S, "v2")
        assertEquals(CarNode.Title(S, "v2"), play(""))
        last = null
        assertEquals(CarNode.Title(DEV, LOCAL_A), play(""))
    }

    @Test fun emptyQuery_offline_stillMeansTheLastTitle_notAnotherDownloadedBook() = runTest {
        // A voice request right after the car started the service: the connection check has not answered yet. The
        // start reports "not downloaded" if it really cannot stream; it never silently plays something else.
        last = CarNode.Title(S, "v2") // streamed, not downloaded
        assertEquals(CarNode.Title(S, "v2"), play("", offline))
    }

    @Test fun seriesName_playsTheVolumeToListenToNow() = runTest {
        assertEquals(CarNode.Title(S, "v2"), play("Testreihe")) // in progress
        assertEquals(CarNode.Title(S, "a2"), play("alpha")) // first unfinished
        assertEquals(CarNode.Title(S, "v1"), play("testreihe", offline)) // the only playable volume
    }

    @Test fun seriesWithNumber_playsThatVolume() = runTest {
        assertEquals(CarNode.Title(S, "v3"), play("Testreihe 3"))
        assertEquals(CarNode.Title(S, "v3"), play("testreihe folge 3"))
        assertEquals(CarNode.Title(S, "d2"), play("die drei fragezeichen 2"))
    }

    @Test fun spokenSymbols_matchTheirSeries() = runTest {
        assertEquals(CarNode.Title(S, "d1"), play("Die drei Fragezeichen"))
        assertEquals(CarNode.Title(S, "x1"), play("die drei ausrufezeichen"))
        assertEquals(CarNode.Title(S, "d2"), play("die drei fragezeichen und der phantomsee"))
    }

    @Test fun titleAndAuthor() = runTest {
        assertEquals(CarNode.Title(S, "v3"), play("Band 3"))
        assertEquals(CarNode.Title(S, "multi"), play("mehrteilig"))
        assertEquals(CarNode.Title(S, "multi"), play("Bert Bauer"))
        assertEquals(CarNode.Title(S, "v2"), play("Anna Autorin")) // the author's title in progress
        assertEquals(CarNode.Title(S, "multi"), play("", hints = SearchHints(title = "Mehrteilig")))
    }

    @Test fun nothingFound() = runTest {
        assertNull(play("zzz"))
        assertNull(play("mehrteilig zzz"))
    }
}
