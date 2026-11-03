package io.wolftown.lauschig.account

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS
import io.wolftown.lauschig.connection.awaitConnectionAnswer
import io.wolftown.lauschig.download.DownloadPrompts
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.LastPlayedStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.SleepTimer
import io.wolftown.lauschig.playback.forgetServerTitle
import io.wolftown.lauschig.rmab.RmabRepository
import io.wolftown.lauschig.sync.BookmarkSync
import io.wolftown.lauschig.sync.ProgressSync
import io.wolftown.lauschig.ui.search.RecentSearches
import io.wolftown.lauschig.ui.setup.OidcSignIn
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

interface AccountSignOut {
    /**
     * Pauses playback (its position is saved), pushes what the syncs would upload – at most [SignOut.PUSH_TIMEOUT_MS],
     * only once the connection check answered "online" – and reports what is left. Null without an account. Never signs out.
     */
    suspend fun check(): SignOutCheck?

    /** Signs out (see [SignOut]); [keepDownloads] keeps this account's downloads and rows for its next sign-in. */
    suspend fun execute(keepDownloads: Boolean)
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
        rmab: RmabRepository,
        oidc: OidcSignIn,
        recentSearches: RecentSearches,
        prompts: DownloadPrompts,
        @ApplicationContext context: Context,
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
        clearAccountState = { sid ->
            step("rmab") { rmab.disconnect() }
            step("oidc") { oidc.clearPending() }
            step("searches") { recentSearches.clear() }
            step("prompts") { prompts.clearFor(sid) }
            // Server covers only: a kept download's cover is a file in its download directory.
            step("covers") {
                val images = SingletonImageLoader.get(context)
                images.memoryCache?.clear()
                withContext(Dispatchers.IO) { images.diskCache?.clear() }
            }
        },
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

    override suspend fun execute(keepDownloads: Boolean) = withContext(NonCancellable) {
        val account = accounts.account.first() ?: return@withContext
        val serverId = account.serverId
        val refresh = try { accounts.refreshToken() } catch (e: Exception) { null }
        step("stop playback") { stopPlayback() }
        step("cancel work") { work.cancel(serverId) }
        val loggedOut = async {
            if (refresh == null) false
            else withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { step("logout") { logout(account, refresh) } } == true
        }
        if (keepDownloads) {
            step("pause downloads") { data.pauseDownloads(serverId) }
            step("remember") { kept.put(KeptAccount(serverId, account.username, account.displayHost)) }
        } else {
            step("purge") { data.purge(serverId) }
            step("forget") { kept.remove(serverId) }
        }
        step("last played") { lastPlayed.forgetServerTitle() }
        step("account state") { clearAccountState(serverId) }
        Log.i(TAG, "signed out (kept downloads: $keepDownloads, server logout: ${loggedOut.await()})")
        accounts.clear(keepDownloads)
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
