package io.wolftown.lauschig.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionWaitTest {
    private val online = ConnectionState.Online("https://abs.example.org/".toHttpUrl(), ConnectionState.Via.PUBLIC)
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Offline)
    private val checking = MutableStateFlow(true)

    @Test fun firstCheckAnswersOnline_afterAFewSeconds() = runTest {
        val r = async { awaitConnectionAnswer(connection, checking, 10_000) }
        advanceTimeBy(4_000)
        runCurrent()
        assertFalse(r.isCompleted)
        connection.value = online
        checking.value = false
        runCurrent()
        assertTrue(r.await())
    }

    @Test fun firstCheckAnswersOffline() = runTest {
        val r = async { awaitConnectionAnswer(connection, checking, 10_000) }
        advanceTimeBy(6_000)
        checking.value = false
        runCurrent()
        assertFalse(r.await())
        assertEquals(6_000, testScheduler.currentTime)
    }

    @Test fun aCheckThatNeverAnswers_givesUpAfterTheMaximum() = runTest {
        val r = async { awaitConnectionAnswer(connection, checking, 10_000) }
        advanceTimeBy(10_001)
        runCurrent()
        assertFalse(r.await())
    }

    @Test fun anAnsweredState_decidesAtOnce() = runTest {
        checking.value = false
        assertFalse(awaitConnectionAnswer(connection, checking, 10_000))
        connection.value = online
        assertTrue(awaitConnectionAnswer(connection, checking, 10_000))
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun theAnsweredOnlineState_isHandedOver_forStreams() = runTest {
        val r = async { awaitOnlineAnswer(connection, checking, CONNECTION_ANSWER_WAIT_MS) }
        advanceTimeBy(5_000)
        connection.value = online
        checking.value = false
        runCurrent()
        assertEquals(online, r.await())
        checking.value = false
        connection.value = ConnectionState.Offline
        assertEquals(null, awaitOnlineAnswer(connection, checking, CONNECTION_ANSWER_WAIT_MS))
        assertEquals(10_000L, CONNECTION_ANSWER_WAIT_MS)
    }
}
