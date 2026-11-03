package io.wolftown.lauschig.admin

import io.wolftown.lauschig.absapi.admin.AdminApi
import io.wolftown.lauschig.absapi.admin.MatchRequest
import io.wolftown.lauschig.absapi.admin.OnlineUsers
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.sync.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import javax.inject.Inject

/** What an admin action came to. */
sealed interface AdminResult<out T> {
    data class Ok<T>(val value: T) : AdminResult<T>
    data object Offline : AdminResult<Nothing>
    /** The server said 403: not (or no longer) an admin. */
    data object Forbidden : AdminResult<Nothing>
    data class Failed(val message: String?) : AdminResult<Nothing>
}

/** Outcome of a quick match. */
sealed interface MatchOutcome {
    data class Matched(val updated: Boolean) : MatchOutcome
    data class NoMatch(val warning: String) : MatchOutcome
    /** The library has no metadata provider to match with. */
    data object NoProvider : MatchOutcome
}

/**
 * The small admin menu (spec 2.7a): scan a library, rescan or re-match a title, see who is online. Only the signed-in
 * account's online server is ever asked; afterwards the affected item (at once) or library (one minute later, the
 * server scans in the background) is refreshed in Room.
 */
class AdminRepository(
    private val admin: Flow<AdminApi?>,
    private val library: Flow<LibraryApi?>,
    private val account: Flow<Account?>,
    private val itemLibraryId: suspend (serverId: String, itemId: String) -> String?,
    private val loadDetails: suspend (serverId: String, itemId: String) -> Unit,
    private val syncLibrarySoon: () -> Unit,
) {
    @Inject constructor(api: ApiProvider, accounts: AccountStore, items: ItemDao, sync: LibrarySync, trigger: SyncTrigger) : this(
        admin = api.adminApi,
        library = api.libraryApi,
        account = accounts.account,
        itemLibraryId = { sid, id -> items.get(sid, id)?.libraryId },
        loadDetails = sync::loadDetails,
        syncLibrarySoon = trigger::syncLibrarySoon,
    )

    suspend fun scanLibrary(libraryId: String): AdminResult<Unit> = call { api, _ ->
        api.scanLibrary(libraryId)
        syncLibrarySoon()
    }

    /** The scan result code ("UPDATED", "UPTODATE", "REMOVED", …). */
    suspend fun scanItem(itemId: String): AdminResult<String?> = call { api, serverId ->
        val result = api.scanItem(itemId).result
        refresh(serverId, itemId)
        result
    }

    suspend fun matchItem(itemId: String): AdminResult<MatchOutcome> = call { api, serverId ->
        val libraryId = itemLibraryId(serverId, itemId)
        val provider = library.first()?.libraries()?.libraries?.firstOrNull { it.id == libraryId }?.provider?.takeIf { it.isNotBlank() }
        if (provider == null) return@call MatchOutcome.NoProvider
        val r = api.matchItem(itemId, MatchRequest(provider))
        if (r.warning != null && !r.updated) return@call MatchOutcome.NoMatch(r.warning!!)
        refresh(serverId, itemId)
        MatchOutcome.Matched(r.updated)
    }

    suspend fun onlineUsers(): AdminResult<OnlineUsers> = call { api, _ -> api.onlineUsers() }

    /** The changed item from the server into Room; a failure here does not make the action fail. */
    private suspend fun refresh(serverId: String, itemId: String) {
        try {
            loadDetails(serverId, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // the next sync picks it up
        }
    }

    private suspend fun <T> call(block: suspend (AdminApi, String) -> T): AdminResult<T> {
        val a = account.first()?.takeIf { it.isAdmin } ?: return AdminResult.Forbidden
        val api = admin.first() ?: return AdminResult.Offline
        return try {
            AdminResult.Ok(block(api, a.serverId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 403) AdminResult.Forbidden else AdminResult.Failed("HTTP ${e.code()}")
        } catch (e: Exception) {
            AdminResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
