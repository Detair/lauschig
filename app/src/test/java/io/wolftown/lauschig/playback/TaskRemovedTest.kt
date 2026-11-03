package io.wolftown.lauschig.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRemovedTest {
    @Test fun swipedAway_stopsOnlyWhenNothingPlays_andNeverWhileThePlayKeyGuardWaits() {
        assertTrue(PlaybackService.stopsOnTaskRemoved(hasPlayer = true, playWhenReady = false, itemCount = 3, guardArmed = false))
        assertTrue(PlaybackService.stopsOnTaskRemoved(hasPlayer = true, playWhenReady = true, itemCount = 0, guardArmed = false))
        assertTrue(PlaybackService.stopsOnTaskRemoved(hasPlayer = false, playWhenReady = false, itemCount = 0, guardArmed = false))
        assertFalse(PlaybackService.stopsOnTaskRemoved(hasPlayer = true, playWhenReady = true, itemCount = 3, guardArmed = false))
        // Started by a play key, the resumption still running: stopping now would crash (no startForeground yet);
        // the guard settles and stops an empty service itself.
        assertFalse(PlaybackService.stopsOnTaskRemoved(hasPlayer = true, playWhenReady = false, itemCount = 0, guardArmed = true))
    }
}
