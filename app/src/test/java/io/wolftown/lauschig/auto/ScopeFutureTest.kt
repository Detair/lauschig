package io.wolftown.lauschig.auto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException

class ScopeFutureTest {
    @Test fun completesWithTheResult_orTheError() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val ok = scopeFuture(scope) { 42 }
        val failed = scopeFuture(scope) { error("boom") }
        testScheduler.advanceUntilIdle()
        assertEquals(42, ok.get())
        assertTrue(runCatching { failed.get() }.exceptionOrNull() is ExecutionException)
    }

    @Test fun aScopeCancelledBeforeTheRequestRan_cancelsTheFuture_insteadOfLeavingItOpen() = runTest {
        // The service is being destroyed while Android Auto waits for a result: Media3 must get an answer.
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val f = scopeFuture(scope) { 1 }
        scope.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(f.isDone)
        assertTrue(f.isCancelled)
        val running = scopeFuture(scope) { awaitCancellation() }
        testScheduler.advanceUntilIdle()
        assertTrue(running.isDone)
    }

    @Test fun cancellingTheFuture_cancelsTheWork() = runTest {
        val scope = TestScope(testScheduler)
        var finished = false
        val f = scopeFuture(scope) { awaitCancellation(); @Suppress("UNREACHABLE_CODE") finished = true }
        testScheduler.runCurrent()
        f.cancel(false)
        testScheduler.advanceUntilIdle()
        assertTrue(!finished)
    }
}
