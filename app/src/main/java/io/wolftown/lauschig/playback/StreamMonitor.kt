package io.wolftown.lauschig.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** STARTING: since prepare/seek nothing played yet; STALLED: buffering after it had played. */
enum class StreamPhase { IDLE, STARTING, PLAYING, STALLED }

/**
 * What the player's stream is doing. [bytes] counts from the start of the current load (prepare, seek or stall);
 * [expectedBytes] is in the same unit and known only while the index at the end of a file is read (see
 * [StreamMonitor.onTransferStart]). Times are [StreamMonitor.now] milliseconds.
 */
data class StreamStats(
    /** playWhenReady and the current file comes from the server. */
    val active: Boolean = false,
    val phase: StreamPhase = StreamPhase.IDLE,
    val phaseSinceMs: Long = 0,
    val bufferedAheadMs: Long = 0,
    val bytes: Long = 0,
    val expectedBytes: Long? = null,
    /** Over the last ~3 s. */
    val bytesPerSec: Long = 0,
    /** Over the last ~10 s; the stream's share of the connection for download estimates. */
    val recentBytesPerSec: Long = 0,
)

/**
 * Sliding transfer rate over [windowMs]. Samples are coalesced into 100 ms buckets; a burst after the window ran
 * empty is measured from its first sample, so a fresh transfer is not diluted by the idle time before it.
 */
class RateWindow(private val windowMs: Long) {
    private val samples = ArrayDeque<LongArray>() // [bucketStartMs, bytes]
    private var total = 0L
    private var burstStart = 0L

    fun add(atMs: Long, bytes: Long) {
        trim(atMs)
        if (samples.isEmpty()) burstStart = atMs
        val last = samples.lastOrNull()
        if (last != null && atMs - last[0] < BUCKET_MS) last[1] += bytes else samples.addLast(longArrayOf(atMs, bytes))
        total += bytes
    }

    fun bytesPerSec(nowMs: Long): Long {
        trim(nowMs)
        if (samples.isEmpty()) return 0
        val span = (nowMs - maxOf(burstStart, nowMs - windowMs)).coerceAtLeast(MIN_SPAN_MS)
        return total * 1_000 / span
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && samples.first()[0] <= nowMs - windowMs) total -= samples.removeFirst()[1]
    }

    private companion object {
        const val BUCKET_MS = 100L
        const val MIN_SPAN_MS = 500L
    }
}

/**
 * Watches the player's network reads (as [TransferListener] on its HTTP data source) and its state (polled by
 * [PlaybackService]), for the buffer display and for [io.wolftown.lauschig.download.StreamPriority]. Service, download
 * workers and UI share this singleton (one process). Thread-safe: transfers are reported from loader threads.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamMonitor(private val clock: () -> Long) : TransferListener {
    @Inject constructor() : this(SystemClock::elapsedRealtime)

    private val _state = MutableStateFlow(StreamStats())
    val state: StateFlow<StreamStats> = _state.asStateFlow()

    private val throttledItems = HashSet<String>()
    private val _downloadThrottled = MutableStateFlow(false)
    /** A download has been yielding to the stream for a while (see [io.wolftown.lauschig.download.StreamPriority.showThrottled]). */
    val downloadThrottled: StateFlow<Boolean> = _downloadThrottled.asStateFlow()

    private val rate = RateWindow(3_000)
    private val recentRate = RateWindow(10_000)
    private var startedPlaying = false
    /** Start position of the previous read of the current load; null before the first one. */
    private var lastTransferStart: Long? = null
    private var loadBytes = 0L
    private var expected: Long? = null
    private var lastPublish = Long.MIN_VALUE

    fun now(): Long = clock()

    /** Polled by the service. [buffering]: the player waits for data (STATE_BUFFERING). */
    @Synchronized
    fun update(active: Boolean, playing: Boolean, buffering: Boolean, bufferedAheadMs: Long) {
        if (playing) startedPlaying = true
        val phase = when {
            !active -> StreamPhase.IDLE
            buffering -> if (startedPlaying) StreamPhase.STALLED else StreamPhase.STARTING
            else -> StreamPhase.PLAYING
        }
        val loadStarts = phase != _state.value.phase && (phase == StreamPhase.STARTING || phase == StreamPhase.STALLED)
        if (loadStarts) freshLoad()
        publish(active = active, phase = phase, bufferedAheadMs = bufferedAheadMs)
    }

    /** A new queue was prepared or the user seeked: the next buffering is a start, not a stall. */
    @Synchronized
    fun onSeekOrPrepare() {
        startedPlaying = false
        val phase = _state.value.phase
        val loading = phase == StreamPhase.STARTING || phase == StreamPhase.STALLED
        freshLoad()
        publish(phase = if (loading) StreamPhase.STARTING else phase, forceSince = loading)
    }

    /**
     * A read starts at [position]; [contentLength] is the response's length (null if unknown). Only a jump forward
     * within a load gives an expectation: that is ExoPlayer fetching the index (`moov`) at the end of an m4b before the
     * first sound, and that read ends with the file. A read from 0, after a seek or back to the audio data runs to the
     * end of the book, which says nothing about when playback starts.
     */
    @Synchronized
    fun onTransferStart(position: Long, contentLength: Long?) {
        val previous = lastTransferStart
        lastTransferStart = position
        expected = if (contentLength != null && position > 0 && previous != null && position > previous) loadBytes + contentLength else null
        val now = clock()
        rate.add(now, 0)
        recentRate.add(now, 0)
        publish()
    }

    @Synchronized
    fun onBytes(bytes: Long) {
        val now = clock()
        rate.add(now, bytes)
        recentRate.add(now, bytes)
        loadBytes += bytes
        // Reads come every few KB; the UI does not need more than a few updates per second.
        if (now - lastPublish >= BYTES_PUBLISH_MS) publish()
    }

    @Synchronized
    fun setDownloadThrottled(itemId: String, throttled: Boolean) {
        if (throttled) throttledItems += itemId else throttledItems -= itemId
        _downloadThrottled.value = throttledItems.isNotEmpty()
    }

    private fun freshLoad() {
        lastTransferStart = null
        loadBytes = 0
        expected = null
    }

    private fun publish(
        active: Boolean = _state.value.active,
        phase: StreamPhase = _state.value.phase,
        bufferedAheadMs: Long = _state.value.bufferedAheadMs,
        forceSince: Boolean = false,
    ) {
        val now = clock()
        lastPublish = now
        val s = _state.value
        _state.value = StreamStats(
            active = active,
            phase = phase,
            phaseSinceMs = if (forceSince || phase != s.phase) now else s.phaseSinceMs,
            bufferedAheadMs = bufferedAheadMs,
            bytes = loadBytes,
            expectedBytes = expected,
            bytesPerSec = rate.bytesPerSec(now),
            recentBytesPerSec = recentRate.bytesPerSec(now),
        )
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val length = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.firstOrNull()?.toLongOrNull()
        onTransferStart(dataSpec.position, length)
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) onBytes(bytesTransferred.toLong())
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    private companion object {
        const val BYTES_PUBLISH_MS = 250L
    }
}
