package io.wolftown.lauschig.ui.discover

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.discoverDataStore by preferencesDataStore(name = "discover_prefs")

/** Sort order of the "All titles" grid. */
enum class DiscoverSort { TITLE, AUTHOR, ADDED, DURATION }

/** Remembers the [DiscoverSort] across app starts; unknown stored names fall back to [DiscoverSort.TITLE]. */
@Singleton
class DiscoverPreferences(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.discoverDataStore)

    val sort: Flow<DiscoverSort> = store.data
        .map { prefs -> DiscoverSort.entries.firstOrNull { it.name == prefs[KEY] } ?: DiscoverSort.TITLE }
        .distinctUntilChanged()

    suspend fun setSort(sort: DiscoverSort) {
        store.edit { it[KEY] = sort.name }
    }

    private companion object {
        val KEY = stringPreferencesKey("all_titles_sort")
    }
}
