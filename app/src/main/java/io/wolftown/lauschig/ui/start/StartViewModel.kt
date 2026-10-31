package io.wolftown.lauschig.ui.start

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.PlayerController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Start screen data straight from Room (offline-first: no network on this path). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StartViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
                    val percent = progress.associate { it.itemId to it }
                    fun ItemEntity.row() = StartRow(id, title, author, percent[id]?.let { p ->
                        val d = if (p.durationSec > 0) p.durationSec else durationSec
                        if (p.isFinished) context.getString(R.string.start_progress_percent, 100)
                        else if (d > 0) context.getString(R.string.start_progress_percent, (p.currentTimeSec / d * 100).toInt().coerceIn(0, 100))
                        else null
                    })
                    StartUiState(inProgress.firstOrNull()?.row(), downloaded.map { it.row() })
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    fun play(itemId: String) {
        serverId?.let { player.play(it, itemId) }
    }
}
