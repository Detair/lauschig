package io.wolftown.lauschig.ui.extras

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.BookmarkDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.QueueDao
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.history.History
import io.wolftown.lauschig.history.HistoryRepository
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.sync.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.floor

/** One queued title with what the list shows. */
@Immutable
data class QueueRow(val key: String, val title: String, val subtitle: String?)

/** Bookmarks, listening history and the queue for the player sheets and the detail page; all from Room. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TitleExtrasViewModel @Inject constructor(
    accountStore: AccountStore,
    private val bookmarks: BookmarkDao,
    private val history: HistoryRepository,
    private val queue: QueueDao,
    private val items: ItemDao,
    private val episodes: EpisodeDao,
    private val player: PlayerController,
    private val api: ApiProvider,
    private val sync: SyncTrigger,
) : ViewModel() {
    private val serverId: StateFlow<String?> = accountStore.account.map { it?.serverId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val online: Flow<Boolean> = api.connection.map { it is ConnectionState.Online }.distinctUntilChanged()

    fun bookmarks(key: String?): Flow<List<BookmarkEntity>> =
        if (key == null) flowOf(emptyList())
        else serverId.flatMapLatest { sid -> if (sid == null) flowOf(emptyList()) else bookmarks.observeForKey(sid, key) }

    fun history(key: String?): Flow<History> =
        if (key == null) flowOf(History(emptyList(), 0.0))
        else serverId.flatMapLatest { sid -> if (sid == null) flowOf(History(emptyList(), 0.0)) else history.observe(sid, key) }

    /** Fetches the server sessions of [key] once online; a failure keeps the cache (offline shows what is stored). */
    fun refreshHistory(key: String) = withServer { sid ->
        val on = api.connection.first() is ConnectionState.Online
        if (!on) return@withServer
        try {
            history.refresh(sid, key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep the cache
        }
    }

    /** New bookmarks sit on whole seconds (like the ABS web client). */
    fun addBookmark(key: String, positionSec: Double, note: String?) = withServer { sid ->
        bookmarks.save(sid, key, floor(positionSec), note, System.currentTimeMillis())
        sync.syncNow()
    }

    fun editBookmark(row: BookmarkEntity, note: String?) = withServer { sid ->
        bookmarks.save(sid, row.itemKey, row.timeSec, note, System.currentTimeMillis())
        sync.syncNow()
    }

    fun deleteBookmark(row: BookmarkEntity) = withServer { sid ->
        bookmarks.delete(sid, row.itemKey, row.timeSec)
        sync.syncNow()
    }

    /** Seeks if [key] is loaded, otherwise plays it from [sec]. */
    fun jumpTo(key: String, sec: Double) = withServer { sid ->
        if (player.state.value.itemId == key) player.seekTo(sec) else player.play(sid, key, sec)
    }

    val queueRows: StateFlow<List<QueueRow>> = serverId.flatMapLatest { sid ->
        if (sid == null) flowOf(emptyList())
        else queue.observe(sid).map { rows -> rows.map { describe(sid, it.itemKey) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun describe(sid: String, key: String): QueueRow {
        val p = PlayableKey.parse(key)
        val parent = items.get(sid, p.libraryItemId)
        if (p.episodeId == null) return QueueRow(key, parent?.title ?: key, parent?.author)
        val ep = episodes.get(sid, p.libraryItemId, p.episodeId)
        return QueueRow(key, ep?.title ?: key, parent?.title)
    }

    fun enqueue(key: String) = withServer { sid -> queue.add(sid, key, System.currentTimeMillis()) }
    fun removeFromQueue(key: String) = withServer { sid -> queue.remove(sid, key) }
    fun moveInQueue(key: String, delta: Int) = withServer { sid -> queue.move(sid, key, delta) }
    fun clearQueue() = withServer { sid -> queue.clear(sid) }

    /** Plays a queued title now; it leaves the queue. */
    fun playFromQueue(key: String) = withServer { sid ->
        queue.remove(sid, key)
        player.play(sid, key)
    }

    private fun withServer(block: suspend (String) -> Unit) {
        viewModelScope.launch { block(serverId.filterNotNull().first()) }
    }
}
