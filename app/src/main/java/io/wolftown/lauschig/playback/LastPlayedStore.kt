package io.wolftown.lauschig.playback

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lastPlayedDataStore by preferencesDataStore(
    name = "last_played",
    produceMigrations = { listOf(LastPlayedMigration) },
)

/** The title last played on THIS device and when it last paused or stopped (epoch ms). Never synced. */
data class LastPlayed(val serverId: String, val itemId: String, val pausedAt: Long)

/**
 * One title per account, kept while the account is set aside for another profile: back on it, the play keys, resumption
 * and the car find its title again. The device's own titles are one more entry ([DeviceLibrary.SERVER_ID]): they are this
 * device's, whoever is signed in.
 */
interface LastPlayedStore {
    /** The title last played under [serverId]: an account's server title, or with [DeviceLibrary.SERVER_ID] the device title. */
    suspend fun get(serverId: String): LastPlayed?
    suspend fun set(last: LastPlayed)
    /** Sign-out or a removed profile: play keys, resumption and the car cannot start its title any more. Other entries stay. */
    suspend fun forget(serverId: String)
}

/** What resumes for the signed-in account [serverId]: its own title or the device title, whichever was played last. */
suspend fun LastPlayedStore.forAccount(serverId: String): LastPlayed? =
    listOfNotNull(get(serverId), get(DeviceLibrary.SERVER_ID)).maxByOrNull { it.pausedAt }

/** Before profiles there was one title on the device, with its server id: it moves to the entry of that id. */
object LastPlayedMigration : DataMigration<Preferences> {
    private val SERVER = stringPreferencesKey("server_id")
    private val ITEM = stringPreferencesKey("item_id")
    private val AT = longPreferencesKey("paused_at")

    override suspend fun shouldMigrate(currentData: Preferences) = SERVER in currentData || ITEM in currentData || AT in currentData

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        val server = this[SERVER]
        val item = this[ITEM]
        val at = this[AT]
        if (server != null && item != null && at != null) {
            this[itemKey(server)] = item
            this[atKey(server)] = at
        }
        remove(SERVER)
        remove(ITEM)
        remove(AT)
    }.toPreferences()

    override suspend fun cleanUp() = Unit

    internal fun itemKey(serverId: String) = stringPreferencesKey("item_id:$serverId")
    internal fun atKey(serverId: String) = longPreferencesKey("paused_at:$serverId")
}

@Singleton
class DataStoreLastPlayedStore(private val store: DataStore<Preferences>) : LastPlayedStore {
    @Inject constructor(@ApplicationContext context: Context) : this(context.lastPlayedDataStore)

    override suspend fun get(serverId: String): LastPlayed? {
        val p = store.data.first()
        return LastPlayed(serverId, p[LastPlayedMigration.itemKey(serverId)] ?: return null, p[LastPlayedMigration.atKey(serverId)] ?: return null)
    }

    override suspend fun set(last: LastPlayed) {
        store.edit {
            it[LastPlayedMigration.itemKey(last.serverId)] = last.itemId
            it[LastPlayedMigration.atKey(last.serverId)] = last.pausedAt
        }
    }

    override suspend fun forget(serverId: String) {
        store.edit {
            it.remove(LastPlayedMigration.itemKey(serverId))
            it.remove(LastPlayedMigration.atKey(serverId))
        }
    }
}
