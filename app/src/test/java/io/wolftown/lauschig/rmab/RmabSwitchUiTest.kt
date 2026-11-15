package io.wolftown.lauschig.rmab

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewmodel.compose.viewModel
import io.wolftown.lauschig.account.TokenCipher
import io.wolftown.lauschig.external.AudibleVolume
import io.wolftown.lauschig.external.MissingVolume
import io.wolftown.lauschig.settings.SettingsScreen
import io.wolftown.lauschig.settings.SettingsUiState
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.browse.BrowseEntry
import io.wolftown.lauschig.ui.browse.BrowseScreen
import io.wolftown.lauschig.ui.browse.BrowseUiState
import io.wolftown.lauschig.ui.nav.BrowseKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What the screens show of ReadMeABook with the build switch off and on. The account has a working connection in both
 * cases: off, neither the settings section nor the request button nor the tile symbol is there, and no view model is
 * asked for; on, all three are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp")
class RmabSwitchUiTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val rule = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private val active = MutableStateFlow<String?>("aaaaaaaaaaaaaaaa")

    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/local/login" -> json(200, """{"success":true,"user":{"id":"1"},"accessToken":"JWT","refreshToken":"R"}""")
                "/api/user/api-tokens" -> json(201, """{"token":{"id":"t1"},"fullToken":"rmab_abc"}""")
                // Volume 3 is being fetched, volume 4 was never asked for.
                else -> json(200, """{"requests":[{"id":"9","status":"downloading","audiobook":{"audibleAsin":"B3"}}]}""")
            }
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "rmab.preferences_pb") }
        // The account is connected, as a build with the feature left it.
        runBlocking {
            assertEquals(RmabConnectResult.Ok, repo(enabled = true).connect(server.url("/").toString(), "fabi", "pw", allowCleartext = true))
        }
    }

    @After fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun repo(enabled: Boolean) = RmabRepository(store, cipher, { "Tablet" }, active, enabled = enabled)

    private fun audible(seq: String) = MissingVolume(seq, seq.toDouble(), AudibleVolume("B$seq", "Audible Title $seq", authors = listOf("Ann Author"), sequence = seq), false)

    private val series = BrowseUiState(
        BrowseKind.SERIES, "Saga", entries = listOf(BrowseEntry.Missing("3", audible("3")), BrowseEntry.Missing("4", audible("4"))), loaded = true,
    )

    /** The series page as the route builds it: the missing volumes get what [rmabBrowse] hands out. */
    @Composable
    private fun SeriesPage(rmab: RmabBrowse) = LauschigTheme {
        BrowseScreen(series, {}, {}, {}, { _, _ -> }, missingActions = { rmab.actions(it.volume) }, missingStatuses = rmab.statuses)
    }

    @Test fun browse_off_missingVolumesStay_withoutSymbolAndRequestButton() {
        rule.setContent { SeriesPage(rmabBrowse(enabled = false, viewModel = { error("no view model while the feature is off") })) }
        rule.onAllNodesWithTag("missingVolume").assertCountEquals(2)
        rule.onAllNodesWithTag("rmabTileStatus", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Volume 4, not in the library").performClick()
        rule.onNodeWithText("Audible Title 4").assertIsDisplayed()
        rule.onNodeWithTag("rmabRequest").assertDoesNotExist()
        rule.onNodeWithTag("rmabStatus").assertDoesNotExist()
        rule.onAllNodesWithText("Request").assertCountEquals(0)
    }

    @Test fun browse_on_symbolOnTheTile_andRequestButtonInTheSheet() {
        val repo = repo(enabled = true)
        rule.setContent { SeriesPage(rmabBrowse(enabled = true, viewModel = { viewModel { RmabViewModel(repo) } })) }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("rmabTileStatus", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithContentDescription("Volume 4, not in the library").performClick()
        rule.onNodeWithTag("rmabRequest").assertIsDisplayed()
    }

    @Composable
    private fun Settings(section: @Composable () -> Unit) = LauschigTheme {
        SettingsScreen(SettingsUiState(), onBack = {}, onOpenCleanup = {}, onNextVolume = {}, onWifiOnly = {}, onCleanupReminder = {}, rmabSection = section)
    }

    @Test fun settings_off_noSection() {
        rule.setContent { Settings { RmabSettingsSection(enabled = false, viewModel = { error("no view model while the feature is off") }) } }
        rule.onNodeWithTag("settingsScreen").assertIsDisplayed()
        rule.onAllNodesWithText("ReadMeABook", substring = true, ignoreCase = true).assertCountEquals(0)
        rule.onAllNodesWithText("RMAB", substring = true, ignoreCase = true).assertCountEquals(0)
        rule.onNodeWithTag("rmabConnect").assertDoesNotExist()
        rule.onNodeWithTag("rmabConnected").assertDoesNotExist()
    }

    @Test fun settings_on_theSectionShowsTheConnection() {
        val repo = repo(enabled = true)
        rule.setContent { Settings { RmabSettingsSection(enabled = true, viewModel = { viewModel { RmabSettingsViewModel(repo) } }) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("rmabConnected").fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag("rmabConnected").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("ReadMeABook").assertIsDisplayed()
        rule.onNodeWithText("Connected as fabi").assertIsDisplayed()
    }
}
