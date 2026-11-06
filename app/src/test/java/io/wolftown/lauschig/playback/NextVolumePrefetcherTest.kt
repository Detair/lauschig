package io.wolftown.lauschig.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.settings.AppSettings
import io.wolftown.lauschig.settings.NextVolumeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task 1.7: the next volume is queued for the account whose title reached the threshold, never for the next one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NextVolumePrefetcherTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private fun account(id: String) = Account(id, "u", "n", false, ServerAddresses(null, null), emptySet())

    private val player = object : PlayerController {
        override val state = MutableStateFlow(PlayerUiState())
        override fun play(serverId: String, itemId: String, startSec: Double?) {}
        override fun togglePlayPause() {}
        override fun pause() {}
        override fun seekBy(deltaSec: Int) {}
        override fun seekTo(globalSec: Double) {}
        override fun seekToChapter(index: Int) {}
        override fun setSpeed(speed: Float) {}
    }
    private val queued = mutableListOf<String>()
    private val downloads = object : DownloadManager {
        override suspend fun enqueue(serverId: String, itemId: String, network: NetworkType?) { queued += "$serverId:$itemId" }
        override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
        override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = flowOf(null)
    }

    @After fun tearDown() = db.close()

    private suspend fun series(server: String) {
        db.itemDao().upsertFromList(
            listOf(
                ItemEntity(server, "vol1", "l", "Eins", seriesName = "Saga", seriesSequence = "1", seriesSortKey = 1.0),
                ItemEntity(server, "vol2", "l", "Zwei", seriesName = "Saga", seriesSequence = "2", seriesSortKey = 2.0),
            ),
        )
    }

    private val store = FakeAccountStore().apply { acc.value = account(a) }
    private val asked = CompletableDeferred<Unit>()
    private val answer = CompletableDeferred<Unit>()

    /** The prefetcher's read of the account: it says when it got there and answers when the test says so. */
    private val slow = object : AccountStore by store {
        override val account: Flow<Account?> = flow {
            asked.complete(Unit)
            answer.await()
            emit(store.acc.value)
        }
    }

    private fun start(scope: CoroutineScope) = runBlocking {
        val settings = AppSettings(ctx).apply { setNextVolumeMode(NextVolumeMode.PREFETCH) }
        NextVolumePrefetcher(player, settings, slow, db.itemDao(), db.downloadDao(), downloads).start(scope)
        player.state.value = PlayerUiState(itemId = "vol1", positionSec = 90.0, durationSec = 100.0)
        withTimeout(5_000) { asked.await() }
    }

    private fun awaitQueued(n: Int) = runBlocking { withTimeout(5_000) { while (queued.size < n) kotlinx.coroutines.delay(10) } }

    @Test fun aTitleOverTheThreshold_queuesItsNextVolume_forItsAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking { series(a) }
        start(scope)
        answer.complete(Unit)
        awaitQueued(1)
        assertEquals(listOf("$a:vol2"), queued)
        scope.cancel()
    }

    @Test fun aTitleThatLeftThePlayerWhileItsAccountWasRead_queuesNothingForTheNextAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking { series(a); series(b) }
        start(scope)

        // The switch: the player is emptied, then the next account is active.
        player.state.value = PlayerUiState()
        store.acc.value = account(b)
        answer.complete(Unit)
        Thread.sleep(300)

        assertEquals(emptyList<String>(), queued)
        scope.cancel()
    }
}
