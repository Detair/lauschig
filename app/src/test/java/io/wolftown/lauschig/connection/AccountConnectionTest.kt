package io.wolftown.lauschig.connection

import io.wolftown.lauschig.account.Account
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountConnectionTest {
    private fun account(id: String, url: String) = Account(id, "u", "n", false, ServerAddresses(null, url.toHttpUrl()), emptySet())
    private val a = account("aaaaaaaaaaaaaaaa", "https://a.example/")
    private val b = account("bbbbbbbbbbbbbbbb", "https://b.example/")

    @Test fun everyServer_startsWithAReset_soTheOldServersOnlineNeverLeaks() = runTest(UnconfinedTestDispatcher()) {
        val accounts = MutableStateFlow<Account?>(a)
        val seen = mutableListOf<ConnectionState?>()
        val probes = mutableListOf<String>()
        backgroundScope.launch {
            accountConnection(accounts) { addresses ->
                flow {
                    probes += addresses.public.toString()
                    emit(ConnectionState.Online(addresses.public!!, ConnectionState.Via.PUBLIC))
                    awaitCancellation()
                }
            }.collect { seen += it }
        }
        accounts.value = a.copy(username = "renamed") // same server and addresses: no new probe loop
        accounts.value = b
        accounts.value = null
        assertEquals(listOf("https://a.example/", "https://b.example/"), probes)
        assertEquals(
            listOf(
                null, ConnectionState.Online("https://a.example/".toHttpUrl(), ConnectionState.Via.PUBLIC),
                null, ConnectionState.Online("https://b.example/".toHttpUrl(), ConnectionState.Via.PUBLIC),
                ConnectionState.Offline,
            ),
            seen,
        )
    }
}
