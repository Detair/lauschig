package io.wolftown.lauschig.ui.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.db.entities.LibraryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.filterDataStore by preferencesDataStore(name = "filter_prefs")

/** Per-screen kind filter selection. An empty set means "All". Unknown stored names are ignored. */
@Singleton
class FilterPreferences(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.filterDataStore)

    fun kinds(screen: String): Flow<Set<LibraryKind>> = store.data.map { prefs ->
        prefs[key(screen)].orEmpty().mapNotNull { name -> LibraryKind.entries.firstOrNull { it.name == name } }.toSet()
    }

    suspend fun setKinds(screen: String, kinds: Set<LibraryKind>) {
        store.edit { it[key(screen)] = kinds.map { k -> k.name }.toSet() }
    }

    private fun key(screen: String) = stringSetPreferencesKey("filter_kinds_$screen")
}
