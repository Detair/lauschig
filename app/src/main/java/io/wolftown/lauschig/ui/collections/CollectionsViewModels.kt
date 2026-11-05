package io.wolftown.lauschig.ui.collections

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.CollectionDao
import io.wolftown.lauschig.db.dao.CollectionWithCount
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.QueueDao
import io.wolftown.lauschig.db.entities.CollectionEntity
import io.wolftown.lauschig.db.entities.CollectionEntryEntity
import io.wolftown.lauschig.db.entities.CollectionKind
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.sync.CollectionSync
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.toSummary
import io.wolftown.lauschig.ui.nav.Collection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The two Library tabs "Collections" / "Playlists" from the Room cache; refreshed once per screen when online. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectionListViewModel @Inject constructor(
    accountStore: AccountStore,
    private val dao: CollectionDao,
    private val sync: CollectionSync,
    private val api: ApiProvider,
) : ViewModel() {
    private val serverId = accountStore.account.map { it?.serverId }.distinctUntilChanged()
    private var refreshed = false

    fun lists(kind: CollectionKind): Flow<List<CollectionWithCount>?> =
        serverId.flatMapLatest { sid -> if (sid == null) flowOf(emptyList()) else dao.observeByKind(sid, kind) }

    /** One background refresh per screen instance, as soon as the server is online; failures keep the cache. */
    fun refreshOnce() {
        if (refreshed) return
        refreshed = true
        viewModelScope.launch {
            val sid = serverId.first() ?: return@launch
            api.connection.filterIsInstance<ConnectionState.Online>().first()
            try {
                sync.sync(sid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep what is cached
            }
        }
    }
}

/** One title of a collection page: a book (cover tile) or a podcast episode (row). */
@Immutable
sealed interface CollectionEntryUi {
    val key: String

    data class Book(val summary: ItemSummary) : CollectionEntryUi {
        override val key get() = summary.id
    }

    data class Episode(
        override val key: String,
        val title: String,
        val podcast: String?,
        val durationSec: Double,
        val downloaded: Boolean,
        val finished: Boolean,
    ) : CollectionEntryUi
}

@Immutable
data class CollectionUiState(
    val found: Boolean = false,
    val loaded: Boolean = false,
    val kind: CollectionKind = CollectionKind.COLLECTION,
    val name: String = "",
    val description: String? = null,
    val entries: List<CollectionEntryUi> = emptyList(),
    /** Entries of the server list that are not in the library on this device (yet). */
    val missing: Int = 0,
)

/** Builds the page entries from the cached list; unknown titles are counted as [CollectionUiState.missing]. */
internal suspend fun collectionEntries(
    serverId: String,
    entries: List<CollectionEntryEntity>,
    items: ItemDao,
    episodes: EpisodeDao,
    progress: Map<String, ProgressEntity>,
    downloaded: Set<String>,
    covers: CoverPathCache,
): Pair<List<CollectionEntryUi>, Int> {
    var missing = 0
    val out = entries.mapNotNull { e ->
        val p = PlayableKey.parse(e.itemKey)
        val parent = items.get(serverId, p.libraryItemId)
        val episodeId = p.episodeId
        val ui: CollectionEntryUi? = if (episodeId == null) {
            parent?.let { CollectionEntryUi.Book(it.toSummary(covers, progress[it.id], it.id in downloaded)) }
        } else {
            episodes.get(serverId, p.libraryItemId, episodeId)?.let { ep ->
                CollectionEntryUi.Episode(e.itemKey, ep.title, parent?.title, ep.durationSec, e.itemKey in downloaded, progress[e.itemKey]?.isFinished == true)
            }
        }
        if (ui == null) missing++
        ui
    }
    return out to missing
}

/** The page of one collection or playlist; "play all" plays the first title and queues the rest. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    accountStore: AccountStore,
    private val dao: CollectionDao,
    private val items: ItemDao,
    private val episodes: EpisodeDao,
    private val progress: ProgressDao,
    private val downloads: DownloadDao,
    private val covers: CoverPathCache,
    private val player: PlayerController,
    private val queue: QueueDao,
) : ViewModel() {
    private val id = savedState.toRoute<Collection>().id
    private val serverId: StateFlow<String?> = accountStore.account.map { it?.serverId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<CollectionUiState> = serverId.flatMapLatest { sid ->
        if (sid == null) flowOf(CollectionUiState(loaded = true))
        else combine(dao.observe(sid, id), dao.observeEntries(sid, id), progress.observeAll(sid), downloads.observeCompleteIds(sid)) { c, e, p, d ->
            build(sid, c, e, p.associateBy { it.itemId }, d.toHashSet())
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState())

    private suspend fun build(sid: String, c: CollectionEntity?, e: List<CollectionEntryEntity>, p: Map<String, ProgressEntity>, d: Set<String>): CollectionUiState {
        if (c == null) return CollectionUiState(loaded = true)
        covers.sync(d)
        val (entries, missing) = collectionEntries(sid, e, items, episodes, p, d, covers)
        return CollectionUiState(true, true, c.kind, c.name, c.description, entries, missing)
    }

    fun play(key: String) = withServer { player.play(it, key) }
    fun enqueue(key: String) = withServer { queue.add(it, key, System.currentTimeMillis()) }

    /** Plays the first title and replaces the queue with the rest, in list order. */
    fun playAll(playable: (CollectionEntryUi) -> Boolean) = withServer { sid ->
        val keys = state.value.entries.filter(playable).map { it.key }
        val first = keys.firstOrNull() ?: return@withServer
        queue.replace(sid, keys.drop(1), System.currentTimeMillis())
        player.play(sid, first)
    }

    private fun withServer(block: suspend (String) -> Unit) {
        viewModelScope.launch { serverId.value?.let { block(it) } }
    }
}
