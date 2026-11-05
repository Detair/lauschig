package io.wolftown.lauschig.ui.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recentSearchDataStore by preferencesDataStore(name = "recent_searches")

/**
 * The last [MAX] submitted search terms of the signed-in account, most recent first, unique ignoring case. Stored on the
 * device only, per account (a profile set aside finds its own again); signed out there are none.
 */
@Singleton
class RecentSearches(private val store: DataStore<Preferences>, private val activeServer: Flow<String?>) {
    @Inject constructor(@ApplicationContext context: Context, accounts: AccountStore) : this(context.recentSearchDataStore, accounts.account.map { it?.serverId })

    val queries: Flow<List<String>> = combine(activeServer, store.data) { sid, p -> if (sid == null) emptyList() else decode(p[key(sid)]) }

    suspend fun record(query: String) {
        val q = query.trim().replace(SEPARATOR, ' ')
        if (q.isEmpty()) return
        val key = key(activeServer.first() ?: return)
        store.edit { prefs ->
            val next = (listOf(q) + decode(prefs[key]).filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
            prefs[key] = next.joinToString(SEPARATOR.toString())
        }
    }

    /**
     * Before profiles there was one list on the device: it belongs to the account signed in at the upgrade
     * ([activeServerId]); signed out it has no owner and goes. Idempotent; never over a list the account has.
     */
    suspend fun migrate(activeServerId: String?) {
        store.edit { p ->
            val old = p[OLD_KEY] ?: return@edit
            if (activeServerId != null && p[key(activeServerId)] == null) p[key(activeServerId)] = old
            p.remove(OLD_KEY)
        }
    }

    /** Sign-out or a removed profile: this list goes, the next account does not see it. */
    suspend fun clear(serverId: String) {
        store.edit { it.remove(key(serverId)) }
    }

    private fun decode(raw: String?): List<String> = raw.orEmpty().split(SEPARATOR).filter { it.isNotBlank() }

    companion object {
        const val MAX = 8
        private const val SEPARATOR = '\n'

        private fun key(serverId: String) = stringPreferencesKey("queries:$serverId")

        /** The one list of the device before profiles ([migrate]). */
        private val OLD_KEY = stringPreferencesKey("queries")
    }
}
