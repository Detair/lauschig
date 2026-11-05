package io.wolftown.lauschig.download

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.volumePurgeDataStore by preferencesDataStore(name = "volume_purges")

/**
 * Download roots on volumes that were not there when an account's data was purged (an SD card that was out): the purge
 * counts as done only once each of them was mounted again and the account's directory there deleted.
 */
interface VolumePurges {
    suspend fun add(serverId: String, roots: Set<String>)

    /**
     * Deletes what it can now with [delete] (true: done, false: failed, null: the volume is still not there) and returns
     * the roots still waiting for [serverId].
     */
    suspend fun finish(serverId: String, delete: (root: String) -> Boolean?): Set<String>

    /** Nothing recorded (tests, and code paths that never see another volume). */
    object None : VolumePurges {
        override suspend fun add(serverId: String, roots: Set<String>) {}
        override suspend fun finish(serverId: String, delete: (root: String) -> Boolean?): Set<String> = emptySet()
    }
}

@Singleton
class DataStoreVolumePurges @Inject constructor(@ApplicationContext context: Context) : VolumePurges {
    private val store = context.volumePurgeDataStore

    override suspend fun add(serverId: String, roots: Set<String>) {
        if (roots.isEmpty()) return
        store.edit { p -> p[WAITING] = p[WAITING].orEmpty() + roots.map { "$serverId|$it" } }
    }

    override suspend fun finish(serverId: String, delete: (root: String) -> Boolean?): Set<String> {
        val mine = store.data.first()[WAITING].orEmpty().filter { it.startsWith("$serverId|") }
        val done = mine.filter { delete(it.substringAfter('|')) == true }.toSet()
        if (done.isNotEmpty()) store.edit { p -> p[WAITING] = p[WAITING].orEmpty() - done }
        return (mine - done).map { it.substringAfter('|') }.toSet()
    }

    private companion object {
        val WAITING = stringSetPreferencesKey("waiting")
    }
}
