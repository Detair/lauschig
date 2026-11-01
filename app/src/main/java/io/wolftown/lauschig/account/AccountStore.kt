package io.wolftown.lauschig.account

import kotlinx.coroutines.flow.Flow

interface AccountStore {
    val account: Flow<Account?>
    val needsReauth: Flow<Boolean>

    /** Stores the account and tokens and clears the re-auth flag. */
    suspend fun save(a: Account, accessToken: String, refreshToken: String)
    suspend fun accessToken(): String?
    suspend fun refreshToken(): String?

    /** Replaces both tokens and clears the re-auth flag. */
    suspend fun updateTokens(access: String, refresh: String)

    /** Flags that the user should sign in again. Never removes the account or any data. */
    suspend fun markNeedsReauth()

    /**
     * Removes the account and tokens and records [keepDownloads].
     * NOTE: this does not delete downloaded files or Room data; that is the job of a later task
     * (sign-out flow), which reads the recorded flag.
     */
    suspend fun clear(keepDownloads: Boolean)
}
