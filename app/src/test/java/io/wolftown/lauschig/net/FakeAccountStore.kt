package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAccountStore : AccountStore {
    val acc = MutableStateFlow<Account?>(null)
    val reauth = MutableStateFlow(false)
    var access: String? = null
    var refresh: String? = null
    var failUpdate = false
    override val account: Flow<Account?> = acc
    override val needsReauth: Flow<Boolean> = reauth
    override suspend fun save(a: Account, accessToken: String, refreshToken: String) {
        acc.value = a; access = accessToken; refresh = refreshToken; reauth.value = false
    }
    override suspend fun accessToken() = access
    override suspend fun refreshToken() = refresh
    override suspend fun updateTokens(access: String, refresh: String) {
        if (failUpdate) throw java.io.IOException("disk full")
        this.access = access; this.refresh = refresh; reauth.value = false
    }
    override suspend fun markNeedsReauth() { reauth.value = true }
    override suspend fun clear(keepDownloads: Boolean) { acc.value = null; access = null; refresh = null }
}
