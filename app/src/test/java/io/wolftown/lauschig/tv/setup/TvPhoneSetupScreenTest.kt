package io.wolftown.lauschig.tv.setup

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tvsetup.TvSetupCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvPhoneSetupScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val code = TvSetupCode("192.168.1.50", 41234, ByteArray(32) { it.toByte() })
    private val waiting = TvPhoneSetupState.Waiting(code.text, "4821")

    private var newCodes = 0
    private var types = 0
    private var confirms = 0
    private var declines = 0
    private var state by mutableStateOf<TvPhoneSetupState>(TvPhoneSetupState.Starting)

    private fun show(initial: TvPhoneSetupState, addProfile: Boolean = false) {
        state = initial
        rule.setContent {
            TvTheme {
                TvPhoneSetupScreen(
                    state, addProfile, onNewCode = { newCodes++ }, onType = { types++ }, onConfirm = { confirms++ }, onDecline = { declines++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun ok() {
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
    }

    private fun change(next: TvPhoneSetupState) {
        state = next
        rule.waitForIdle()
    }

    @Test fun waiting_showsCodeAndNumber_andTypeHasTheFocus() {
        show(waiting)
        rule.onNodeWithTag(TAG_PHONE_QR).assertIsDisplayed().assertWidthIsAtLeast(240.dp).assertHeightIsAtLeast(240.dp)
        rule.onNodeWithTag(TAG_PHONE_NUMBER).assertTextEquals("48 21")
        rule.onNodeWithText(context.getString(R.string.tv_phone_title)).assertExists()
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertIsFocused()
        ok()
        assertEquals(1, types)
    }

    @Test fun theNumber_isReadDigitByDigit() {
        show(waiting)
        rule.onNodeWithTag(TAG_PHONE_NUMBER).assertContentDescriptionEquals(context.getString(R.string.tv_phone_number_description, "4 8 2 1"))
    }

    @Test fun thePage_staysInsideTheTvMargins() {
        show(waiting)
        val root = rule.onRoot().getBoundsInRoot()
        for (tag in listOf(TAG_PHONE_QR, TAG_PHONE_NUMBER, TAG_PHONE_TYPE)) {
            val b = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue(tag, b.left >= TvDimens.overscanHorizontal && b.top >= TvDimens.overscanVertical)
            assertTrue(tag, b.right <= root.right - TvDimens.overscanHorizontal && b.bottom <= root.bottom - TvDimens.overscanVertical)
        }
    }

    @Test fun addProfile_saysSo() {
        show(waiting, addProfile = true)
        rule.onNodeWithText(context.getString(R.string.profiles_add)).assertExists()
    }

    @Test fun starting_progressInsteadOfTheCode_typeHasTheFocus() {
        show(TvPhoneSetupState.Starting)
        rule.onNodeWithTag(TAG_PHONE_QR).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PHONE_PROGRESS).assertExists()
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertIsFocused()
    }

    @Test fun signingIn_theCodeIsGone_theFocusIsOnTheLine() {
        show(TvPhoneSetupState.SigningIn)
        rule.onNodeWithTag(TAG_PHONE_QR).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PHONE_NUMBER).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PHONE_STATUS).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.tv_phone_signing_in)).assertExists()
    }

    @Test fun noHomeNetwork_retryHasTheFocus_andTypeIsThere() {
        show(TvPhoneSetupState.NoHomeNetwork)
        rule.onNodeWithText(context.getString(R.string.tv_phone_no_network)).assertExists()
        rule.onNodeWithTag(TAG_PHONE_AGAIN).assertIsFocused().assertTextEquals(context.getString(R.string.tv_retry))
        ok()
        assertEquals(1, newCodes)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertIsFocused()
        ok()
        assertEquals(1, types)
    }

    @Test fun stopped_oneSentencePerReason_newCodeHasTheFocus() {
        val sentences = mapOf(
            TvPhoneSetupStop.EXPIRED to R.string.tv_phone_expired,
            TvPhoneSetupStop.TOO_MANY_ATTEMPTS to R.string.tv_phone_too_many,
            TvPhoneSetupStop.SERVER_UNREACHABLE to R.string.tv_phone_unreachable,
            TvPhoneSetupStop.SIGN_IN_REFUSED to R.string.tv_phone_refused,
            TvPhoneSetupStop.FAILED to R.string.tv_phone_failed,
            TvPhoneSetupStop.DECLINED to R.string.tv_phone_declined,
        )
        show(TvPhoneSetupState.Stopped(TvPhoneSetupStop.EXPIRED))
        for ((reason, sentence) in sentences) {
            change(TvPhoneSetupState.Stopped(reason))
            rule.onNodeWithTag(TAG_PHONE_STATUS).assertTextEquals(context.getString(sentence))
            rule.onNodeWithTag(TAG_PHONE_AGAIN).assertIsFocused().assertTextEquals(context.getString(R.string.tv_phone_new_code))
        }
        ok()
        assertEquals(1, newCodes)
    }

    /** Every change of the state leaves the focus on the button named for the new one, never nowhere. */
    @Test fun theFocus_followsEveryChange() {
        show(TvPhoneSetupState.Starting)
        val steps = listOf(
            waiting to TAG_PHONE_TYPE,
            TvPhoneSetupState.Stopped(TvPhoneSetupStop.EXPIRED) to TAG_PHONE_AGAIN,
            TvPhoneSetupState.Starting to TAG_PHONE_TYPE,
            waiting to TAG_PHONE_TYPE,
            asking to TAG_PHONE_CANCEL,
            TvPhoneSetupState.SigningIn to TAG_PHONE_STATUS,
            TvPhoneSetupState.Stopped(TvPhoneSetupStop.SERVER_UNREACHABLE) to TAG_PHONE_AGAIN,
            TvPhoneSetupState.NoHomeNetwork to TAG_PHONE_AGAIN,
            TvPhoneSetupState.Starting to TAG_PHONE_TYPE,
        )
        for ((next, tag) in steps) {
            change(next)
            rule.onNodeWithTag(tag).assertIsFocused()
        }
    }

    // --- The question before a packet is used (spec E17).

    private val asking = TvPhoneSetupState.Confirming(
        listOf(TvPhoneServer("http://192.168.1.20:13378", encrypted = false), TvPhoneServer("https://abs.example.org", encrypted = true)),
    )

    @Test fun confirming_listsTheAddressesInOrder_marksPlainHttp_noCode() {
        show(asking)
        rule.onNodeWithText(context.getString(R.string.tv_phone_confirm_title)).assertExists()
        val lines = rule.onAllNodesWithTag(TAG_PHONE_SERVER).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals(
            listOf("http://192.168.1.20:13378 " + context.getString(R.string.tv_phone_confirm_not_encrypted), "https://abs.example.org"),
            lines,
        )
        rule.onNodeWithTag(TAG_PHONE_QR).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PHONE_NUMBER).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertDoesNotExist()
    }

    @Test fun confirming_cancelHasTheFocus_andOkCancels() {
        show(waiting)
        change(asking)
        rule.onNodeWithTag(TAG_PHONE_CANCEL).assertIsFocused()
        ok()
        assertEquals(1 to 0, declines to confirms)
    }

    @Test fun confirming_rightThenOk_setsUp() {
        show(asking)
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PHONE_CONFIRM).assertIsFocused()
        ok()
        assertEquals(0 to 1, declines to confirms)
    }

    @Test fun confirming_backIsANo() {
        show(asking)
        back()
        assertEquals(1, declines)
    }

    @Test fun declined_saysSo_newCodeHasTheFocus() {
        show(asking)
        change(TvPhoneSetupState.Stopped(TvPhoneSetupStop.DECLINED))
        rule.onNodeWithTag(TAG_PHONE_STATUS).assertTextEquals(context.getString(R.string.tv_phone_declined))
        rule.onNodeWithTag(TAG_PHONE_AGAIN).assertIsFocused()
    }

    // --- Which page: the route's switch (TvSetupPages).

    private var shown = 0
    private var hidden = 0
    private lateinit var lifecycle: LifecycleRegistry

    private fun pages(phoneFirst: Boolean, tester: StateRestorationTester? = null) {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        lifecycle = owner.registry
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TvTheme {
                    TvSetupPages(
                        phoneFirst = phoneFirst, onShow = { shown++ }, onHide = { hidden++ },
                        phonePage = { onType -> TvPhoneSetupScreen(waiting, false, onNewCode = {}, onType = onType, onConfirm = {}, onDecline = {}) },
                    ) { Text("form") }
                }
            }
        }
        if (tester != null) tester.setContent(content) else rule.setContent(content)
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test fun pages_theCodeComesFirst_typeLeadsToTheForm_backLeadsBack() {
        pages(phoneFirst = true)
        rule.onNodeWithTag(TAG_PHONE_QR).assertExists()
        assertEquals(1 to 0, shown to hidden)
        ok()
        rule.onNodeWithText("form").assertExists()
        assertEquals("the port closes with the code page", 1 to 1, shown to hidden)
        back()
        rule.onNodeWithTag(TAG_PHONE_QR).assertExists()
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertIsFocused()
        assertEquals("a new code", 2 to 1, shown to hidden)
    }

    @Test fun pages_forgottenPin_onlyTheForm() {
        pages(phoneFirst = false)
        rule.onNodeWithText("form").assertExists()
        rule.onNodeWithTag(TAG_PHONE_QR).assertDoesNotExist()
        assertEquals(0 to 0, shown to hidden)
    }

    @Test fun pages_homeOnTheTv_closesThePort_andComingBackOpensANewOne() {
        pages(phoneFirst = true)
        rule.runOnUiThread { lifecycle.currentState = Lifecycle.State.CREATED }
        rule.waitForIdle()
        assertEquals(1 to 1, shown to hidden)
        rule.runOnUiThread { lifecycle.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        assertEquals(2 to 1, shown to hidden)
    }

    @Test fun pages_theFormSurvivesARecreation() {
        val tester = StateRestorationTester(rule)
        pages(phoneFirst = true, tester = tester)
        ok()
        rule.onNodeWithText("form").assertExists()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("form").assertExists()
        rule.onNodeWithTag(TAG_PHONE_QR).assertDoesNotExist()
    }
}
