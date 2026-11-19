package io.wolftown.lauschig.tv.profiles

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvPinPadTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val submitted = mutableListOf<String>()
    private var cancelled = 0

    private fun show(notice: String? = null, waitMs: Long = 0, checking: Boolean = false) = rule.setContent {
        TvTheme { TvPinPad(notice, waitMs, checking, onSubmit = { submitted += it }, onCancel = { cancelled++ }) }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun entered(count: Int) = rule.onNodeWithTag(TAG_PIN_ENTERED).assertContentDescriptionEquals(
        if (count == 1) "1 digit entered" else "$count digits entered",
    )

    @Test fun theFocusStartsOnTheFive() {
        show()
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsFocused()
    }

    /** 5, 2 (up), 1 (left), 4 (down) and OK, which is right of the 0 below the 7, 8 and 9. */
    @Test fun digitsAndOk_submitTheEntry_andClearIt() {
        show()
        press(Key.DirectionCenter) // 5
        press(Key.DirectionUp)
        press(Key.DirectionCenter) // 2
        press(Key.DirectionLeft)
        press(Key.DirectionCenter) // 1
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // 4
        entered(4)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight, times = 2)
        rule.onNodeWithTag(TAG_PIN_OK).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("5214"), submitted)
        entered(0)
    }

    @Test fun theRemotesDigitKeys_typeToo() {
        show()
        press(Key.One)
        press(Key.Two)
        press(Key.Three)
        press(Key.NumPad4)
        entered(4)
    }

    @Test fun delete_takesTheLastDigitAway() {
        show()
        press(Key.DirectionCenter, times = 3) // 555
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionLeft)
        rule.onNodeWithTag(TAG_PIN_DELETE).assertIsFocused()
        press(Key.DirectionCenter)
        entered(2)
        press(Key.DirectionUp, times = 2)
        press(Key.DirectionRight)
        press(Key.DirectionCenter) // 5 again, then 1
        press(Key.One)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_PIN_OK).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("5551"), submitted)
    }

    @Test fun okNeedsAWholePin() {
        show()
        press(Key.DirectionCenter, times = 3)
        rule.onNodeWithTag(TAG_PIN_OK).assertIsNotEnabled()
        assertEquals(emptyList<String>(), submitted)
    }

    @Test fun whileChecking_nothingCanBeEntered() {
        show(checking = true)
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsFocused().assertIsNotEnabled()
        press(Key.DirectionCenter)
        press(Key.One)
        entered(0)
        rule.onNodeWithTag(TAG_PIN_OK).assertIsNotEnabled()
    }

    @Test fun theWait_isShown_andLocksThePad() {
        show(notice = "Too many wrong entries.", waitMs = 27_000)
        rule.onNodeWithTag(TAG_PIN_WAIT).assertTextEquals("0:27 left")
        rule.onNodeWithTag(TAG_PIN_NOTICE).assertContentDescriptionEquals("Too many wrong entries.")
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsNotEnabled()
    }

    /** Through the activity's back dispatcher: how Back arrives on newer Android, and what a key nobody takes ends in. */
    @Test fun back_cancels() {
        show()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, cancelled)
        assertEquals(emptyList<String>(), submitted)
    }

    /** OK on a whole entry empties the pad, and OK would do nothing more: the focus goes back to the 5, where OK types again. */
    @Test fun afterAnEntry_theFocusIsOnTheFive_andOkTypesAgain() {
        show()
        press(Key.One); press(Key.Two); press(Key.Three); press(Key.Four)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_PIN_OK).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("1234"), submitted)
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsFocused()
        press(Key.DirectionCenter)
        entered(1)
    }

    /** A check that ends (a wrong PIN) or a wait that is over opens the pad again with the focus on the 5. */
    @Test fun whenThePadOpensAgain_theFocusIsOnTheFive() {
        var checking by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { TvTheme { TvPinPad(null, 0, checking, onSubmit = { submitted += it; checking = true }, onCancel = {}) } }
        press(Key.One); press(Key.Two); press(Key.Three); press(Key.Four)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        // Somewhere else while checking: the person moved on to the delete key.
        rule.onNodeWithTag(TAG_PIN_DELETE).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        checking = false
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsFocused()
        press(Key.DirectionCenter)
        entered(1)
    }

    /** The labels sit in the middle of their round keys. */
    @Test fun theLabels_areCentredInTheirKeys() {
        show()
        listOf(TAG_PIN_DIGIT + '5', TAG_PIN_DIGIT + '0', TAG_PIN_OK, TAG_PIN_DELETE).forEach { tag ->
            val key = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            // The innermost node below the key: the text or the icon.
            val label = rule.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(tag)), useUnmergedTree = true)
                .fetchSemanticsNodes().last().boundsInRoot
            assertEquals("$tag x", key.center.x, label.center.x, 1.5f)
            assertEquals("$tag y", key.center.y, label.center.y, 1.5f)
        }
    }
}
