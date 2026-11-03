package io.wolftown.lauschig.admin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.absapi.admin.OnlineUsers
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.ui.nav.ItemDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A message after an admin action (shown once). */
sealed interface AdminEvent {
    data class ScanStarted(val library: String) : AdminEvent
    data class ItemScanned(val result: String?) : AdminEvent
    data class Matched(val outcome: MatchOutcome) : AdminEvent
    data class Problem(val result: AdminResult<Nothing>) : AdminEvent
}

/** Settings entry: visible for admins only, disabled offline. */
data class AdminAccess(val admin: Boolean = false, val online: Boolean = false)

private fun access(account: Flow<Account?>, connection: Flow<ConnectionState>): Flow<AdminAccess> =
    combine(account.map { it?.isAdmin == true }, connection.map { it is ConnectionState.Online }) { admin, online -> AdminAccess(admin, online) }
        .distinctUntilChanged()

@HiltViewModel
class AdminAccessViewModel @Inject constructor(accounts: AccountStore, api: ApiProvider) : ViewModel() {
    val access: StateFlow<AdminAccess> = access(accounts.account, api.connection)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdminAccess())
}

sealed interface OnlineState {
    data object Loading : OnlineState
    data class Loaded(val users: OnlineUsers) : OnlineState
    data class Problem(val result: AdminResult<Nothing>) : OnlineState
}

data class ServerAdminUi(
    val access: AdminAccess = AdminAccess(),
    val libraries: List<LibraryEntity> = emptyList(),
    val online: OnlineState? = null,
    /** Library ids with a scan request on the way. */
    val scanning: Set<String> = emptySet(),
    /** The signed-in user (an open session of their own shows their name even without a socket). */
    val selfId: String? = null,
    val selfName: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ServerAdminViewModel(
    account: Flow<Account?>,
    connection: Flow<ConnectionState>,
    libraries: (serverId: String) -> Flow<List<LibraryEntity>>,
    private val repo: AdminRepository,
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, api: ApiProvider, libraryDao: LibraryDao, repo: AdminRepository) :
        this(accounts.account, api.connection, libraryDao::observeAll, repo)

    private val events = Channel<AdminEvent>(Channel.BUFFERED)
    val messages: Flow<AdminEvent> = events.receiveAsFlow()

    private val online = MutableStateFlow<OnlineState?>(null)
    private val scanning = MutableStateFlow<Set<String>>(emptySet())
    private val libs = account.map { it?.serverId }.distinctUntilChanged()
        .flatMapLatest { sid -> if (sid == null || DeviceLibrary.isLocal(sid)) flowOf(emptyList()) else libraries(sid) }
        .map { list -> list.sortedBy { it.displayOrder } }

    private val accessFlow = access(account, connection)

    val state: StateFlow<ServerAdminUi> = combine(accessFlow, libs, online, scanning, account) { a, l, o, s, acc ->
        ServerAdminUi(a, l, o, s, acc?.userId, acc?.username)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerAdminUi())

    init {
        // "Wer ist online" loads as soon as the server is reachable.
        viewModelScope.launch {
            accessFlow.collect { if (it.admin && it.online && online.value !is OnlineState.Loaded) refreshOnline() }
        }
    }

    fun refreshOnline() {
        if (online.value == OnlineState.Loading) return
        online.value = OnlineState.Loading
        viewModelScope.launch {
            online.value = when (val r = repo.onlineUsers()) {
                is AdminResult.Ok -> OnlineState.Loaded(r.value)
                is AdminResult.Failed -> OnlineState.Problem(r)
                AdminResult.Forbidden -> OnlineState.Problem(AdminResult.Forbidden)
                AdminResult.Offline -> OnlineState.Problem(AdminResult.Offline)
            }
        }
    }

    fun scan(library: LibraryEntity) {
        if (library.id in scanning.value) return
        scanning.value += library.id
        viewModelScope.launch {
            val r = repo.scanLibrary(library.id)
            scanning.value -= library.id
            events.send(
                when (r) {
                    is AdminResult.Ok -> AdminEvent.ScanStarted(library.name)
                    is AdminResult.Failed -> AdminEvent.Problem(r)
                    AdminResult.Forbidden -> AdminEvent.Problem(AdminResult.Forbidden)
                    AdminResult.Offline -> AdminEvent.Problem(AdminResult.Offline)
                },
            )
        }
    }
}

data class ItemAdminUi(val visible: Boolean = false, val online: Boolean = false, val busy: Boolean = false)

/** The admin entries of a title's detail page (server titles only). */
@HiltViewModel
class ItemAdminViewModel(
    private val itemId: String,
    account: Flow<Account?>,
    connection: Flow<ConnectionState>,
    private val repo: AdminRepository,
) : ViewModel() {
    @Inject constructor(savedState: SavedStateHandle, accounts: AccountStore, api: ApiProvider, repo: AdminRepository) :
        this(savedState.toRoute<ItemDetail>().itemId, accounts.account, api.connection, repo)

    private val busy = MutableStateFlow(false)
    private val events = Channel<AdminEvent>(Channel.BUFFERED)
    val messages: Flow<AdminEvent> = events.receiveAsFlow()

    val state: StateFlow<ItemAdminUi> = combine(access(account, connection), busy) { a, b ->
        ItemAdminUi(visible = a.admin && !DeviceLibrary.isLocalKey(itemId), online = a.online, busy = b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemAdminUi())

    fun rescan() = run { repo.scanItem(itemId).toEvent { AdminEvent.ItemScanned(it) } }

    fun rematch() = run { repo.matchItem(itemId).toEvent { AdminEvent.Matched(it) } }

    private fun run(block: suspend () -> AdminEvent) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                events.send(block())
            } finally {
                busy.value = false
            }
        }
    }
}

private fun <T> AdminResult<T>.toEvent(ok: (T) -> AdminEvent): AdminEvent = when (this) {
    is AdminResult.Ok -> ok(value)
    is AdminResult.Failed -> AdminEvent.Problem(this)
    AdminResult.Forbidden -> AdminEvent.Problem(AdminResult.Forbidden)
    AdminResult.Offline -> AdminEvent.Problem(AdminResult.Offline)
}
