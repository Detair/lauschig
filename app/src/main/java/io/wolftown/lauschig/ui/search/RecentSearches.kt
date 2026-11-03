package io.wolftown.lauschig.ui.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recentSearchDataStore by preferencesDataStore(name = "recent_searches")

/** The last [MAX] submitted search terms, most recent first, unique ignoring case. Stored on the device only. */
@Singleton
class RecentSearches(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.recentSearchDataStore)

    val queries: Flow<List<String>> = store.data.map { decode(it[KEY]) }

    suspend fun record(query: String) {
        val q = query.trim().replace(SEPARATOR, ' ')
        if (q.isEmpty()) return
        store.edit { prefs ->
            val next = (listOf(q) + decode(prefs[KEY]).filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
            prefs[KEY] = next.joinToString(SEPARATOR.toString())
        }
    }

    /** Sign-out: the next account does not see this user's searches. */
    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): List<String> = raw.orEmpty().split(SEPARATOR).filter { it.isNotBlank() }

    companion object {
        const val MAX = 8
        private const val SEPARATOR = '\n'
        private val KEY = stringPreferencesKey("queries")
    }
}
