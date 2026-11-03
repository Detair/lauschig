package io.wolftown.lauschig.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lastPlayedDataStore by preferencesDataStore(name = "last_played")

/** The title last played on THIS device and when it last paused or stopped (epoch ms). Never synced. */
data class LastPlayed(val serverId: String, val itemId: String, val pausedAt: Long)

interface LastPlayedStore {
    suspend fun get(): LastPlayed?
    suspend fun set(last: LastPlayed)
}

@Singleton
class DataStoreLastPlayedStore @Inject constructor(@ApplicationContext context: Context) : LastPlayedStore {
    private val store = context.lastPlayedDataStore

    override suspend fun get(): LastPlayed? {
        val p = store.data.first()
        return LastPlayed(p[SERVER] ?: return null, p[ITEM] ?: return null, p[AT] ?: return null)
    }

    override suspend fun set(last: LastPlayed) {
        store.edit {
            it[SERVER] = last.serverId
            it[ITEM] = last.itemId
            it[AT] = last.pausedAt
        }
    }

    private companion object {
        val SERVER = stringPreferencesKey("server_id")
        val ITEM = stringPreferencesKey("item_id")
        val AT = longPreferencesKey("paused_at")
    }
}
