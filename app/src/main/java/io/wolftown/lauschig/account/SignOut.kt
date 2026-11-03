package io.wolftown.lauschig.account

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS
import io.wolftown.lauschig.connection.awaitConnectionAnswer
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.LastPlayedStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.SleepTimer
import io.wolftown.lauschig.playback.forgetServerTitle
import io.wolftown.lauschig.sync.BookmarkSync
import io.wolftown.lauschig.sync.ProgressSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** What the sign-out dialog shows, after the pending changes were pushed. */
data class SignOutCheck(
    /** Changes still not on the server ([AccountData.unsynced]). */
    val unsynced: Int,
    /** Bytes of this account's downloads on the device; 0 = nothing to keep (the checkbox is hidden). */
    val downloadBytes: Long,
)

sealed interface SignOutResult {
    data object Done : SignOutResult

    /**
     * Not signed out: without keeping, more changes were found after playback stopped than the dialog showed (it was
     * resumed meanwhile, e.g. with a headset key). The dialog asks again with [check].
     */
    data class Changed(val check: SignOutCheck) : SignOutResult
}

interface AccountSignOut {
    /**
     * Pauses playback (its position is saved), pushes what the syncs would upload – at most [SignOut.PUSH_TIMEOUT_MS],
     * only once the connection check answered "online" – and reports what is left. Null without an account. Never signs out.
     */
    suspend fun check(): SignOutCheck?

    /**
     * Signs out (see [SignOut]); [keepDownloads] keeps this account's downloads and rows for its next sign-in.
     * [shownUnsynced] is the count the dialog showed (null: unknown); without keeping, a larger count after playback
     * stopped aborts with [SignOutResult.Changed] instead of deleting changes the user was not warned about.
     */
    suspend fun execute(keepDownloads: Boolean, shownUnsynced: Int? = null): SignOutResult
}

/**
 * Sign-out („Abmelden“), in this order: playback stops and the queue is emptied → the account's work is cancelled (and
 * stopped) → the server logout runs alongside (at most [LOGOUT_TIMEOUT_MS], never decisive) → keep: waiting downloads
 * pause and the account is remembered for the next one's question ([KeptAccountStore]); else every row, file and cache of
 * the server id goes ([AccountData.purge]) → the last-played server title, the RMAB connection, a pending SSO attempt,
 * recent searches, an open download question and the cover caches go → the account and its tokens go **last**
 * ([AccountStore.clear]), so the app turns to the sign-in only when the data is in its final state. Device titles are
 * never touched. Runs to the end even if the caller is cancelled; a failing step is logged and the rest still runs.
 */
class SignOut(
    private val accounts: AccountStore,
    private val data: AccountData,
    private val kept: KeptAccountStore,
    private val work: AccountWork,
    private val lastPlayed: LastPlayedStore,
    /** Pauses a playing title and gives the service a moment to save it. */
    private val pausePlayback: suspend () -> Unit,
    /** Stops playback entirely and empties the queue. */
    private val stopPlayback: suspend () -> Unit,
    /** The syncs that push pending changes for a server id; each may fail on its own. */
    private val pushes: List<suspend (serverId: String) -> Unit>,
    /** True once the connection check answered "online" (false: offline, or no answer in time). */
    private val awaitOnline: suspend () -> Boolean,
    private val logout: suspend (Account, refreshToken: String) -> Boolean,
    /** Per-account state outside Room and the downloads (secrets, caches, open questions). */
    private val clearAccountState: suspend (serverId: String) -> Unit,
) : AccountSignOut {
    @Inject constructor(
        accounts: AccountStore,
        data: AccountData,
        kept: KeptAccountStore,
        work: AccountWork,
        lastPlayed: LastPlayedStore,
        player: PlayerController,
        sleepTimer: SleepTimer,
        progressSync: ProgressSync,
        bookmarkSync: BookmarkSync,
        api: ApiProvider,
        serverLogout: ServerLogout,
        cleaner: AccountStateCleaner,
    ) : this(
        accounts, data, kept, work, lastPlayed,
        pausePlayback = {
            withContext(Dispatchers.Main.immediate) {
                val s = player.state.value
                if (s.isPlaying || s.isLoading) {
                    player.pause()
                    delay(SAVE_SETTLE_MS) // the service's tracker saves the position on the pause
                }
            }
        },
        stopPlayback = {
            withContext(Dispatchers.Main.immediate) { sleepTimer.cancel() }
            player.stop()
        },
        pushes = listOf({ sid -> progressSync.sync(sid) }, { sid -> bookmarkSync.sync(sid) }),
        awaitOnline = { awaitConnectionAnswer(api.connection, api.checking, CONNECTION_ANSWER_WAIT_MS) },
        logout = serverLogout::logout,
        clearAccountState = cleaner::clear,
    )

    override suspend fun check(): SignOutCheck? {
        val serverId = accounts.account.first()?.serverId ?: return null
        step("pause") { pausePlayback() }
        if (data.unsynced(serverId) > 0) {
            withTimeoutOrNull(PUSH_TIMEOUT_MS) {
                if (awaitOnline()) pushes.forEach { push -> step("push") { push(serverId) } }
            }
        }
        return SignOutCheck(data.unsynced(serverId), data.downloadBytes(serverId))
    }

    override suspend fun execute(keepDownloads: Boolean, shownUnsynced: Int?): SignOutResult = withContext(NonCancellable) {
        val account = accounts.account.first() ?: return@withContext SignOutResult.Done
        val serverId = account.serverId
        val refresh = try { accounts.refreshToken() } catch (e: Exception) { null }
        step("stop playback") { stopPlayback() }
        if (!keepDownloads && shownUnsynced != null) {
            val now = step("recount") { data.unsynced(serverId) }
            if (now != null && now > shownUnsynced) {
                Log.i(TAG, "more unsynced changes than shown ($now > $shownUnsynced): asking again")
                return@withContext SignOutResult.Changed(SignOutCheck(now, step("size") { data.downloadBytes(serverId) } ?: 0))
            }
        }
        step("cancel work") { work.cancel(serverId) }
        val loggedOut = async {
            if (refresh == null) false
            else withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { step("logout") { logout(account, refresh) } } == true
        }
        if (keepDownloads) {
            step("pause downloads") { data.pauseDownloads(serverId) }
            step("remember") { kept.put(KeptAccount(serverId, account.username, account.displayHost, userId = account.userId)) }
        } else {
            // A purge that fails (disk full, files left) is finished at the next start: the data never becomes unreachable.
            if (step("purge") { data.purge(serverId); true } != true) step("pending purge") { kept.addPendingPurge(serverId) }
            step("forget") { kept.remove(serverId) }
        }
        step("last played") { lastPlayed.forgetServerTitle() }
        step("account state") { clearAccountState(serverId) }
        val serverConfirmed = loggedOut.await()
        // Late writes of the stopping service (its last progress save) or a worker: the rows once more, now that up to
        // the logout's few seconds have passed. Idempotent.
        if (!keepDownloads) step("rows again") { data.deleteRows(serverId) }
        Log.i(TAG, "signed out (kept downloads: $keepDownloads, server logout: $serverConfirmed)")
        accounts.clear(keepDownloads)
        SignOutResult.Done
    }

    companion object {
        private const val TAG = "SignOut"
        /** The push of pending changes before the dialog. */
        const val PUSH_TIMEOUT_MS = 15_000L
        const val LOGOUT_TIMEOUT_MS = 5_000L
        /** After pausing a playing title, before its position is pushed. */
        private const val SAVE_SETTLE_MS = 300L

        /** Runs [block]; a failure (not a cancellation) is logged and yields null, so the next step still runs. */
        private suspend fun <T> step(name: String, block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sign-out step '$name' failed", e)
            null
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SignOutModule {
    @Binds abstract fun signOut(impl: SignOut): AccountSignOut
}
