package io.wolftown.lauschig.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.wolftown.lauschig.download.DownloadPrompt
import io.wolftown.lauschig.download.DownloadPrompts
import io.wolftown.lauschig.rmab.RmabRepository
import io.wolftown.lauschig.ui.search.RecentSearches
import io.wolftown.lauschig.ui.setup.OidcLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The production parts of the per-account state, each really cleared (deleting one line of the cleaner fails here). */
@RunWith(RobolectricTestRunner::class)
class AccountStateCleanerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun store(name: String) = PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("$name.preferences_pb") }
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }
    private val rmabStore = store("rmab")
    private val oidcStore = store("oidc")
    private val rmab = RmabRepository(rmabStore, cipher, { "Pixel" })
    private val oidc = OidcLogin(oidcStore, cipher, OkHttpClient())
    private val searches = RecentSearches(store("searches"))
    private val prompts = DownloadPrompts()
    private val a = "aaaaaaaaaaaaaaaa"

    @After fun tearDown() = scope.cancel()

    private fun seed() = runBlocking {
        rmabStore.edit { it[stringPreferencesKey("url")] = "https://rmab.example"; it[stringPreferencesKey("token")] = "enc:t" }
        oidcStore.edit { it[stringPreferencesKey("verifier")] = "v"; it[stringPreferencesKey("state")] = "s" }
        searches.record("Die drei")
        prompts.show(DownloadPrompt.Metered(a, "book1", "Titel", 1))
        assertTrue(rmab.config.first() != null)
    }

    @Test fun everyPartIsCleared() = runBlocking {
        seed()
        var images = 0
        var notifications = 0
        AccountStateCleaner(rmab, oidc, searches, prompts, { images++ }, { notifications++ }).clear(a)
        assertNull(rmab.config.first())
        assertTrue(oidcStore.data.first().asMap().isEmpty())
        assertEquals(emptyList<String>(), searches.queries.first())
        assertNull(prompts.prompt.value)
        assertEquals(1, images)
        assertEquals(1, notifications)
    }

    @Test fun aFailingPart_neverStopsTheOthers() = runBlocking {
        seed()
        AccountStateCleaner(rmab, oidc, searches, prompts, { error("disk cache") }, {}).clear(a)
        assertNull(rmab.config.first())
        assertEquals(emptyList<String>(), searches.queries.first())
        assertNull(prompts.prompt.value)
    }

    @Test fun theCleanupReminder_isCancelled() = runBlocking {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("t", "t", android.app.NotificationManager.IMPORTANCE_LOW))
        nm.notify(io.wolftown.lauschig.cleanup.CleanupReminderWorker.NOTIFICATION_ID, androidx.core.app.NotificationCompat.Builder(ctx, "t").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Der Anfang").build())
        assertEquals(1, nm.activeNotifications.size)
        AccountStateCleaner(rmab, oidc, searches, prompts, ctx).clear(a)
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test fun aPromptOfAnotherServer_stays() = runBlocking {
        val other = DownloadPrompt.Metered("bbbbbbbbbbbbbbbb", "x", "T", 1)
        prompts.show(other)
        AccountStateCleaner(rmab, oidc, searches, prompts, {}, {}).clear(a)
        assertEquals(other, prompts.prompt.value)
    }

    @Test fun theDiagnosticLog_goesOnEverySignOut() = runBlocking {
        var cleared = 0
        AccountStateCleaner(rmab, oidc, searches, prompts, {}, {}, { cleared++ }).clear(a)
        assertEquals(1, cleared)
    }
}
