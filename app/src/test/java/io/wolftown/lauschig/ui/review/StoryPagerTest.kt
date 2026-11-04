package io.wolftown.lauschig.ui.review

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The story pager: its time, taps, holding, closing, the mode without auto-advance (TalkBack) and the background. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoryPagerTest {
    @get:Rule val rule = createComposeRule()

    private class Owner(state: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    private var closed = 0
    private val entrances = mutableListOf<Pair<Int, Boolean>>()

    @Composable
    private fun Story(state: StoryState, motion: StoryMotion) {
        StoryPager(
            state, onClose = { closed++ }, title = "Story",
            colors = { SlideColors(Color.White, Color.Black) },
            description = { "Text $it" },
            motion = motion,
        ) { i, entrance ->
            // Once per composition of a slide (what the pager hands it then).
            remember { entrances.add(i to entrance) }
            Text("Slide $i")
        }
    }

    private fun show(state: StoryState, motion: StoryMotion = StoryMotion(), owner: LifecycleOwner? = null, clock: Boolean = false) {
        // With auto-advance the clock is moved by hand: left to itself it would run the whole story.
        rule.mainClock.autoAdvance = !clock
        rule.setContent {
            if (owner == null) Story(state, motion) else CompositionLocalProvider(LocalLifecycleOwner provides owner) { Story(state, motion) }
        }
    }

    private fun segments(vararg expected: SegmentState) {
        val nodes = rule.onAllNodesWithTag("storySegment")
        nodes.assertCountEquals(expected.size)
        expected.forEachIndexed { i, s -> nodes[i].assert(SemanticsMatcher.expectValue(SegmentStateKey, s)) }
    }

    @Test fun movesOnAfterSevenSeconds_theSegmentsFollow_andStaysOnTheLast() {
        val state = StoryState(3)
        show(state, clock = true)
        rule.mainClock.advanceTimeBy(3_500)
        assertEquals(0, state.index)
        assertEquals(0.5f, state.progress, 0.02f)
        segments(SegmentState.CURRENT, SegmentState.TODO, SegmentState.TODO)

        rule.mainClock.advanceTimeBy(3_600)
        assertEquals(1, state.index)
        segments(SegmentState.DONE, SegmentState.CURRENT, SegmentState.TODO)

        rule.mainClock.advanceTimeBy(7_100)
        assertEquals(2, state.index)
        rule.mainClock.advanceTimeBy(10_000)
        // The last slide stays (its time full); the story is closed by the user.
        assertEquals(2, state.index)
        assertEquals(1f, state.progress, 0f)
        assertEquals(0, closed)
    }

    @Test fun aTapOnTheRightIsNext_onTheLeftThirdTheOneBefore_theFirstStartsAgain() {
        val state = StoryState(3)
        show(state, StoryMotion(autoAdvance = false))
        val slide = rule.onNodeWithTag("storySlide")
        slide.performTouchInput { click(percentOffset(0.9f, 0.5f)) }
        assertEquals(1, state.index)
        slide.performTouchInput { click(percentOffset(0.5f, 0.5f)) }
        assertEquals(2, state.index)
        slide.performTouchInput { click(percentOffset(0.9f, 0.5f)) }
        assertEquals(2, state.index) // no further than the last
        slide.performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        slide.performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        assertEquals(0, state.index)
        state.progress = 0.4f
        slide.performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        assertEquals(0, state.index)
        assertEquals(0f, state.progress, 0f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h600dp")
    fun onAWideWindow_theSidesBesideTheStoryAreTapAreasToo() {
        val state = StoryState(3)
        show(state, StoryMotion(autoAdvance = false))
        val story = rule.onNodeWithTag("story")
        story.performTouchInput { click(percentOffset(0.95f, 0.5f)) }
        assertEquals(1, state.index)
        story.performTouchInput { click(percentOffset(0.05f, 0.5f)) }
        assertEquals(0, state.index)
    }

    @Test fun holdingPauses_andItsReleaseMovesNothing() {
        val state = StoryState(3)
        show(state, clock = true)
        rule.mainClock.advanceTimeBy(1_000)
        val before = state.progress
        val slide = rule.onNodeWithTag("storySlide")
        slide.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(10_000)
        assertEquals(0, state.index)
        assertEquals(before, state.progress, 0.01f)
        slide.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(0, state.index) // a long press is no tap
        rule.mainClock.advanceTimeBy(7_000)
        assertEquals(1, state.index)
    }

    @Test fun theXAndASwipeDownClose() {
        show(StoryState(2), StoryMotion(autoAdvance = false))
        rule.onNodeWithTag("storyClose").assertContentDescriptionEquals("Close year in review").performClick()
        assertEquals(1, closed)
        rule.onNodeWithTag("storySlide").performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertEquals(2, closed)
    }

    @Test fun withoutAutoAdvance_itWaits_buttonsMove_andEachSlideIsOneAnnouncedItem() {
        val state = StoryState(3)
        show(state, StoryMotion(autoAdvance = false), clock = true)
        rule.mainClock.advanceTimeBy(30_000)
        assertEquals(0, state.index)
        rule.mainClock.autoAdvance = true // nothing runs by itself now
        val slide = rule.onNodeWithTag("storySlide")
        slide.assertContentDescriptionEquals("Slide 1 of 3. Text 0")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithTag("storyPrevious").assertIsNotEnabled()
        rule.onNodeWithTag("storyNext").assertIsEnabled().performClick()
        assertEquals(1, state.index)
        slide.assertContentDescriptionEquals("Slide 2 of 3. Text 1")
        rule.onNodeWithTag("storyNext").performClick()
        rule.onNodeWithTag("storyNext").assertIsNotEnabled()
        rule.onNodeWithTag("storyPrevious").performClick()
        assertEquals(1, state.index)
        // TalkBack's actions menu moves too, and a double tap on the slide is "next" (said so) – not on the last one.
        val config = slide.fetchSemanticsNode().config
        assertEquals(listOf("Next slide", "Previous slide"), config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label })
        assertEquals("Next slide", config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label)
        slide.performClick()
        assertEquals(2, state.index)
        val last = slide.fetchSemanticsNode().config
        assertTrue(androidx.compose.ui.semantics.SemanticsActions.OnClick !in last)
        // Only actions that lead somewhere: the last slide has no "next", the first no "previous".
        assertEquals(listOf("Previous slide"), last[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label })
        rule.onNodeWithTag("storyPrevious").performClick()
        rule.onNodeWithTag("storyPrevious").performClick()
        assertEquals(0, state.index)
        assertEquals(listOf("Next slide"), slide.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label })
    }

    @Test fun keys_rightAndLeftMove_spacePausesAndResumes() {
        val state = StoryState(3)
        show(state, clock = true)
        rule.mainClock.advanceTimeByFrame()
        val story = rule.onNodeWithTag("story")
        story.performKeyInput { pressKey(Key.DirectionRight) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1, state.index)
        story.performKeyInput { pressKey(Key.DirectionLeft) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(0, state.index)
        story.performKeyInput { pressKey(Key.Spacebar) }
        rule.mainClock.advanceTimeBy(10_000)
        assertEquals(0, state.index)
        story.performKeyInput { pressKey(Key.Spacebar) }
        // Nothing asks for frames while paused: let the state change reach the composition first.
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(7_200)
        assertEquals(1, state.index)
    }

    @Test fun aHeldKey_actsOnce_andAPauseIsShown_untilSpaceOrAnArrowEndsIt() {
        val state = StoryState(9)
        show(state, clock = true)
        rule.mainClock.advanceTimeByFrame()
        val story = rule.onNodeWithTag("story")
        // An arrow held for a second and a half: one slide, not the whole story.
        story.performKeyInput { keyDown(Key.DirectionRight); advanceEventTime(1_500); keyUp(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(1, state.index)
        // A held space pauses once (no flicker) – and says so.
        story.performKeyInput { keyDown(Key.Spacebar); advanceEventTime(1_000); keyUp(Key.Spacebar) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(10_000)
        assertEquals(1, state.index)
        rule.onNodeWithTag("storyPaused", useUnmergedTree = true).assertContentDescriptionEquals("Paused")
        // The next arrow goes on and ends the pause.
        story.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(2, state.index)
        rule.onAllNodesWithTag("storyPaused", useUnmergedTree = true).assertCountEquals(0)
        rule.mainClock.advanceTimeBy(7_200)
        assertEquals(3, state.index)
    }

    @Test fun aLongHold_showsThePause_aTapDoesNot() {
        val state = StoryState(3)
        show(state, clock = true)
        val slide = rule.onNodeWithTag("storySlide")
        slide.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        rule.onAllNodesWithTag("storyPaused", useUnmergedTree = true).assertCountEquals(0)
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("storyPaused", useUnmergedTree = true).assertExists()
        slide.performTouchInput { up() }
        rule.mainClock.advanceTimeByFrame()
        rule.onAllNodesWithTag("storyPaused", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun withTalkBack_theStoryTakesNoKeyboardFocus_otherwiseItDoes() {
        show(StoryState(3), StoryMotion(autoAdvance = false, keys = false))
        rule.onNodeWithTag("story").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
    }

    @Test fun withAKeyboard_theStoryHasTheFocus() {
        show(StoryState(3), StoryMotion(autoAdvance = false))
        rule.onNodeWithTag("story").assertIsFocused()
    }

    @Test fun rightToLeft_theTapZonesAndTheArrowsAreMirrored() {
        val state = StoryState(3)
        rule.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                Story(state, StoryMotion(autoAdvance = false))
            }
        }
        val story = rule.onNodeWithTag("story")
        // The right third is "back" in right-to-left, the rest "next".
        story.performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        assertEquals(1, state.index)
        story.performTouchInput { click(percentOffset(0.9f, 0.5f)) }
        assertEquals(0, state.index)
        story.performKeyInput { pressKey(Key.DirectionLeft) }
        assertEquals(1, state.index)
        story.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(0, state.index)
    }

    @Test fun theUsersTimeToTakeAction_makesASlideStayLonger() {
        val manager = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).setNonInteractiveUiTimeout(20_000)
        val state = StoryState(3)
        show(state, clock = true)
        rule.mainClock.advanceTimeBy(7_500)
        assertEquals(0, state.index)
        rule.mainClock.advanceTimeBy(13_000)
        assertEquals(1, state.index)
    }

    @Test fun pulledDown_itWaits_andGoesOnOnceLetGo() {
        val state = StoryState(3)
        show(state, clock = true)
        val slide = rule.onNodeWithTag("storySlide")
        slide.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 20f)); moveBy(androidx.compose.ui.geometry.Offset(0f, 40f)) }
        rule.mainClock.advanceTimeBy(10_000)
        assertEquals(0, state.index)
        slide.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(8_000)
        assertEquals(1, state.index)
        assertEquals(0, closed)
    }

    @Test fun withAutoAdvance_thereAreNoButtons() {
        show(StoryState(3), clock = true)
        rule.onAllNodesWithTag("storyNext").assertCountEquals(0)
        rule.onAllNodesWithTag("storyPrevious").assertCountEquals(0)
    }

    @Test fun notInFront_itNeverMoves() {
        val owner = Owner(Lifecycle.State.STARTED)
        val state = StoryState(3)
        show(state, owner = owner, clock = true)
        rule.mainClock.advanceTimeBy(10_000)
        assertEquals(0, state.index)
        assertEquals(0f, state.progress, 0f)
        owner.registry.currentState = Lifecycle.State.RESUMED
        rule.mainClock.advanceTimeBy(7_200)
        assertEquals(1, state.index)
        owner.registry.currentState = Lifecycle.State.STARTED
        rule.mainClock.advanceTimeBy(10_000)
        assertEquals(1, state.index)
    }

    @Test fun eachSlidesEntrancePlaysOnce_andNotWithoutAnimations() {
        val state = StoryState(3)
        show(state, StoryMotion(autoAdvance = false))
        val slide = rule.onNodeWithTag("storySlide")
        slide.performTouchInput { click(percentOffset(0.9f, 0.5f)) }
        rule.waitForIdle()
        slide.performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        rule.waitForIdle()
        assertEquals(listOf(0 to true, 1 to true, 0 to false), entrances)
    }

    @Test fun withoutAnimations_noEntrance() {
        show(StoryState(2), StoryMotion(autoAdvance = false, animations = false))
        assertEquals(listOf(0 to false), entrances)
    }
}
