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
 * The last title per account, kept while the account is set aside for another profile: back on it, the mini player, the
 * play keys, resumption, the car and the widget find its title again. For each account its last server title and the last
 * device title it played: what one profile hears on the device does not take another's place. One more entry is
 * the device's alone ([DeviceLibrary.SERVER_ID]): the single title of a version before profiles if it was a device title,
 * one played signed out, or what a signed-out account left. An account that played nothing itself gets that one, until
 * the next title is played by anyone.
 */
interface LastPlayedStore {
    /** The title last played under [serverId]: an account's server title, or with [DeviceLibrary.SERVER_ID] the device's own entry. */
    suspend fun get(serverId: String): LastPlayed?

    /** The device title [account] played last. */
    suspend fun deviceTitleOf(account: String): LastPlayed?

    /** [account]: who was signed in when it played; it owns a device title. A server title is its server's. */
    suspend fun set(last: LastPlayed, account: String? = null)

    /**
     * Sign-out or a removed profile: play keys, resumption and the car cannot start [serverId]'s title any more. If that was
     * a device title it stays on the device, as the device's own entry unless that is newer. Other accounts' entries stay.
     */
    suspend fun forget(serverId: String)

    /** A removed profile: all of [serverId]'s entries go, and nothing passes on to whoever is signed in. */
    suspend fun drop(serverId: String)

    /**
     * The device's own entry becomes [account]'s device title if it has no title of its own at all, server or device: at the
     * start of the app and when an account is set aside, so a title from before profiles stays with the account of that time
     * and goes to nobody else. An account with its own title leaves it alone, whatever is newer: it stays for whoever left it,
     * and whether the app restarted in between makes no difference.
     */
    suspend fun adoptDeviceEntry(account: String)
}

/** What [serverId] itself played last: its server title or a device title. */
suspend fun LastPlayedStore.ownOf(serverId: String): LastPlayed? =
    listOfNotNull(get(serverId), deviceTitleOf(serverId)).maxByOrNull { it.pausedAt }

/** What resumes for the signed-in account [serverId]: its own last title; if it has none, the device's own entry. */
suspend fun LastPlayedStore.forAccount(serverId: String): LastPlayed? = ownOf(serverId) ?: get(DeviceLibrary.SERVER_ID)

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

    override suspend fun deviceTitleOf(account: String): LastPlayed? {
        val p = store.data.first()
        val entry = ownedDevice(account)
        return LastPlayed(DeviceLibrary.SERVER_ID, p[LastPlayedMigration.itemKey(entry)] ?: return null, p[LastPlayedMigration.atKey(entry)] ?: return null)
    }

    override suspend fun set(last: LastPlayed, account: String?) {
        val device = DeviceLibrary.SERVER_ID
        val entry = if (DeviceLibrary.isLocal(last.serverId) && account != null) ownedDevice(account) else last.serverId
        store.edit {
            it[LastPlayedMigration.itemKey(entry)] = last.itemId
            it[LastPlayedMigration.atKey(entry)] = last.pausedAt
            // Someone's own title now: the device's entry was only for the time before.
            if (entry != device) {
                it.remove(LastPlayedMigration.itemKey(device))
                it.remove(LastPlayedMigration.atKey(device))
            }
        }
    }

    override suspend fun forget(serverId: String) {
        val own = ownedDevice(serverId)
        val device = DeviceLibrary.SERVER_ID
        store.edit {
            val serverAt = it[LastPlayedMigration.atKey(serverId)]
            it.remove(LastPlayedMigration.itemKey(serverId))
            it.remove(LastPlayedMigration.atKey(serverId))
            val item = it[LastPlayedMigration.itemKey(own)]
            val at = it[LastPlayedMigration.atKey(own)]
            val wasItsLast = at != null && (serverAt == null || at > serverAt)
            if (item != null && wasItsLast && (it[LastPlayedMigration.atKey(device)] ?: Long.MIN_VALUE) < at!!) {
                it[LastPlayedMigration.itemKey(device)] = item
                it[LastPlayedMigration.atKey(device)] = at
            }
            it.remove(LastPlayedMigration.itemKey(own))
            it.remove(LastPlayedMigration.atKey(own))
        }
    }

    override suspend fun drop(serverId: String) {
        val own = ownedDevice(serverId)
        store.edit {
            it.remove(LastPlayedMigration.itemKey(serverId))
            it.remove(LastPlayedMigration.atKey(serverId))
            it.remove(LastPlayedMigration.itemKey(own))
            it.remove(LastPlayedMigration.atKey(own))
        }
    }

    override suspend fun adoptDeviceEntry(account: String) {
        val own = ownedDevice(account)
        val device = DeviceLibrary.SERVER_ID
        store.edit {
            val item = it[LastPlayedMigration.itemKey(device)] ?: return@edit
            val at = it[LastPlayedMigration.atKey(device)] ?: return@edit
            if (it[LastPlayedMigration.itemKey(own)] != null || it[LastPlayedMigration.itemKey(account)] != null) return@edit
            it[LastPlayedMigration.itemKey(own)] = item
            it[LastPlayedMigration.atKey(own)] = at
            it.remove(LastPlayedMigration.itemKey(device))
            it.remove(LastPlayedMigration.atKey(device))
        }
    }

    private fun ownedDevice(account: String) = "${DeviceLibrary.SERVER_ID}:$account"
}
