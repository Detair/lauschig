package io.wolftown.lauschig.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAccountStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }
    private val store by lazy { DataStoreAccountStore(PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("account.preferences_pb") }, cipher) }
    private val account = Account("aaaaaaaaaaaaaaaa", "u1", "fabian", false, ServerAddresses("http://10.0.0.2:13378".toHttpUrl(), null), emptySet())

    @After fun tearDown() = scope.cancel()

    @Test fun aRefreshThatLandsAfterTheSignOut_writesNoTokens() = runTest {
        store.save(account, "a1", "r1")
        store.clear(keepDownloads = false)
        // A request that was in flight during the sign-out got a 401 and refreshed: nothing comes back.
        store.updateTokens("a2", "r2")
        store.markNeedsReauth()
        assertNull(store.account.first())
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
        assertEquals(false, store.needsReauth.first())
    }

    @Test fun whileSignedIn_refreshAndReauthWork() = runTest {
        store.save(account, "a1", "r1")
        store.updateTokens("a2", "r2")
        assertEquals("a2", store.accessToken())
        assertEquals("r2", store.refreshToken())
        store.markNeedsReauth()
        assertEquals(true, store.needsReauth.first())
        assertEquals(account, store.account.first())
    }
}
