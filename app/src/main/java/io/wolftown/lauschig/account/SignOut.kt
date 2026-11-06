package io.wolftown.lauschig.account

import io.wolftown.lauschig.diagnostics.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS
import io.wolftown.lauschig.connection.awaitConnectionAnswer
import io.wolftown.lauschig.download.SwitchedDownloads
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.LastPlayedStore
import io.wolftown.lauschig.playback.LastTitleStarter
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.SleepTimer
import io.wolftown.lauschig.sync.BookmarkSync
import io.wolftown.lauschig.sync.ProgressSync
import io.wolftown.lauschig.sync.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What the sign-out dialog shows, after the pending changes were pushed. */
data class SignOutCheck(
    /** Changes still not on the server ([AccountData.unsynced]). */
    val unsynced: Int,
    /** Bytes of this account's downloads on the device; 0 = nothing to keep (the checkbox is hidden). */
    val downloadBytes: Long,
    /** Some downloads are on an SD card that is out: deleting them waits until it is back (the dialog says so). */
    val onMissingCard: Boolean = false,
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

    /**
     * A sign-in (setup, re-auth): another account than the stored one goes through [switchTo] (the old one keeps its
     * data), the same one – or none stored – just saves the new tokens. Waits for any other account operation to finish
     * (sign-out, switch, removing a profile), like each of those.
     */
    suspend fun signIn(next: Account, accessToken: String, refreshToken: String)
}

/**
 * Profile switch on a shared device (no screen uses it yet). First the active account's playback pauses – its position is
 * final – and its changes go up, at most [SignOut.SWITCH_PUSH_TIMEOUT_MS]; offline or out of time the switch goes on and
 * they stay in Room until the account is active again. Then it is left like a sign-out with „Behalten“ – playback stops,
 * its work stops, waiting downloads pause (and are noted) – but it stays signed in: no server logout, its tokens are set
 * aside in the store ([AccountStore.saved]), nobody is asked to delete its data, and its last title, RMAB connection and
 * recent searches wait for its return. The next account becomes active as the last step, so the app never sees "no
 * account" in between. Then it arrives: the downloads the switch paused go on (the Wi-Fi rule applies), its last title
 * waits paused in the mini player, a sync starts. Nothing ever starts playing by itself.
 */
interface ProfileSwitch {
    /**
     * Switches to the saved account [serverId]. False, touching nothing, if it is not saved. False too if it could not
     * become active: the active account stays, and goes on as after a switch back.
     */
    suspend fun switchProfile(serverId: String): Boolean

    /**
     * Sets the active account aside and makes the newly signed-in [next] the active one ([AccountStore.add]); the same
     * account signing in again just saves. If [next] cannot be stored this throws: the active account stays and goes on.
     */
    suspend fun addProfile(next: Account, accessToken: String, refreshToken: String)

    /**
     * Removes the saved (not active) account [serverId] like a sign-out without keeping: its session on the server is
     * ended where it can be (at most [SignOut.LOGOUT_TIMEOUT_MS], see below), its rows and downloads are purged, it is
     * forgotten. The purge is owed
     * before the account is forgotten (a pending purge the next start finishes), so its data never stays without an
     * owner. False, touching nothing, if [serverId] is not saved; the active account is never removed here – also not
     * when it became the active one just before (the account operations run one after the other).
     *
     * The server logout goes to that profile's own addresses ([ServerLogout.logoutProfile], also on another server than
     * the signed-in account's); if none answers in time its session runs out on its own, and the removal goes through.
     */
    suspend fun removeProfile(serverId: String): Boolean
}

/**
 * Sign-out („Abmelden“), in this order: playback stops and the queue is emptied → the account's work is cancelled (and
 * stopped) → the server logout runs alongside (at most [LOGOUT_TIMEOUT_MS], never decisive) → keep: waiting downloads
 * pause and the account is remembered for the next one's question ([KeptAccountStore]); else every row, file and cache of
 * the server id goes ([AccountData.purge]) → the last-played server title, the RMAB connection, a pending SSO attempt,
 * recent searches, an open download question and the cover caches go → the account and its tokens go **last**
 * ([AccountStore.clear]), so the app turns to the sign-in only when the data is in its final state; only a last stop
 * follows, for a title that started in the meantime. Device titles are never touched. Runs to the end even if the caller is cancelled; a failing step is logged and the rest still runs.
 * The same steps leave an account for a [ProfileSwitch], without the logout and without forgetting what is its own.
 *
 * One instance for the app: its account operations (sign-out, sign-in, switches, removing a profile) run one after the
 * other ([operations]), so none of them works on an account another one is just changing.
 */
@Singleton
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
    /** Per-account state outside Room and the downloads (secrets, caches, open questions): gone at a sign-out. */
    private val clearAccountState: suspend (serverId: String) -> Unit,
    /** The same when an account is set aside for another profile: what is its own waits for its return. */
    private val setAsideState: suspend (serverId: String) -> Unit = {},
    /** The same for a saved profile that is removed, while another account is signed in. */
    private val forgetSavedState: suspend (serverId: String) -> Unit = {},
    /** Notes the account's waiting and running downloads, right before a switch pauses them ([SwitchedDownloads]). */
    private val noteDownloads: suspend (serverId: String) -> Unit = {},
    /** Those of the account that is active now go on. */
    private val resumeDownloads: suspend (serverId: String) -> Unit = {},
    /** The account is signed out or removed: nothing waits for its return. */
    private val forgetDownloads: suspend (serverId: String) -> Unit = {},
    /** A sync for the account that just became active. */
    private val syncNow: () -> Unit = {},
    /** Shows the title it last played, paused, in the mini player; never starts it. */
    private val showLastTitle: suspend (serverId: String) -> Unit = {},
    /** The server logout of a saved profile, over its own addresses. */
    private val logoutProfile: suspend (Account, refreshToken: String) -> Boolean = logout,
    /** Once the account has changed: stops what started since [stopPlayback] (a headset key, the car, a tap on the old page). */
    private val stopAgain: suspend () -> Unit = {},
    /** An account has just signed in. */
    private val onSignedIn: suspend (serverId: String) -> Unit = {},
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
        switched: SwitchedDownloads,
        sync: SyncTrigger,
        lastTitle: LastTitleStarter,
        locks: ProfileLocks,
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
        stopPlayback = stopping(player, sleepTimer),
        pushes = listOf({ sid -> progressSync.sync(sid) }, { sid -> bookmarkSync.sync(sid) }),
        awaitOnline = { awaitConnectionAnswer(api.connection, api.checking, CONNECTION_ANSWER_WAIT_MS) },
        logout = serverLogout::logout,
        clearAccountState = cleaner::clear,
        setAsideState = cleaner::setAside,
        forgetSavedState = cleaner::forget,
        noteDownloads = switched::rememberActive,
        resumeDownloads = switched::resume,
        forgetDownloads = switched::forget,
        syncNow = sync::syncNow,
        // Only what the account itself played: the device's own entry stays with the play keys, its screen starts clean.
        showLastTitle = {
            lastTitle.last(ownOnly = true)?.let {
                withContext(Dispatchers.Main.immediate) { player.showPaused(it.serverId, it.itemId) }
            }
        },
        logoutProfile = serverLogout::logoutProfile,
        stopAgain = stopping(player, sleepTimer),
        onSignedIn = locks::accountSignedIn,
    )

    override suspend fun check(): SignOutCheck? {
        val serverId = accounts.account.first()?.serverId ?: return null
        step("pause") { pausePlayback() }
        if (data.unsynced(serverId) > 0) {
            withTimeoutOrNull(PUSH_TIMEOUT_MS) {
                if (awaitOnline()) pushes.forEach { push -> step("push") { push(serverId) } }
            }
        }
        return SignOutCheck(data.unsynced(serverId), data.downloadBytes(serverId), step("card") { data.downloadsOnMissingVolume(serverId) } == true)
    }

    override suspend fun downloadBytes(): Long = try {
        accounts.account.first()?.serverId?.let { data.downloadBytes(it) } ?: 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "download size unreadable", e)
        0
    }

    /** Account operations run one after the other: one never works on an account another one is just changing. */
    private val operations = Mutex()

    /** [block] under [operations], to the end even if the caller goes away. */
    private suspend fun <T> exclusive(block: suspend CoroutineScope.() -> T): T = withContext(NonCancellable) {
        val scope = this
        operations.withLock { scope.block() }
    }

    override suspend fun execute(keepDownloads: Boolean, shownUnsynced: Int?): SignOutResult = exclusive {
        leave(if (keepDownloads) Leave.KEEP else Leave.PURGE, shownUnsynced) { accounts.clear(keepDownloads) }
    }

    override suspend fun switchTo(next: Account, accessToken: String, refreshToken: String) = exclusive { replaceWith(next, accessToken, refreshToken) }

    override suspend fun signIn(next: Account, accessToken: String, refreshToken: String) = exclusive<Unit> {
        if (accounts.account.first()?.serverId == next.serverId) accounts.save(next, accessToken, refreshToken)
        else replaceWith(next, accessToken, refreshToken)
        step("signed in") { onSignedIn(next.serverId) }
    }

    /** [switchTo] itself (callers hold [operations]). [next] may be a profile that was set aside: what waits for it goes on. */
    private suspend fun replaceWith(next: Account, accessToken: String, refreshToken: String) {
        if (accounts.account.first() == null) accounts.save(next, accessToken, refreshToken)
        else leave(Leave.KEEP, shownUnsynced = null) { accounts.save(next, accessToken, refreshToken) }
        step("resume downloads") { resumeDownloads(next.serverId) }
    }

    override suspend fun switchProfile(serverId: String): Boolean = exclusive {
        if (accounts.saved.first().none { it.serverId == serverId }) return@exclusive false
        val current = accounts.account.first()
        if (current == null) {
            val activated = accounts.activate(serverId)
            if (activated) arrive(serverId)
            return@exclusive activated
        }
        saveBeforeLeaving(current.serverId)
        var switched = false
        leave(Leave.SET_ASIDE, shownUnsynced = null) { switched = step("activate") { accounts.activate(serverId) } == true }
        arrive(if (switched) serverId else current.serverId)
        switched
    }

    override suspend fun addProfile(next: Account, accessToken: String, refreshToken: String) = exclusive<Unit> {
        val current = accounts.account.first()
        when {
            current?.serverId == next.serverId -> accounts.save(next, accessToken, refreshToken)
            current == null -> {
                accounts.save(next, accessToken, refreshToken)
                arrive(next.serverId)
            }
            else -> {
                saveBeforeLeaving(current.serverId)
                try {
                    leave(Leave.SET_ASIDE, shownUnsynced = null) { accounts.add(next, accessToken, refreshToken) }
                } catch (e: Exception) {
                    if (e !is CancellationException) arrive(current.serverId) // it stays the active one
                    throw e
                }
                arrive(next.serverId)
            }
        }
        step("signed in") { onSignedIn(next.serverId) }
    }

    /**
     * Before an account is set aside: its playback pauses (the position is final) and its changes go up, at most
     * [SWITCH_PUSH_TIMEOUT_MS]. Offline, out of time or failing, the switch goes on: they stay in Room and go up when the
     * account is active again.
     */
    private suspend fun saveBeforeLeaving(serverId: String) {
        step("pause") { pausePlayback() }
        withTimeoutOrNull(SWITCH_PUSH_TIMEOUT_MS) {
            if (step("unsynced") { data.unsynced(serverId) } != 0 && awaitOnline()) pushes.forEach { push -> step("push") { push(serverId) } }
        }
    }

    /** [serverId] is the active account after a switch: what waits for it goes on and it syncs. Nothing starts playing. */
    private suspend fun arrive(serverId: String) {
        step("resume downloads") { resumeDownloads(serverId) }
        step("last title") { showLastTitle(serverId) }
        step("sync") { syncNow() }
    }

    override suspend fun removeProfile(serverId: String): Boolean = exclusive {
        val account = accounts.saved.first().firstOrNull { it.serverId == serverId } ?: return@exclusive false
        val refresh = try { accounts.savedCredentials(serverId)?.refreshToken } catch (e: Exception) { null }
        // Owed first: should anything stop here, the next start finishes the purge (the janitor), the data is never ownerless.
        if (step("pending purge") { kept.addPendingPurge(serverId); true } != true) return@exclusive false
        // Forgotten only if it still was a saved one (decided in the same write as the removal).
        if (step("forget") { accounts.forget(serverId) } != true) {
            // Still saved, or the signed-in one by now: its data stays wanted.
            step("keep") { kept.removePendingPurge(serverId) }
            return@exclusive false
        }
        if (step("still away") { accounts.account.first()?.serverId != serverId } != true) {
            // Signed in as that account from outside these operations meanwhile: never purge the signed-in account.
            step("keep") { kept.removePendingPurge(serverId) }
            return@exclusive false
        }
        // Its own work only (its downloads were paused when it was set aside): the syncs are the signed-in account's.
        step("cancel work") { work.cancelOwn(serverId) }
        val loggedOut = async {
            if (refresh == null) false
            else withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { step("logout") { logoutProfile(account, refresh) } } == true
        }
        if (step("purge") { data.purge(serverId); true } == true) step("purged") { kept.removePendingPurge(serverId) }
        step("forget kept") { kept.remove(serverId) }
        step("last played") { lastPlayed.drop(serverId) }
        step("paused by a switch") { forgetDownloads(serverId) }
        step("account state") { forgetSavedState(serverId) }
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
        stopWithin("stop playback", stopPlayback)
        if (mode == Leave.PURGE && shownUnsynced != null) {
            val now = step("recount") { data.unsynced(serverId) }
            if (now != null && now > shownUnsynced) {
                Log.i(TAG, "more unsynced changes than shown ($now > $shownUnsynced): asking again")
                return@withContext SignOutResult.Changed(SignOutCheck(now, step("size") { data.downloadBytes(serverId) } ?: 0))
            }
        }
        // What a switch pauses goes on when the account is back; after a sign-out the user decides. Noted before the work goes:
        // a process that dies from here on leaves rows without work, and the note brings them back.
        if (mode == Leave.SET_ASIDE) step("note downloads") { noteDownloads(serverId) }
        step("cancel work") { work.cancel(serverId) }
        val loggedOut = async {
            if (refresh == null) false
            else withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { step("logout") { logout(account, refresh) } } == true
        }
        if (mode != Leave.PURGE) {
            // Once more: the cancel can take seconds, and what was queued in them is paused here too.
            if (mode == Leave.SET_ASIDE) step("note downloads") { noteDownloads(serverId) }
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
        if (mode == Leave.SET_ASIDE) {
            step("account state") { setAsideState(serverId) }
        } else {
            step("last played") { lastPlayed.forget(serverId) }
            step("paused by a switch") { forgetDownloads(serverId) }
            step("account state") { clearAccountState(serverId) }
        }
        val serverConfirmed = loggedOut.await()
        // Late writes of the stopping service (its last progress save) or a worker: the rows once more, now that up to
        // the logout's few seconds have passed. Idempotent.
        if (mode == Leave.PURGE) step("rows again") { data.deleteRows(serverId) }
        if (mode == Leave.SET_ASIDE) Log.i(TAG, "set aside for another profile")
        else Log.i(TAG, "signed out (kept downloads: ${mode == Leave.KEEP}, server logout: $serverConfirmed)")
        finish()
        // The account stayed the active one until now, so a title could still start; before the next account arrives it goes.
        stopWithin("stop again", stopAgain)
        // What that stop ended saved its position just now: the rows of a purged account stay gone.
        if (mode == Leave.PURGE) step("rows after the last stop") { data.deleteRows(serverId) }
        SignOutResult.Done
    }

    /** A stop that hangs must not hold [operations], and with it every sign-in, sign-out and switch, for good. */
    private suspend fun stopWithin(name: String, stop: suspend () -> Unit) {
        if (step(name) { withTimeoutOrNull(STOP_TIMEOUT_MS) { stop() } } == null) Log.w(TAG, "'$name' did not finish in time or failed")
    }

    companion object {
        private const val TAG = "SignOut"
        /** The push of pending changes before the dialog. */
        const val PUSH_TIMEOUT_MS = 15_000L
        const val LOGOUT_TIMEOUT_MS = 5_000L
        /** The push of pending changes before a profile switch: the person is waiting for the other profile. */
        const val SWITCH_PUSH_TIMEOUT_MS = 5_000L
        /** The player's stop, each time. */
        const val STOP_TIMEOUT_MS = 5_000L
        /** After pausing a playing title, before its position is pushed. */
        private const val SAVE_SETTLE_MS = 300L

        /** Stops playback entirely and empties the queue, and with it the sleep timer. */
        private fun stopping(player: PlayerController, sleepTimer: SleepTimer): suspend () -> Unit = {
            withContext(Dispatchers.Main.immediate) { sleepTimer.cancel() }
            player.stop()
        }

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
