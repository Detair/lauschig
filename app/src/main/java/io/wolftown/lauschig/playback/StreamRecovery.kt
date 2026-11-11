package io.wolftown.lauschig.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brings back a stream that ran dry. ExoPlayer retries a failing load by itself for as long as its read-ahead lasts; once
 * that is used up it reports the error and stops (idle), still wanting to play. Nothing happened then until someone
 * pressed play: a long dead spot on the road ended the listening.
 *
 * After such an error the player is prepared again: once the server is in reach ([online]; right away if the connection
 * never went, e.g. one bad answer), after a pause that grows with every failed try ([delaysMs]), and that many times at
 * most. Never against the listener: a pause, another title or a title that already plays again end the try
 * ([Host.waitsToStream]). A title that plays again gets its tries back ([onPlaying]), and so does every other title.
 * Calls on the player's thread.
 *
 * The wait for the server ends after [giveUpAfterMs]: Media3 keeps the service in the foreground for ten minutes after
 * the player stopped, and Android refuses to start playback from the background after that. Then only a play key helps.
 */
class StreamRecovery(
    private val host: Host,
    private val online: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val delaysMs: List<Long> = listOf(2_000, 10_000, 30_000),
    private val giveUpAfterMs: Long = 8 * 60_000L,
) {
    interface Host {
        /** The player stopped on an error (idle), holds a streamed file and still wants to play. */
        fun waitsToStream(): Boolean

        /** What is loaded; a try is for the title that failed only. */
        fun title(): Any?

        fun prepare()
    }

    private var job: Job? = null
    private var tries = 0
    /** The title the tries (and a waiting try) belong to. */
    private var triedTitle: Any? = null

    /** The player reported an error. [retryable]: reading the source failed in a way another try can cure. */
    fun onError(retryable: Boolean) {
        if (!retryable || !host.waitsToStream()) return
        val title = host.title()
        if (title != triedTitle) {
            // Another title: a try still waiting for the one before would find it gone and do nothing.
            job?.cancel()
            job = null
            tries = 0
            triedTitle = title
        }
        if (job?.isActive == true) return
        if (tries >= delaysMs.size) {
            Log.i(TAG, "the stream did not come back after $tries tries: waiting for play")
            return
        }
        val wait = delaysMs[tries++]
        job = scope.launch {
            val inReach = withTimeoutOrNull(giveUpAfterMs) {
                delay(wait)
                online.first { it }
            }
            if (inReach == null) {
                Log.i(TAG, "the server did not come back in time: waiting for play")
            } else if (host.waitsToStream() && host.title() == title) {
                Log.i(TAG, "the stream ran dry: preparing again (try $tries)")
                host.prepare()
            }
        }
    }

    /** It plays (again): the next dropout starts counting anew. */
    fun onPlaying() {
        tries = 0
    }

    fun release() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "StreamRecovery"

        /** `PlaybackException.ERROR_CODE_IO_*`: the source could not be read. */
        private val IO_ERRORS = 2000..2999

        /**
         * Another try can cure [error]: reading the source failed (no network, a timeout, a server error), but not with an
         * answer that stays the same however often one asks (gone, not allowed).
         */
        @OptIn(UnstableApi::class) // the HTTP status of a refused stream
        fun cures(error: PlaybackException): Boolean {
            if (error.errorCode !in IO_ERRORS) return false
            val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return true
            return status !in 400..499 || status == 408 || status == 429
        }
    }
}
