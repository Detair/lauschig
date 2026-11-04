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
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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
        /** Rows the server lists but Lauschig cannot read (counted in the total, dropped from the page). */
        var unreadable = 0
        /** Runs before a page is cut, e.g. to change a session while a run is paging. */
        var beforePage: (itemsPerPage: Int, page: Int) -> Unit = { _, _ -> }
        /** A server that sorts by `startedAt` instead (ABS's order is not a contract). */
        var byStart = false

        override suspend fun me() = AbsUser("u", "n", "user")
        override suspend fun updateProgress(itemId: String, u: ProgressUpdate) = error("unused")
        override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
        override suspend fun syncLocalSessions(body: LocalSessionsBody): LocalSessionsResult = error("unused")
        override suspend fun listeningSessions(itemsPerPage: Int, page: Int): UserListeningSessionsPage {
            asked += itemsPerPage to page
            if (page == failPage && itemsPerPage > 1) throw IOException("down")
            beforePage(itemsPerPage, page)
            val sorted = if (byStart) sessions.sortedByDescending { it.startedAt } else sessions.sortedByDescending { it.updatedAt }
            val total = sorted.size + unreadable
            val pages = (total + itemsPerPage - 1) / itemsPerPage
            return UserListeningSessionsPage(total, pages, page, itemsPerPage, sorted.drop(page * itemsPerPage).take(itemsPerPage))
        }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private var unmetered = true
    private val full = ListeningSessionSync.FULL_PAGE // 250
    private val later = ListeningSessionSync.LATER_PAGE // 20

    private fun sync(api: MeApi? = server, maxPages: Int = ListeningSessionSync.MAX_PAGES) =
        ListeningSessionSync(flowOf(api), db.listeningSessionDao(), db.sessionDao(), stamps, { unmetered }, { now }, maxPages)

    /** [n] sessions, one per hour, the newest updated at [newest]. */
    private fun sessions(n: Int, newest: Long = now) = (0 until n).map { i -> session("s$i", updatedAt = newest - i * hour) }

    private fun session(id: String, updatedAt: Long, listened: Double = 60.0, item: String? = "li_1", episode: String? = null, startedAt: Long = updatedAt - 60_000) =
        UserListeningSessionDto(
            id = id, libraryItemId = item, episodeId = episode, mediaType = if (episode == null) "book" else "podcast",
            displayTitle = if (episode == null) "Buch" else "Folge", displayAuthor = "Autor", mediaMetadata = SessionMediaMetadataDto(if (episode == null) "Buch" else "Podcast"),
            duration = 3600.0, timeListening = listened, currentTime = 120.0, startedAt = startedAt, updatedAt = updatedAt,
        )

    private suspend fun rows(): List<ListeningSessionEntity> = db.listeningSessionDao().observeAll(s).first()

    private suspend fun history() = sync().historyLoad(s).first()

    private suspend fun syncExpectingFailure(sync: ListeningSessionSync = sync()) {
        try {
            sync.sync(s)
            fail("the failure must reach the caller")
        } catch (_: IOException) {
        }
    }

    // --- full runs: unmetered, 250 per page, resumable -----------------------------------------------------------------

    @Test fun firstRun_onAnUnmeteredNetwork_readsEveryPage_writesEachPage_andStampsTheNewest() = runTest {
        server.sessions = sessions(600)

        sync().sync(s)

        assertEquals(listOf(full to 0, full to 1, full to 2), server.asked)
        assertEquals(600, rows().size)
        assertEquals(now, stamps.get("listening_sessions:$s"))
        assertEquals(now, stamps.get("listening_sessions_run:$s"))
    }

    @Test fun firstRun_onAMeteredNetwork_waitsForWifi_andSaysSo_withoutARequest() = runTest {
        server.sessions = sessions(600)
        unmetered = false
        val sync = sync()

        sync.sync(s)
        sync.syncIfDue(s) // the worker 15 minutes later: no run stamp was written, it looks again – still nothing to fetch

        assertEquals(emptyList<Pair<Int, Int>>(), server.asked)
        assertEquals(HistoryLoad.WAITING_FOR_UNMETERED, sync.historyLoad(s).first())
        assertNull(stamps.get("listening_sessions_run:$s"))
        unmetered = true
        sync.syncIfDue(s)
        assertEquals(600, rows().size)
        assertEquals(HistoryLoad.NONE, sync.historyLoad(s).first())
    }

    @Test fun whileAFullRunReadsPages_theHistoryIsLoading() = runTest {
        server.sessions = sessions(600)
        val sync = sync()
        val seen = mutableListOf<HistoryLoad>()
        server.beforePage = { _, _ -> seen += kotlinx.coroutines.runBlocking { sync.historyLoad(s).first() } }

        sync.sync(s)

        assertEquals(List(3) { HistoryLoad.LOADING }, seen)
        assertEquals(HistoryLoad.NONE, sync.historyLoad(s).first())
    }

    @Test fun aCancelledFullRun_continuesWhereItStopped_withOnePageOfOverlap() = runTest {
        server.sessions = sessions(1000) // 4 pages
        server.failPage = 2
        syncExpectingFailure()
        assertEquals(500, rows().size)
        assertNull(stamps.get("listening_sessions:$s")) // no stamps before the run is complete

        server.failPage = null
        server.asked.clear()
        sync().sync(s)

        assertEquals(listOf(full to 1, full to 2, full to 3), server.asked) // page 1 again, then on
        assertEquals(1000, rows().size)
        assertEquals(now, stamps.get("listening_sessions:$s"))
    }

    @Test fun aResumedRun_startsOver_whenTheTotalMovedByMoreThanAPage() = runTest {
        server.sessions = sessions(1000)
        server.failPage = 2
        syncExpectingFailure()
        server.failPage = null
        server.asked.clear()
        // 300 new sessions meanwhile: the rows of page 1 moved further than one page of overlap covers.
        server.sessions = server.sessions + (1..300).map { session("n$it", now + it * 1000L) }

        sync().sync(s)

        assertEquals(listOf(full to 1, full to 0, full to 1, full to 2, full to 3, full to 4, full to 5), server.asked)
        assertEquals(1300, rows().size)
    }

    @Test fun aCappedFullRun_movesNoStamps_andContinuesNextTime() = runTest {
        server.sessions = sessions(1000)

        sync(maxPages = 2).sync(s)
        assertEquals(500, rows().size)
        assertNull(stamps.get("listening_sessions:$s"))
        assertNull(stamps.get("listening_sessions_run:$s"))

        server.asked.clear()
        sync().sync(s)
        assertEquals(listOf(full to 1, full to 2, full to 3), server.asked)
        assertEquals(now, stamps.get("listening_sessions:$s"))
    }

    @Test fun anEmptyCache_isAFirstSync_whateverTheStampsSay() = runTest {
        server.sessions = sessions(30)
        sync().sync(s)
        // The rows went (a purge that left the stamps, a late write after sign-out), the stamps stayed.
        db.listeningSessionDao().delete(s, rows().map { it.id })
        server.asked.clear()
        now += hour

        sync().sync(s)

        assertEquals(listOf(full to 0), server.asked)
        assertEquals(30, rows().size)
    }

    @Test fun anAccountWithoutSessions_isNoFirstSyncForever() = runTest {
        sync().sync(s)
        unmetered = false
        server.asked.clear()
        now += hour

        sync().sync(s)

        assertEquals(listOf(1 to 0), server.asked) // just the probe, also on mobile data
        assertEquals(HistoryLoad.NONE, history())
    }

    // --- later runs: any network ---------------------------------------------------------------------------------------

    @Test fun laterRun_nothingChanged_onlyAsksForOneSession_alsoOnMobileData() = runTest {
        server.sessions = sessions(600)
        sync().sync(s)
        server.asked.clear()
        unmetered = false
        now += hour

        sync().sync(s)

        assertEquals(listOf(1 to 0), server.asked)
        assertEquals(now, stamps.get("listening_sessions_run:$s"))
    }

    @Test fun laterRun_stopsOnceAPageReachesThe24HourOverlap_andUpdatesAGrowingSession() = runTest {
        server.sessions = sessions(600)
        sync().sync(s)
        val firstNewest = now
        server.asked.clear()
        unmetered = false
        // Two new sessions, and s5 (5 h old) grew on another device: it is now the newest.
        now += 2 * hour
        server.sessions = server.sessions.map { if (it.id == "s5") it.copy(timeListening = 999.0, updatedAt = now) else it } +
            listOf(session("n1", now - 60_000), session("n2", now - 120_000))

        sync().sync(s)

        // Probe, then pages of 20 until one ends below (last newest − 24 h): page 1 does (one hour apart).
        assertEquals(listOf(1 to 0, later to 0, later to 1), server.asked)
        assertTrue(server.sessions.sortedByDescending { it.updatedAt }.take(40).any { it.updatedAt < firstNewest - 24 * hour })
        val all = rows()
        assertEquals(602, all.size) // grown, not duplicated
        assertEquals(999.0, all.single { it.id == "s5" }.timeListeningSec, 0.0)
        assertEquals(now, stamps.get("listening_sessions:$s"))
    }

    @Test fun laterRun_aSessionUploadedLateButWithinTheOverlap_isFound() = runTest {
        server.sessions = sessions(30)
        sync().sync(s)
        server.asked.clear()
        server.sessions = server.sessions + session("late", now - 3 * hour - 1)
        now += hour

        sync().sync(s)

        assertEquals(listOf(1 to 0, later to 0, later to 1), server.asked)
        assertTrue(rows().any { it.id == "late" })
    }

    @Test fun aServerThatDoesNotSortByUpdatedAt_getsAFullRead_insteadOfAnEarlyStop() = runTest {
        server.sessions = sessions(100)
        sync().sync(s)
        server.asked.clear()
        // The server now sorts by start: s5 (started 5 h before the newest) grew, and a new session arrived. Page 0
        // is not newest-updated first, so no page may be taken for "older than the overlap": everything is read.
        server.byStart = true
        now += hour
        server.sessions = server.sessions.map { if (it.id == "s5") it.copy(timeListening = 777.0, updatedAt = now) else it } +
            session("n1", now - 1000)

        sync().sync(s)

        assertEquals(listOf(1 to 0, later to 0, full to 0), server.asked)
        assertEquals(777.0, rows().single { it.id == "s5" }.timeListeningSec, 0.0)
    }

    @Test fun aCappedLaterRun_movesNoStamps() = runTest {
        server.sessions = sessions(100)
        sync().sync(s)
        val before = stamps.get("listening_sessions:$s")
        server.sessions = sessions(100, newest = now + 10 * hour) // everything changed
        now += 11 * hour

        sync(maxPages = 1).sync(s)

        assertEquals(before, stamps.get("listening_sessions:$s"))
        assertEquals(100L, stamps.get("listening_sessions_total:$s"))
    }

    // --- reconcile ------------------------------------------------------------------------------------------------------

    @Test fun anotherDevicesSessionUploadedDaysLate_bringsAFullRun_onWifi_atMostOncePerDay() = runTest {
        server.sessions = sessions(600)
        sync().sync(s)
        // Another device was offline for three days and uploads its session now: the server keeps its old updatedAt
        // (verified on ABS 2.37.1), so it sorts far below the 24 h overlap.
        server.sessions = server.sessions + session("late", now - 72 * hour - 1)
        now += hour
        server.asked.clear()

        sync().sync(s)
        assertEquals(listOf(1 to 0, later to 0, later to 1), server.asked) // the last full run was an hour ago: waits
        assertTrue(rows().none { it.id == "late" })

        now += 24 * hour
        unmetered = false
        server.asked.clear()
        sync().sync(s)
        assertEquals(listOf(1 to 0), server.asked) // due, but on mobile data: waits for Wi-Fi, quietly
        assertEquals(HistoryLoad.NONE, history())

        unmetered = true
        server.asked.clear()
        sync().sync(s)
        assertEquals(listOf(1 to 0, full to 0, full to 1, full to 2), server.asked)
        assertTrue(rows().any { it.id == "late" })

        server.asked.clear()
        now += hour
        sync().sync(s)
        assertEquals(listOf(1 to 0), server.asked)
    }

    @Test fun thisDevicesOwnSessionsUploadedLate_bringNoFullRun() = runTest {
        server.sessions = sessions(600)
        sync().sync(s)
        // This device was offline for two days; its sessions reach the server now with their old updatedAt. The
        // statistics count them from local_sessions already: nothing to fetch.
        val mine = (1..30).map { session("mine$it", now - 48 * hour - it * 60_000L) }
        mine.forEach { db.sessionDao().upsert(LocalSessionEntity(it.id, s, "li_1", 0.0, 120.0, 60.0, it.startedAt, it.updatedAt, synced = true)) }
        server.sessions = server.sessions + mine
        now += 25 * hour
        server.asked.clear()

        sync().sync(s)

        assertFalse(server.asked.any { it.first == full })
    }

    @Test fun deletionsOnTheServer_startNoFullRun_theNextFullRunDropsThem_keepingOnesThatMovedUp() = runTest {
        server.sessions = sessions(600)
        sync().sync(s)
        server.sessions = server.sessions.filterNot { it.id == "s400" }
        now += 25 * hour
        server.asked.clear()
        sync().sync(s)
        assertFalse(server.asked.any { it.first == full }) // fewer on the server: no full run for that
        assertEquals(600, rows().size)

        // A full run that happens anyway (another device's late upload) drops it. While it reads page 1, s100 is
        // listened to on another device and moves to the top (page 0, read already): not taken for deleted.
        server.sessions = server.sessions + session("late", now - 100 * hour) + session("late2", now - 101 * hour)
        server.beforePage = { size, page ->
            if (size == full && page == 1) server.sessions = server.sessions.map { if (it.id == "s100") it.copy(updatedAt = now, timeListening = 777.0) else it }
        }
        now += 25 * hour
        server.asked.clear()
        sync().sync(s)

        assertTrue(server.asked.containsAll(listOf(full to 0, full to 1, full to 2, later to 0)))
        val all = rows()
        assertTrue(all.none { it.id == "s400" })
        assertEquals(777.0, all.single { it.id == "s100" }.timeListeningSec, 0.0)
        assertEquals(601, all.size)
    }

    @Test fun unreadableRows_preventDropping_bringNoDailyFullRun_butAreRetriedAfterAWeek() = runTest {
        server.sessions = sessions(600)
        sync().sync(s)
        // A session the cache holds is now unreadable on the server (a format change): listed, but dropped from its page.
        server.sessions = server.sessions.filterNot { it.id == "s10" }
        server.unreadable = 1
        server.sessions = server.sessions + session("late", now - 200 * hour) // forces a full run
        now += 25 * hour
        server.asked.clear()
        sync().sync(s)
        assertTrue(server.asked.any { it.first == full })
        assertTrue(rows().any { it.id == "s10" }) // not taken for deleted: a row was unreadable

        server.asked.clear()
        now += 25 * hour
        sync().sync(s)
        assertEquals(listOf(1 to 0), server.asked) // no full run every day for it

        server.asked.clear()
        now += 7 * 24 * hour
        sync().sync(s)
        assertTrue(server.asked.any { it.first == full }) // retried after a week
    }

    @Test fun aWholePageThatCannotBeRead_dropsNothing() = runTest {
        server.sessions = sessions(30)
        sync().sync(s)
        // A server update makes every row unreadable for Lauschig: the pages come back empty, the total stays.
        val all = server.sessions
        server.sessions = emptyList()
        server.unreadable = 31
        now += 8 * 24 * hour
        sync().sync(s)

        assertEquals(30, rows().size)
        server.sessions = all
        server.unreadable = 0
    }

    // --- general -------------------------------------------------------------------------------------------------------

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
        assertEquals(listOf(full to 0), server.asked)
        server.asked.clear()
        now += 29 * 60_000L
        sync().syncIfDue(s)
        assertEquals(emptyList<Pair<Int, Int>>(), server.asked)
        now += 2 * 60_000L
        sync().syncIfDue(s)
        assertEquals(listOf(1 to 0), server.asked)
    }

    @Test fun everyStampKey_endsWithTheServerId_andGoesWithTheAccount() = runTest {
        server.sessions = sessions(1000)
        server.failPage = 3 // leaves a resume place
        syncExpectingFailure()
        server.failPage = null
        sync().sync(s)
        server.unreadable = 1
        sync(maxPages = 1).sync(s) // whatever runs

        val keys = ListeningSessionSync.keys(s)
        assertEquals(7, keys.size)
        assertTrue(keys.all { it.endsWith(":$s") })
        assertTrue(keys.count { stamps.get(it) != null } >= 6)
        stamps.removeServer(s)
        assertTrue(keys.all { stamps.get(it) == null })
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
