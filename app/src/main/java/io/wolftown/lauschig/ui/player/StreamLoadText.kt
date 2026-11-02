package io.wolftown.lauschig.ui.player

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.StreamLoad

/** 0..1 of the expected bytes, null when nothing is expected. */
fun StreamLoad.fraction(): Float? = expectedBytes?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }

/** "Loading 3.2 of 10 MB · 512 kB/s · 14 s left" (full player). */
@Composable
fun streamLoadText(load: StreamLoad): String {
    val context = LocalContext.current
    val rate = Formatter.formatShortFileSize(context, load.bytesPerSec)
    val done = Formatter.formatShortFileSize(context, load.bytes)
    val base = if (load.expectedBytes != null) {
        stringResource(R.string.player_stream_loading_of, done, Formatter.formatShortFileSize(context, load.expectedBytes), rate)
    } else {
        stringResource(R.string.player_stream_loading, done, rate)
    }
    return load.etaSec?.let { base + " · " + stringResource(R.string.player_stream_eta, shortDuration(it)) } ?: base
}

/** "42 % · 14 s left", or "Loading · 512 kB/s" without an expectation (mini player). */
@Composable
fun streamLoadShort(load: StreamLoad): String {
    val fraction = load.fraction()
    return if (fraction != null && load.etaSec != null) {
        stringResource(R.string.player_stream_progress_short, (fraction * 100).toInt(), shortDuration(load.etaSec))
    } else {
        stringResource(R.string.player_stream_rate_short, Formatter.formatShortFileSize(LocalContext.current, load.bytesPerSec))
    }
}

/** "45 s", "3 min", "1 h 20 min" (minutes rounded up). */
@Composable
fun shortDuration(sec: Long): String {
    if (sec < 60) return stringResource(R.string.duration_short_sec, sec.toInt())
    val min = ((sec + 59) / 60).toInt()
    return if (min < 60) stringResource(R.string.missing_duration_min, min) else stringResource(R.string.missing_duration_h_min, min / 60, min % 60)
}
