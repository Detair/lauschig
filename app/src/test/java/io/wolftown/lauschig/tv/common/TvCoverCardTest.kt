package io.wolftown.lauschig.tv.common

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.ui.common.ItemSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal fun tvItem(id: String, title: String = "Title $id", progress: Float = 0f) =
    ItemSummary("s", id, title, "Author", null, null, null, progress, isDownloaded = false, isFinished = false)

internal fun cardTag(id: String) = TAG_CARD + "s:$id"

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvCoverCardTest {
    @get:Rule val rule = createComposeRule()

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun theCard_takesTheFocus_andOkOpens() {
        var clicks = 0
        rule.setContent { TvTheme { TvCoverCard(tvItem("a", progress = 0.4f), onClick = { clicks++ }) } }
        rule.onNodeWithTag(cardTag("a")).requestFocus().assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, clicks)
    }

    private val five = (1..5).map { tvItem("$it") }

    /** A button above the row, to leave it and come back. */
    private fun showRow(items: () -> List<ItemSummary>, opened: MutableList<String> = mutableListOf()) = rule.setContent {
        TvTheme {
            Column {
                Button(onClick = {}, modifier = Modifier.testTag("above")) { Text("Above") }
                TvItemRow("Continue listening", items(), onOpen = { opened += it.id })
            }
        }
    }

    @Test fun right_movesAlongTheRow_andOkOpensThatCard() {
        val opened = mutableListOf<String>()
        showRow({ five }, opened)
        rule.onNodeWithTag(cardTag("1")).requestFocus()
        press(Key.DirectionRight, times = 2)
        rule.onNodeWithTag(cardTag("3")).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("3"), opened)
    }

    @Test fun leavingAndComingBack_landsOnTheRememberedCard() {
        showRow({ five })
        rule.onNodeWithTag(cardTag("1")).requestFocus()
        press(Key.DirectionRight, times = 3)
        press(Key.DirectionUp)
        rule.onNodeWithTag("above").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(cardTag("4")).assertIsFocused()
    }

    /** Review focus 1: the focused card goes, the focus does not get lost. */
    @Test fun theRowShrinksUnderTheFocus_theFocusIsOnTheLastCard() {
        var items by mutableStateOf(five)
        showRow({ items })
        rule.onNodeWithTag(cardTag("1")).requestFocus()
        press(Key.DirectionRight, times = 4)
        rule.onNodeWithTag(cardTag("5")).assertIsFocused()
        items = five.take(3)
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("3")).assertIsFocused()
    }

    @Test fun theFocusedCardIsReplaced_theFocusStaysAtItsPlace() {
        var items by mutableStateOf(five)
        showRow({ items })
        rule.onNodeWithTag(cardTag("1")).requestFocus()
        press(Key.DirectionRight)
        items = listOf(five[0], tvItem("new"), five[2])
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("new")).assertIsFocused()
    }

    @Test fun theRememberedCard_isFoundByItsKey_whenItMoved() {
        var items by mutableStateOf(five)
        showRow({ items })
        rule.onNodeWithTag(cardTag("1")).requestFocus()
        press(Key.DirectionRight, times = 2) // "3"
        press(Key.DirectionUp)
        items = listOf(five[2]) + five.filter { it.id != "3" } // heard just now: first in the row
        rule.waitForIdle()
        press(Key.DirectionDown)
        rule.onNodeWithTag(cardTag("3")).assertIsFocused()
    }

    @Test fun aMessageWithoutAction_holdsTheFocus() {
        rule.setContent { TvTheme { TvMessage("Loading") } }
        rule.onNodeWithTag(TAG_MESSAGE).assertIsFocused()
    }

    @Test fun aMessageWithAction_focusesIt() {
        var retries = 0
        rule.setContent { TvTheme { TvMessage("Offline", "Try again") { retries++ } } }
        rule.onNodeWithTag(TAG_MESSAGE_ACTION).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, retries)
    }
}
