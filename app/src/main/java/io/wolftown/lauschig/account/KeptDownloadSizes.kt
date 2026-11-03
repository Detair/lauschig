package io.wolftown.lauschig.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** Data another (signed-out) account kept on this device, with its size on disk. */
data class KeptDownloads(val account: KeptAccount, val bytes: Long)

/**
 * Every kept account but the signed-in one, each with its size on disk ([bytes], by server id). A walk that fails counts
 * as 0 for that account and that emission only: the list is still there, and the next change walks again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun keptDownloads(accounts: AccountStore, kept: KeptAccountStore, bytes: suspend (serverId: String) -> Long): Flow<List<KeptDownloads>> =
    combine(accounts.account, kept.accounts) { a, list -> list.filter { it.serverId != a?.serverId } }
        .mapLatest { list -> list.map { KeptDownloads(it, try { bytes(it.serverId) } catch (e: CancellationException) { throw e } catch (e: Exception) { 0L }) } }

/**
 * [keptDownloads] for everyone who watches it at the same time: the Downloads page needs the sizes twice (its storage
 * header and the "Downloads früherer Konten" section), but the directories are walked once per visit. The walk stops
 * [STOP_TIMEOUT_MS] after the last watcher left and starts afresh at the next visit.
 */
@Singleton
class KeptDownloadSizes(accounts: AccountStore, kept: KeptAccountStore, bytes: suspend (serverId: String) -> Long, scope: CoroutineScope) {
    @Inject constructor(accounts: AccountStore, kept: KeptAccountStore, data: AccountData) :
        this(accounts, kept, data::downloadBytes, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    val entries: Flow<List<KeptDownloads>> = keptDownloads(accounts, kept, bytes)
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
