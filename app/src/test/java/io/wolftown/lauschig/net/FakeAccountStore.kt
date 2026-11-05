package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.Credentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeAccountStore : AccountStore {
    val acc = MutableStateFlow<Account?>(null)
    val reauth = MutableStateFlow(false)
    var access: String? = null
    var refresh: String? = null
    var failUpdate = false

    /** A saved account with its tokens and flag (in the real store: ciphertext). */
    data class Aside(val account: Account, val access: String?, val refresh: String?, val reauth: Boolean)
    val aside = MutableStateFlow<List<Aside>>(emptyList())

    override val account: Flow<Account?> = acc
    override val needsReauth: Flow<Boolean> = reauth
    override val saved: Flow<List<Account>> = aside.map { list -> list.map { it.account } }
    override val savedNeedingReauth: Flow<Set<String>> = aside.map { list -> list.filter { it.reauth }.mapTo(HashSet()) { it.account.serverId } }

    override suspend fun save(a: Account, accessToken: String, refreshToken: String) {
        acc.value = a; access = accessToken; refresh = refreshToken; reauth.value = false
        aside.value = aside.value.filterNot { it.account.serverId == a.serverId }
    }
    override suspend fun credentials() = acc.value?.let { Credentials(it.serverId, access, refresh) }
    override suspend fun updateTokens(serverId: String, access: String, refresh: String) {
        if (failUpdate) throw java.io.IOException("disk full")
        if (acc.value?.serverId == serverId) {
            this.access = access; this.refresh = refresh; reauth.value = false
        } else {
            aside.value = aside.value.map { if (it.account.serverId == serverId) it.copy(access = access, refresh = refresh, reauth = false) else it }
        }
    }
    override suspend fun markNeedsReauth(serverId: String) {
        if (acc.value?.serverId == serverId) reauth.value = true
        else aside.value = aside.value.map { if (it.account.serverId == serverId) it.copy(reauth = true) else it }
    }
    /** The flag of the last [clear], null if it was never called. */
    var clearedKeeping: Boolean? = null
    /** Called on [clear] (tests that record the order of a sign-out). */
    var onClear: () -> Unit = {}
    override suspend fun clear(keepDownloads: Boolean) { onClear(); acc.value = null; access = null; refresh = null; clearedKeeping = keepDownloads }

    private fun active(): Aside? = acc.value?.let { Aside(it, access, refresh, reauth.value) }

    private fun putActive(e: Aside) { acc.value = e.account; access = e.access; refresh = e.refresh; reauth.value = e.reauth }

    /** Called on [add] and [activate] (tests that record the order of a switch). */
    var onSwitch: () -> Unit = {}

    override suspend fun add(a: Account, accessToken: String, refreshToken: String) {
        onSwitch()
        val old = active()?.takeIf { it.account.serverId != a.serverId }
        putActive(Aside(a, accessToken, refreshToken, false))
        aside.value = aside.value.filterNot { it.account.serverId == a.serverId || it.account.serverId == old?.account?.serverId } + listOfNotNull(old)
    }
    override suspend fun activate(serverId: String): Boolean {
        val next = aside.value.firstOrNull { it.account.serverId == serverId } ?: return false
        onSwitch()
        val old = active()
        putActive(next)
        aside.value = aside.value.filterNot { it.account.serverId == serverId } + listOfNotNull(old)
        return true
    }
    /** Called on [forget] (tests that record the order of a removal). */
    var onForget: () -> Unit = {}
    override suspend fun forget(serverId: String): Boolean {
        onForget()
        val before = aside.value
        aside.value = before.filterNot { it.account.serverId == serverId }
        return aside.value.size != before.size
    }
    override suspend fun savedCredentials(serverId: String) =
        aside.value.firstOrNull { it.account.serverId == serverId }?.let { Credentials(serverId, it.access, it.refresh) }
}
