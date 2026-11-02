package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.me.LocalSessionResult
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.MediaProgressDto
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

private class FakeMe : MeApi {
    var server: List<MediaProgressDto> = emptyList()
    val patches = mutableListOf<Pair<String, ProgressUpdate>>()
    val uploads = mutableListOf<LocalSessionsBody>()
    var failOnPatch = false
    var failIds: Set<String> = emptyList<String>().toSet()
    var emptyResults = false
    var events = mutableListOf<String>()

    override suspend fun me() = AbsUser("u", "n", "user", mediaProgress = server).also { events += "me" }
    val episodePatches = mutableListOf<Triple<String, String, ProgressUpdate>>()
    override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) {
        episodePatches += Triple(itemId, episodeId, u)
    }
    override suspend fun updateProgress(itemId: String, u: ProgressUpdate) {
        if (failOnPatch) throw IOException("boom")
        patches += itemId to u
    }
    override suspend fun syncLocalSessions(body: LocalSessionsBody): LocalSessionsResult {
        uploads += body
        events += "sessions"
        return LocalSessionsResult(
            if (emptyResults) emptyList() else body.sessions.map { LocalSessionResult(it.id, it.id !in failIds) },
        )
    }
}

@RunWith(RobolectricTestRunner::class)
class ProgressSyncTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val me = FakeMe()
    private val device = DeviceInfoDto(deviceId = "dev")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun sync() = ProgressSync(flowOf(me), db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { device })

    private fun srv(id: String, t: Double, last: Long, fin: Boolean = false, ep: String? = null) =
        MediaProgressDto(id, ep, t, 100.0, t / 100.0, fin, last)

    private fun session(id: String) = LocalSessionEntity(id, s, "a", 0.0, 10.0, 10.0, 1_000, 2_000)

    @Test fun newerServerProgress_winsAndEmitsJump() = runTest {
        db.progressDao().saveLocal(s, "a", 10.0, 100.0, false, 1_000)
        db.progressDao().markSynced(s, "a", 1_000)
        db.progressDao().saveLocal(s, "b", 10.0, 100.0, false, 1_000)
        db.progressDao().markSynced(s, "b", 1_000)
        me.server = listOf(srv("a", 80.0, 5_000), srv("b", 40.0, 5_000), srv("c", 7.0, 5_000), srv("e", 9.0, 9_000, ep = "ep1"))

        val jumps = sync().sync(s)

        assertEquals(listOf(PositionJump("a", 10.0, 80.0)), jumps) // b moved only 30 s, c is new
        assertEquals(80.0, db.progressDao().get(s, "a")!!.currentTimeSec, 0.0)
        assertEquals(40.0, db.progressDao().get(s, "b")!!.currentTimeSec, 0.0)
        assertEquals(7.0, db.progressDao().get(s, "c")!!.currentTimeSec, 0.0)
        assertEquals(null, db.progressDao().get(s, "e")) // not stored under the plain item id
        assertTrue(me.patches.isEmpty())
    }

    @Test fun episodeProgress_pulledUnderEpisodeKey() = runTest {
        me.server = listOf(srv("pod", 33.0, 5_000, ep = "ep1"), srv("pod", 70.0, 5_000))
        sync().sync(s)
        assertEquals(33.0, db.progressDao().get(s, PlayableKey.episode("pod", "ep1"))!!.currentTimeSec, 0.0)
        assertEquals(70.0, db.progressDao().get(s, "pod")!!.currentTimeSec, 0.0)
    }

    @Test fun episodeProgress_pushedWithEpisodeId() = runTest {
        val key = PlayableKey.episode("pod", "ep1")
        db.progressDao().saveLocal(s, key, 50.0, 100.0, false, 9_000)
        me.server = listOf(srv("pod", 10.0, 5_000, ep = "ep1"))
        sync().sync(s)
        val (item, ep, u) = me.episodePatches.single()
        assertEquals("pod", item)
        assertEquals("ep1", ep)
        assertEquals(50.0, u.currentTime, 0.0)
        assertTrue(me.patches.isEmpty())
        assertFalse(db.progressDao().get(s, key)!!.dirty)
    }

    @Test fun episodeSession_uploadedWithEpisodeId() = runTest {
        db.itemDao().upsertFromList(listOf(ItemEntity(s, "pod", "l", "Podcast", author = "Host", durationSec = 0.0)))
        db.episodeDao().upsert(listOf(EpisodeEntity(s, "pod", "ep1", "Folge 1", durationSec = 1234.0)))
        db.sessionDao().upsert(LocalSessionEntity("s1", s, PlayableKey.episode("pod", "ep1"), 0.0, 10.0, 10.0, 1_000, 2_000))
        sync().sync(s)
        val d = me.uploads.single().sessions.single()
        assertEquals("pod", d.libraryItemId)
        assertEquals("ep1", d.episodeId)
        assertEquals("podcast", d.mediaType)
        assertEquals("Folge 1", d.displayTitle)
        assertEquals(1234.0, d.duration, 0.0)
    }

    @Test fun hideFromContinue_isPushed_andPulled() = runTest {
        db.progressDao().saveUserAction(io.wolftown.lauschig.db.entities.ProgressEntity(s, "a", 50.0, 100.0, false, 9_000, hideFromContinue = true))
        me.server = listOf(srv("a", 50.0, 5_000), MediaProgressDto("b", null, 30.0, 100.0, 0.3, false, 6_000, hideFromContinueListening = true))
        sync().sync(s)
        assertEquals(true, me.patches.single { it.first == "a" }.second.hideFromContinueListening)
        assertTrue(db.progressDao().get(s, "b")!!.hideFromContinue)
    }

    @Test fun finishedRow_withAKeptPosition_isPushedAsFullProgress() = runTest {
        db.progressDao().saveLocal(s, "a", 30.0, 100.0, true, 9_000)
        me.server = listOf(srv("a", 10.0, 5_000))
        sync().sync(s)
        val u = me.patches.single().second
        assertEquals(1.0, u.progress, 0.0)
        assertEquals(30.0, u.currentTime, 0.0)
        assertTrue(u.isFinished)
    }

    @Test fun newerLocalProgress_isPushed_andNotOverwritten() = runTest {
        db.progressDao().saveLocal(s, "a", 50.0, 100.0, false, 9_000)
        db.progressDao().saveLocal(s, "b", 20.0, 100.0, false, 1_000) // server newer: not pushed
        me.server = listOf(srv("a", 10.0, 5_000), srv("b", 90.0, 5_000))

        val jumps = sync().sync(s)

        assertEquals(listOf("a"), me.patches.map { it.first })
        assertEquals(0.5, me.patches.single().second.progress, 1e-9)
        val a = db.progressDao().get(s, "a")!!
        assertEquals(50.0, a.currentTimeSec, 0.0)
        assertFalse(a.dirty)
        assertEquals(90.0, db.progressDao().get(s, "b")!!.currentTimeSec, 0.0)
        assertEquals(listOf(PositionJump("b", 20.0, 90.0)), jumps)
    }

    @Test fun offlineSessions_uploadedOnceAndMarkedSynced() = runTest {
        db.itemDao().upsertFromList(listOf(ItemEntity(s, "a", "l", "Titel", author = "Autor", durationSec = 99.0)))
        db.sessionDao().upsert(session("s1"))
        db.sessionDao().upsert(session("s2"))

        sync().sync(s)
        sync().sync(s)

        assertEquals(1, me.uploads.size)
        val body = me.uploads.single()
        assertEquals(listOf("s1", "s2"), body.sessions.map { it.id })
        assertEquals(device, body.deviceInfo)
        assertEquals(device, body.sessions.first().deviceInfo)
        assertEquals("Titel", body.sessions.first().displayTitle)
        assertTrue(db.sessionDao().unsynced(s).isEmpty())
        assertEquals("sessions", me.events.first()) // before me()
    }

    @Test fun session_failedOnServer_staysUnsynced() = runTest {
        db.sessionDao().upsert(session("s1"))
        db.sessionDao().upsert(session("s2"))
        me.failIds = setOf("s2")
        sync().sync(s)
        assertEquals(listOf("s2"), db.sessionDao().unsynced(s).map { it.id })
    }

    @Test fun session_resultWithoutIds_marksAllOnHttpSuccess() = runTest {
        db.sessionDao().upsert(session("s1"))
        me.emptyResults = true
        sync().sync(s)
        assertTrue(db.sessionDao().unsynced(s).isEmpty())
    }

    @Test fun syncFailure_midway_leavesDirtyFlagsIntact() = runTest {
        db.progressDao().saveLocal(s, "a", 50.0, 100.0, false, 9_000)
        db.sessionDao().upsert(session("s1"))
        me.failOnPatch = true

        try { sync().sync(s); fail("expected IOException") } catch (_: IOException) {}

        assertTrue(db.progressDao().get(s, "a")!!.dirty)
        assertEquals(50.0, db.progressDao().get(s, "a")!!.currentTimeSec, 0.0)
        assertTrue(db.sessionDao().unsynced(s).isEmpty()) // session upload had succeeded before
    }

    @Test fun offline_isNoOp() = runTest {
        db.progressDao().saveLocal(s, "a", 50.0, 100.0, false, 9_000)
        val r = ProgressSync(flowOf(null), db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { device }).sync(s)
        assertTrue(r.isEmpty())
        assertTrue(db.progressDao().get(s, "a")!!.dirty)
    }

    @Test fun onlineTransitions_emitOncePerOfflineToOnline() = runTest {
        val on = ConnectionState.Online("http://h/".toHttpUrl(), ConnectionState.Via.LOCAL)
        val off = ConnectionState.Offline
        val n = SyncScheduler.onlineTransitions(flowOf(off, on, on, off, off, on)).toList().size
        assertEquals(2, n)
    }
}
