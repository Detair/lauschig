package io.wolftown.lauschig.ui.extras

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.history.HistoryRepository
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.sync.SyncTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Recorder : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    val calls = mutableListOf<String>()
    override fun play(serverId: String, itemId: String, startSec: Double?) { calls += "play $itemId $startSec" }
    override fun togglePlayPause() { calls += "toggle" }
    override fun pause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) { calls += "seek $globalSec" }
    override fun seekToAndPlay(globalSec: Double) { calls += "seekAndPlay $globalSec" }
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TitleExtrasViewModelTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val player = Recorder()
    private val triggers = mutableListOf<String>()
    private val sync = object : SyncTrigger {
        override fun syncNow() { triggers += "full" }
        override fun syncBookmarks() { triggers += "bookmarks" }
    }
    private val viewModels = TestViewModels()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun vm(): TitleExtrasViewModel {
        val store = FakeAccountStore()
        store.acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet())
        return viewModels.own(
            TitleExtrasViewModel(
                store, db.bookmarkDao(), HistoryRepository(flowOf(null), db.historyDao(), db.sessionDao()), db.queueDao(),
                db.itemDao(), db.episodeDao(), player, flowOf(ConnectionState.Offline), sync,
            ),
        )
    }

    private fun settle() = runBlocking { kotlinx.coroutines.delay(200) }

    @Test fun jump_loadedTitle_seeksAndPlays_otherTitle_startsThere() {
        val vm = vm()
        player.state.value = PlayerUiState(itemId = "b1")
        vm.jumpTo("b1", 125.0)
        vm.jumpTo("b2", 30.0)
        settle()
        assertEquals(listOf("seekAndPlay 125.0", "play b2 30.0"), player.calls)
    }

    @Test fun bookmarkChanges_triggerTheBookmarkSyncOnly_andAddKeepsAnExistingNote() {
        val vm = vm()
        vm.addBookmark("b1", 60.7, "Alt")
        settle()
        vm.addBookmark("b1", 60.2, null)
        settle()
        val row = runBlocking { db.bookmarkDao().get(s, "b1", 60.0)!! }
        assertEquals("Alt", row.note)
        vm.editBookmark(row, null)
        settle()
        vm.deleteBookmark(row)
        settle()
        assertEquals(listOf("bookmarks", "bookmarks", "bookmarks", "bookmarks"), triggers)
    }

    /** "Play from the queue" picks the title for the automatic clean-up before it leaves the queue (no unprotected gap). */
    @Test fun playFromQueue_picksTheTitle_beforeItLeavesTheQueue() = runBlocking {
        io.wolftown.lauschig.cleanup.TitleHandover.reset()
        db.queueDao().add(s, "c", 1)
        var pickedWhenGone: Boolean? = null
        val watch = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            db.queueDao().observe(s).first { rows -> rows.none { it.itemKey == "c" } }
            pickedWhenGone = io.wolftown.lauschig.cleanup.TitleHandover.state.value.picked.containsKey("c")
        }
        vm().playFromQueue("c")
        kotlinx.coroutines.withTimeout(5_000) { while (pickedWhenGone == null) kotlinx.coroutines.delay(10) }
        watch.cancel()
        assertEquals(true, pickedWhenGone)
        assertEquals(listOf("play c null"), player.calls)
        io.wolftown.lauschig.cleanup.TitleHandover.reset()
    }
}
