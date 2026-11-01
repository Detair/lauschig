package io.wolftown.lauschig.external

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.settings.AppSettings
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
import org.junit.Before
import org.junit.Test
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
        {"asin":"C2","title":"Zwei","subtitle":"Sub","authors":[{"name":"A"}],"narrators":null,"language":"german","format_type":"unabridged","release_date":"2026-12-24","runtime_length_min":300,"product_images":{"500":"http://img/c2.jpg"},"merchandising_summary":"<p>hi</p>"}]}"""

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
        runBlocking { settings.setMissingVolumesEnabled(true); settings.setAudibleRegion("de") }
        repo = repository()
    }

    private fun repository(endpoints: AudibleEndpoints = AudibleEndpoints { server.url("/") }) =
        ExternalSeriesRepository(AudibleApis(endpoints, AudibleClient.okHttp()), db.externalSeriesDao(), settings, clock)

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    @Test fun lookup_fetchesChildren_andComputesMissing() = runTest {
        val r = repo.missingFor("srv", "lib", "Die Serie", owned)!!
        assertEquals(listOf("C2"), r.map { it.volume.asin })
        assertEquals(true, r.single().upcoming)
        assertEquals("Sub", r.single().volume.subtitle)
        assertEquals("http://img/c2.jpg", r.single().volume.coverUrl)
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

    @Test fun disabled_noRequest() = runTest {
        settings.setMissingVolumesEnabled(false)
        assertNull(repo.missingFor("srv", "lib", "Die Serie", owned))
        assertEquals(0, paths.size)
    }

    @Test fun noAsin_noRequest() = runTest {
        assertNull(repo.missingFor("srv", "lib", "Die Serie", owned.map { it.copy(asin = null) }))
        assertEquals(0, paths.size)
    }
}
