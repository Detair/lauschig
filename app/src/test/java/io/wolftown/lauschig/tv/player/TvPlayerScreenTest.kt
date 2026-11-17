package io.wolftown.lauschig.tv.player

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
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

    private fun show() = rule.setContent {
        TvTheme {
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
        rule.onNodeWithTag(TAG_STAGE).assertExists()
        rule.mainClock.advanceTimeBy(30 * 60_000L)
        rule.onNodeWithTag(TAG_SCREEN_OFF).assertDoesNotExist()
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
}
