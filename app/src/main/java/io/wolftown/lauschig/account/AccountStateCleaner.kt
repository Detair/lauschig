package io.wolftown.lauschig.account

import android.content.Context
import android.util.Log
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.download.DownloadPrompts
import io.wolftown.lauschig.rmab.RmabRepository
import io.wolftown.lauschig.ui.search.RecentSearches
import io.wolftown.lauschig.ui.setup.OidcSignIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Per-account state outside Room and the downloads that goes on every sign-out (also when the downloads are kept): the
 * RMAB connection (its token), a pending SSO attempt, the user's recent searches, an open download question of that
 * server and the image caches (server covers only; a kept download's cover is a file in its download directory). Each
 * part is cleared on its own; one failing never stops the others.
 */
class AccountStateCleaner(
    private val rmab: RmabRepository,
    private val oidc: OidcSignIn,
    private val recentSearches: RecentSearches,
    private val prompts: DownloadPrompts,
    /** Coil's memory and disk cache. */
    private val clearImageCaches: suspend () -> Unit,
) {
    @Inject constructor(
        rmab: RmabRepository,
        oidc: OidcSignIn,
        recentSearches: RecentSearches,
        prompts: DownloadPrompts,
        @ApplicationContext context: Context,
    ) : this(
        rmab, oidc, recentSearches, prompts,
        clearImageCaches = {
            val images = SingletonImageLoader.get(context)
            images.memoryCache?.clear()
            withContext(Dispatchers.IO) { images.diskCache?.clear() }
        },
    )

    suspend fun clear(serverId: String) {
        part("rmab") { rmab.disconnect() }
        part("oidc") { oidc.clearPending() }
        part("searches") { recentSearches.clear() }
        part("prompts") { prompts.clearFor(serverId) }
        part("images") { clearImageCaches() }
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
