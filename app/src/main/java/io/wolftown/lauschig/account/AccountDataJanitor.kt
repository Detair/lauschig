package io.wolftown.lauschig.account

import android.util.Log
import io.wolftown.lauschig.download.DownloadFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Runs once per app start. Finishes purges that failed during a sign-out ([KeptAccountStore.pendingPurges]; dropped
 * when that account signed in again, its data is live then) and deletes download directories nobody can reach any
 * more: no rows, not the signed-in account, not kept, not pending. The device library has no download directory, and
 * the folders of "Auf dem Gerät" are the user's own documents: neither is ever touched.
 */
class AccountDataJanitor(
    private val accounts: AccountStore,
    private val kept: KeptAccountStore,
    private val data: AccountData,
    private val serverDirs: suspend () -> List<String>,
    private val deleteDir: suspend (serverId: String) -> Boolean,
) {
    @Inject constructor(accounts: AccountStore, kept: KeptAccountStore, data: AccountData, files: DownloadFiles) : this(
        accounts, kept, data,
        serverDirs = { withContext(Dispatchers.IO) { files.serverIds() } },
        deleteDir = { sid -> withContext(Dispatchers.IO) { files.deleteServer(sid) } },
    )

    suspend fun run() {
        val current = accounts.account.first()?.serverId
        val keptIds = kept.accounts.first().map { it.serverId }.toSet()
        for (sid in kept.pendingPurges.first()) {
            // Signed in again, or kept by a later sign-out: the data is wanted, the old purge is void.
            if (sid == current || sid in keptIds) {
                kept.removePendingPurge(sid)
                continue
            }
            try {
                data.purge(sid)
                kept.removePendingPurge(sid)
                Log.i(TAG, "finished the purge of a signed-out account")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "purge still failing; retried at the next start", e)
            }
        }
        val owned = kept.accounts.first().map { it.serverId }.toSet() + kept.pendingPurges.first()
        for (sid in serverDirs()) {
            if (sid == current || sid in owned || data.hasRows(sid)) continue
            // A sign-in may have happened meanwhile: its first download must not lose its directory.
            if (accounts.account.first()?.serverId == sid) continue
            if (deleteDir(sid)) Log.i(TAG, "deleted downloads without an owner")
        }
    }

    private companion object {
        const val TAG = "AccountDataJanitor"
    }
}
