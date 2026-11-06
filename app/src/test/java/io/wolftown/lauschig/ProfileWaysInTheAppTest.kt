package io.wolftown.lauschig

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.FakeProfileLocks
import io.wolftown.lauschig.account.ProfileLocks
import io.wolftown.lauschig.account.ProfileLocksModule
import io.wolftown.lauschig.connection.ConnectionModule
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.DatabaseModule
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.net.AccountBindingsModule
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlaybackModule
import kotlinx.coroutines.job
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The review's M7: from the real activity to the form of a forgotten PIN, through `AppRoot` and `AppNavHost`, which no other test
 * passes. A protection that cannot be read leads there without a text field in a dialog (see `LocalPinDialogFrame`). The form it opens
 * has to be that account's: a plain one would sign out whoever is active.
 */
@HiltAndroidTest
@UninstallModules(AccountBindingsModule::class, ConnectionModule::class, DatabaseModule::class, PlaybackModule::class, ProfileLocksModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class ProfileWaysInTheAppTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Module
    @InstallIn(SingletonComponent::class)
    object Locks {
        @Provides @Singleton fun fake(): FakeProfileLocks = FakeProfileLocks()
        @Provides fun locks(locks: FakeProfileLocks): ProfileLocks = locks
    }

    @Inject lateinit var accounts: AccountStore
    @Inject lateinit var locks: FakeProfileLocks
    @Inject lateinit var db: LauschigDatabase
    @Inject lateinit var playerScope: ColdStartPlayerScope

    private val store get() = accounts as FakeAccountStore
    private val addresses = ServerAddresses(null, "https://abs.example.org/".toHttpUrl())
    private val kind = Account(Account.computeServerId(addresses, "u2"), "u2", "kind", false, addresses, emptySet())

    @Before fun inject() {
        hilt.inject()
    }

    private fun awaitMainThread(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    @After fun close() {
        val job = playerScope.scope.coroutineContext.job
        job.cancel()
        awaitMainThread { job.isCompleted }
        db.close()
    }

    private fun theFormIsThatAccounts() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("setupScreen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sign in as kind. The protection of this profile is then removed and you switch to it.").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun fromTheAvatarsSheet_itLeadsToThatAccountsForm_andTheActiveAccountStays() {
        val active = store.acc.value!!
        store.aside.value = listOf(FakeAccountStore.Aside(kind, "a", "r", false))
        locks.unreadable(kind.serverId)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("profileAvatar").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Profiles, active: ${active.username}").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("profile:kind").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("profile:kind").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("signInInstead").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("signInInstead").performClick()
            theFormIsThatAccounts()
            assertEquals(active, store.acc.value)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun fromThePicker_itLeadsToThatAccountsForm_andNobodyIsActive() {
        val other = store.acc.value!!
        store.acc.value = null
        store.aside.value = listOf(FakeAccountStore.Aside(other, "a", "r", false), FakeAccountStore.Aside(kind, "a", "r", false))
        locks.unreadable(kind.serverId)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("profile:kind").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("profilePicker").assertExists()
            compose.onNodeWithTag("profile:kind").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("signInInstead").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("signInInstead").performClick()
            theFormIsThatAccounts()
            assertEquals(null, store.acc.value)
        }
    }
}
