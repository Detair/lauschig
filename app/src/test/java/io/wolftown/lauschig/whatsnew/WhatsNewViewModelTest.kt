package io.wolftown.lauschig.whatsnew

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.settings.UiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsNewViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = TestViewModels()
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("ui.preferences_pb") } }
    private val settings by lazy { UiSettings(store) }
    private val releases = listOf(Release(2, "0.2.0", 0))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { viewModels.clear(); scope.cancel(); Dispatchers.resetMain() }

    private fun vm(updated: Boolean, current: Int = 2) = viewModels.own(WhatsNewViewModel(settings, current, { updated }, releases))

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test fun firstInstall_noSheet_andTheVersionIsRemembered() {
        val vm = vm(updated = false)
        await { vm.checked.first { it } }
        assertTrue(vm.pending.value.isEmpty())
        assertEquals(2, await { settings.lastSeenVersion.first { it != null } })
    }

    @Test fun update_showsOnce_dismissRemembers() {
        val vm = vm(updated = true)
        assertEquals(releases, await { vm.pending.first { it.isNotEmpty() } })
        vm.dismiss()
        assertTrue(vm.pending.value.isEmpty())
        assertEquals(2, await { settings.lastSeenVersion.first { it == 2 } })
        // Next start: nothing.
        val again = vm(updated = true)
        await { again.checked.first { it } }
        assertTrue(again.pending.value.isEmpty())
    }

    @Test fun notDismissed_showsAgainNextStart() {
        val first = vm(updated = true)
        await { first.pending.first { it.isNotEmpty() } }
        val second = vm(updated = true)
        assertEquals(releases, await { second.pending.first { it.isNotEmpty() } })
    }
}
