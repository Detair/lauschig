package io.wolftown.lauschig.tv.player

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.SleepMode
import io.wolftown.lauschig.playback.SleepTimerUi
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.tv.browse.TAG_CHAPTER
import io.wolftown.lauschig.tv.browse.TAG_CHAPTER_LIST
import io.wolftown.lauschig.tv.common.TAG_MESSAGE_ACTION
import io.wolftown.lauschig.ui.detail.ChapterRow
import io.wolftown.lauschig.ui.player.PlayerScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvPlayerScreenTest {
    // An activity: Back goes through its dispatcher to the page's handlers.
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val chapters = listOf(
        ChapterRow(0, "One", 600.0, isCurrent = false),
        ChapterRow(1, "Two", 600.0, isCurrent = true, positionInChapterSec = 30.0),
        ChapterRow(2, "Three", 600.0, isCurrent = false),
    )
    private val playing = PlayerScreenState(
        itemId = "b1", title = "Der Schimmelreiter", author = "Theodor Storm", isPlaying = true, chapterTitle = "Two",
        chapterProgress = 0.05f, chapterPositionSec = 30.0, chapterDurationSec = 600.0, chapterElapsedText = "00:30",
        chapterRemainingSec = 570.0, bookRemainingSec = 1170.0, speed = 1.0f, chapters = chapters,
        seekBackSec = 10, seekForwardSec = 30,
    )

    private var state by mutableStateOf(playing)
    private var sleep by mutableStateOf<SleepTimerUi?>(null)
    private var screenOffMinutes by mutableIntStateOf(5)
    private val calls = mutableListOf<String>()

    /** Keys that no element of the page took: they go on to the activity, and from there to the media session. */
    private val passedOn = mutableListOf<Key>()

    private fun show() = rule.setContent {
        TvTheme {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) passedOn += event.key
                    false
                },
            ) { Screen() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
            TvPlayerScreen(
                state, sleep, screenOffMinutes,
                onToggle = { calls += "toggle" },
                onSeekBy = { calls += "seek:$it" },
                onChapter = { calls += "chapter:$it" },
                onSpeed = { calls += "speed:$it" },
                onSleep = { calls += "sleep:${sleepOptionKey(it)}" },
                onExtendSleep = { calls += "extend" },
                onBack = { calls += "back" },
            )
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** With the clock held: what the test or a key changed reaches the page, then a second passes. */
    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test fun theStage_hasTheFocus_andShowsWhatPlays() {
        show()
        rule.onNodeWithTag(TAG_STAGE).assertIsFocused()
        rule.onNodeWithText("Der Schimmelreiter").assertExists()
        rule.onNodeWithText("Theodor Storm").assertExists()
        rule.onNodeWithText("Two").assertExists()
        rule.onNodeWithText("00:30").assertExists()
        rule.onNodeWithText("−09:30").assertExists()
        rule.onNodeWithText(context.getString(R.string.tv_player_book_left, "19:30")).assertExists()
    }

    @Test fun ok_toggles() {
        show()
        press(Key.DirectionCenter)
        assertEquals(listOf("toggle"), calls)
    }

    @Test fun leftAndRight_seekByTheStepsOfTheState() {
        show()
        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        assertEquals(listOf("seek:-10", "seek:30"), calls)
        rule.onNodeWithTag(TAG_STAGE).assertIsFocused()
    }

    /** A held key repeats about twenty times a second; the player gets at most five seeks a second of it (review focus 4). */
    @Test fun holdingRight_seeksAtMostFiveTimesASecond() {
        show()
        rule.onNode(isFocused()).performKeyInput {
            keyDown(Key.DirectionRight)
            advanceEventTime(2_000)
            keyUp(Key.DirectionRight)
        }
        rule.waitForIdle()
        val seeks = calls.count { it == "seek:30" }
        // The remote's repeats: from 0.5 s on, every 50 ms, 31 events in all. Seeks: the press, then one every 0.2 s of the
        // repeats (0.5 s … 1.9 s), so 9; without the limit there would be 31.
        assertTrue("seeks: $seeks", seeks in 5..9)
    }

    @Test fun holdingOk_togglesOnce() {
        show()
        rule.onNode(isFocused()).performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
        assertEquals(listOf("toggle"), calls)
    }

    @Test fun down_showsTheBar_upHidesIt() {
        show()
        rule.onNodeWithTag(TAG_CONTROLS).assertDoesNotExist()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_CONTROLS).assertExists()
        rule.onNodeWithTag(TAG_CHAPTERS_BUTTON).assertIsFocused()
        // Inside the bar left and right move between the buttons and do not seek.
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_SLOWER).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(TAG_CONTROLS).assertDoesNotExist()
        rule.onNodeWithTag(TAG_STAGE).assertIsFocused()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun back_hidesTheBarFirst() {
        show()
        press(Key.DirectionDown)
        back()
        rule.onNodeWithTag(TAG_CONTROLS).assertDoesNotExist()
        rule.onNodeWithTag(TAG_STAGE).assertIsFocused()
    }

    @Test fun speed_stepsByATenth() {
        show()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_FASTER).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("speed:0.9", "speed:1.1"), calls)
    }

    @Test fun speed_keepsTheLimits() {
        state = playing.copy(speed = 3.0f)
        show()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_FASTER).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("only slower at the top", listOf("speed:2.9"), calls)
        calls.clear()
        state = playing.copy(speed = 0.5f)
        rule.waitForIdle()
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        rule.onNodeWithTag(TAG_SLOWER).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("only faster at the bottom", listOf("speed:0.6"), calls)
    }

    @Test fun speedStep_goesToTheNextTenth_withinTheLimits() {
        assertEquals(1.3f, speedStep(1.25f, up = true)!!, 0.001f)
        assertEquals(1.2f, speedStep(1.25f, up = false)!!, 0.001f)
        assertEquals(1.1f, speedStep(1.0f, up = true)!!, 0.001f)
        assertEquals(3.0f, speedStep(2.95f, up = true)!!, 0.001f)
        assertNull(speedStep(3.0f, up = true))
        assertNull(speedStep(0.5f, up = false))
    }

    @Test fun sleepTimer_thirtyMinutes_andOff() {
        show()
        press(Key.DirectionDown)
        press(Key.DirectionRight, times = 3)
        rule.onNodeWithTag(TAG_SLEEP_BUTTON).assertIsFocused()
        press(Key.DirectionCenter)
        // Without a timer the list opens on "Off".
        rule.onNodeWithTag(TAG_SLEEP_OPTION + "off").assertIsFocused()
        press(Key.DirectionUp, times = 4)
        rule.onNodeWithTag(TAG_SLEEP_OPTION + "30").assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_SLEEP_PANEL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_SLEEP_BUTTON).assertIsFocused()
        sleep = SleepTimerUi(SleepMode.Minutes(30), 30 * 60_000L, shakeToExtend = false)
        rule.waitForIdle()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_SLEEP_OPTION + "30").assertIsFocused()
        press(Key.DirectionDown, times = 4)
        rule.onNodeWithTag(TAG_SLEEP_OPTION + "off").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("sleep:30", "sleep:off"), calls)
    }

    @Test fun chapters_openOnTheCurrentOne_okPlaysIt_andBackClosesThem() {
        show()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_CHAPTER + 1).assertIsFocused()
        back()
        rule.onNodeWithTag(TAG_CHAPTER_LIST).assertDoesNotExist()
        rule.onNodeWithTag(TAG_CHAPTERS_BUTTON).assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(listOf("chapter:2"), calls)
        rule.onNodeWithTag(TAG_CHAPTER_LIST).assertDoesNotExist()
        rule.onNodeWithTag(TAG_CHAPTERS_BUTTON).assertIsFocused()
    }

    @Test fun whileTheSleepTimerEnds_okExtendsIt() {
        sleep = SleepTimerUi(SleepMode.Minutes(15), 40_000L, shakeToExtend = true)
        show()
        rule.onNodeWithTag(TAG_EXTEND_HINT).assertExists()
        press(Key.DirectionCenter)
        assertEquals(listOf("extend"), calls)
    }

    @Test fun afterTheWait_onlyBlackRemains_andTheFirstKeyOnlyWakes() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(4 * 60_000L)
        rule.onNodeWithText("Der Schimmelreiter").assertExists()
        rule.mainClock.advanceTimeBy(60_000L + 1_000)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertExists()
        rule.onNodeWithText("Der Schimmelreiter").assertDoesNotExist()
        rule.onNodeWithTag(TAG_STAGE).assertDoesNotExist()
        rule.onNodeWithTag(TAG_SCREEN_OFF).performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertDoesNotExist()
        rule.onNodeWithText("Der Schimmelreiter").assertExists()
        rule.onNodeWithTag(TAG_STAGE).assertIsFocused()
        assertEquals("woken, nothing else", emptyList<String>(), calls)
        // Awake again, keys act as before.
        rule.onNodeWithTag(TAG_STAGE).performKeyInput { pressKey(Key.DirectionCenter) }
        settle()
        assertEquals(listOf("toggle"), calls)
    }

    @Test fun aKeyPress_startsTheWaitAgain() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(4 * 60_000L)
        rule.onNodeWithTag(TAG_STAGE).performKeyInput { pressKey(Key.DirectionDown) }
        settle()
        rule.mainClock.advanceTimeBy(4 * 60_000L)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(60_000L + 1_000)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertExists()
    }

    @Test fun back_whileBlack_onlyWakes() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(5 * 60_000L + 1_000)
        rule.onNodeWithTag(TAG_SCREEN_OFF).performKeyInput { pressKey(Key.Back) }
        settle()
        rule.onNodeWithTag(TAG_STAGE).assertExists()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun aPause_bringsThePictureBack_andPausedItStaysOn() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(5 * 60_000L + 1_000)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertExists()
        state = playing.copy(isPlaying = false)
        settle()
        rule.mainClock.advanceTimeBy(PAUSE_WAKES_MS)
        rule.onNodeWithTag(TAG_STAGE).assertExists()
        rule.mainClock.advanceTimeBy(30 * 60_000L)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertDoesNotExist()
    }

    /** Media3 says "not playing" while it buffers (a stall, the next file, a seek): that is no pause, the room stays dark. */
    @Test fun buffering_keepsTheScreenBlack() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(5 * 60_000L + 1_000)
        state = playing.copy(isPlaying = false)
        settle()
        rule.mainClock.advanceTimeBy(1_000)
        state = playing
        settle()
        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertExists()
        rule.onNodeWithTag(TAG_STAGE).assertDoesNotExist()
    }

    @Test fun never_keepsThePicture() {
        screenOffMinutes = 0
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(120 * 60_000L)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertDoesNotExist()
        rule.onNodeWithText("Der Schimmelreiter").assertExists()
    }

    @Test fun nothingLoaded_saysSo_andOffersBack() {
        state = PlayerScreenState()
        show()
        rule.onNodeWithText(context.getString(R.string.tv_player_nothing)).assertExists()
        rule.onNodeWithTag(TAG_MESSAGE_ACTION).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("back"), calls)
    }

    private fun blackScreen() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(5 * 60_000L + 1_000)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertIsFocused()
    }

    /**
     * Held while black (review focus 4): the press wakes, and neither its repeats nor its release do anything once the stage
     * is back. The stage is there before the repeats come, so without the swallowing they would seek or toggle.
     */
    private fun holdWhileBlack(key: Key) {
        blackScreen()
        // As a remote sends a held key: the press, repeats every 50 ms from 0.5 s on, the release. Sent one by one, so the
        // stage is back before the repeats arrive.
        fun event(action: Int, repeat: Int, at: Long) =
            androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(0L, at, action, key.nativeKeyCode, repeat))
        rule.onNodeWithTag(TAG_SCREEN_OFF).performKeyPress(event(android.view.KeyEvent.ACTION_DOWN, 0, 0L))
        settle()
        rule.onNodeWithTag(TAG_STAGE).assertIsFocused()
        for (i in 1..10) {
            rule.onNodeWithTag(TAG_STAGE).performKeyPress(event(android.view.KeyEvent.ACTION_DOWN, i, 450L + 50L * i))
        }
        rule.onNodeWithTag(TAG_STAGE).performKeyPress(event(android.view.KeyEvent.ACTION_UP, 0, 1_100L))
        settle()
        assertEquals("woken by $key, nothing else", emptyList<String>(), calls)
        // Released: the next press acts again.
        rule.onNodeWithTag(TAG_STAGE).performKeyInput { pressKey(key) }
        settle()
        assertEquals(1, calls.size)
    }

    @Test fun holdingRight_whileBlack_onlyWakes() = holdWhileBlack(Key.DirectionRight)

    @Test fun holdingLeft_whileBlack_onlyWakes() = holdWhileBlack(Key.DirectionLeft)

    @Test fun holdingOk_whileBlack_onlyWakes() = holdWhileBlack(Key.DirectionCenter)

    /** Back from the activity's dispatcher (not as a key) while black: it wakes, and the page is not left. */
    @Test fun backThroughTheDispatcher_whileBlack_onlyWakes() {
        blackScreen()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        rule.onNodeWithTag(TAG_STAGE).assertExists()
        assertFalse("the activity was not left", rule.activity.isFinishing)
        assertEquals(emptyList<String>(), calls)
    }

    /** A media key while black wakes the picture and still goes on to the media session. */
    @Test fun aMediaKey_whileBlack_wakes_andPassesOn() {
        blackScreen()
        rule.onNodeWithTag(TAG_SCREEN_OFF).performKeyInput { pressKey(Key.MediaPlayPause) }
        settle()
        rule.onNodeWithTag(TAG_STAGE).assertExists()
        assertEquals(listOf(Key.MediaPlayPause), passedOn)
        assertEquals(emptyList<String>(), calls)
    }

    /** In the dark while the timer fades: OK extends it and the screen stays black; held, it extends once. */
    @Test fun okWhileBlack_andTheTimerEnds_extends_andStaysBlack() {
        sleep = SleepTimerUi(SleepMode.Minutes(15), 40_000L, shakeToExtend = true)
        blackScreen()
        rule.onNodeWithTag(TAG_SCREEN_OFF).performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_500)
            keyUp(Key.DirectionCenter)
        }
        settle()
        assertEquals(listOf("extend"), calls)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertExists()
        // Any other key still only wakes.
        rule.onNodeWithTag(TAG_SCREEN_OFF).performKeyInput { pressKey(Key.DirectionLeft) }
        settle()
        rule.onNodeWithTag(TAG_STAGE).assertExists()
        assertEquals(listOf("extend"), calls)
    }

    /** The timer reaches its end while the bar (and a list) is open: they make way, and OK extends instead of opening a list. */
    @Test fun theTimerEnding_withTheBarOpen_givesTheStageTheFocus_soOkExtends() {
        sleep = SleepTimerUi(SleepMode.Minutes(15), 90_000L, shakeToExtend = false)
        show()
        press(Key.DirectionDown)
        press(Key.DirectionRight, times = 3)
        rule.onNodeWithTag(TAG_SLEEP_BUTTON).assertIsFocused()
        sleep = SleepTimerUi(SleepMode.Minutes(15), 59_000L, shakeToExtend = true)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CONTROLS).assertDoesNotExist()
        rule.onNodeWithTag(TAG_STAGE).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("extend"), calls)
    }

    @Test fun theTimerEnding_withAListOpen_closesIt() {
        sleep = SleepTimerUi(SleepMode.Minutes(15), 90_000L, shakeToExtend = false)
        show()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_CHAPTER_LIST).assertExists()
        sleep = SleepTimerUi(SleepMode.Minutes(15), 59_000L, shakeToExtend = true)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CHAPTER_LIST).assertDoesNotExist()
        rule.onNodeWithTag(TAG_STAGE).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("extend"), calls)
    }

    /** Another title without chapters while "Chapters" has the focus: the focus moves to the first button left in the bar. */
    @Test fun theChaptersButtonGoes_theFocusMovesToTheBar() {
        show()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_CHAPTERS_BUTTON).assertIsFocused()
        state = playing.copy(chapters = emptyList())
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CHAPTERS_BUTTON).assertDoesNotExist()
        rule.onNodeWithTag(TAG_SLOWER).assertIsFocused()
    }

    @Test fun theChaptersGo_whileTheirListIsOpen_theListCloses_andTheFocusStaysOnThePage() {
        show()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_CHAPTER + 1).assertIsFocused()
        state = playing.copy(chapters = emptyList())
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CHAPTER_LIST).assertDoesNotExist()
        rule.onNodeWithTag(TAG_SLOWER).assertIsFocused()
    }

    /** A timer from the phone the TV does not offer (10 minutes): the bar says how long it runs, the list opens on "Off". */
    @Test fun aTimerSetElsewhere_showsItsTime_andTheListOpensOnOff() {
        sleep = SleepTimerUi(SleepMode.Minutes(10), 5 * 60_000L, shakeToExtend = false)
        show()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_SLEEP_BUTTON).assert(hasText(context.getString(R.string.player_sleep_active, "05:00")))
        press(Key.DirectionRight, times = 3)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_SLEEP_OPTION + "off").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("sleep:off"), calls)
    }

    /** A running TV choice, partly over: the list opens on it. */
    @Test fun aRunningTimer_partlyOver_theListOpensOnIt() {
        for ((mode, key) in listOf(SleepMode.Minutes(45) to "45", SleepMode.EndOfChapter to "chapter")) {
            assertEquals(key, sleepOptionKey(SLEEP_OPTIONS[sleepPanelStart(mode)]))
        }
        assertEquals("off", sleepOptionKey(SLEEP_OPTIONS[sleepPanelStart(SleepMode.Minutes(90))]))
        assertEquals("off", sleepOptionKey(SLEEP_OPTIONS[sleepPanelStart(null)]))
        sleep = SleepTimerUi(SleepMode.Minutes(45), 12 * 60_000L, shakeToExtend = false)
        show()
        press(Key.DirectionDown)
        press(Key.DirectionRight, times = 3)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_SLEEP_OPTION + "45").assertIsFocused()
    }

    /** Every choice of the list sets its own mode. */
    @Test fun everySleepChoice_setsItsMode() {
        show()
        press(Key.DirectionDown)
        press(Key.DirectionRight, times = 3)
        // From "Off" up to each choice: chapter 1 up, 60 2, 45 3, 30 4, 15 5.
        for (ups in 1..5) {
            press(Key.DirectionCenter)
            rule.onNodeWithTag(TAG_SLEEP_OPTION + "off").assertIsFocused()
            press(Key.DirectionUp, times = ups)
            press(Key.DirectionCenter)
            rule.onNodeWithTag(TAG_SLEEP_BUTTON).assertIsFocused()
        }
        assertEquals(listOf("sleep:chapter", "sleep:60", "sleep:45", "sleep:30", "sleep:15"), calls)
    }

    /** HOME and back: the wait did not run in the background, the picture is there, and it starts again from the return. */
    @Test fun afterHome_thePictureIsThere_andTheWaitStartsAgain() {
        rule.mainClock.autoAdvance = false
        show()
        rule.mainClock.advanceTimeBy(4 * 60_000L)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        settle()
        rule.mainClock.advanceTimeBy(30 * 60_000L)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        rule.onNodeWithTag(TAG_STAGE).assertExists()
        rule.mainClock.advanceTimeBy(4 * 60_000L)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(60_000L + 1_000)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertExists()
    }

    @Test fun blackWhenLeft_isLitOnReturn() {
        blackScreen()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        settle()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertDoesNotExist()
        rule.onNodeWithTag(TAG_STAGE).assertExists()
    }

    /** The chapter's bar is said as a progress bar (screen readers), not as a silent stripe. */
    @Test fun theChaptersProgress_isAProgressBarForScreenReaders() {
        show()
        rule.onNodeWithTag(TAG_PROGRESS).assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics.ProgressBarRangeInfo(0.05f, 0f..1f),
            ),
        )
    }
}
