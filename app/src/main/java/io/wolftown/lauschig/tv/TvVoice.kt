package io.wolftown.lauschig.tv

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.wolftown.lauschig.links.VoiceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** "Play X in Lauschig" as the TV holds it until it may run; [receivedAt] on the [TvQuestions] clock. */
class TvVoice(val request: VoiceRequest, val receivedAt: Long)

/**
 * The voice request waiting for its turn. It does not wait for ever: one nobody could run within [maxAgeMs] (the question
 * left open, the app put away) is let go, as a title starting by itself minutes after it was asked for would surprise.
 */
class TvVoiceInbox(
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val maxAgeMs: Long = VOICE_MAX_AGE_MS,
) {
    private val _pending = MutableStateFlow<TvVoice?>(null)
    val pending: StateFlow<TvVoice?> = _pending.asStateFlow()
    private var expiry: Job? = null

    /** A new request replaces one still waiting: the last thing said counts. */
    fun offer(request: VoiceRequest) {
        val voice = TvVoice(request, now())
        _pending.value = voice
        expiry?.cancel()
        expiry = scope.launch {
            delay(maxAgeMs)
            _pending.compareAndSet(voice, null)
        }
    }

    /** [voice] was run or let go; a newer request stays. */
    fun done(voice: TvVoice) {
        if (_pending.compareAndSet(voice, null)) expiry?.cancel()
    }
}

internal const val VOICE_MAX_AGE_MS = 60_000L

/**
 * What a question on screen means for requests from outside (a voice request, the player to open): "who is listening?" and
 * the sign-in are pages of their own; a PIN or a protection step inside a page registers here ([HoldsAQuestion]). A "who is
 * listening?" left without an answer (Back) is noted in [cancelledAt], so that a request made before it is let go instead
 * of running on the profile that happened to be active.
 */
@Stable
class TvQuestions(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var openInPages by mutableIntStateOf(0)

    /** A PIN or protection step is on screen inside a page. */
    val open: Boolean get() = openInPages > 0

    /** When "who is listening?" was last left without an answer; -1: never. */
    var cancelledAt by mutableLongStateOf(-1L)
        private set

    internal fun opened() { openInPages++ }

    internal fun closed() { openInPages-- }

    fun cancelled() {
        cancelledAt = now()
    }
}

internal val LocalTvQuestions = staticCompositionLocalOf { TvQuestions() }

/** While [active], a question is open on this page: requests from outside wait for it. */
@Composable
internal fun HoldsAQuestion(active: Boolean) {
    if (!active) return
    val questions = LocalTvQuestions.current
    DisposableEffect(questions) {
        questions.opened()
        onDispose { questions.closed() }
    }
}

/** What to do with a waiting voice request now. */
internal enum class VoiceStep { RUN, WAIT, DROP }

/**
 * [onQuestionPage]: "who is listening?" or the sign-in is on screen (or no page yet); [questionOpen]: a PIN or protection step
 * inside a page; [cancelledSinceRequest]: "who is listening?" was left without an answer after the request came. A question
 * left open is answered first (TV spec E8); one that was cancelled takes the request with it.
 */
internal fun voiceStep(onQuestionPage: Boolean, questionOpen: Boolean, cancelledSinceRequest: Boolean): VoiceStep = when {
    cancelledSinceRequest -> VoiceStep.DROP
    onQuestionPage || questionOpen -> VoiceStep.WAIT
    else -> VoiceStep.RUN
}
