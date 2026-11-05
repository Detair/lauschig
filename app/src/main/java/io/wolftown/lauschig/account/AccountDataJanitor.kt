package io.wolftown.lauschig.account

import android.util.Log
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadTidy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Runs once per app start. Finishes purges that failed during a sign-out ([KeptAccountStore.pendingPurges]; dropped
 * when that account signed in again, its data is live then) and deletes download directories nobody can reach any
 * more: no rows, not the signed-in account, not kept, not pending. The directories of the signed-in and the kept accounts
 * are tidied ([DownloadTidy]: titles without a download row, stale copies on a second volume). Every mounted volume is
 * covered (internal storage and SD card); a card that was out at sign-out is cleaned at the first start it is back. The
 * device library has no download directory, and the folders of "Auf dem Gerät" are the user's own documents: neither is
 * ever touched.
 */
class AccountDataJanitor(
    private val accounts: AccountStore,
    private val kept: KeptAccountStore,
    private val data: AccountData,
    private val serverDirs: suspend () -> List<String>,
    private val deleteDir: suspend (serverId: String) -> Boolean,
    /** Tidies one owned account's title directories; returns how many titles were tidied. */
    private val tidyItems: suspend (serverId: String) -> Int = { 0 },
) {
    @Inject constructor(accounts: AccountStore, kept: KeptAccountStore, data: AccountData, files: DownloadFiles, tidy: DownloadTidy) : this(
        accounts, kept, data,
        serverDirs = { withContext(Dispatchers.IO) { files.serverIds() } },
        deleteDir = { sid -> withContext(Dispatchers.IO) { files.deleteServer(sid) } },
        tidyItems = tidy::tidy,
    )

    suspend fun run() {
        // An unreadable account store is never taken for "signed out": then nothing is purged or deleted.
        val current = signedIn() ?: return
        val keptIds = kept.accounts.first().map { it.serverId }.toSet()
        for (sid in kept.pendingPurges.first()) {
            // Signed in again (read anew before each purge), or kept by a later sign-out: the data is wanted, the old
            // purge is void.
            val now = signedIn() ?: return
            if (sid == current.id || sid == now.id || sid in keptIds) {
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
            if (sid == current.id || sid in owned || data.hasRows(sid)) continue
            // A sign-in may have happened meanwhile: its first download must not lose its directory.
            val now = signedIn() ?: return
            if (now.id == sid) continue
            if (deleteDir(sid)) Log.i(TAG, "deleted downloads without an owner")
        }
        val pending = kept.pendingPurges.first()
        val tidyIds = (listOfNotNull(current.id) + kept.accounts.first().map { it.serverId }).distinct().filter { it !in pending }
        for (sid in tidyIds) {
            try {
                val n = tidyItems(sid)
                if (n > 0) Log.i(TAG, "tidied $n download directories")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "tidying the downloads failed; retried at the next start", e)
            }
        }
    }

    /** The signed-in server id right now ([SignedIn.id] null: signed out); null if the store cannot be read. */
    private suspend fun signedIn(): SignedIn? = try {
        SignedIn(accounts.account.first()?.serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "account store unreadable; no cleanup this time", e)
        null
    }

    private class SignedIn(val id: String?)

    private companion object {
        const val TAG = "AccountDataJanitor"
    }
}
