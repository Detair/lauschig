package io.wolftown.lauschig.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.MediaProgressDto
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.ui.common.ProgressEntry
import io.wolftown.lauschig.ui.common.progressEntries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class RecordingPlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    val seeks = mutableListOf<Double>()
    var toggles = 0
    override fun play(serverId: String, itemId: String, startSec: Double?) {}
    override fun togglePlayPause() { toggles++; state.value = state.value.copy(isPlaying = !state.value.isPlaying) }
    override fun pause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) { seeks += globalSec }
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

private class ServerMe(var rows: List<MediaProgressDto>) : MeApi {
    override suspend fun me() = AbsUser("u", "n", "user", mediaProgress = rows)
    override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
    override suspend fun updateProgress(itemId: String, u: ProgressUpdate) {}
    override suspend fun syncLocalSessions(body: LocalSessionsBody) = LocalSessionsResult(emptyList())
}

@RunWith(RobolectricTestRunner::class)
class ProgressActionsTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val player = RecordingPlayer()
    private var syncs = 0
    private val sync = object : SyncTrigger { override fun syncNow() { syncs++ } }
    private var now = 10_000L
    private val me = ServerMe(emptyList())

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun actions(api: MeApi? = me) = ProgressActions(db.progressDao(), db.itemDao(), db.episodeDao(), player, flowOf(api), sync, clock = { now }, settleMs = 0)

    private suspend fun seed(progress: ProgressEntity? = null) {
        db.itemDao().upsertDetails(ItemEntity(s, "b1", "l", "Book", durationSec = 3600.0))
        progress?.let { db.progressDao().saveLocal(it.serverId, it.itemId, it.currentTimeSec, it.durationSec, it.isFinished, it.lastUpdate) }
    }

    @Test fun markFinished_withoutProgress_writesFinishedDirtyAndSyncs() = runTest {
        seed()
        actions().markFinished(s, "b1")
        val p = db.progressDao().get(s, "b1")!!
        assertTrue(p.isFinished)
        assertEquals(3600.0, p.currentTimeSec, 0.0)
        assertTrue(p.dirty)
        assertEquals(now, p.lastUpdate)
        assertEquals(1, syncs)
    }

    @Test fun markFinished_whilePlaying_pausesFirst() = runTest {
        seed(ProgressEntity(s, "b1", 100.0, 3600.0, false, 1))
        player.state.value = PlayerUiState(itemId = "b1", isPlaying = true)
        actions().markFinished(s, "b1")
        assertEquals(1, player.toggles)
        assertTrue(db.progressDao().get(s, "b1")!!.isFinished)
    }

    @Test fun startOver_resetsToZero_andSeeksLoadedPlayer() = runTest {
        seed(ProgressEntity(s, "b1", 3600.0, 3600.0, true, 1))
        player.state.value = PlayerUiState(itemId = "b1", isPlaying = false)
        actions().startOver(s, "b1")
        val p = db.progressDao().get(s, "b1")!!
        assertFalse(p.isFinished)
        assertEquals(0.0, p.currentTimeSec, 0.0)
        assertEquals(listOf(0.0), player.seeks)
    }

    @Test fun hideFromContinue_keepsPosition_andListeningAgainUnhides() = runTest {
        seed(ProgressEntity(s, "b1", 500.0, 3600.0, false, 1))
        actions().hideFromContinue(s, "b1")
        val p = db.progressDao().get(s, "b1")!!
        assertTrue(p.hideFromContinue)
        assertEquals(500.0, p.currentTimeSec, 0.0)
        db.progressDao().saveLocal(s, "b1", 510.0, 3600.0, false, now + 1)
        assertFalse(db.progressDao().get(s, "b1")!!.hideFromContinue)
    }

    @Test fun pullFromServer_overridesNewerLocal_andSeeksPausedPlayer() = runTest {
        seed(ProgressEntity(s, "b1", 900.0, 3600.0, false, 50_000))
        me.rows = listOf(MediaProgressDto("b1", null, 300.0, 3600.0, 300.0 / 3600, false, 20_000))
        player.state.value = PlayerUiState(itemId = "b1", isPlaying = false)
        assertTrue(actions().pullFromServer(s, "b1"))
        val p = db.progressDao().get(s, "b1")!!
        assertEquals(300.0, p.currentTimeSec, 0.0)
        assertFalse(p.dirty)
        assertEquals(listOf(300.0), player.seeks)
    }

    @Test fun pullFromServer_offlineOrMissing_returnsFalse() = runTest {
        seed(ProgressEntity(s, "b1", 900.0, 3600.0, false, 50_000))
        assertFalse(actions(api = null).pullFromServer(s, "b1"))
        assertFalse(actions().pullFromServer(s, "b1"))
        assertEquals(900.0, db.progressDao().get(s, "b1")!!.currentTimeSec, 0.0)
    }

    private val key = PlayableKey.episode("pod", "ep1")
    private suspend fun seedEpisode() {
        db.itemDao().upsertDetails(ItemEntity(s, "pod", "l", "Podcast"))
        db.episodeDao().upsert(listOf(EpisodeEntity(s, "pod", "ep1", "Folge 1", durationSec = 900.0)))
    }

    @Test fun markFinished_episodeWithoutProgress_usesEpisodeDuration() = runTest {
        seedEpisode()
        actions().markFinished(s, key)
        val p = db.progressDao().get(s, key)!!
        assertTrue(p.isFinished)
        assertEquals(900.0, p.currentTimeSec, 0.0)
        assertEquals(900.0, p.durationSec, 0.0)
        assertTrue(p.dirty)
    }

    @Test fun pullFromServer_episodeKey_matchesEpisodeRowOnly() = runTest {
        seedEpisode()
        me.rows = listOf(
            MediaProgressDto("pod", null, 1.0, 900.0, 0.0, false, 20_000),
            MediaProgressDto("pod", "other", 2.0, 900.0, 0.0, false, 20_000),
            MediaProgressDto("pod", "ep1", 300.0, 900.0, 300.0 / 900, false, 20_000),
        )
        assertTrue(actions().pullFromServer(s, key))
        assertEquals(300.0, db.progressDao().get(s, key)!!.currentTimeSec, 0.0)
        assertNull(db.progressDao().get(s, "pod"))
    }

    @Test fun markFinished_stopsOnlyTheLoadedKey() = runTest {
        seedEpisode()
        // The player carries the playable key: an episode of the same podcast is not "the loaded title" for the podcast id.
        player.state.value = PlayerUiState(itemId = key, isPlaying = true)
        actions().markFinished(s, "pod")
        assertEquals(0, player.toggles)
        actions().markFinished(s, key)
        assertEquals(1, player.toggles)
    }

    @Test fun nextVolume_findsFollowingSequence_caseInsensitive() = runTest {
        val dao = db.itemDao()
        fun vol(id: String, key: Double?, name: String? = "Die Serie") =
            ItemEntity(s, id, "l", "T$id", durationSec = 1.0, seriesName = name, seriesSequence = key?.toString(), seriesSortKey = key)
        dao.upsertFromList(listOf(vol("v1", 1.0), vol("v3", 3.0, "die serie "), vol("v2", 2.0), vol("x", 5.0, "Andere"), vol("solo", null, null)))
        assertEquals("v2", nextVolume(dao, s, "v1")?.id)
        assertEquals("v3", nextVolume(dao, s, "v2")?.id)
        assertNull(nextVolume(dao, s, "v3"))
        assertNull(nextVolume(dao, s, "solo"))
        // Volumes already heard are skipped.
        db.progressDao().saveLocal(s, "v2", 1.0, 1.0, true, 1)
        assertEquals("v3", nextVolume(dao, s, "v1")?.id)
    }

    @Test fun progressEntries_dependOnState() {
        assertEquals(listOf(ProgressEntry.MarkFinished, ProgressEntry.PullFromServer), progressEntries(null, offline = false))
        val started = ProgressEntity(s, "b1", 10.0, 100.0, false, 1)
        assertEquals(listOf(ProgressEntry.MarkFinished, ProgressEntry.StartOver, ProgressEntry.HideFromContinue), progressEntries(started, offline = true))
        val finished = started.copy(isFinished = true)
        assertEquals(listOf(ProgressEntry.StartOver, ProgressEntry.PullFromServer), progressEntries(finished, offline = false))
        assertEquals(listOf(ProgressEntry.MarkFinished, ProgressEntry.StartOver), progressEntries(started.copy(hideFromContinue = true), offline = true))
    }
}
