package io.wolftown.lauschig.ui.extras

import io.wolftown.lauschig.local.DeviceLibrary
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.cleanup.TitleHandover
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.floor

/** One queued title with what the list shows. */
@Immutable
data class QueueRow(val key: String, val title: String, val subtitle: String?)

/** Bookmarks, listening history and the queue for the player sheets and the detail page; all from Room. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TitleExtrasViewModel(
    accountStore: AccountStore,
    private val bookmarks: BookmarkDao,
    private val history: HistoryRepository,
    private val queue: QueueDao,
    private val items: ItemDao,
    private val episodes: EpisodeDao,
    private val player: PlayerController,
    private val connection: Flow<ConnectionState>,
    private val sync: SyncTrigger,
) : ViewModel() {
    @Inject constructor(
        accountStore: AccountStore,
        bookmarks: BookmarkDao,
        history: HistoryRepository,
        queue: QueueDao,
        items: ItemDao,
        episodes: EpisodeDao,
        player: PlayerController,
        api: ApiProvider,
        sync: SyncTrigger,
    ) : this(accountStore, bookmarks, history, queue, items, episodes, player, api.connection, sync)

    private val serverId: StateFlow<String?> = accountStore.account.map { it?.serverId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val online: Flow<Boolean> = connection.map { it is ConnectionState.Online }.distinctUntilChanged()

    /** The server [key] lives on: the device library for a local title, else the account's. */
    private fun serverOf(key: String): Flow<String?> = if (DeviceLibrary.isLocalKey(key)) flowOf(DeviceLibrary.SERVER_ID) else serverId

    fun bookmarks(key: String?): Flow<List<BookmarkEntity>> =
        if (key == null) flowOf(emptyList())
        else serverOf(key).flatMapLatest { sid -> if (sid == null) flowOf(emptyList()) else bookmarks.observeForKey(sid, key) }

    fun history(key: String?): Flow<History> =
        if (key == null) flowOf(History(emptyList(), 0.0))
        else serverOf(key).flatMapLatest { sid -> if (sid == null) flowOf(History(emptyList(), 0.0)) else history.observe(sid, key) }

    /**
     * Fetches the server sessions of [key] if the server comes online within a few seconds (a cold process starts
     * with a placeholder Offline); a failure keeps the cache, offline shows what is stored.
     */
    fun refreshHistory(key: String) = withServer { sid ->
        if (DeviceLibrary.isLocalKey(key)) return@withServer // this device's sessions are all there is
        withTimeoutOrNull(ONLINE_WAIT_MS) { connection.first { it is ConnectionState.Online } } ?: return@withServer
        try {
            history.refresh(sid, key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep the cache
        }
    }

    /**
     * New bookmarks sit on whole seconds (like the ABS web client); one on the same second keeps its note (see
     * `BookmarkRules.add`). Every change pushes the bookmarks only, not the whole library.
     */
    fun addBookmark(key: String, positionSec: Double, note: String?) = withServer(key) { sid ->
        bookmarks.add(sid, key, floor(positionSec), note, System.currentTimeMillis())
        pushBookmarks(key)
    }

    fun editBookmark(row: BookmarkEntity, note: String?) = withServer(row.itemKey) { sid ->
        bookmarks.save(sid, row.itemKey, row.timeSec, note, System.currentTimeMillis())
        pushBookmarks(row.itemKey)
    }

    fun deleteBookmark(row: BookmarkEntity) = withServer(row.itemKey) { sid ->
        bookmarks.delete(sid, row.itemKey, row.timeSec)
        pushBookmarks(row.itemKey)
    }

    /** Device titles' bookmarks never leave the device: nothing to push. */
    private fun pushBookmarks(key: String) {
        if (!DeviceLibrary.isLocalKey(key)) sync.syncBookmarks()
    }

    /** Plays [key] from [sec]: seeks and resumes if it is loaded, otherwise starts it there. */
    fun jumpTo(key: String, sec: Double) = withServer(key) { sid ->
        if (player.state.value.itemId == key) player.seekToAndPlay(sec) else player.play(sid, key, sec)
    }

    val queueRows: StateFlow<List<QueueRow>> = serverId.flatMapLatest { sid ->
        if (sid == null) flowOf(emptyList())
        else queue.observe(sid).map { rows -> rows.map { describe(sid, it.itemKey) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun describe(queueOwner: String, key: String): QueueRow {
        val sid = DeviceLibrary.serverFor(queueOwner, key) ?: queueOwner
        val p = PlayableKey.parse(key)
        val parent = items.get(sid, p.libraryItemId)
        val episodeId = p.episodeId ?: return QueueRow(key, parent?.title ?: key, parent?.author)
        val ep = episodes.get(sid, p.libraryItemId, episodeId)
        return QueueRow(key, ep?.title ?: key, parent?.title)
    }

    fun enqueue(key: String) = withServer { sid -> queue.add(sid, key, System.currentTimeMillis()) }
    fun removeFromQueue(key: String) = withServer { sid -> queue.remove(sid, key) }
    fun moveInQueue(key: String, delta: Int) = withServer { sid -> queue.move(sid, key, delta) }
    fun clearQueue() = withServer { sid -> queue.clear(sid) }

    /** Plays a queued title now; it leaves the queue. */
    fun playFromQueue(key: String) = withServer { sid ->
        TitleHandover.pick(key) // never unprotected between leaving the queue and being loaded
        queue.remove(sid, key)
        player.play(DeviceLibrary.serverFor(sid, key) ?: sid, key)
    }

    /** The account's server (the queue's namespace). */
    private fun withServer(block: suspend (String) -> Unit) {
        viewModelScope.launch { block(serverId.filterNotNull().first()) }
    }

    /** The server of [key] (see [serverOf]). */
    private fun withServer(key: String, block: suspend (String) -> Unit) {
        viewModelScope.launch { block(serverOf(key).filterNotNull().first()) }
    }

    private companion object {
        const val ONLINE_WAIT_MS = 10_000L
    }
}
