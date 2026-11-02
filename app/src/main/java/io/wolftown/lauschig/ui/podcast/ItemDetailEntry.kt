package io.wolftown.lauschig.ui.podcast

import io.wolftown.lauschig.local.DeviceLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.detail.ItemDetailRoute
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.nav.ItemDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A podcast item: it lives in a podcast library or already reports episodes. */
fun isPodcastItem(item: ItemEntity, library: LibraryEntity?): Boolean =
    item.numEpisodes > 0 || library?.effectiveKind == LibraryKind.PODCAST

/** Decides from Room which page the `ItemDetail` route shows. `null` while that is not known yet. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemKindViewModel @Inject constructor(
    savedState: SavedStateHandle,
    accountStore: AccountStore,
    itemDao: ItemDao,
    libraryDao: LibraryDao,
) : ViewModel() {
    private val itemId = savedState.toRoute<ItemDetail>().itemId

    val isPodcast: StateFlow<Boolean?> = accountStore.account.map { DeviceLibrary.serverFor(it?.serverId, itemId) }.distinctUntilChanged()
        .flatMapLatest { sid ->
            if (sid == null) flowOf(false)
            else combine(itemDao.observeItemRow(sid, itemId), libraryDao.observeAll(sid)) { item, libs ->
                item != null && isPodcastItem(item, libs.firstOrNull { it.id == item.libraryId })
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** `ItemDetail(itemId)`: the podcast page for podcast items, the book details for everything else. */
@Composable
fun ItemDetailEntry(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenItem: (itemId: String) -> Unit = {},
    onOpenBrowse: (BrowseKind, String) -> Unit = { _, _ -> },
    kind: ItemKindViewModel = hiltViewModel(),
) {
    when (kind.isPodcast.collectAsStateWithLifecycle().value) {
        null -> Unit
        true -> PodcastRoute(onBack, modifier)
        false -> ItemDetailRoute(onBack, modifier, onOpenItem, onOpenBrowse)
    }
}
