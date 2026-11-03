package io.wolftown.lauschig.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.auto.awaitOnline
import io.wolftown.lauschig.connection.AddressResolver
import io.wolftown.lauschig.connection.ConnectionLog
import io.wolftown.lauschig.connection.ConnectionLogState
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ConnectionStatusMapper
import io.wolftown.lauschig.connection.ConnectionStatusUi
import io.wolftown.lauschig.connection.NetworkDescription
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.connection.ReconnectState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.connection.SyncInfo
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.sync.ProgressSync
import io.wolftown.lauschig.sync.SyncStamps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Device facts read when the window opens, after a connection change and after "Neu verbinden". */
private data class Extras(val network: NetworkDescription?, val onHomeWifi: Boolean?, val sync: SyncInfo?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConnectionStatusViewModel(
    private val account: Flow<Account?>,
    connection: Flow<ConnectionState>,
    checking: Flow<Boolean>,
    log: Flow<ConnectionLogState>,
    private val network: () -> NetworkDescription?,
    private val ssid: suspend () -> String?,
    private val syncInfo: suspend (serverId: String) -> SyncInfo,
    /** Probes every address and restarts the shared probe loop ([AddressResolver.reconnect]). */
    private val reconnectAll: suspend (ServerAddresses) -> Unit,
    /** True once the shared state is online within the given ms. */
    private val waitOnline: suspend (Long) -> Boolean,
) : ViewModel() {
    @Inject constructor(
        accounts: AccountStore,
        api: ApiProvider,
        log: ConnectionLog,
        resolver: AddressResolver,
        net: NetworkInfo,
        progress: ProgressDao,
        sessions: SessionDao,
        stamps: SyncStamps,
    ) : this(
        accounts.account, api.connection, api.checking, log.state, net::describe, net::currentSsid,
        syncInfo = { sid ->
            SyncInfo(
                lastSyncAt = stamps.get(ProgressSync.stampKey(sid)),
                dirtyProgress = progress.dirty(sid).count { !DeviceLibrary.isLocalKey(it.itemId) },
                unsentSessions = sessions.unsynced(sid).count { !DeviceLibrary.isLocalKey(it.itemId) },
            )
        },
        reconnectAll = { resolver.reconnect(it) },
        waitOnline = { ms -> awaitOnline(api.connection, ms) },
    )

    private val reconnect = MutableStateFlow<ReconnectState?>(null)
    private val refreshTick = MutableStateFlow(0)

    private val extras: Flow<Extras> = combine(account, connection.map { it is ConnectionState.Online }.distinctUntilChanged(), refreshTick) { a, _, _ -> a }
        .mapLatest { a ->
            val homes = a?.addresses?.homeSsids.orEmpty()
            Extras(
                network = safe { network() },
                onHomeWifi = if (homes.isEmpty()) null else safe { ssid() } in homes,
                sync = a?.serverId?.let { sid -> safe { syncInfo(sid) } },
            )
        }
        .onStart { emit(Extras(null, null, null)) }

    val state: StateFlow<ConnectionStatusUi> = combine(account, connection, checking, log, extras, reconnect) { values ->
        @Suppress("UNCHECKED_CAST")
        ConnectionStatusMapper.map(
            account = values[0] as Account?,
            connection = values[1] as ConnectionState,
            checking = values[2] as Boolean,
            log = values[3] as ConnectionLogState,
            network = (values[4] as Extras).network,
            onHomeWifi = (values[4] as Extras).onHomeWifi,
            sync = (values[4] as Extras).sync,
            reconnect = values[5] as ReconnectState?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatusUi())

    /** "Neu verbinden": probes all addresses now, then waits up to [RECONNECT_WAIT_MS] for the shared state. */
    fun reconnect() {
        if (reconnect.value == ReconnectState.Running) return
        viewModelScope.launch {
            val addresses = account.first()?.addresses ?: return@launch
            reconnect.value = ReconnectState.Running
            val online = try {
                reconnectAll(addresses)
                waitOnline(RECONNECT_WAIT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            reconnect.value = ReconnectState.Done(online)
            refreshTick.value++
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val RECONNECT_WAIT_MS = 10_000L
    }
}
