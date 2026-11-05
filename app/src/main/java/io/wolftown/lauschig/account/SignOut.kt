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
     * Bytes of the account's downloads on disk, from a walk of its download directory only (no Room): what the dialog
     * still names after a failed [check]. 0 without an account or when it cannot be read.
     */
    suspend fun downloadBytes(): Long

    /**
     * Signs out (see [SignOut]); [keepDownloads] keeps this account's downloads and rows for its next sign-in.
     * [shownUnsynced] is the count the dialog showed (null: unknown); without keeping, a larger count after playback
     * stopped aborts with [SignOutResult.Changed] instead of deleting changes the user was not warned about.
     */
    suspend fun execute(keepDownloads: Boolean, shownUnsynced: Int? = null): SignOutResult

    /**
     * Signs the stored account out keeping its data (as „Abmelden“ with „Behalten“) and stores [next] in its place as
     * the last step, instead of clearing it: the app never sees "no account" in between, so a re-auth Setup on top of
     * the app finishes like any re-auth. For a re-auth that turned out to be another account or another public address
     * (another server id). Without a stored account it just saves [next].
     */
    suspend fun switchTo(next: Account, accessToken: String, refreshToken: String)
}

/**
 * Profile switch on a shared device (Phase 6 seam for Phase 7, no screen uses it yet): the active account is left like a
 * sign-out with „Behalten“ – playback stops, its work stops, waiting downloads pause, the last-played title and the
 * per-account state outside Room go – but it stays signed in: no server logout, its tokens are set aside in the store
 * ([AccountStore.saved]) and nobody is asked to delete its data. The next account becomes active as the last step, so
 * the app never sees "no account" in between.
 */
interface ProfileSwitch {
    /** Switches to the saved account [serverId]. False, touching nothing, if it is not saved. */
    suspend fun switchProfile(serverId: String): Boolean

    /**
     * Sets the active account aside and makes the newly signed-in [next] the active one ([AccountStore.add]); the same
     * account signing in again (or none active) just saves.
     */
    suspend fun addProfile(next: Account, accessToken: String, refreshToken: String)

    /**
     * Removes the saved (not active) account [serverId] like a sign-out without keeping: its session on the server ends
     * (at most [SignOut.LOGOUT_TIMEOUT_MS]), its rows and downloads are purged, it is forgotten. The purge is owed
     * before the account is forgotten (a pending purge the next start finishes), so its data never stays without an
     * owner. False, touching nothing, if [serverId] is not saved; the active account is never removed here.
     */
    suspend fun removeProfile(serverId: String): Boolean
}

/**
 * Sign-out („Abmelden“), in this order: playback stops and the queue is emptied → the account's work is cancelled (and
 * stopped) → the server logout runs alongside (at most [LOGOUT_TIMEOUT_MS], never decisive) → keep: waiting downloads
 * pause and the account is remembered for the next one's question ([KeptAccountStore]); else every row, file and cache of
 * the server id goes ([AccountData.purge]) → the last-played server title, the RMAB connection, a pending SSO attempt,
 * recent searches, an open download question and the cover caches go → the account and its tokens go **last**
 * ([AccountStore.clear]), so the app turns to the sign-in only when the data is in its final state. Device titles are
 * never touched. Runs to the end even if the caller is cancelled; a failing step is logged and the rest still runs.
 * The same steps leave an account for a [ProfileSwitch], without the logout and without forgetting anything.
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
) : AccountSignOut, ProfileSwitch {
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

    override suspend fun downloadBytes(): Long = try {
        accounts.account.first()?.serverId?.let { data.downloadBytes(it) } ?: 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "download size unreadable", e)
        0
    }

    override suspend fun execute(keepDownloads: Boolean, shownUnsynced: Int?): SignOutResult =
        leave(if (keepDownloads) Leave.KEEP else Leave.PURGE, shownUnsynced) { accounts.clear(keepDownloads) }

    override suspend fun switchTo(next: Account, accessToken: String, refreshToken: String) {
        withContext(NonCancellable) {
            if (accounts.account.first() == null) accounts.save(next, accessToken, refreshToken)
            else leave(Leave.KEEP, shownUnsynced = null) { accounts.save(next, accessToken, refreshToken) }
        }
    }

    override suspend fun switchProfile(serverId: String): Boolean = withContext(NonCancellable) {
        if (accounts.saved.first().none { it.serverId == serverId }) return@withContext false
        if (accounts.account.first() == null) return@withContext accounts.activate(serverId)
        var switched = false
        leave(Leave.SET_ASIDE, shownUnsynced = null) { switched = accounts.activate(serverId) }
        switched
    }

    override suspend fun addProfile(next: Account, accessToken: String, refreshToken: String) {
        withContext(NonCancellable) {
            val current = accounts.account.first()
            if (current == null || current.serverId == next.serverId) accounts.save(next, accessToken, refreshToken)
            else leave(Leave.SET_ASIDE, shownUnsynced = null) { accounts.add(next, accessToken, refreshToken) }
        }
    }

    override suspend fun removeProfile(serverId: String): Boolean = withContext(NonCancellable) {
        val account = accounts.saved.first().firstOrNull { it.serverId == serverId } ?: return@withContext false
        val refresh = try { accounts.savedCredentials(serverId)?.refreshToken } catch (e: Exception) { null }
        // Owed first: should anything stop here, the next start finishes the purge (the janitor), the data is never ownerless.
        if (step("pending purge") { kept.addPendingPurge(serverId); true } != true) return@withContext false
        if (step("forget") { accounts.forget(serverId); true } != true) {
            // Still saved: its data stays wanted.
            step("keep") { kept.removePendingPurge(serverId) }
            return@withContext false
        }
        step("cancel work") { work.cancel(serverId) }
        val loggedOut = async {
            if (refresh == null) false
            else withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { step("logout") { logout(account, refresh) } } == true
        }
        if (step("purge") { data.purge(serverId); true } == true) step("purged") { kept.removePendingPurge(serverId) }
        step("forget kept") { kept.remove(serverId) }
        Log.i(TAG, "removed a saved profile (server logout: ${loggedOut.await()})")
        true
    }

    /** What becomes of the account that is left. */
    private enum class Leave {
        /** „Abmelden“: everything of the account goes. */
        PURGE,

        /** „Abmelden“ with „Behalten“: its data stays, the next account is asked about it. */
        KEEP,

        /** Profile switch: its data stays and it stays signed in (no server logout, not offered for deletion). */
        SET_ASIDE,
    }

    /** Leaves the active account; [finish] is the last step, once the data is in its final state (clearing, replacing or switching the account). */
    private suspend fun leave(mode: Leave, shownUnsynced: Int?, finish: suspend () -> Unit): SignOutResult = withContext(NonCancellable) {
        val account = accounts.account.first() ?: return@withContext SignOutResult.Done
        val serverId = account.serverId
        // Only a sign-out ends the session on the server; a profile set aside keeps it for the way back.
        val refresh = if (mode == Leave.SET_ASIDE) null
        else try { accounts.credentials()?.takeIf { it.serverId == serverId }?.refreshToken } catch (e: Exception) { null }
        step("stop playback") { stopPlayback() }
        if (mode == Leave.PURGE && shownUnsynced != null) {
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
        if (mode != Leave.PURGE) {
            step("pause downloads") { data.pauseDownloads(serverId) }
            step("remember") {
                // Kept: the next account is asked about it. Set aside: still signed in, nobody is asked to delete it.
                if (mode == Leave.KEEP) kept.put(KeptAccount(serverId, account.username, account.displayHost, userId = account.userId))
                else kept.remove(serverId)
                // A purge that failed earlier for this account must never run on the data the user now keeps.
                kept.removePendingPurge(serverId)
            }
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
        if (mode == Leave.PURGE) step("rows again") { data.deleteRows(serverId) }
        if (mode == Leave.SET_ASIDE) Log.i(TAG, "set aside for another profile")
        else Log.i(TAG, "signed out (kept downloads: ${mode == Leave.KEEP}, server logout: $serverConfirmed)")
        finish()
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
    @Binds abstract fun profileSwitch(impl: SignOut): ProfileSwitch
}
