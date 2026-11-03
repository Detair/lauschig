package io.wolftown.lauschig.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeptAccountsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("kept.preferences_pb") } }
    private val kept by lazy { DataStoreKeptAccounts(store) }

    @After fun tearDown() = scope.cancel()

    @Test fun putReplacesTheSameServer_removeAndMarkAsked() = runTest {
        assertEquals(emptyList<KeptAccount>(), kept.accounts.first())
        kept.put(KeptAccount("a", "fabian", "abs.example"))
        kept.put(KeptAccount("b", "matthias", "10.0.0.2:13378"))
        kept.markAsked("a")
        assertEquals(listOf(KeptAccount("a", "fabian", "abs.example", asked = true), KeptAccount("b", "matthias", "10.0.0.2:13378")), kept.accounts.first())
        // A new sign-out with "keep" of the same account asks again.
        kept.put(KeptAccount("a", "fabian", "abs.example"))
        assertEquals(listOf(KeptAccount("b", "matthias", "10.0.0.2:13378"), KeptAccount("a", "fabian", "abs.example")), kept.accounts.first())
        kept.remove("b")
        kept.remove("a")
        assertEquals(emptyList<KeptAccount>(), kept.accounts.first())
        // A second instance on the same file reads the same (a new process).
        kept.put(KeptAccount("c", "lukas", "h"))
        assertEquals(listOf(KeptAccount("c", "lukas", "h")), DataStoreKeptAccounts(store).accounts.first())
    }

    @Test fun unreadableValue_isEmpty_notACrash() = runTest {
        store.edit { it[stringPreferencesKey("accounts_json")] = "{not json" }
        assertEquals(emptyList<KeptAccount>(), kept.accounts.first())
        kept.put(KeptAccount("a", "fabian", "h"))
        assertEquals(1, kept.accounts.first().size)
    }

    @Test fun displayHost_publicFirst_portOnlyWhenNotTheDefault() {
        fun acc(local: String?, public: String?) =
            Account("s", "u", "n", false, ServerAddresses(local?.toHttpUrl(), public?.toHttpUrl()), emptySet())
        assertEquals("abs.example.org", acc("http://10.0.0.2:13378", "https://abs.example.org").displayHost)
        assertEquals("abs.example.org:8443", acc(null, "https://abs.example.org:8443/abs/").displayHost)
        assertEquals("10.0.0.2:13378", acc("http://10.0.0.2:13378", null).displayHost)
        assertEquals("nas.local", acc("http://nas.local", null).displayHost)
    }
}
