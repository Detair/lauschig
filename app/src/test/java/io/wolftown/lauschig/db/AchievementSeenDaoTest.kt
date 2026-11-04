package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.entities.AchievementSeenEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which achievement tiers an account was told about. */
@RunWith(RobolectricTestRunner::class)
class AchievementSeenDaoTest {
    private lateinit var db: LauschigDatabase
    private val s = "srv"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun insert_keepsTheFirstSeenTime_perServerAndTier() = runTest {
        val dao = db.achievementSeenDao()
        dao.insertAll(listOf(AchievementSeenEntity(s, "hours", 1, seenAt = 10), AchievementSeenEntity("other", "hours", 1, seenAt = 11)))
        // Told again (a second screen, a retry): the first time stays; a new tier is a new row.
        dao.insertAll(listOf(AchievementSeenEntity(s, "hours", 1, seenAt = 99), AchievementSeenEntity(s, "hours", 2, seenAt = 20)))

        assertEquals(listOf(1 to 10L, 2 to 20L), dao.observeAll(s).first().sortedBy { it.tier }.map { it.tier to it.seenAt })
        assertEquals(listOf(11L), dao.observeAll("other").first().map { it.seenAt })
    }

    @Test fun observeAll_emitsAgainAfterAWrite() = runTest {
        val dao = db.achievementSeenDao()
        assertEquals(emptyList<AchievementSeenEntity>(), dao.observeAll(s).first())
        dao.insertAll(listOf(AchievementSeenEntity(s, AchievementSeenEntity.BASELINE_ID, 0, seenAt = 1)))
        assertEquals(listOf(AchievementSeenEntity.BASELINE_ID), dao.observeAll(s).first().map { it.id })
    }
}
