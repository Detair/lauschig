package io.wolftown.lauschig.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.keptAccountsDataStore by preferencesDataStore(
    name = "kept_accounts",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * A signed-out account whose downloads and rows stayed on the device (sign-out with "Downloads behalten"). Only what the
 * question to the next account and the Downloads page need, no credentials. [asked]: that question was answered with
 * "Behalten" – never asked again, but the Downloads page still offers to delete it. [userId]: the ABS user (empty in
 * entries written before it was stored).
 */
@Serializable
data class KeptAccount(val serverId: String, val username: String, val host: String, val asked: Boolean = false, val userId: String = "")

interface KeptAccountStore {
    val accounts: Flow<List<KeptAccount>>

    /** Adds [account], replacing an entry of the same server id (a new sign-out with "keep" asks again). */
    suspend fun put(account: KeptAccount)
    suspend fun remove(serverId: String)
    suspend fun markAsked(serverId: String)

    /** Server ids whose purge failed during a sign-out; the next app start finishes them ([AccountDataJanitor]). */
    val pendingPurges: Flow<Set<String>>
    suspend fun addPendingPurge(serverId: String)
    suspend fun removePendingPurge(serverId: String)
}

@Singleton
class DataStoreKeptAccounts(private val store: DataStore<Preferences>) : KeptAccountStore {
    @Inject constructor(@ApplicationContext context: Context) : this(context.keptAccountsDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    override val accounts: Flow<List<KeptAccount>> = store.data.map { decode(it) }

    override suspend fun put(account: KeptAccount) = update { list -> list.filterNot { it.serverId == account.serverId } + account }

    override suspend fun remove(serverId: String) = update { list -> list.filterNot { it.serverId == serverId } }

    override suspend fun markAsked(serverId: String) = update { list -> list.map { if (it.serverId == serverId) it.copy(asked = true) else it } }

    override val pendingPurges: Flow<Set<String>> = store.data.map { it[PENDING] ?: emptySet() }

    override suspend fun addPendingPurge(serverId: String) {
        store.edit { it[PENDING] = (it[PENDING] ?: emptySet()) + serverId }
    }

    override suspend fun removePendingPurge(serverId: String) {
        store.edit { it[PENDING] = (it[PENDING] ?: emptySet()) - serverId }
    }

    private suspend fun update(change: (List<KeptAccount>) -> List<KeptAccount>) {
        store.edit { prefs ->
            val next = change(decode(prefs))
            if (next.isEmpty()) prefs.remove(KEY) else prefs[KEY] = json.encodeToString(next)
        }
    }

    private fun decode(prefs: Preferences): List<KeptAccount> =
        prefs[KEY]?.let { runCatching { json.decodeFromString<List<KeptAccount>>(it) }.getOrNull() }.orEmpty()

    private companion object {
        val KEY = stringPreferencesKey("accounts_json")
        val PENDING = stringSetPreferencesKey("pending_purges")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KeptAccountsModule {
    @Binds abstract fun keptAccounts(impl: DataStoreKeptAccounts): KeptAccountStore
}
