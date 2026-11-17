package io.wolftown.lauschig.tv.player

import androidx.compose.ui.input.key.Key

/** What a key on the player's stage does (TV spec: OK pauses and resumes, left and right seek, down opens the controls). */
sealed interface TvPlayerAction {
    data object Toggle : TvPlayerAction
    /** Seconds to move; negative goes back. */
    data class SeekBy(val sec: Int) : TvPlayerAction
    data object ShowControls : TvPlayerAction
    /** The picture comes back from "screen off"; the key does nothing else. */
    data object Wake : TvPlayerAction
    /** OK while the sleep timer fades out: on with it, as a shake does on the phone. */
    data object ExtendSleep : TvPlayerAction
}

/** The keys that count as OK: the remote's centre, and Enter for keyboards and some remotes. */
internal val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * The action of [key] on the stage, or null when the stage leaves it alone. While the sleep timer ends ([sleepEnding]: its
 * last minute, the hint tone and the fade) OK extends it instead of pausing: whoever presses it is awake. While the screen is
 * off ([screenOff]) every key only wakes it, except OK while the timer ends: that extends it and the screen stays black, as
 * someone lying in the dark who hears the fade wants to keep listening, not to light up the room. The media keys are not
 * here: the media session handles them.
 */
fun tvPlayerAction(key: Key, screenOff: Boolean, sleepEnding: Boolean, seekBackSec: Int, seekForwardSec: Int): TvPlayerAction? = when {
    screenOff -> if (sleepEnding && key in OK_KEYS) TvPlayerAction.ExtendSleep else TvPlayerAction.Wake
    key in OK_KEYS -> if (sleepEnding) TvPlayerAction.ExtendSleep else TvPlayerAction.Toggle
    key == Key.DirectionLeft -> TvPlayerAction.SeekBy(-seekBackSec)
    key == Key.DirectionRight -> TvPlayerAction.SeekBy(seekForwardSec)
    key == Key.DirectionDown -> TvPlayerAction.ShowControls
    else -> null
}
