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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val active = kotlinx.coroutines.flow.MutableStateFlow<String?>(a)
    private val rmab = RmabRepository(rmabStore, cipher, { "Pixel" }, active)
    private val oidc = OidcLogin(oidcStore, cipher, OkHttpClient())
    private val searches = RecentSearches(store("searches"), active)
    private val prompts = DownloadPrompts()

    @After fun tearDown() = scope.cancel()

    private fun seed() = runBlocking {
        rmabStore.edit { it[stringPreferencesKey("url:$a")] = "https://rmab.example"; it[stringPreferencesKey("token:$a")] = "enc:t" }
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
        AccountStateCleaner(rmab, oidc, searches, prompts, ctx, io.wolftown.lauschig.sync.PositionJumpBus(), io.wolftown.lauschig.connection.ConnectionLog(), io.wolftown.lauschig.playback.FakeLastPlayedStore(), FakeProfileLocks(), io.wolftown.lauschig.settings.AppSettings(store("app"))).clear(a)
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

    @Test fun aLegacyCoilCacheOfAnEarlierInstall_isGoneAfterSignOut() = runBlocking {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val legacy = ctx.cacheDir.resolve("coil3_disk_cache").apply { mkdirs(); resolve("old-cover.1").writeText("x") }
        AccountStateCleaner(rmab, oidc, searches, prompts, ctx, io.wolftown.lauschig.sync.PositionJumpBus(), io.wolftown.lauschig.connection.ConnectionLog(), io.wolftown.lauschig.playback.FakeLastPlayedStore(), FakeProfileLocks(), io.wolftown.lauschig.settings.AppSettings(store("app"))).clear(a)
        assertEquals(false, legacy.exists())
    }

    // Phase 7, Task 1.1: a switch keeps what is the account's and clears what must not stay on screen or in the log.

    private fun cleaner(images: () -> Unit = {}, memory: () -> Unit = {}, notifications: () -> Unit = {}, diagnostics: () -> Unit = {}, moments: () -> Unit = {}) =
        AccountStateCleaner(rmab, oidc, searches, prompts, { images() }, notifications, diagnostics, { memory() }, moments)

    @Test fun setAside_keepsTheConnectionAndTheSearches_clearsTheRest() = runBlocking {
        seed()
        var disk = 0; var memory = 0; var notifications = 0; var diagnostics = 0; var moments = 0
        cleaner({ disk++ }, { memory++ }, { notifications++ }, { diagnostics++ }, { moments++ }).setAside(a)
        assertEquals("https://rmab.example", rmab.config.first()!!.url)
        assertEquals(listOf("Die drei"), searches.queries.first())
        assertTrue("a pending SSO attempt goes", oidcStore.data.first().asMap().isEmpty())
        assertNull("an open download question goes", prompts.prompt.value)
        assertEquals("the covers on disk stay, the next account only asks for its own", 0, disk)
        assertEquals(1, memory)
        assertEquals(1, notifications)
        assertEquals(1, diagnostics)
        assertEquals(1, moments)
    }

    @Test fun clear_goesOnlyForThatAccount() = runBlocking {
        seed()
        active.value = b
        rmabStore.edit { it[stringPreferencesKey("url:$b")] = "https://other.example"; it[stringPreferencesKey("token:$b")] = "enc:o" }
        searches.record("Bibi")
        active.value = a
        cleaner().clear(a)
        assertNull(rmab.config.first())
        assertEquals(emptyList<String>(), searches.queries.first())
        active.value = b
        assertEquals("https://other.example", rmab.config.first()!!.url)
        assertEquals(listOf("Bibi"), searches.queries.first())
    }

    @Test fun forgettingASavedProfile_takesOnlyWhatIsItsOwn() = runBlocking {
        seed()
        active.value = b // A is a saved profile now
        searches.record("Bibi")
        var images = 0; var diagnostics = 0
        val otherPrompt = DownloadPrompt.Metered(b, "x", "T", 1)
        prompts.show(otherPrompt)
        cleaner({ images++ }, {}, {}, { diagnostics++ }).forget(a)
        assertEquals("the active account's search stays", listOf("Bibi"), searches.queries.first())
        assertEquals(otherPrompt, prompts.prompt.value)
        assertEquals("like a sign-out: its covers go, the signed-in account fetches its own again (review M5)", 1, images)
        assertEquals(0, diagnostics)
        active.value = a
        assertNull(rmab.config.first())
        assertEquals(emptyList<String>(), searches.queries.first())
    }

    /** Review N3: the device's own last title goes to the account active at the upgrade, like the other old values. */
    @Test fun migrate_andSetAside_handTheDevicesLastTitleToTheAccount_aSignOutDoesNot() = runBlocking {
        val adopted = mutableListOf<String>()
        fun cleaner() = AccountStateCleaner(rmab, oidc, searches, prompts, {}, {}, {}, {}, {}, { adopted += it })
        cleaner().migrate(a)
        assertEquals(listOf(a), adopted)
        cleaner().setAside(a)
        assertEquals(listOf(a, a), adopted)
        cleaner().migrate(null)
        assertEquals(listOf(a, a), adopted)
        cleaner().clear(a)
        assertEquals("a sign-out just left it on the device for the next one", listOf(a, a), adopted)
    }

    /** The account that is left owned the old values, also if nothing asked for them since the start of this version. */
    @Test fun leavingBeforeTheStartUpMigrationRan_theOldValuesStayWithTheLeftAccount() = runBlocking {
        rmabStore.edit { it[stringPreferencesKey("url")] = "https://rmab.example"; it[stringPreferencesKey("token")] = "enc:t" }
        cleaner().setAside(a)
        active.value = b
        assertNull(rmab.config.first())
        active.value = a
        assertEquals("https://rmab.example", rmab.config.first()!!.url)
    }

    @Test fun migrate_handsTheOldValuesToTheAccountActiveAtTheUpgrade() = runBlocking {
        rmabStore.edit { it[stringPreferencesKey("url")] = "https://rmab.example"; it[stringPreferencesKey("token")] = "enc:t" }
        cleaner().migrate(a)
        assertEquals("https://rmab.example", rmab.config.first()!!.url)
    }

    /** Coil's real caches: the disk stays over a switch (keys hold no tokens, see [coverKeys_holdNoTokens]), memory goes. */
    @Test fun setAside_realImageCaches_diskStays_memoryGoes() = runBlocking {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val images = coil3.SingletonImageLoader.get(ctx)
        val disk = images.diskCache!!
        disk.openEditor("cover-key")!!.apply { disk.fileSystem.write(data) { writeUtf8("x") }; commitAndOpenSnapshot()!!.close() }
        val key = coil3.memory.MemoryCache.Key("cover-key")
        images.memoryCache!![key] = coil3.memory.MemoryCache.Value(coil3.ColorImage(0, 1, 1))
        AccountStateCleaner(rmab, oidc, searches, prompts, ctx, io.wolftown.lauschig.sync.PositionJumpBus(), io.wolftown.lauschig.connection.ConnectionLog(), io.wolftown.lauschig.playback.FakeLastPlayedStore(), FakeProfileLocks(), io.wolftown.lauschig.settings.AppSettings(store("app"))).setAside(a)
        assertNull(images.memoryCache!![key])
        assertTrue(disk.openSnapshot("cover-key")!!.use { true })
        AccountStateCleaner(rmab, oidc, searches, prompts, ctx, io.wolftown.lauschig.sync.PositionJumpBus(), io.wolftown.lauschig.connection.ConnectionLog(), io.wolftown.lauschig.playback.FakeLastPlayedStore(), FakeProfileLocks(), io.wolftown.lauschig.settings.AppSettings(store("app"))).clear(a)
        assertNull(disk.openSnapshot("cover-key"))
    }

    /** The model a tile hands to Coil is its cache key (no custom key anywhere): the token travels in a header, never in it. */
    @Test fun coverKeys_holdNoTokens() {
        val model = io.wolftown.lauschig.ui.common.coverModel("https://abs.example/".toHttpUrlOrNull(), "item1", null, 5)
        assertEquals("https://abs.example/api/items/item1/cover?ts=5", model)
    }

    // --- 3.7 ---

    private val removed = mutableListOf<String>()
    private fun cleanerWithProtection() = AccountStateCleaner(rmab, oidc, searches, prompts, {}, {}, removeProtection = { removed += it })

    @Test fun aSignOut_takesTheProtectionOfThatAccount() = runBlocking {
        cleanerWithProtection().clear(a)
        assertEquals(listOf(a), removed)
    }

    @Test fun aRemovedProfile_takesItsProtection() = runBlocking {
        cleanerWithProtection().forget(b)
        assertEquals(listOf(b), removed)
    }

    @Test fun aSwitch_keepsTheProtection() = runBlocking {
        cleanerWithProtection().setAside(a)
        assertTrue(removed.isEmpty())
    }

    @Test fun aProtectionThatDoesNotGo_stopsNothingElse() = runBlocking {
        seed()
        AccountStateCleaner(rmab, oidc, searches, prompts, {}, {}, removeProtection = { error("disk") }).clear(a)
        assertNull(rmab.config.first())
    }

    // --- The final review's Minor 1 ---

    private val cleared = mutableListOf<String>()
    private fun cleanerWithAudible() = AccountStateCleaner(rmab, oidc, searches, prompts, {}, {}, clearAudibleAnswer = { cleared += it })

    @Test fun aSignOut_takesTheAnswerAboutAudible() = runBlocking {
        cleanerWithAudible().clear(a)
        assertEquals(listOf(a), cleared)
    }

    @Test fun aRemovedProfile_takesItsAnswerAboutAudible() = runBlocking {
        cleanerWithAudible().forget(b)
        assertEquals(listOf(b), cleared)
    }

    @Test fun aSwitch_keepsTheAnswerAboutAudible() = runBlocking {
        cleanerWithAudible().setAside(a)
        assertTrue(cleared.isEmpty())
    }
}
