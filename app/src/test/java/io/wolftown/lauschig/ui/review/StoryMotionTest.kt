package io.wolftown.lauschig.ui.review

import android.animation.ValueAnimator
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** How the story moves follows the device: TalkBack (live) and "remove animations" (read on composition and resume). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoryMotionTest {
    @get:Rule val rule = createComposeRule()

    private val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)

    /** The hidden test API behind "remove animations" (0 = removed). */
    private fun durationScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @After fun tearDown() {
        shadowOf(manager).setTouchExplorationEnabled(false)
        durationScale(1f)
    }

    @Test fun talkBack_turnsAutoAdvanceOff_andOnAgain_whileShown() {
        var motion: StoryMotion? = null
        rule.setContent { motion = rememberStoryMotion() }
        assertEquals(StoryMotion(autoAdvance = true, animations = true, keys = true), motion)
        shadowOf(manager).setTouchExplorationEnabled(true)
        rule.waitForIdle()
        // TalkBack: no auto-advance, and no keyboard focus on the story (the buttons and actions are TalkBack's way).
        assertEquals(StoryMotion(autoAdvance = false, animations = true, keys = false), motion)
        shadowOf(manager).setTouchExplorationEnabled(false)
        rule.waitForIdle()
        assertEquals(StoryMotion(autoAdvance = true, animations = true), motion)
    }

    @Test fun removedAnimations_noAutoAdvance_noEntrances() {
        durationScale(0f)
        var motion: StoryMotion? = null
        rule.setContent { motion = rememberStoryMotion() }
        assertEquals(StoryMotion(autoAdvance = false, animations = false), motion)
    }
}
