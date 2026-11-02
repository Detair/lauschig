package io.wolftown.lauschig.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.StubMeApi
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.me.ListeningSessionDto
import io.wolftown.lauschig.absapi.me.ListeningSessionsPage
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class HistoryRepositoryTest {
    private lateinit var db: LauschigDatabase
    private val s = "srv"
    private val calls = mutableListOf<String>()
    private var fail = false
    private val api = object : StubMeApi() {
        override suspend fun me() = AbsUser("u", "n", "user")
        override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
        override suspend fun updateProgress(itemId: String, u: ProgressUpdate) = error("unused")
        override suspend fun syncLocalSessions(body: LocalSessionsBody): LocalSessionsResult = error("unused")
        override suspend fun itemListeningSessions(itemId: String, itemsPerPage: Int, page: Int): ListeningSessionsPage {
            calls += "item $itemId $itemsPerPage"
            if (fail) throw IOException("down")
            return ListeningSessionsPage(sessions = listOf(ListeningSessionDto("srv1", itemId, timeListening = 60.0, currentTime = 90.0, startedAt = 10, updatedAt = 20, deviceInfo = DeviceInfoDto(deviceName = "Web"))))
        }
        override suspend fun episodeListeningSessions(itemId: String, episodeId: String, itemsPerPage: Int, page: Int): ListeningSessionsPage {
            calls += "episode $itemId $episodeId"
            return ListeningSessionsPage()
        }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun repo(online: Boolean = true) = HistoryRepository(flowOf(if (online) api else null), db.historyDao(), db.sessionDao())

    @Test fun refresh_cachesServerSessions_andMergesLocalOnes() = runTest {
        db.sessionDao().upsert(LocalSessionEntity("loc", s, "li_1", 0.0, 30.0, 30.0, 50, 60))
        repo().refresh(s, "li_1")
        val h = repo(online = false).observe(s, "li_1").first()
        assertEquals(listOf("loc", "srv1"), h.entries.map { it.id })
        assertEquals("Web", h.entries[1].deviceName)
        assertEquals(90.0, h.entries[1].endSec, 0.0)
    }

    @Test fun refresh_episodeUsesEpisodeRoute_offlineIsNoop_failureKeepsCache() = runTest {
        repo().refresh(s, "pod~ep")
        assertEquals(listOf("episode pod ep"), calls)
        repo(online = false).refresh(s, "li_1")
        assertEquals(1, calls.size)
        repo().refresh(s, "li_1")
        fail = true
        assertTrue(runCatching { repo().refresh(s, "li_1") }.isFailure)
        assertEquals(1, repo().observe(s, "li_1").first().entries.size)
    }
}
