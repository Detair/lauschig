package io.wolftown.lauschig.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountDataStore by preferencesDataStore(
    name = "account",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Account metadata as JSON, tokens encrypted with [TokenCipher], all in a Preferences DataStore. The active account has
 * the keys it always had; saved accounts (set aside, [AccountStore.saved]) live in one more key, only while there are
 * any – a single account is stored exactly as before. Their tokens are the ciphertext the active account had: a switch
 * moves it, it never decrypts or re-encrypts.
 */
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

    /** A saved account: its metadata, its encrypted tokens and its re-auth flag. */
    @Serializable
    private data class Saved(val account: Dto, val access: String? = null, val refresh: String? = null, val needsReauth: Boolean = false)

    override val account: Flow<Account?> = store.data.map { p -> p.activeDto()?.toAccount() }

    override val needsReauth: Flow<Boolean> = store.data.map { it[NEEDS_REAUTH] ?: false }

    override val saved: Flow<List<Account>> = store.data.map { p -> p.savedList().map { it.account.toAccount() } }

    override suspend fun save(a: Account, accessToken: String, refreshToken: String) {
        store.edit {
            it.putActive(Saved(a.toDto(), cipher.encrypt(accessToken), cipher.encrypt(refreshToken)))
            it.putSaved(it.savedList().filterNot { s -> s.account.serverId == a.serverId })
        }
    }

    override suspend fun add(a: Account, accessToken: String, refreshToken: String) {
        store.edit {
            val aside = it.activeEntry()?.takeIf { e -> e.account.serverId != a.serverId }
            it.putActive(Saved(a.toDto(), cipher.encrypt(accessToken), cipher.encrypt(refreshToken)))
            it.putSaved(it.savedList().filterNot { s -> s.account.serverId == a.serverId || s.account.serverId == aside?.account?.serverId } + listOfNotNull(aside))
        }
    }

    override suspend fun activate(serverId: String): Boolean {
        var done = false
        store.edit {
            val list = it.savedList()
            val next = list.firstOrNull { s -> s.account.serverId == serverId } ?: return@edit
            val aside = it.activeEntry()
            it.putActive(next)
            it.putSaved(list.filterNot { s -> s.account.serverId == serverId } + listOfNotNull(aside))
            done = true
        }
        return done
    }

    override suspend fun forget(serverId: String) {
        store.edit { it.putSaved(it.savedList().filterNot { s -> s.account.serverId == serverId }) }
    }

    override suspend fun savedCredentials(serverId: String): Credentials? {
        val saved = store.data.first().savedList().firstOrNull { it.account.serverId == serverId } ?: return null
        return Credentials(serverId, saved.access?.let(cipher::decrypt), saved.refresh?.let(cipher::decrypt))
    }

    override suspend fun credentials(): Credentials? {
        val p = store.data.first()
        val serverId = p.activeDto()?.serverId ?: return null
        return Credentials(serverId, p[ACCESS]?.let(cipher::decrypt), p[REFRESH]?.let(cipher::decrypt))
    }

    /** Only while that account is stored: a refresh that lands after a sign-out never writes tokens back. */
    override suspend fun updateTokens(serverId: String, access: String, refresh: String) {
        store.edit {
            if (it.activeDto()?.serverId == serverId) {
                it[ACCESS] = cipher.encrypt(access)
                it[REFRESH] = cipher.encrypt(refresh)
                it[NEEDS_REAUTH] = false
            } else {
                it.updateSaved(serverId) { s -> s.copy(access = cipher.encrypt(access), refresh = cipher.encrypt(refresh), needsReauth = false) }
            }
        }
    }

    override suspend fun markNeedsReauth(serverId: String) {
        store.edit {
            if (it.activeDto()?.serverId == serverId) it[NEEDS_REAUTH] = true
            else it.updateSaved(serverId) { s -> s.copy(needsReauth = true) }
        }
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

    private fun Preferences.activeDto(): Dto? = this[ACCOUNT]?.let { runCatching { Json.decodeFromString<Dto>(it) }.getOrNull() }

    /** The active account as a saved entry, exactly as stored (ciphertext); null without a readable one. */
    private fun Preferences.activeEntry(): Saved? =
        activeDto()?.let { Saved(it, this[ACCESS], this[REFRESH], this[NEEDS_REAUTH] ?: false) }

    private fun MutablePreferences.putActive(e: Saved) {
        this[ACCOUNT] = Json.encodeToString(e.account)
        if (e.access != null) this[ACCESS] = e.access else remove(ACCESS)
        if (e.refresh != null) this[REFRESH] = e.refresh else remove(REFRESH)
        this[NEEDS_REAUTH] = e.needsReauth
    }

    /** The readable saved entries. An unreadable one (damaged, or from a later version) is skipped and kept as it is. */
    private fun Preferences.savedList(): List<Saved> = savedElements().mapNotNull { it.second }

    /** Every stored element with its entry (null: unreadable). A value that is no list at all is one unreadable element. */
    private fun Preferences.savedElements(): List<Pair<JsonElement, Saved?>> {
        val raw = this[SAVED] ?: return emptyList()
        val array = runCatching { lenient.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return listOf(JsonPrimitive(raw) to null)
        return array.map { e -> e to runCatching { lenient.decodeFromJsonElement<Saved>(e) }.getOrNull() }
    }

    /**
     * Stores [list] as the readable saved entries, writing unreadable ones back as they were; nothing is written while
     * the readable entries do not change. No key without saved accounts: a single account keeps the form it always had.
     */
    private fun MutablePreferences.putSaved(list: List<Saved>) {
        val elements = savedElements()
        if (elements.mapNotNull { it.second } == list) return
        val all = list.map { lenient.encodeToJsonElement(it) } + elements.filter { it.second == null }.map { it.first }
        if (all.isEmpty()) remove(SAVED) else this[SAVED] = JsonArray(all).toString()
    }

    private fun MutablePreferences.updateSaved(serverId: String, change: (Saved) -> Saved) {
        val list = savedList()
        if (list.none { it.account.serverId == serverId }) return
        putSaved(list.map { if (it.account.serverId == serverId) change(it) else it })
    }

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
        /** Saved accounts (JSON list); absent while there are none. */
        val SAVED = stringPreferencesKey("saved_accounts_json")

        /** For the saved list, which later versions may extend. */
        val lenient = Json { ignoreUnknownKeys = true }
    }
}
