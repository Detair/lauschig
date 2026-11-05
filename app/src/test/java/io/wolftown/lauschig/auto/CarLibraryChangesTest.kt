package io.wolftown.lauschig.auto

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Task 1.7: a car connected during a switch reloads what it shows, also when both accounts' lists look alike. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarLibraryChangesTest {
    /** Never completes, like the app's: a completed flow would flush the debounce at once. */
    private val parts: (String?) -> List<Flow<Any>> = { listOf(MutableStateFlow<Any>("the same list")) }

    @Test fun aSwitch_tellsTheBrowsers_afterTheDebounce() = runTest {
        val account = MutableStateFlow<String?>("a")
        var told = 0
        backgroundScope.launch { carLibraryChanges(account, parts, 1_500).collect { told++ } }
        runCurrent()
        advanceTimeBy(1_501)
        assertEquals(1, told)
        account.value = "b"
        runCurrent()
        assertEquals("debounced", 1, told)
        advanceTimeBy(1_501)
        assertEquals(2, told)
    }

    @Test fun aChangeOfThePartsTellsThem_noChangeDoesNot() = runTest {
        val library = MutableStateFlow<Any>(listOf("x"))
        var told = 0
        backgroundScope.launch { carLibraryChanges(flowOf("a"), { listOf(library) }, 1_500).collect { told++ } }
        runCurrent()
        advanceTimeBy(1_501)
        assertEquals(1, told)
        library.value = listOf("x")
        advanceTimeBy(1_501)
        assertEquals(1, told)
        library.value = listOf("x", "y")
        advanceTimeBy(1_501)
        assertEquals(2, told)
    }
}
