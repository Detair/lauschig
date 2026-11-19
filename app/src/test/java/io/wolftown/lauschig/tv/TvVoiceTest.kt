package io.wolftown.lauschig.tv

import io.wolftown.lauschig.links.VoiceRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a voice request may run, and how long it may wait. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvVoiceTest {
    /** Every combination: a cancelled question ends it, any open question holds it, otherwise it runs. */
    @Test fun voiceStep_everyCase() {
        for (page in listOf(false, true)) for (open in listOf(false, true)) for (cancelled in listOf(false, true)) {
            val expected = when {
                cancelled -> VoiceStep.DROP
                page || open -> VoiceStep.WAIT
                else -> VoiceStep.RUN
            }
            assertEquals("page=$page open=$open cancelled=$cancelled", expected, voiceStep(page, open, cancelled))
        }
        // Spelled out once, the cases that matter most.
        assertEquals(VoiceStep.RUN, voiceStep(onQuestionPage = false, questionOpen = false, cancelledSinceRequest = false))
        assertEquals(VoiceStep.WAIT, voiceStep(onQuestionPage = false, questionOpen = true, cancelledSinceRequest = false))
        assertEquals(VoiceStep.DROP, voiceStep(onQuestionPage = true, questionOpen = false, cancelledSinceRequest = true))
    }

    private val one = VoiceRequest("eins", null)
    private val two = VoiceRequest("zwei", null)

    @Test fun inbox_letsARequestGo_afterAMinute() = runTest {
        val inbox = TvVoiceInbox(backgroundScope, now = { testScheduler.currentTime })
        inbox.offer(one)
        assertSame(one, inbox.pending.value?.request)
        advanceTimeBy(VOICE_MAX_AGE_MS - 1)
        runCurrent()
        assertSame(one, inbox.pending.value?.request)
        advanceTimeBy(2)
        runCurrent()
        assertNull(inbox.pending.value)
    }

    @Test fun inbox_aNewerRequestReplacesTheOld_andKeepsItsOwnMinute() = runTest {
        val inbox = TvVoiceInbox(backgroundScope, now = { testScheduler.currentTime })
        inbox.offer(one)
        val first = inbox.pending.value!!
        advanceTimeBy(40_000)
        inbox.offer(two)
        advanceTimeBy(30_000)
        runCurrent()
        assertSame(two, inbox.pending.value?.request)
        // The old one is done elsewhere: the newer one stays.
        inbox.done(first)
        assertSame(two, inbox.pending.value?.request)
        assertEquals(40_000L, inbox.pending.value?.receivedAt)
        inbox.done(inbox.pending.value!!)
        assertNull(inbox.pending.value)
    }

    @Test fun questions_openWhileHeld_andACancelIsTimed() {
        var clock = 100L
        val questions = TvQuestions { clock }
        assertFalse(questions.open)
        questions.opened()
        questions.opened()
        questions.closed()
        assertTrue(questions.open)
        questions.closed()
        assertFalse(questions.open)
        assertEquals(-1L, questions.cancelledAt)
        clock = 250
        questions.cancelled()
        assertEquals(250L, questions.cancelledAt)
    }
}
