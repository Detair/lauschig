package io.wolftown.lauschig.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.toSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Start screen data straight from Room (offline-first: no network on this path). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StartViewModel @Inject constructor(
    private val covers: CoverPathCache,
    private val accountStore: AccountStore,
    itemDao: ItemDao,
    progressDao: ProgressDao,
    private val player: PlayerController,
) : ViewModel() {
    private var serverId: String? = null

    val state: StateFlow<StartUiState> = accountStore.account
        .flatMapLatest { account ->
            serverId = account?.serverId
            if (account == null) {
                flowOf(StartUiState())
            } else {
                combine(
                    itemDao.observeInProgress(account.serverId),
                    itemDao.observeDownloaded(account.serverId),
                    progressDao.observeAll(account.serverId),
                ) { inProgress, downloaded, progress ->
                    val byId = progress.associateBy { it.itemId }
                    val downloadedIds = downloaded.mapTo(HashSet()) { it.id }
                    covers.sync(downloadedIds)
                    fun ItemEntity.summary() = toSummary(covers, byId[id], id in downloadedIds)
                    StartUiState(inProgress.firstOrNull()?.summary(), downloaded.map { it.summary() })
                }.flowOn(Dispatchers.Default).distinctUntilChanged()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    fun play(itemId: String) {
        serverId?.let { player.play(it, itemId) }
    }
}
