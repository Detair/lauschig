package io.wolftown.lauschig.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeKeptAccounts(initial: List<KeptAccount> = emptyList()) : KeptAccountStore {
    val list = MutableStateFlow(initial)
    override val accounts: Flow<List<KeptAccount>> = list
    override suspend fun put(account: KeptAccount) = list.update { l -> l.filterNot { it.serverId == account.serverId } + account }
    override suspend fun remove(serverId: String) = list.update { l -> l.filterNot { it.serverId == serverId } }
    override suspend fun markAsked(serverId: String) = list.update { l -> l.map { if (it.serverId == serverId) it.copy(asked = true) else it } }
}
