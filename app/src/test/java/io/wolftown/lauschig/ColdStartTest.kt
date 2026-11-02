package io.wolftown.lauschig

import android.content.Context
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import androidx.media3.common.MediaItem
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.net.AccountBindingsModule
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionModule
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.connection.ReachabilityProbe
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.DatabaseModule
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.MediaItemFactory
import io.wolftown.lauschig.playback.PlaybackModule
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerControllerImpl
import io.wolftown.lauschig.playback.PlayerFacade
import io.wolftown.lauschig.playback.PlayerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val ITEM_ID = "li_coldstart"
private const val TITLE = "Der kleine Hörtest"

/** Records what was handed to the player; stands in for the Media3 service (ExoPlayer cannot decode under Robolectric). */
class RecordingFacade : PlayerFacade {
    @Volatile var queue: List<MediaItem> = emptyList()
    @Volatile private var snap = PlayerSnapshot(false, 0, 0, 1f)
    private var onChange: () -> Unit = {}
    override suspend fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        queue = items; snap = snap.copy(mediaIndex = startIndex, positionMs = startPositionMs)
    }
    override fun replaceMediaItem(index: Int, item: MediaItem) { queue = queue.toMutableList().also { it[index] = item } }
    override fun play() { snap = snap.copy(isPlaying = true); onChange() }
    override fun pause() { snap = snap.copy(isPlaying = false); onChange() }
    override fun seekTo(mediaIndex: Int, positionMs: Long) { snap = snap.copy(mediaIndex = mediaIndex, positionMs = positionMs) }
    override fun setSpeed(speed: Float) { snap = snap.copy(speed = speed) }
    override fun snapshot() = snap
    override fun hasMedia() = queue.isNotEmpty()
    override fun setOnError(onError: () -> Unit) {}
    override fun setOnDisconnected(onDisconnected: () -> Unit) {}
    override fun setOnChange(onChange: () -> Unit) { this.onChange = onChange }
}

/**
 * A probe that never answers (a black-holed server): it suspends until cancelled, so anything on the start path
 * that awaited the network would hang here instead of passing quickly on an instant "unreachable".
 */
private val DEAD_PROBE = ReachabilityProbe { awaitCancellation() }

@Module
@InstallIn(SingletonComponent::class)
object ColdStartTestModule {
    private const val SERVER_ID_SEED_USER = "u1"

    @Provides @Singleton
    fun accountStore(): AccountStore = FakeAccountStore().apply {
        val addresses = ServerAddresses(null, "https://abs.example.org/".toHttpUrl())
        acc.value = Account(Account.computeServerId(addresses, SERVER_ID_SEED_USER), SERVER_ID_SEED_USER, "fabian", false, addresses, emptySet())
        // Expired access token and a refresh token the (offline) server could never be asked about.
        access = "expired.access.token"
        refresh = "stale-refresh-token"
    }

    @Provides @Singleton fun probe(): ReachabilityProbe = DEAD_PROBE

    @Provides @Singleton fun networkInfo(): NetworkInfo = object : NetworkInfo {
        override val isConnected: Flow<Boolean> = flowOf(true)
        override suspend fun currentSsid(): String? = null
    }

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): LauschigDatabase =
        Room.inMemoryDatabaseBuilder(context, LauschigDatabase::class.java).allowMainThreadQueries().build()

    @Provides fun itemDao(db: LauschigDatabase) = db.itemDao()
    @Provides fun libraryDao(db: LauschigDatabase) = db.libraryDao()
    @Provides fun chapterDao(db: LauschigDatabase) = db.chapterDao()
    @Provides fun audioFileDao(db: LauschigDatabase) = db.audioFileDao()
    @Provides fun progressDao(db: LauschigDatabase) = db.progressDao()
    @Provides fun sessionDao(db: LauschigDatabase) = db.sessionDao()
    @Provides fun downloadDao(db: LauschigDatabase) = db.downloadDao()
    @Provides fun episodeDao(db: LauschigDatabase) = db.episodeDao()
    @Provides fun catalogDao(db: LauschigDatabase) = db.catalogDao()
    @Provides fun externalSeriesDao(db: LauschigDatabase) = db.externalSeriesDao()

    @Provides @Singleton fun facade(): RecordingFacade = RecordingFacade()

    @Provides @Singleton fun playerScope(): ColdStartPlayerScope = ColdStartPlayerScope(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    @Provides @Singleton
    fun player(scope: ColdStartPlayerScope, facade: RecordingFacade, factory: MediaItemFactory, items: ItemDao, files: DownloadFiles): PlayerController {
        val impl = PlayerControllerImpl(
            scope = scope.scope,
            facade = facade,
            factory = factory,
            loadItem = { serverId, itemId -> items.observeItem(serverId, itemId).first() },
            onlineBaseUrl = { null }, // never online in this test
            coverFile = { s, i -> files.coverFile(s, i) },
        )
        return impl
    }
}

/** The player's own scope, so the test can cancel it: it must not outlive the test and keep resuming onto Main. */
class ColdStartPlayerScope(val scope: CoroutineScope)

/**
 * Review focus 1: cold start with the server down and an expired token still shows the local library and
 * plays what is downloaded: no spinner, no login screen.
 *
 * Runs the real MainActivity with Hilt; Room (in-memory), the account store, the connectivity probe and the
 * playback service connection are replaced. The player itself is a [RecordingFacade] because ExoPlayer cannot
 * decode audio under Robolectric, so "plays" is asserted as PlayerUiState.isPlaying plus the exact local
 * `file://` URIs handed to the player (no http URL anywhere).
 */
@HiltAndroidTest
@UninstallModules(AccountBindingsModule::class, ConnectionModule::class, DatabaseModule::class, PlaybackModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class ColdStartTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var accounts: AccountStore
    @Inject lateinit var items: ItemDao
    @Inject lateinit var audioFiles: AudioFileDao
    @Inject lateinit var db: LauschigDatabase
    @Inject lateinit var player: PlayerController
    @Inject lateinit var facade: RecordingFacade
    @Inject lateinit var playerScope: ColdStartPlayerScope

    private lateinit var audio: File

    @Before fun seed() {
        hilt.inject()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serverId = runBlocking { accounts.account.first()!!.serverId }
        // A fully "downloaded" item: the file really exists on disk, localPath is set, download is COMPLETE.
        audio = File(context.filesDir, "downloads/$serverId/$ITEM_ID/0.wav").apply { parentFile!!.mkdirs() }
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("silence.wav")).use { src -> audio.outputStream().use { src.copyTo(it) } }
        runBlocking {
            items.upsertDetails(ItemEntity(serverId, ITEM_ID, "lib1", TITLE, author = "Autor", durationSec = 1.0))
            audioFiles.replaceFromServer(serverId, ITEM_ID, listOf(AudioFileEntity(serverId, ITEM_ID, 0, "ino0", 1.0, audio.length(), "wav")))
            audioFiles.setLocal(serverId, ITEM_ID, 0, audio.path, audio.length())
            db.downloadDao().upsert(DownloadEntity(serverId, ITEM_ID, DownloadState.COMPLETE, 0))
        }
    }

    /** Pumps the (paused) Robolectric main looper until [condition] holds, so Room -> Main continuations run. */
    private fun awaitMainThread(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("condition not met within $timeoutMs ms", condition())
    }

    @After fun close() {
        // Cancel the player's coroutines and let them finish on the main looper before the database closes.
        val job = playerScope.scope.coroutineContext.job
        job.cancel()
        awaitMainThread(5_000) { job.isCompleted }
        db.close()
    }

    @Test
    fun coldStart_serverUnreachable_showsLibraryAndPlays() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Title from Room appears within 2 s although the server is down and the token is expired.
            compose.waitUntil(timeoutMillis = 2_000) { compose.onAllNodesWithText(TITLE).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(TITLE).assertIsDisplayed()
            // No login screen, no spinner.
            compose.onAllNodesWithTag("setupScreen").assertCountEquals(0)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)

            // The cover's play button starts it (tapping the cover opens the details).
            compose.onNodeWithContentDescription("Play $TITLE").performClick()
            awaitMainThread(2_000) { player.state.value.isPlaying }
            assertTrue(player.state.value.isPlaying)
            assertEquals(TITLE, player.state.value.title)
            assertEquals(listOf(android.net.Uri.fromFile(audio)), facade.queue.map { it.localConfiguration!!.uri })
        }
    }
}
