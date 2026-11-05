package io.wolftown.lauschig.account

import android.content.Context
import io.wolftown.lauschig.diagnostics.Log
import androidx.core.app.NotificationManagerCompat
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.cleanup.CleanupReminderWorker
import io.wolftown.lauschig.diagnostics.DiagnosticLog
import io.wolftown.lauschig.diagnostics.DiagnosticReport
import io.wolftown.lauschig.download.DownloadPrompts
import io.wolftown.lauschig.rmab.RmabRepository
import io.wolftown.lauschig.ui.common.deleteLegacyCoverCaches
import io.wolftown.lauschig.ui.search.RecentSearches
import io.wolftown.lauschig.ui.setup.OidcSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Per-account state outside Room and the downloads. Each part is cleared on its own; one failing never stops the others.
 *
 * - [clear], every sign-out (also when the downloads are kept): the account's RMAB connection (its token) and recent
 *   searches, a pending SSO attempt, an open download question of that server, the image caches (server covers only; a
 *   kept download's cover is a file in its download directory), the cleanup reminder (it names the account's titles), the
 *   diagnostic log and what this process holds in memory about the account.
 * - [setAside], a profile switch: the same, but the account's own RMAB connection and searches wait for its return, and
 *   the covers on disk stay. Their keys are the cover URLs, without any token ([io.wolftown.lauschig.ui.common.coverModel]),
 *   and the next account only asks for the titles of its own rows; back on the account its covers are there at once.
 * - [forget], a saved profile is removed while another account is active: its connection and searches, and like at a
 *   sign-out the image caches (the active account fetches its own covers again; they cannot be told apart by account).
 *   Everything else here is the active account's by now.
 */
class AccountStateCleaner(
    private val rmab: RmabRepository,
    private val oidc: OidcSignIn,
    private val recentSearches: RecentSearches,
    private val prompts: DownloadPrompts,
    /** Coil's memory and disk cache. */
    private val clearImageCaches: suspend () -> Unit,
    /** Notifications about the account's titles (the cleanup reminder). */
    private val cancelNotifications: () -> Unit,
    /** The diagnostic log and its shared copy: the next account on a family device must not send these entries. */
    private val clearDiagnostics: () -> Unit = {},
    /** Coil's memory cache only. */
    private val clearMemoryCovers: suspend () -> Unit = {},
    /** What this process holds about the account in memory (pending position jumps, connection checks). */
    private val clearMoments: () -> Unit = {},
    /** The device's own last title becomes this account's if it has none ([io.wolftown.lauschig.playback.LastPlayedStore.adoptDeviceEntry]). */
    private val adoptDeviceTitle: suspend (account: String) -> Unit = {},
) {
    @Inject constructor(
        rmab: RmabRepository,
        oidc: OidcSignIn,
        recentSearches: RecentSearches,
        prompts: DownloadPrompts,
        @ApplicationContext context: Context,
        jumps: io.wolftown.lauschig.sync.PositionJumpBus,
        log: io.wolftown.lauschig.connection.ConnectionLog,
        lastPlayed: io.wolftown.lauschig.playback.LastPlayedStore,
    ) : this(
        rmab, oidc, recentSearches, prompts,
        clearImageCaches = {
            val images = SingletonImageLoader.get(context)
            images.memoryCache?.clear()
            withContext(Dispatchers.IO) {
                images.diskCache?.clear()
                // An old install's default cache must not outlive the sign-out either.
                deleteLegacyCoverCaches(context)
            }
        },
        cancelNotifications = { NotificationManagerCompat.from(context).cancel(CleanupReminderWorker.NOTIFICATION_ID) },
        clearDiagnostics = { DiagnosticLog.clear(); DiagnosticReport.discardShared(context) },
        clearMemoryCovers = { SingletonImageLoader.get(context).memoryCache?.clear() },
        clearMoments = { jumps.clear(); log.clear() },
        adoptDeviceTitle = lastPlayed::adoptDeviceEntry,
    )

    suspend fun clear(serverId: String) {
        part("old values") { migrateValues(serverId) }
        part("rmab") { rmab.forget(serverId) }
        part("searches") { recentSearches.clear(serverId) }
        part("images") { clearImageCaches() }
        leaveTheScreen(serverId)
    }

    suspend fun setAside(serverId: String) {
        // The account being left is the one signed in: should the start-up migration not have run yet, these are its values.
        part("old values") { migrate(serverId) }
        part("memory covers") { clearMemoryCovers() }
        leaveTheScreen(serverId)
    }

    suspend fun forget(serverId: String) {
        part("rmab") { rmab.forget(serverId) }
        part("searches") { recentSearches.clear(serverId) }
        part("images") { clearImageCaches() }
    }

    /**
     * Before profiles the RMAB connection, the recent searches and the last device title were one per device: they belong
     * to the account signed in at the upgrade ([activeServerId]); signed out the first two have no owner and go, the device
     * title stays the device's. Idempotent, called at every start.
     */
    suspend fun migrate(activeServerId: String?) {
        migrateValues(activeServerId)
        if (activeServerId != null) part("device title") { adoptDeviceTitle(activeServerId) }
    }

    /** Not the device title: a sign-out has just left that for whoever signs in next. */
    private suspend fun migrateValues(activeServerId: String?) {
        part("rmab migration") { rmab.migrate(activeServerId) }
        part("searches migration") { recentSearches.migrate(activeServerId) }
    }

    /** What may not stay for the next account on the device, whatever becomes of this one. */
    private suspend fun leaveTheScreen(serverId: String) {
        part("oidc") { oidc.clearPending() }
        part("prompts") { prompts.clearFor(serverId) }
        part("notifications") { cancelNotifications() }
        part("diagnostics") { withContext(Dispatchers.IO) { clearDiagnostics() } }
        part("moments") { clearMoments() }
    }

    private suspend fun part(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "clearing $name failed", e)
        }
    }

    private companion object {
        const val TAG = "AccountStateCleaner"
    }
}
