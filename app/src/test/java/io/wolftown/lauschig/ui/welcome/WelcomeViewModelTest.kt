package io.wolftown.lauschig.ui.welcome

import androidx.lifecycle.SavedStateHandle
import io.wolftown.lauschig.TestViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // Log on failures
@Config(sdk = [35], application = android.app.Application::class)
class WelcomeViewModelTest {
    private val viewModels = TestViewModels()
    private var seen = 0
    private var failing = false

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = viewModels.own(
        WelcomeViewModel(handle) {
            if (failing) throw IOException("disk full")
            seen++
        },
    )

    @Test fun whenItIsDue() {
        assertTrue("a first install", welcomeIsDue(seen = false, lastSeenVersion = null, currentVersion = 5, updated = false))
        assertTrue("a restart in the middle of it: the version check of the first start ran", welcomeIsDue(false, 5, 5, false))
        assertFalse("seen", welcomeIsDue(true, 5, 5, false))
        assertFalse("from an older version, signed out", welcomeIsDue(false, 4, 5, updated = true))
        assertFalse("an older version without a stored number", welcomeIsDue(false, null, 5, updated = true))
        assertFalse("a stored older number is an older version too", welcomeIsDue(false, 4, 5, updated = false))
    }

    @Test fun page_byPage_onlyTheLastOneCounts() {
        val vm = vm()
        assertEquals(0, vm.page.value)
        vm.next()
        assertEquals(1, vm.page.value)
        vm.next()
        assertEquals(WELCOME_PAGE_COUNT - 1, vm.page.value)
        assertEquals("not before the end", 0, seen)
        assertFalse(vm.done.value)
        vm.next()
        assertEquals(1, seen)
        assertTrue(vm.done.value)
    }

    @Test fun skipping_onAnyPage_countsAsSeen() {
        val vm = vm()
        vm.next()
        vm.skip()
        assertEquals(1, seen)
        assertTrue(vm.done.value)
    }

    @Test fun back_goesAPageBack_notBeyondTheFirst() {
        val vm = vm()
        vm.next()
        assertTrue(vm.back())
        assertEquals(0, vm.page.value)
        assertFalse(vm.back())
        assertEquals(0, vm.page.value)
    }

    @Test fun aRecreationKeepsThePage() {
        val handle = SavedStateHandle()
        vm(handle).next()
        assertEquals(1, vm(handle).page.value)
        assertEquals(0, seen)
    }

    @Test fun notBeingAbleToRemember_doesNotStopTheWayToTheSignIn() {
        failing = true
        val vm = vm()
        vm.skip()
        assertTrue(vm.done.value)
    }
}
