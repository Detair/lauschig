package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A small model of ABS 2.37.1's progress rules (`server/managers/PlaybackSessionManager.js` syncLocalSession and
 * `models/MediaProgress.js` applyProgressUpdate): a PATCH stamps the progress with the server clock; an uploaded local
 * session overwrites the progress unless it is newer than the session, takes the session's `updatedAt` as its own
 * `lastUpdate`, and unmarks a finished title whose position changed (this library never auto-marks).
 */
private class AbsProgressServer(var clock: Long) : Dispatcher() {
    data class Row(var currentTime: Double, var duration: Double, var isFinished: Boolean, var hide: Boolean, var updatedAt: Long)

    val rows = HashMap<String, Row>()
    val log = mutableListOf<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            request.method == "GET" && path == "/api/me" -> { log += "me"; json(me()) }
            request.method == "PATCH" && path.startsWith("/api/me/progress/") -> {
                val id = path.removePrefix("/api/me/progress/")
                log += "patch $id"
                val b = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val row = rows.getOrPut(id) { Row(0.0, 0.0, false, false, 0) }
                row.currentTime = b.getValue("currentTime").jsonPrimitive.double
                row.duration = b.getValue("duration").jsonPrimitive.double
                b["isFinished"]?.jsonPrimitive?.booleanOrNull?.let { row.isFinished = it }
                b["hideFromContinueListening"]?.jsonPrimitive?.booleanOrNull?.let { row.hide = it }
                row.updatedAt = clock++
                MockResponse().setResponseCode(200)
            }
            request.method == "POST" && path == "/api/session/local-all" -> {
                val sessions = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("sessions").jsonArray
                val results = sessions.map { e ->
                    val o = e.jsonObject
                    val id = o.getValue("libraryItemId").jsonPrimitive.content
                    log += "session $id"
                    applySession(id, o)
                    """{"id":"${o.getValue("id").jsonPrimitive.content}","success":true}"""
                }
                json("""{"results":[${results.joinToString(",")}]}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun applySession(id: String, o: JsonObject) {
        val updatedAt = o.getValue("updatedAt").jsonPrimitive.long
        val current = o.getValue("currentTime").jsonPrimitive.double
        val row = rows[id]
        if (row != null && row.updatedAt > updatedAt) return // "updated more recently"
        val r = row ?: Row(0.0, 0.0, false, false, 0).also { rows[id] = it }
        if (r.isFinished && r.currentTime != current) r.isFinished = false
        r.currentTime = current
        o["duration"]?.jsonPrimitive?.doubleOrNull?.let { r.duration = it }
        r.updatedAt = updatedAt
    }

    private fun me(): String {
        val list = rows.entries.joinToString(",") { (id, r) ->
            """{"libraryItemId":"$id","currentTime":${r.currentTime},"duration":${r.duration},"progress":${if (r.duration > 0) r.currentTime / r.duration else 0.0},""" +
                """"isFinished":${r.isFinished},"lastUpdate":${r.updatedAt},"hideFromContinueListening":${r.hide}}"""
        }
        return """{"id":"u","username":"n","type":"user","mediaProgress":[$list]}"""
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressSyncAbsTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private lateinit var db: LauschigDatabase
    private lateinit var web: MockWebServer
    private lateinit var abs: AbsProgressServer
    private lateinit var api: MeApi

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
        abs = AbsProgressServer(clock = 0)
        web = MockWebServer().apply { dispatcher = abs; start() }
        api = AbsClientFactory.retrofit(web.url("/"), OkHttpClient()).create(MeApi::class.java)
    }

    @After fun tearDown() {
        web.shutdown()
        db.close()
    }

    private fun sync() = ProgressSync({ flowOf(api) }, db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto(deviceId = "d") })

    /** The tracker's last save of a title played to its end: progress and session share one timestamp. */
    private suspend fun playedToTheEnd(at: Long) {
        db.itemDao().upsertDetails(ItemEntity(s, "li_1", "l", "Buch", durationSec = 1200.0))
        db.progressDao().saveLocal(s, "li_1", 1200.0, 1200.0, true, at)
        db.sessionDao().upsert(LocalSessionEntity("sess", s, "li_1", 600.0, 1200.0, 600.0, at - 600_000, at))
    }

    @Test fun titleFinishedAtTheEnd_staysFinishedOnTheServerAndLocally_serverClockAhead() = runTest {
        abs.rows["li_1"] = AbsProgressServer.Row(600.0, 1200.0, false, false, updatedAt = 5_000)
        abs.clock = 20_000
        playedToTheEnd(at = 10_000)
        sync().sync(s)
        assertTrue("server lost the finished flag: ${abs.rows}", abs.rows.getValue("li_1").isFinished)
        assertEquals(listOf("me", "patch li_1", "session li_1", "me"), abs.log)
        val local = db.progressDao().get(s, "li_1")!!
        assertTrue(local.isFinished)
        assertFalse(local.dirty)
        // A later sync changes nothing.
        sync().sync(s)
        assertTrue(abs.rows.getValue("li_1").isFinished)
        assertTrue(db.progressDao().get(s, "li_1")!!.isFinished)
    }

    @Test fun titleFinishedAtTheEnd_staysFinished_serverClockBehind() = runTest {
        abs.rows["li_1"] = AbsProgressServer.Row(600.0, 1200.0, false, false, updatedAt = 5_000)
        abs.clock = 7_000
        playedToTheEnd(at = 10_000)
        sync().sync(s)
        assertTrue(abs.rows.getValue("li_1").isFinished)
        sync().sync(s)
        assertTrue(abs.rows.getValue("li_1").isFinished)
        assertTrue(db.progressDao().get(s, "li_1")!!.isFinished)
    }

    @Test fun equalTimestamps_withADifferentFinishedState_arePushed() = runTest {
        // What an earlier version left behind: the session already moved the server row to the same timestamp, unfinished.
        abs.rows["li_1"] = AbsProgressServer.Row(1200.0, 1200.0, false, false, updatedAt = 10_000)
        abs.clock = 20_000
        db.itemDao().upsertDetails(ItemEntity(s, "li_1", "l", "Buch", durationSec = 1200.0))
        db.progressDao().saveLocal(s, "li_1", 1200.0, 1200.0, true, 10_000)
        sync().sync(s)
        assertTrue(abs.rows.getValue("li_1").isFinished)
        assertTrue(db.progressDao().get(s, "li_1")!!.isFinished)
        // Equal and identical: nothing is sent.
        abs.log.clear()
        db.progressDao().saveLocal(s, "li_2", 30.0, 100.0, false, 30_000)
        abs.rows["li_2"] = AbsProgressServer.Row(30.0, 100.0, false, false, updatedAt = 30_000)
        sync().sync(s)
        assertFalse(abs.log.any { it.startsWith("patch") })
        assertFalse(db.progressDao().get(s, "li_2")!!.dirty)
    }
}
