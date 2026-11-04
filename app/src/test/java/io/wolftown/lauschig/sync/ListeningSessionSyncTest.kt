package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.StubMeApi
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.absapi.me.SessionMediaMetadataDto
import io.wolftown.lauschig.absapi.me.UserListeningSessionDto
import io.wolftown.lauschig.absapi.me.UserListeningSessionsPage
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ListeningSessionSyncTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val hour = 3_600_000L
    private val stamps = InMemorySyncStamps()
    private var now = 1_000L * hour

    /** The server: sessions newest updatedAt first, like ABS; [failPage] throws for that page (not for the probe). */
    private val server = FakeServer()

    private class FakeServer : StubMeApi() {
        var sessions: List<UserListeningSessionDto> = emptyList()
        val asked = mutableListOf<Pair<Int, Int>>()
        var failPage: Int? = null

        override suspend fun me() = AbsUser("u", "n", "user")
        override suspend fun updateProgress(itemId: String, u: ProgressUpdate) = error("unused")
        override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
        override suspend fun syncLocalSessions(body: LocalSessionsBody): LocalSessionsResult = error("unused")
        override suspend fun listeningSessions(itemsPerPage: Int, page: Int): UserListeningSessionsPage {
            asked += itemsPerPage to page
            if (page == failPage && itemsPerPage > 1) throw IOException("down")
            val sorted = sessions.sortedByDescending { it.updatedAt }
            val pages = (sorted.size + itemsPerPage - 1) / itemsPerPage
            return UserListeningSessionsPage(sorted.size, pages, page, itemsPerPage, sorted.drop(page * itemsPerPage).take(itemsPerPage))
        }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun sync(api: MeApi? = server) = ListeningSessionSync(flowOf(api), db.listeningSessionDao(), stamps) { now }

    /** [n] sessions, one per hour, the newest updated at [newest]. */
    private fun sessions(n: Int, newest: Long = now) = (0 until n).map { i -> session("s$i", updatedAt = newest - i * hour) }

    private fun session(id: String, updatedAt: Long, listened: Double = 60.0, item: String? = "li_1", episode: String? = null) =
        UserListeningSessionDto(
            id = id, libraryItemId = item, episodeId = episode, mediaType = if (episode == null) "book" else "podcast",
            displayTitle = if (episode == null) "Buch" else "Folge", displayAuthor = "Autor", mediaMetadata = SessionMediaMetadataDto(if (episode == null) "Buch" else "Podcast"),
            duration = 3600.0, timeListening = listened, currentTime = 120.0, startedAt = updatedAt - 60_000, updatedAt = updatedAt,
        )

    private suspend fun rows(): List<ListeningSessionEntity> = db.listeningSessionDao().observeAll(s).first()

    @Test fun firstRun_fetchesEveryPage_writesEachPage_andStampsTheNewest() = runTest {
        server.sessions = sessions(250)

        sync().sync(s)

        assertEquals(listOf(100 to 0, 100 to 1, 100 to 2), server.asked)
        assertEquals(250, rows().size)
        assertEquals(now, stamps.get("listening_sessions:$s"))
        assertEquals(now, stamps.get("listening_sessions_run:$s"))
    }

    @Test fun laterRun_nothingChanged_onlyAsksForOneSession() = runTest {
        server.sessions = sessions(250)
        sync().sync(s)
        server.asked.clear()
        now += hour

        sync().sync(s)

        assertEquals(listOf(1 to 0), server.asked)
        assertEquals(now, stamps.get("listening_sessions_run:$s"))
    }

    @Test fun laterRun_stopsOnceAPageReachesThe24HourOverlap_andUpdatesAGrowingSession() = runTest {
        server.sessions = sessions(250)
        sync().sync(s)
        val firstNewest = now
        server.asked.clear()
        // Two new sessions, and s5 (5 h old) grew on another device: it is now the newest.
        now += 2 * hour
        server.sessions = server.sessions.map { if (it.id == "s5") it.copy(timeListening = 999.0, updatedAt = now) else it } +
            listOf(session("n1", now - 60_000), session("n2", now - 120_000))

        sync().sync(s)

        // Probe, then pages of 20 until one holds a session updated before (last newest − 24 h): page 1 does
        // (the sessions are one hour apart), so page 2 is never asked for.
        assertEquals(listOf(1 to 0, 20 to 0, 20 to 1), server.asked)
        assertTrue(server.sessions.sortedByDescending { it.updatedAt }.take(40).any { it.updatedAt < firstNewest - 24 * hour })
        val all = rows()
        assertEquals(252, all.size) // grown, not duplicated
        assertEquals(999.0, all.single { it.id == "s5" }.timeListeningSec, 0.0)
        assertEquals(now, stamps.get("listening_sessions:$s"))
    }

    @Test fun laterRun_aSessionUploadedLate_isFoundThroughTheTotal() = runTest {
        server.sessions = sessions(30)
        sync().sync(s)
        server.asked.clear()
        // Another device uploads an offline session from 3 hours before the newest one: same newest, one more in total.
        server.sessions = server.sessions + session("late", now - 3 * hour - 1)
        now += hour

        sync().sync(s)

        assertEquals(listOf(1 to 0, 20 to 0, 20 to 1), server.asked)
        assertTrue(rows().any { it.id == "late" })
    }

    @Test fun aFailingPage_keepsWhatArrived_butNoStamp_soTheNextRunStartsOver() = runTest {
        server.sessions = sessions(250)
        server.failPage = 1

        try {
            sync().sync(s)
            fail("the failure must reach the caller")
        } catch (_: IOException) {
        }

        assertEquals(100, rows().size)
        assertNull(stamps.get("listening_sessions:$s"))
        assertNull(stamps.get("listening_sessions_run:$s"))
        server.failPage = null
        server.asked.clear()
        sync().sync(s)
        assertEquals(listOf(100 to 0, 100 to 1, 100 to 2), server.asked)
        assertEquals(250, rows().size)
    }

    @Test fun offline_orTheDeviceLibrary_doNothing() = runTest {
        server.sessions = sessions(5)

        sync(api = null).sync(s)
        sync().sync(DeviceLibrary.SERVER_ID)

        assertEquals(emptyList<Pair<Int, Int>>(), server.asked)
        assertNull(stamps.get("listening_sessions_run:$s"))
        assertEquals(0, db.listeningSessionDao().observeAll(DeviceLibrary.SERVER_ID).first().size)
    }

    @Test fun syncIfDue_runsAtMostEvery30Minutes_aFailureDoesNotCount() = runTest {
        server.sessions = sessions(3)
        server.failPage = 0
        runCatching { sync().syncIfDue(s) }
        server.failPage = null
        server.asked.clear()

        sync().syncIfDue(s) // the failed run did not count: it runs
        assertEquals(listOf(100 to 0), server.asked)
        server.asked.clear()
        now += 29 * 60_000L
        sync().syncIfDue(s)
        assertEquals(emptyList<Pair<Int, Int>>(), server.asked)
        now += 2 * 60_000L
        sync().syncIfDue(s)
        assertEquals(listOf(1 to 0), server.asked)
    }

    @Test fun rows_carryTheItemKey_titleAndTimes_episodesUnderTheirPodcast() = runTest {
        server.sessions = listOf(
            session("b", now, listened = 42.0),
            session("e", now - 1, item = "li_pod", episode = "ep_1"),
            session("gone", now - 2, item = null),
        )

        sync().sync(s)

        val byId = rows().associateBy { it.id }
        assertEquals(ListeningSessionEntity(s, "b", "li_1", "Buch", "Autor", now - 60_000, now, 42.0, 3600.0, 120.0), byId["b"])
        assertEquals("li_pod~ep_1", byId.getValue("e").itemKey)
        assertEquals("Podcast", byId.getValue("e").title) // the podcast, not the episode: the statistics count items
        assertEquals("", byId.getValue("gone").itemKey)
    }
}
