package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The account's listening sessions for the statistics (server copy) and this device's own sessions of an account. */
@RunWith(RobolectricTestRunner::class)
class ListeningSessionDaoTest {
    private lateinit var db: LauschigDatabase
    private val s = "srv"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun row(id: String, server: String = s, updatedAt: Long = 2, listened: Double = 10.0) =
        ListeningSessionEntity(server, id, "li_1", "Titel", "Autor", startedAt = 1, updatedAt = updatedAt, timeListeningSec = listened, durationSec = 100.0, currentTimeSec = 20.0)

    @Test fun upsert_growingSessionReplacesItsRow_perServer() = runTest {
        val dao = db.listeningSessionDao()
        dao.upsertAll(listOf(row("a"), row("b"), row("a", server = "other")))
        // The same session again, grown on the server: one row, the new values.
        dao.upsertAll(listOf(row("a", updatedAt = 9, listened = 55.0)))

        val mine = dao.observeAll(s).first().sortedBy { it.id }
        assertEquals(listOf("a", "b"), mine.map { it.id })
        assertEquals(55.0, mine[0].timeListeningSec, 0.0)
        assertEquals(9L, mine[0].updatedAt)
        assertEquals(listOf("a"), dao.observeAll("other").first().map { it.id })
    }

    @Test fun observeAll_emitsAgainAfterAWrite() = runTest {
        val dao = db.listeningSessionDao()
        assertEquals(emptyList<ListeningSessionEntity>(), dao.observeAll(s).first())
        dao.upsertAll(listOf(row("a")))
        assertEquals(1, dao.observeAll(s).first().size)
    }

    @Test fun countIdsAndDelete_onlyTouchThisServer() = runTest {
        val dao = db.listeningSessionDao()
        dao.upsertAll(listOf(row("a"), row("b"), row("c"), row("a", server = "other")))

        assertEquals(3, dao.count(s))
        assertEquals(listOf("a", "b", "c"), dao.ids(s).sorted())
        dao.delete(s, listOf("a", "c"))

        assertEquals(listOf("b"), dao.ids(s))
        assertEquals(listOf("a"), dao.ids("other"))
    }

    @Test fun localSessions_observeAll_onlyThisServer() = runTest {
        val dao = db.sessionDao()
        dao.upsert(LocalSessionEntity("l1", s, "li_1", 0.0, 5.0, 5.0, 1, 2, synced = false))
        dao.upsert(LocalSessionEntity("l2", s, "li_2", 0.0, 5.0, 5.0, 1, 2, synced = true))
        dao.upsert(LocalSessionEntity("l3", "device", "local:x", 0.0, 5.0, 5.0, 1, 2))

        assertEquals(listOf("l1", "l2"), dao.observeAll(s).first().map { it.id }.sorted())
    }
}
