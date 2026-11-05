package io.wolftown.lauschig.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The multi-account seam of [DataStoreAccountStore] (Phase 6, Task 12.1): more than one account on the device, one of
 * them active; switching never decrypts or re-encrypts a token, and a single account is stored exactly as before.
 */
class AccountStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Counts what it does: tokens only move as ciphertext. */
    private val cipher = object : TokenCipher {
        var encrypts = 0
        var decrypts = 0
        override fun encrypt(plain: String) = "enc:$plain".also { encrypts++ }
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:").also { decrypts++ }
    }
    private val prefs: DataStore<Preferences> by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("account.preferences_pb") } }
    private val store by lazy { DataStoreAccountStore(prefs, cipher) }

    private fun account(id: String, user: String = "u-$id") =
        Account(id, user, "name-$id", false, ServerAddresses("http://10.0.0.2:13378".toHttpUrl(), null), emptySet())

    private val fabian = account("aaaaaaaaaaaaaaaa", "u1")
    private val matthias = account("bbbbbbbbbbbbbbbb", "u2")
    private val third = account("cccccccccccccccc", "u3")

    @After fun tearDown() = scope.cancel()

    private suspend fun rawKeys() = prefs.data.first().asMap().keys.map { it.name }.toSet()

    @Test fun anInstallOfTheCurrentFormat_readsItsAccountUnchanged() = runTest {
        // Exactly what the store wrote before the seam (keys and JSON as of 0.3.0).
        prefs.edit {
            it[stringPreferencesKey("account_json")] = """{"serverId":"aaaaaaaaaaaaaaaa","userId":"u1","username":"fabian","isAdmin":true,""" +
                """"local":"http://10.0.0.2:13378/","public":"https://abs.example.org/","homeSsids":["Heimnetz"],"allowCleartext":["http://10.0.0.2:13378"]}"""
            it[stringPreferencesKey("access_token_enc")] = "enc:a1"
            it[stringPreferencesKey("refresh_token_enc")] = "enc:r1"
            it[booleanPreferencesKey("needs_reauth")] = true
        }
        val expected = Account(
            "aaaaaaaaaaaaaaaa", "u1", "fabian", true,
            ServerAddresses("http://10.0.0.2:13378/".toHttpUrl(), "https://abs.example.org/".toHttpUrl(), setOf("Heimnetz")),
            setOf("http://10.0.0.2:13378"),
        )
        assertEquals(expected, store.account.first())
        assertEquals("a1", store.accessToken())
        assertEquals("r1", store.refreshToken())
        assertEquals(Credentials("aaaaaaaaaaaaaaaa", "a1", "r1"), store.credentials())
        assertTrue(store.needsReauth.first())
        assertEquals(emptyList<Account>(), store.saved.first())
        // A refresh and a sign-out work on it as before; nothing new is written for a single account.
        store.updateTokens("aaaaaaaaaaaaaaaa", "a2", "r2")
        assertEquals(setOf("account_json", "access_token_enc", "refresh_token_enc", "needs_reauth"), rawKeys())
        assertEquals("a2", store.accessToken())
        assertFalse(store.needsReauth.first())
        store.clear(keepDownloads = true)
        assertNull(store.account.first())
        assertEquals(setOf("needs_reauth", "keep_downloads_on_clear"), rawKeys())
    }

    @Test fun aSingleAccount_isStoredWithTheOldKeysOnly() = runTest {
        store.save(fabian, "a1", "r1")
        store.markNeedsReauth(fabian.serverId)
        assertEquals(setOf("account_json", "access_token_enc", "refresh_token_enc", "needs_reauth"), rawKeys())
        assertEquals("enc:a1", prefs.data.first()[stringPreferencesKey("access_token_enc")])
    }

    @Test fun add_setsTheActiveAccountAside_withItsTokensAndFlag() = runTest {
        store.save(fabian, "a1", "r1")
        store.markNeedsReauth(fabian.serverId)
        store.add(matthias, "a2", "r2")
        assertEquals(matthias, store.account.first())
        assertEquals(Credentials(matthias.serverId, "a2", "r2"), store.credentials())
        assertFalse(store.needsReauth.first())
        assertEquals(listOf(fabian), store.saved.first())
        // No token in plain text anywhere in the file.
        val raw = prefs.data.first().asMap().values.joinToString()
        listOf("a1", "r1", "a2", "r2").forEach { t -> assertFalse(Regex("(?<!enc:)\\b$t\\b").containsMatchIn(raw)) }
        // Fabian waits with his own tokens and his "sign in again".
        assertTrue(store.activate(fabian.serverId))
        assertEquals(Credentials(fabian.serverId, "a1", "r1"), store.credentials())
        assertTrue(store.needsReauth.first())
    }

    @Test fun activate_swapsInOneStep_movingCiphertextOnly() = runTest {
        store.save(fabian, "a1", "r1")
        store.add(matthias, "a2", "r2")
        store.markNeedsReauth(matthias.serverId)
        val encrypts = cipher.encrypts
        val decrypts = cipher.decrypts
        assertTrue(store.activate(fabian.serverId))
        assertEquals(encrypts, cipher.encrypts)
        assertEquals(decrypts, cipher.decrypts)
        assertEquals(fabian, store.account.first())
        assertFalse(store.needsReauth.first())
        assertEquals(Credentials(fabian.serverId, "a1", "r1"), store.credentials())
        assertEquals(listOf(matthias), store.saved.first())
        // And back: Matthias still has his tokens and his "sign in again".
        assertTrue(store.activate(matthias.serverId))
        assertEquals(Credentials(matthias.serverId, "a2", "r2"), store.credentials())
        assertTrue(store.needsReauth.first())
        assertEquals(listOf(fabian), store.saved.first())
    }

    @Test fun activate_anUnknownOrTheActiveAccount_changesNothing() = runTest {
        store.save(fabian, "a1", "r1")
        assertFalse(store.activate(matthias.serverId))
        assertFalse(store.activate(fabian.serverId))
        assertEquals(fabian, store.account.first())
        assertEquals(emptyList<Account>(), store.saved.first())
    }

    @Test fun activate_whileSignedOut_takesTheSavedAccount() = runTest {
        store.save(fabian, "a1", "r1")
        store.add(matthias, "a2", "r2")
        store.clear(keepDownloads = false) // Matthias signs out; Fabian stays saved
        assertNull(store.account.first())
        assertEquals(listOf(fabian), store.saved.first())
        assertTrue(store.activate(fabian.serverId))
        assertEquals(Credentials(fabian.serverId, "a1", "r1"), store.credentials())
        assertEquals(emptyList<Account>(), store.saved.first())
    }

    @Test fun savingASavedAccount_makesItActive_once() = runTest {
        store.save(fabian, "a1", "r1")
        store.add(matthias, "a2", "r2")
        store.add(third, "a3", "r3")
        assertEquals(listOf(fabian, matthias), store.saved.first())
        // Fabian signs in again (re-auth with fresh tokens) over the third account: he is no longer a saved one.
        store.add(fabian, "a1b", "r1b")
        assertEquals(fabian, store.account.first())
        assertEquals(listOf(matthias, third), store.saved.first())
        store.save(matthias, "a2b", "r2b") // replaces Fabian (as before), takes Matthias out of the saved ones
        assertEquals(listOf(third), store.saved.first())
    }

    @Test fun forget_removesASavedAccountAndItsTokens_neverTheActiveOne() = runTest {
        store.save(fabian, "a1", "r1")
        store.add(matthias, "a2", "r2")
        store.forget(matthias.serverId) // the active account: no effect
        store.forget(fabian.serverId)
        assertEquals(matthias, store.account.first())
        assertEquals(Credentials(matthias.serverId, "a2", "r2"), store.credentials())
        assertEquals(emptyList<Account>(), store.saved.first())
        assertFalse(store.activate(fabian.serverId))
        assertFalse(prefs.data.first().asMap().values.joinToString().contains("enc:a1"))
    }

    @Test fun tokensAndReauth_reachTheAccountTheyBelongTo_evenAfterASwitch() = runTest {
        store.save(fabian, "a1", "r1")
        store.add(matthias, "a2", "r2")
        // A refresh that Fabian's request started lands after the switch to Matthias.
        store.updateTokens(fabian.serverId, "a1-new", "r1-new")
        store.markNeedsReauth(fabian.serverId)
        assertEquals(Credentials(matthias.serverId, "a2", "r2"), store.credentials())
        assertFalse(store.needsReauth.first())
        assertTrue(store.activate(fabian.serverId))
        assertEquals(Credentials(fabian.serverId, "a1-new", "r1-new"), store.credentials())
        // The refresh cleared the flag, the later rejection set it again.
        assertTrue(store.needsReauth.first())
        // An account that is gone gets nothing.
        store.forget(matthias.serverId)
        store.updateTokens(matthias.serverId, "x", "y")
        store.markNeedsReauth(matthias.serverId)
        assertEquals(emptyList<Account>(), store.saved.first())
        assertEquals(Credentials(fabian.serverId, "a1-new", "r1-new"), store.credentials())
    }

    @Test fun credentials_neverPrintTheirTokens() {
        val c = Credentials("aaaaaaaaaaaaaaaa", "secret-access", "secret-refresh")
        assertFalse(c.toString().contains("secret"))
        assertTrue(c.toString().contains("aaaaaaaaaaaaaaaa"))
    }

    // Fix round 1, E7: one unreadable saved entry (damaged, or from a later version) hides nothing else and is never lost.

    private val savedKey = stringPreferencesKey("saved_accounts_json")

    @Test fun anUnreadableSavedEntry_isSkipped_andKeptAsItIs() = runTest {
        store.save(fabian, "a1", "r1")
        store.add(matthias, "a2", "r2")
        val unreadable = """{"account":{"serverId":"zzzzzzzzzzzzzzzz"},"fromALaterVersion":true}"""
        prefs.edit { it[savedKey] = it[savedKey]!!.removeSuffix("]") + "," + unreadable + "]" }
        assertEquals(listOf(fabian), store.saved.first())

        store.add(third, "a3", "r3")
        assertEquals(listOf(fabian, matthias), store.saved.first())
        assertTrue(prefs.data.first()[savedKey]!!.contains(unreadable))
        assertTrue(store.activate(fabian.serverId))
        store.forget(matthias.serverId)
        store.updateTokens(third.serverId, "a3b", "r3b")
        assertEquals(listOf(third), store.saved.first())
        assertTrue(prefs.data.first()[savedKey]!!.contains(unreadable))
        // Only the unreadable one left: the key stays for it.
        store.forget(third.serverId)
        assertEquals(emptyList<Account>(), store.saved.first())
        assertEquals("[$unreadable]", prefs.data.first()[savedKey])
    }

    @Test fun aSavedValueThatIsNoListAtAll_isNeverOverwritten() = runTest {
        prefs.edit { it[savedKey] = "{oops" }
        store.save(fabian, "a1", "r1")
        store.markNeedsReauth(fabian.serverId)
        assertEquals("{oops", prefs.data.first()[savedKey])
        assertEquals(emptyList<Account>(), store.saved.first())
        // A new saved account is stored beside it; the old value stays, as it was, in the list.
        store.add(matthias, "a2", "r2")
        assertEquals(listOf(fabian), store.saved.first())
        assertTrue(prefs.data.first()[savedKey]!!.contains("\"{oops\""))
    }

    @Test fun savedCredentials_areTheSavedAccountsOwn() = runTest {
        store.save(fabian, "a1", "r1")
        store.add(matthias, "a2", "r2")
        assertEquals(Credentials(fabian.serverId, "a1", "r1"), store.savedCredentials(fabian.serverId))
        assertNull(store.savedCredentials(matthias.serverId)) // the active one is no saved one
    }
}
