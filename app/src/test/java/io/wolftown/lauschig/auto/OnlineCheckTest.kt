package io.wolftown.lauschig.auto

import io.wolftown.lauschig.connection.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineCheckTest {
    private val online = ConnectionState.Online("http://abs.test/".toHttpUrl(), ConnectionState.Via.PUBLIC)

    @Test fun waitsForTheFirstProbe() = runTest {
        // A cold start: Offline first (the initial value), the probe answers a second later.
        val connection = flow {
            emit(ConnectionState.Offline)
            delay(1_000)
            emit(online)
        }
        assertTrue(awaitOnline(connection, 3_000))
        assertEquals(1_000, currentTime)
    }

    @Test fun givesUpAfterTheWait() = runTest {
        val connection = flow {
            emit(ConnectionState.Offline)
            delay(60_000)
        }
        assertFalse(awaitOnline(connection, 3_000))
        assertEquals(3_000, currentTime)
        assertFalse(awaitOnline(flowOf(ConnectionState.Offline), 3_000))
    }
}
