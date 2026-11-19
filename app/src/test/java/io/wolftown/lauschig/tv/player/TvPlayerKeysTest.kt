package io.wolftown.lauschig.tv.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvPlayerKeysTest {
    private val ok = listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
    private val all = ok + listOf(
        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.Back,
        Key.MediaPlayPause, Key.MediaFastForward, Key.MediaRewind, Key.VolumeUp, Key.A,
    )

    private fun action(key: Key, screenOff: Boolean = false, sleepEnding: Boolean = false) =
        tvPlayerAction(key, screenOff, sleepEnding, seekBackSec = 10, seekForwardSec = 30)

    @Test fun screenOff_everyKeyOnlyWakes() {
        for (key in all) assertEquals(key.toString(), TvPlayerAction.Wake, action(key, screenOff = true))
    }

    /** In the dark while the timer fades: OK extends it and the screen stays black; every other key only wakes. */
    @Test fun screenOff_whileTheSleepTimerEnds_okExtends_otherKeysWake() {
        for (key in ok) assertEquals(key.toString(), TvPlayerAction.ExtendSleep, action(key, screenOff = true, sleepEnding = true))
        for (key in all - ok.toSet()) assertEquals(key.toString(), TvPlayerAction.Wake, action(key, screenOff = true, sleepEnding = true))
    }

    @Test fun ok_toggles() {
        for (key in ok) assertEquals(key.toString(), TvPlayerAction.Toggle, action(key))
    }

    @Test fun ok_whileTheSleepTimerEnds_extendsIt() {
        for (key in ok) assertEquals(key.toString(), TvPlayerAction.ExtendSleep, action(key, sleepEnding = true))
    }

    @Test fun leftAndRight_seekByTheSteps_alsoWhileTheSleepTimerEnds() {
        for (ending in listOf(false, true)) {
            assertEquals(TvPlayerAction.SeekBy(-10), action(Key.DirectionLeft, sleepEnding = ending))
            assertEquals(TvPlayerAction.SeekBy(30), action(Key.DirectionRight, sleepEnding = ending))
        }
        assertEquals(TvPlayerAction.SeekBy(-5), tvPlayerAction(Key.DirectionLeft, false, false, seekBackSec = 5, seekForwardSec = 15))
        assertEquals(TvPlayerAction.SeekBy(15), tvPlayerAction(Key.DirectionRight, false, false, seekBackSec = 5, seekForwardSec = 15))
    }

    @Test fun down_showsTheControls() {
        assertEquals(TvPlayerAction.ShowControls, action(Key.DirectionDown))
        assertEquals(TvPlayerAction.ShowControls, action(Key.DirectionDown, sleepEnding = true))
    }

    /** Up, Back and the media keys are someone else's: the page's Back handler, the media session, the system's volume. */
    @Test fun otherKeys_areLeftAlone() {
        for (key in listOf(Key.DirectionUp, Key.Back, Key.MediaPlayPause, Key.MediaFastForward, Key.MediaRewind, Key.VolumeUp, Key.A)) {
            for (ending in listOf(false, true)) assertNull("$key ending=$ending", action(key, sleepEnding = ending))
        }
    }
}
