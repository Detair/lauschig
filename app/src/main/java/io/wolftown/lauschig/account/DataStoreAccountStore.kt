package io.wolftown.lauschig.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountDataStore by preferencesDataStore(
    name = "account",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Account metadata as JSON, tokens encrypted with [TokenCipher], all in a Preferences DataStore. */
@Singleton
class DataStoreAccountStore(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : AccountStore {
    @Inject constructor(@ApplicationContext context: Context, cipher: TokenCipher) : this(context.accountDataStore, cipher)

    @Serializable
    private data class Dto(
        val serverId: String,
        val userId: String,
        val username: String,
        val isAdmin: Boolean,
        val local: String?,
        val public: String?,
        val homeSsids: Set<String>,
        val allowCleartext: Set<String>,
    )

    override val account: Flow<Account?> = store.data.map { p ->
        p[ACCOUNT]?.let { runCatching { Json.decodeFromString<Dto>(it).toAccount() }.getOrNull() }
    }

    override val needsReauth: Flow<Boolean> = store.data.map { it[NEEDS_REAUTH] ?: false }

    override suspend fun save(a: Account, accessToken: String, refreshToken: String) {
        store.edit {
            it[ACCOUNT] = Json.encodeToString(a.toDto())
            it[ACCESS] = cipher.encrypt(accessToken)
            it[REFRESH] = cipher.encrypt(refreshToken)
            it[NEEDS_REAUTH] = false
        }
    }

    override suspend fun accessToken(): String? = read(ACCESS)

    override suspend fun refreshToken(): String? = read(REFRESH)

    /** Only while an account is stored: a refresh that lands after a sign-out never writes tokens back. */
    override suspend fun updateTokens(access: String, refresh: String) {
        store.edit {
            if (it[ACCOUNT] == null) return@edit
            it[ACCESS] = cipher.encrypt(access)
            it[REFRESH] = cipher.encrypt(refresh)
            it[NEEDS_REAUTH] = false
        }
    }

    override suspend fun markNeedsReauth() {
        store.edit { if (it[ACCOUNT] != null) it[NEEDS_REAUTH] = true }
    }

    override suspend fun clear(keepDownloads: Boolean) {
        store.edit {
            it.remove(ACCOUNT)
            it.remove(ACCESS)
            it.remove(REFRESH)
            it[NEEDS_REAUTH] = false
            it[KEEP_DOWNLOADS_ON_CLEAR] = keepDownloads
        }
    }

    private suspend fun read(key: Preferences.Key<String>): String? =
        store.data.first()[key]?.let(cipher::decrypt)

    private fun Account.toDto() = Dto(
        serverId, userId, username, isAdmin,
        addresses.local?.toString(), addresses.public?.toString(), addresses.homeSsids, allowCleartext,
    )

    private fun Dto.toAccount() = Account(
        serverId, userId, username, isAdmin,
        ServerAddresses(local?.toHttpUrlOrNull(), public?.toHttpUrlOrNull(), homeSsids),
        allowCleartext,
    )

    private companion object {
        val ACCOUNT = stringPreferencesKey("account_json")
        val ACCESS = stringPreferencesKey("access_token_enc")
        val REFRESH = stringPreferencesKey("refresh_token_enc")
        val NEEDS_REAUTH = booleanPreferencesKey("needs_reauth")
        val KEEP_DOWNLOADS_ON_CLEAR = booleanPreferencesKey("keep_downloads_on_clear")
    }
}
