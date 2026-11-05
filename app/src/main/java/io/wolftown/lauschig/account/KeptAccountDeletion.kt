package io.wolftown.lauschig.account

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Deletes the data of a kept (signed-out) account on request: the question after a sign-in, the Downloads page. */
class KeptAccountDeletion(
    private val accounts: AccountStore,
    private val data: AccountData,
    private val kept: KeptAccountStore,
    /** Called after every attempt that touched the data: the sizes of the kept data changed, whatever the entry says. */
    private val changed: () -> Unit = {},
) {
    @Inject constructor(accounts: AccountStore, data: AccountData, kept: KeptAccountStore, sizes: KeptDownloadSizes) :
        this(accounts, data, kept, sizes::refresh)

    /**
     * True if everything of [account] is gone. On failure (disk full, files left) the entry is put back as [account]
     * is, so the data stays reachable and can be deleted again later. The signed-in account and the saved ones (profile
     * switch) are never deleted here (the screens filter the signed-in one out; this is the last line), nor anything while
     * the account store cannot be read. Runs to the end even if the caller goes away.
     */
    suspend fun delete(account: KeptAccount): Boolean = withContext(NonCancellable) {
        val signedIn = try {
            setOfNotNull(accounts.account.first()?.serverId) + accounts.saved.first().map { it.serverId }
        } catch (e: Exception) {
            Log.w(TAG, "account store unreadable; nothing deleted", e)
            return@withContext false
        }
        if (account.serverId in signedIn) {
            Log.w(TAG, "refused to delete the data of a signed-in account")
            return@withContext false
        }
        val deleted = try {
            data.purge(account.serverId)
            kept.remove(account.serverId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "deleting a kept account's data failed", e)
            try {
                kept.put(account)
            } catch (e2: Exception) {
                Log.w(TAG, "could not keep the entry", e2)
            }
            false
        }
        changed()
        deleted
    }

    private companion object {
        const val TAG = "KeptAccountDeletion"
    }
}
