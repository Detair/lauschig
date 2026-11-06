package io.wolftown.lauschig.external

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.settings.AppSettings
import kotlinx.coroutines.async
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.wolftown.lauschig.ui.browse.MissingVolumesSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalSeriesRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var db: LauschigDatabase
    private lateinit var settings: AppSettings
    private lateinit var repo: ExternalSeriesRepository
    private val paths = CopyOnWriteArrayList<String>()
    private var now = Instant.parse("2026-11-01T12:00:00Z")
    private var failing = false
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val owned = listOf(
        ItemEntity("srv", "i1", "lib", "Eins", seriesName = "Die Serie", seriesSequence = "1", seriesSortKey = 1.0, asin = "OWN1", language = "German"),
    )

    private val productBody = """{"product":{"asin":"OWN1","series":[{"asin":"OTHER","title":"Anderes","sequence":"1"},{"asin":"SER","title":"Die Serie","sequence":"1"}]}}"""
    private val relBody = """{"product":{"asin":"SER","relationships":[
        {"asin":"OWN1","relationship_to_product":"child","relationship_type":"series","sequence":"1","sort":"1"},
        {"asin":"C2","relationship_to_product":"child","relationship_type":"series","sequence":"2","sort":"2"},
        {"asin":"BOX","relationship_to_product":"child","relationship_type":"series","sequence":"1-2","sort":"3"},
        {"asin":"PARENT","relationship_to_product":"parent","relationship_type":"series","sequence":"9"}]}}"""
    private val detailBody = """{"products":[
        {"asin":"OWN1","title":"Eins","language":"german","format_type":"unabridged","release_date":"2020-01-01"},
        {"asin":"C2","title":"Zwei","subtitle":"Sub","authors":[{"name":"A"}],"narrators":null,"language":"german","format_type":"unabridged","release_date":"2026-12-24","runtime_length_min":300,"product_images":{"500":"https://img/c2.jpg"},"merchandising_summary":"<p>hi</p>"}]}"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                if (failing) return MockResponse().setResponseCode(500)
                val p = request.path!!
                return when {
                    p.startsWith("/1.0/catalog/products/OWN1") -> MockResponse().setBody(productBody)
                    p.startsWith("/1.0/catalog/products/SER") -> MockResponse().setBody(relBody)
                    p.startsWith("/1.0/catalog/products?") -> MockResponse().setBody(detailBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LauschigDatabase::class.java).allowMainThreadQueries().build()
        settings = AppSettings(context)
        // The DataStore outlives a test within the JVM; start from the defaults.
        runBlocking { settings.setMissingVolumesEnabled("srv", true); settings.setAudibleRegion("de") }
        repo = repository()
    }

    private fun repository(
        endpoints: AudibleEndpoints = AudibleEndpoints { server.url("/") },
        settingsToUse: AppSettings = settings,
    ) =
        ExternalSeriesRepository(AudibleApis(endpoints, AudibleClient.okHttp()), db.externalSeriesDao(), settingsToUse, clock)

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test fun lookup_fetchesChildren_andComputesMissing() = runTest {
        val r = repo.missingFor("srv", "lib", "Die Serie", owned)!!
        assertEquals(listOf("C2"), r.map { it.volume.asin })
        assertEquals(true, r.single().upcoming)
        assertEquals("Sub", r.single().volume.subtitle)
        assertEquals("https://img/c2.jpg", r.single().volume.coverUrl)
        assertNotNull(db.externalSeriesDao().get("srv", "lib", "Die Serie"))
    }

    @Test fun lookup_fallsBackToOtherRegion_on404() = runTest {
        val usServer = MockWebServer().also { it.start() }
        try {
            usServer.dispatcher = server.dispatcher
            val de404 = MockWebServer().also { it.start() }
            de404.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += "de:" + request.path
                    return MockResponse().setResponseCode(404)
                }
            }
            val endpoints = AudibleEndpoints { region -> if (region == "de") de404.url("/") else usServer.url("/") }
            val r = repository(endpoints).missingFor("srv", "lib", "Die Serie", owned)
            assertEquals(listOf("C2"), r!!.map { it.volume.asin })
            assertEquals(1, paths.count { it.startsWith("de:") })
            de404.shutdown()
        } finally {
            usServer.shutdown()
        }
    }

    @Test fun cache_freshWithinTtl_noNetwork() = runTest {
        repo.missingFor("srv", "lib", "Die Serie", owned)
        val before = paths.size
        now = now.plusSeconds(6L * 24 * 3600)
        val r = repo.missingFor("srv", "lib", "Die Serie", owned)
        assertEquals(before, paths.size)
        assertEquals(listOf("C2"), r!!.map { it.volume.asin })
        now = now.plusSeconds(2L * 24 * 3600)
        repo.missingFor("srv", "lib", "Die Serie", owned)
        assertEquals(true, paths.size > before)
    }

    @Test fun networkError_returnsStaleCacheOrNull() = runTest {
        failing = true
        paths.clear()
        // No cache yet and the network fails.
        assertNull(repo.missingFor("srv", "lib", "Andere", owned))
        failing = false
        repo.missingFor("srv", "lib", "Die Serie", owned)
        now = now.plusSeconds(30L * 24 * 3600)
        failing = true
        val r = repo.missingFor("srv", "lib", "Die Serie", owned)
        assertEquals(listOf("C2"), r!!.map { it.volume.asin })
    }

    /** A store nobody has written to: an install, or a 0.4.0 that never touched the switch. */
    private fun kotlinx.coroutines.test.TestScope.freshSettings(name: String) =
        AppSettings(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("$name.preferences_pb") })

    @Test fun neverAnswered_noRequest_andStillUndecided() = runTest {
        val fresh = freshSettings("fresh")
        assertEquals(true, fresh.missingVolumesUndecided("srv").first())
        assertEquals(false, fresh.missingVolumesEnabled("srv").first())
        assertNull(repository(settingsToUse = fresh).missingFor("srv", "lib", "Die Serie", owned))
        assertEquals(0, paths.size)
    }

    @Test fun anAnswer_isNoLongerUndecided_andKeepsItsValue() = runTest {
        val fresh = freshSettings("fresh2")
        fresh.setMissingVolumesEnabled("srv", false)
        assertEquals(false, fresh.missingVolumesUndecided("srv").first())
        assertEquals(false, fresh.missingVolumesEnabled("srv").first())
        fresh.setMissingVolumesEnabled("srv", true)
        assertEquals(false, fresh.missingVolumesUndecided("srv").first())
        assertEquals(true, fresh.missingVolumesEnabled("srv").first())
    }

    /** The real consent wiring: real settings, source and repository, no fake between them. */
    @Test fun consent_noRequestUntilAllowed_andAskedOnlyOnce() = runTest {
        val fresh = freshSettings("wired")
        val repo = repository(settingsToUse = fresh)
        val source = MissingVolumesSource(flowOf(true), fresh, repo::missingFor)
        val byLibrary = mapOf("lib" to owned)

        assertEquals(true, source.undecided("srv").first())
        assertNull(source.forSeries("srv", byLibrary).first())
        assertEquals("nothing may leave before an answer", 0, paths.size)

        source.answer("srv", false)
        assertEquals(false, source.undecided("srv").first())
        assertNull(source.forSeries("srv", byLibrary).first())
        assertEquals("nothing may leave after a no", 0, paths.size)

        source.answer("srv", true)
        assertNotNull(source.forSeries("srv", byLibrary).first())
        assertTrue("a yes looks up", paths.isNotEmpty())

        // A new page (a new source on the same store) does not ask again.
        assertEquals(false, MissingVolumesSource(flowOf(true), fresh, repo::missingFor).undecided("srv").first())
    }

    // --- The final review's Minor 1: one person's yes is nobody else's ---

    private val ownedOfB get() = owned.map { it.copy(serverId = "b", id = "b-${it.id}", asin = "ASIN-OF-B") }

    private fun assertNothingOfB() = assertTrue(paths.toString(), paths.none { "ASIN-OF-B" in it })

    /** Through the real settings, source and repository. */
    @Test fun consent_oneAccountsYes_sendsNothingForAnother() = runTest {
        val fresh = freshSettings("two")
        val repo = repository(settingsToUse = fresh)
        val source = MissingVolumesSource(flowOf(true), fresh, repo::missingFor)
        source.answer("a", true)
        assertNotNull(source.forSeries("a", mapOf("lib" to owned)).first())
        assertTrue(paths.isNotEmpty())
        paths.clear()

        assertEquals("B is asked itself", true, source.undecided("b").first())
        assertEquals(false, source.undecided("a").first())
        assertNull(source.forSeries("b", mapOf("lib" to ownedOfB)).first())
        assertNull(repo.missingFor("b", "lib", "Die Serie", ownedOfB))
        assertEquals("right after a switch: nothing for B", 0, paths.size)

        source.answer("b", false)
        assertNull(source.forSeries("b", mapOf("lib" to ownedOfB)).first())
        assertEquals(0, paths.size)
        assertEquals("B's no is B's", true, fresh.missingVolumesEnabled("a").first())

        source.answer("b", true)
        source.answer("a", false)
        source.forSeries("b", mapOf("lib" to ownedOfB)).first()
        assertTrue("B's own yes looks up", paths.any { "ASIN-OF-B" in it })
        paths.clear()
        assertNull(source.forSeries("a", mapOf("lib" to owned)).first())
        assertEquals("and A's no holds", 0, paths.size)
    }

    /** A's lookup is still on its way when the device goes over to B. */
    @Test fun consent_aRunningLookupOfOneAccount_sendsNothingForAnother() = runBlocking {
        val fresh = AppSettings(PreferenceDataStoreFactory.create(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)) { tmp.newFile("running.preferences_pb") })
        val repo = repository(settingsToUse = fresh)
        val source = MissingVolumesSource(flowOf(true), fresh, repo::missingFor)
        fresh.setMissingVolumesEnabled("a", true)
        val hold = java.util.concurrent.CountDownLatch(1)
        val inFlight = java.util.concurrent.CountDownLatch(1)
        val answers = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                inFlight.countDown()
                hold.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return answers.dispatch(request)
            }
        }
        val running = async(kotlinx.coroutines.Dispatchers.IO) { source.forSeries("a", mapOf("lib" to owned)).first() }
        assertTrue("A's lookup is on the wire", inFlight.await(10, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(true, source.undecided("b").first())
        assertNull(source.forSeries("b", mapOf("lib" to ownedOfB)).first())
        assertNull(repo.missingFor("b", "lib", "Die Serie", ownedOfB))
        hold.countDown()
        assertNotNull(running.await())
        assertNothingOfB()
    }

    @Test fun consent_theDeviceWideAnswer_goesToTheAccountActiveAtTheUpgrade() = runTest {
        val old = freshSettings("upgrade")
        old.putLegacyMissingVolumes(true)
        old.migrateMissingVolumes("a")
        assertEquals(true, old.missingVolumesEnabled("a").first())
        assertEquals(false, old.missingVolumesUndecided("a").first())
        assertEquals("the others are asked", true, old.missingVolumesUndecided("b").first())
        assertEquals(false, old.missingVolumesEnabled("b").first())
        old.migrateMissingVolumes("b")
        assertEquals("once, whoever comes later", true, old.missingVolumesUndecided("b").first())
    }

    @Test fun consent_aDeviceWideNo_goesThatWayToo() = runTest {
        val old = freshSettings("upgradeNo")
        old.putLegacyMissingVolumes(false)
        old.migrateMissingVolumes("a")
        assertEquals(false, old.missingVolumesUndecided("a").first())
        assertEquals(false, old.missingVolumesEnabled("a").first())
    }

    @Test fun consent_noDeviceWideAnswer_staysUndecided() = runTest {
        val fresh = freshSettings("upgradeNothing")
        fresh.migrateMissingVolumes("a")
        assertEquals(true, fresh.missingVolumesUndecided("a").first())
    }

    /** Signed out at the upgrade: nobody it could belong to, as with the auto-delete. */
    @Test fun consent_aDeviceWideAnswer_withNobodySignedIn_isGone() = runTest {
        val old = freshSettings("upgradeNobody")
        old.putLegacyMissingVolumes(true)
        old.migrateMissingVolumes(null)
        old.migrateMissingVolumes("a")
        assertEquals(true, old.missingVolumesUndecided("a").first())
    }

    /** The answer of an account that already has one is not overwritten by what is left of the old value. */
    @Test fun consent_theOldValue_doesNotOverwriteAnAnswer() = runTest {
        val old = freshSettings("upgradeOver")
        old.setMissingVolumesEnabled("a", false)
        old.putLegacyMissingVolumes(true)
        old.migrateMissingVolumes("a")
        assertEquals(false, old.missingVolumesEnabled("a").first())
    }

    @Test fun consent_clearing_takesOnlyThatAccounts_andItIsAskedAgain() = runTest {
        val fresh = freshSettings("clear")
        fresh.setMissingVolumesEnabled("a", true)
        fresh.setMissingVolumesEnabled("b", true)
        fresh.clearMissingVolumes("a")
        assertEquals(true, fresh.missingVolumesUndecided("a").first())
        assertEquals(false, fresh.missingVolumesEnabled("a").first())
        assertEquals(true, fresh.missingVolumesEnabled("b").first())
    }

    @Test fun consent_noAccount_nothingToAskAndNothingAllowed() = runTest {
        val fresh = freshSettings("nobody")
        assertEquals(false, fresh.missingVolumesUndecided(null).first())
        assertEquals(false, fresh.missingVolumesEnabled(null).first())
    }

    /** The region stays one for the device. */
    @Test fun consent_theRegionIsTheDevices() = runTest {
        val fresh = freshSettings("region")
        fresh.setAudibleRegion("uk")
        fresh.setMissingVolumesEnabled("a", true)
        assertEquals("uk", fresh.audibleRegion.first())
    }

    @Test fun aCover_isOnlyLoadedOverHttps() {
        assertEquals("https://img/c.jpg", httpsOnly("https://img/c.jpg"))
        assertNull(httpsOnly("http://img/c.jpg"))
        assertNull(httpsOnly(null))
    }

    @Test fun disabled_noRequest() = runTest {
        settings.setMissingVolumesEnabled("srv", false)
        assertNull(repo.missingFor("srv", "lib", "Die Serie", owned))
        assertEquals(0, paths.size)
    }

    @Test fun noAsin_noRequest() = runTest {
        assertNull(repo.missingFor("srv", "lib", "Die Serie", owned.map { it.copy(asin = null) }))
        assertEquals(0, paths.size)
    }

    @Test fun walksOwnedAsins_untilOneResolves() = runTest {
        val two = listOf(
            owned[0].copy(id = "x", asin = "UNKNOWN"),
            owned[0].copy(id = "y", asin = "OWN1"),
        )
        val r = repo.missingFor("srv", "lib", "Die Serie", two)
        assertEquals(listOf("C2"), r!!.map { it.volume.asin })
        // UNKNOWN tried in both regions (404), then OWN1 resolves.
        assertEquals(2, paths.count { it.startsWith("/1.0/catalog/products/UNKNOWN") })
    }

    @Test fun noData_isNegativeCachedForSixHours() = runTest {
        val unknown = owned.map { it.copy(asin = "UNKNOWN") }
        assertNull(repo.missingFor("srv", "lib", "Die Serie", unknown))
        val before = paths.size
        now = now.plusSeconds(5L * 3600)
        assertNull(repo.missingFor("srv", "lib", "Die Serie", unknown))
        assertEquals(before, paths.size)
        now = now.plusSeconds(2L * 3600)
        assertNull(repo.missingFor("srv", "lib", "Die Serie", unknown))
        assertEquals(true, paths.size > before)
    }

    @Test fun failure_withStaleCache_isNotRetriedFor6Hours() = runTest {
        repo.missingFor("srv", "lib", "Die Serie", owned)
        now = now.plusSeconds(8L * 24 * 3600)
        failing = true
        paths.clear()
        assertEquals(1, repo.missingFor("srv", "lib", "Die Serie", owned)!!.size)
        val n = paths.size
        assertEquals(1, repo.missingFor("srv", "lib", "Die Serie", owned)!!.size)
        assertEquals(n, paths.size)
    }

    @Test fun concurrentCalls_shareOneRequestChain() = runTest {
        val a = async { repo.missingFor("srv", "lib", "Die Serie", owned) }
        val b = async { repo.missingFor("srv", "lib", "Die Serie", owned) }
        assertEquals(1, a.await()!!.size)
        assertEquals(1, b.await()!!.size)
        assertEquals(3, paths.size)
    }

    @Test fun setAudibleRegion_ignoresUnknownCodes() = runTest {
        settings.setAudibleRegion("uk")
        settings.setAudibleRegion("xx")
        assertEquals("uk", settings.audibleRegion.first())
    }
}
