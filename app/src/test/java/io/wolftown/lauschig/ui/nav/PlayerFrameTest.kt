package io.wolftown.lauschig.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The frame's bars change only underneath a player that covers it (see [playerCoversFrame]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerFrameTest {
    @get:Rule val rule = createComposeRule()

    /** A state change as a tap would make it: applied, then the frames run on the test clock. */
    private fun set(change: () -> Unit) {
        rule.runOnUiThread { Snapshot.withMutableSnapshot(change) }
    }

    @Test fun barsStay_untilTheSlideHasCoveredThem_andAreBack_beforeThePlayerSlidesAway() {
        var onPlayer by mutableStateOf(false)
        var covers = true
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val slide = remember { MutableTransitionState(false) }
            if (slide.targetState != onPlayer) slide.targetState = onPlayer
            covers = playerCoversFrame(onPlayer, slide)
            AnimatedVisibility(slide, enter = slideInVertically(playerSlide()) { it }, exit = slideOutVertically(playerSlide()) { it }) {
                Box(Modifier.size(10.dp))
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertFalse(covers)

        set { onPlayer = true }
        rule.mainClock.advanceTimeBy(PLAYER_SLIDE_MS / 2L)
        assertFalse("mid-slide the bars are still there", covers)
        rule.mainClock.advanceTimeBy(PLAYER_SLIDE_MS * 2L)
        assertTrue("once covered, the frame may drop them", covers)

        set { onPlayer = false }
        rule.mainClock.advanceTimeByFrame()
        assertFalse("back at the first frame of the slide down", covers)
        rule.mainClock.advanceTimeBy(PLAYER_SLIDE_MS * 2L)
        assertFalse(covers)
    }
}
