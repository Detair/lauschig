package io.wolftown.lauschig.account

import kotlinx.coroutines.flow.Flow

/**
 * The signed-in accounts of this device: one active account (everything in the app works with it) and, for a profile
 * switch on a shared tablet (Phase 7), accounts set aside that stay signed in ([saved]). Tokens are only ever stored
 * encrypted; switching moves them as they are stored.
 */
interface AccountStore {
    /** The active account. */
    val account: Flow<Account?>
    val needsReauth: Flow<Boolean>

    /** Stores the account and tokens as the active one (replacing the active account) and clears the re-auth flag. */
    suspend fun save(a: Account, accessToken: String, refreshToken: String)

    /** The active account's server id with its tokens, read together: a switch in between cannot mix two accounts. */
    suspend fun credentials(): Credentials?
    suspend fun accessToken(): String? = credentials()?.accessToken
    suspend fun refreshToken(): String? = credentials()?.refreshToken

    /**
     * Replaces both tokens of [serverId] and clears its re-auth flag – the active account, or a saved one if it was set
     * aside since the refresh started. Nothing if it is neither: a refresh that lands after a sign-out never writes
     * tokens back.
     */
    suspend fun updateTokens(serverId: String, access: String, refresh: String)

    /** Flags that [serverId] (active or saved) should sign in again. Never removes an account or any data. */
    suspend fun markNeedsReauth(serverId: String)

    /**
     * Removes the active account and its tokens and records [keepDownloads]; saved accounts stay.
     * NOTE: this does not delete downloaded files or Room data; that is the job of a later task
     * (sign-out flow), which reads the recorded flag.
     */
    suspend fun clear(keepDownloads: Boolean)

    /** Accounts set aside that stay signed in, without tokens, in the order they were set aside. Never the active one. */
    val saved: Flow<List<Account>>

    /**
     * Stores [a] as the active account like [save], but the active account (another server id) is set aside into [saved]
     * with its tokens and re-auth flag instead of being replaced. [a] itself is no longer a saved one.
     */
    suspend fun add(a: Account, accessToken: String, refreshToken: String)

    /**
     * Makes the saved account [serverId] the active one in one write; the active account (if any) is set aside in its
     * place. False, changing nothing, if [serverId] is not saved.
     */
    suspend fun activate(serverId: String): Boolean

    /**
     * Removes the saved account [serverId] with its tokens; the active account is never touched here. Only the store's
     * last step of removing a profile: [ProfileSwitch.removeProfile] owes the account's purge first (as a sign-out does),
     * so its rows and downloads never stay without an owner.
     */
    suspend fun forget(serverId: String)

    /** The saved account [serverId]'s tokens (decrypted), for its server logout when it is removed; null if not saved. */
    suspend fun savedCredentials(serverId: String): Credentials?
}

/** The active account's server id and tokens (null: none or undecryptable). Its text never shows the tokens. */
class Credentials(val serverId: String, val accessToken: String?, val refreshToken: String?) {
    override fun equals(other: Any?) =
        other is Credentials && other.serverId == serverId && other.accessToken == accessToken && other.refreshToken == refreshToken

    override fun hashCode() = serverId.hashCode()

    override fun toString() = "Credentials($serverId, access=${if (accessToken == null) "none" else "…"}, refresh=${if (refreshToken == null) "none" else "…"})"
}
