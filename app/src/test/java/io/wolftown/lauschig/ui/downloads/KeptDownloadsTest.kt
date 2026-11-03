package io.wolftown.lauschig.ui.downloads

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.FakeKeptAccounts
import io.wolftown.lauschig.account.KeptAccount
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.LauschigTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class KeptDownloadsTest {
    @get:Rule val rule = createComposeRule()
    private val viewModels = TestViewModels()
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val c = "cccccccccccccccc"
    private val fabian = KeptAccount(a, "fabian", "abs.example.org", asked = true)
    private val lukas = KeptAccount(c, "lukas", "nas")
    private val accounts = FakeAccountStore().apply { acc.value = Account(b, "u", "matthias", false, ServerAddresses(null, null), emptySet()) }
    private val kept = FakeKeptAccounts(listOf(fabian, lukas))
    private val deleted = mutableListOf<String>()
    private var fails = false

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(
        KeptDownloadsViewModel(accounts, kept, { sid -> if (sid == a) 2_000_000_000 else 0 }) { k ->
            if (fails) false else { deleted += k.serverId; kept.remove(k.serverId); true }
        },
    )

    @Test fun listsEveryKeptAccount_alsoAnsweredWithKeep_butNotTheSignedInOne() = runBlocking {
        runBlocking { kept.put(KeptAccount(b, "matthias", "x")) }
        val vm = vm()
        assertEquals(listOf(KeptDownloads(fabian, 2_000_000_000), KeptDownloads(lukas, 0)), vm.entries.first { it.isNotEmpty() })
    }

    @Test fun delete_purgesThatAccount_aFailureStaysListed() = runBlocking {
        val vm = vm()
        vm.entries.first { it.size == 2 }
        fails = true
        vm.delete(fabian)
        assertEquals(a, vm.failed.value)
        assertEquals(2, kept.list.value.size)
        fails = false
        vm.delete(fabian)
        assertEquals(listOf(a), deleted)
        assertEquals(null, vm.failed.value)
        assertEquals(listOf(KeptDownloads(lukas, 0)), vm.entries.first { it.size == 1 })
    }

    @Test fun section_showsSizeOrData_andDeletesOnlyAfterConfirming() {
        val asked = mutableListOf<String>()
        rule.setContent {
            LauschigTheme { KeptDownloadsContent(listOf(KeptDownloads(fabian, 1_500_000_000), KeptDownloads(lukas, 0)), null, null) { asked += it.serverId } }
        }
        rule.onNodeWithText("Downloads of earlier accounts").assertExists()
        rule.onNodeWithText("fabian (abs.example.org)").assertExists()
        rule.onNodeWithText("1.5 GB").assertExists()
        rule.onNodeWithText("Data, no downloads").assertExists()
        rule.onNodeWithTag("deleteKept:$a").performClick()
        rule.onNodeWithText("Delete the data of fabian (abs.example.org)?").assertExists()
        assertEquals(emptyList<String>(), asked)
        rule.onNodeWithTag("confirmDeleteKept").performClick()
        assertEquals(listOf(a), asked)
    }

    @Test fun nothingKept_noSection() {
        rule.setContent { LauschigTheme { KeptDownloadsContent(emptyList(), null, null) {} } }
        rule.onNodeWithTag("keptDownloads").assertDoesNotExist()
    }
}
