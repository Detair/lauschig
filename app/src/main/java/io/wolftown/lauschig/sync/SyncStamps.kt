package io.wolftown.lauschig.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncStampsDataStore by preferencesDataStore(name = "sync_stamps")

/** When a background job last succeeded (epoch ms), per key; used to throttle expensive syncs. Device-local. */
interface SyncStamps {
    suspend fun get(key: String): Long?
    suspend fun set(key: String, at: Long)
}

class InMemorySyncStamps : SyncStamps {
    private val map = ConcurrentHashMap<String, Long>()
    override suspend fun get(key: String): Long? = map[key]
    override suspend fun set(key: String, at: Long) { map[key] = at }
}

@Singleton
class DataStoreSyncStamps @Inject constructor(@ApplicationContext context: Context) : SyncStamps {
    private val store = context.syncStampsDataStore
    override suspend fun get(key: String): Long? = store.data.first()[longPreferencesKey(key)]
    override suspend fun set(key: String, at: Long) { store.edit { it[longPreferencesKey(key)] = at } }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncStampsModule {
    @Binds abstract fun syncStamps(impl: DataStoreSyncStamps): SyncStamps
}
