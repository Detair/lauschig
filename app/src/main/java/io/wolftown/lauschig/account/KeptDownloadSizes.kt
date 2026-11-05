package io.wolftown.lauschig.account

import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KeptDownloads"

/** Data another (signed-out) account kept on this device, with its size on disk. */
data class KeptDownloads(val account: KeptAccount, val bytes: Long)

/**
 * Every kept account but the signed-in one, each with its size on disk ([bytes], by server id). A walk that fails is
 * logged and counts as 0 for that account and that emission only: the list is still there, and the next change walks again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun keptDownloads(accounts: AccountStore, kept: KeptAccountStore, bytes: suspend (serverId: String) -> Long): Flow<List<KeptDownloads>> =
    combine(accounts.account, kept.accounts) { a, list -> list.filter { it.serverId != a?.serverId } }
        .mapLatest { list -> list.map { KeptDownloads(it, size(it.serverId, bytes)) } }

private suspend fun size(serverId: String, bytes: suspend (String) -> Long): Long =
    try {
        bytes(serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not read the size of the downloads of $serverId", e)
        0L
    }

/**
 * [keptDownloads] for everyone who watches it at the same time: the Downloads page needs the sizes twice (its storage
 * header and the "Downloads früherer Konten" section), but the directories are walked once per visit. The walk stops
 * [STOP_TIMEOUT_MS] after the last watcher left and starts afresh at the next visit; [refresh] walks again at once.
 *
 * It lives as long as the application, so nothing may escape it: a store that cannot be read (a corrupt DataStore file)
 * is logged and shown as "nothing kept" until the next visit, never passed to the uncaught-exception handler.
 */
@Singleton
class KeptDownloadSizes(accounts: AccountStore, kept: KeptAccountStore, bytes: suspend (serverId: String) -> Long, scope: CoroutineScope) {
    @Inject constructor(accounts: AccountStore, kept: KeptAccountStore, data: AccountData) :
        this(accounts, kept, data::downloadBytes, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val refreshes = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: Flow<List<KeptDownloads>> = refreshes.flatMapLatest { keptDownloads(accounts, kept, bytes) }
        .catch { e ->
            Log.w(TAG, "the kept accounts could not be read", e)
            emit(emptyList())
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    /** Walks the directories again at once, for a change the stores do not show (a delete that failed half way). */
    fun refresh() = refreshes.update { it + 1 }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
