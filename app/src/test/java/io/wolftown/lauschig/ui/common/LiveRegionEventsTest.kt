package io.wolftown.lauschig.ui.common

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.profiles.GatePrompt
import io.wolftown.lauschig.ui.profiles.PinNotice
import io.wolftown.lauschig.ui.profiles.PinPromptBody
import io.wolftown.lauschig.ui.profiles.ProfileRow
import io.wolftown.lauschig.ui.setup.SetupError
import io.wolftown.lauschig.ui.setup.SetupScreen
import io.wolftown.lauschig.ui.setup.SetupUiState
import io.wolftown.lauschig.ui.welcome.WelcomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The accessibility events themselves, as Compose sends them with a screen reader on: why [AnnouncedLine] looks the way it
 * does. Only a node that was in the accessibility tree and changes gets a content event with its new words. A node that
 * appears, or that had no size, gets at most a subtree change; this test does not say what TalkBack makes of that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class LiveRegionEventsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<AccessibilityEvent>()

    private inner class Host(context: Context) : FrameLayout(context) {
        override fun requestSendAccessibilityEvent(child: View, event: AccessibilityEvent): Boolean {
            events += AccessibilityEvent(event)
            return true
        }
    }

    @Before fun aScreenReaderIsOn() {
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        for (m in listOf(manager, rule.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)) {
            val shadow = shadowOf(m)
            shadow.setEnabled(true)
            shadow.setTouchExplorationEnabled(true)
            shadow.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        }
    }

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread {
            val host = Host(rule.activity)
            host.addView(ComposeView(rule.activity).apply { setContent(content) })
            rule.activity.setContentView(host)
        }
        rule.waitForIdle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun sourceOf(event: AccessibilityEvent): Int {
        val id = ReflectionHelpers.getField<Long>(event, "mSourceNodeId")
        return (id shr 32).toInt()
    }

    /** The event that says the words of this node changed: a content change with it as the source. */
    private fun spokenFrom(id: Int) = events.filter {
        it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && sourceOf(it) == id &&
            it.contentChangeTypes and (AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION or AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) != 0
    }

    @Test fun aLineThatWasThere_sendsTheEvent_whenItGetsItsWords() {
        var text by mutableStateOf<String?>(null)
        show {
            Column {
                Text("above")
                AnnouncedLine(text, tag = "line")
                Text("below")
            }
        }
        settle()
        val id = rule.onNodeWithTag("line").fetchSemanticsNode().id
        events.clear()

        text = "Wrong"
        rule.waitForIdle()
        settle()
        assertTrue("events: $events", spokenFrom(id).isNotEmpty())
    }

    @Test fun aLiveRegionThatAppears_getsNoContentEvent() {
        var text by mutableStateOf<String?>(null)
        show {
            Column {
                Text("above")
                text?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("new")) }
            }
        }
        settle()
        events.clear()

        text = "Wrong"
        rule.waitForIdle()
        settle()
        val id = rule.onNodeWithTag("new").fetchSemanticsNode().id
        assertEquals("events: $events", 0, spokenFrom(id).size)
    }

    @Test fun aNewWizardPage_isSentFromTheSameNode() {
        var page by mutableStateOf(0)
        show { LauschigTheme { WelcomeScreen(page, onNext = {}, onSkip = {}) } }
        settle()
        val id = rule.onNodeWithTag("welcomePage").fetchSemanticsNode().id
        events.clear()

        page = 1
        rule.waitForIdle()
        settle()
        assertTrue("events: $events", spokenFrom(id).isNotEmpty())
    }

    @Test fun theFirstWrongPassword_isSent() {
        var state by mutableStateOf(SetupUiState(addProfile = true))
        show { LauschigTheme { SetupScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        settle()
        val id = rule.onNodeWithTag("setupError").fetchSemanticsNode().id
        events.clear()

        state = state.copy(error = SetupError.WRONG_LOGIN)
        rule.waitForIdle()
        settle()
        assertTrue("events: $events", spokenFrom(id).isNotEmpty())
    }

    /** Task 3.5: the wrong entries, the start of the wait and its end are all read out, from the same line. */
    @Test fun whatThePinPromptSays_isSentFromTheSameNode() {
        val row = ProfileRow("aaaaaaaaaaaaaaaa", "kind", null, active = false, needsReauth = false)
        var prompt by mutableStateOf(GatePrompt.Pin(row))
        show { LauschigTheme { PinPromptBody(prompt, "", {}, FocusRequester(), {}, {}) } }
        settle()
        val id = rule.onNodeWithTag("pinNotice").fetchSemanticsNode().id
        for (next in listOf(PinNotice.Wrong(4), PinNotice.Wrong(3), PinNotice.Wait(30_000), PinNotice.Ready)) {
            events.clear()
            prompt = prompt.copy(notice = next)
            rule.waitForIdle()
            settle()
            assertTrue("$next, events: $events", spokenFrom(id).isNotEmpty())
        }
    }

    /** The countdown changes every second, for the eye: it must not talk. */
    @Test fun theCountdown_saysNothingByItself() {
        val row = ProfileRow("aaaaaaaaaaaaaaaa", "kind", null, active = false, needsReauth = false)
        var prompt by mutableStateOf(GatePrompt.Pin(row, notice = PinNotice.Wait(30_000), waitMs = 30_000))
        show { LauschigTheme { PinPromptBody(prompt, "", {}, FocusRequester(), {}, {}) } }
        settle()
        val id = rule.onNodeWithTag("pinNotice").fetchSemanticsNode().id
        events.clear()
        prompt = prompt.copy(waitMs = 29_000)
        rule.waitForIdle()
        settle()
        assertEquals("events: $events", 0, spokenFrom(id).size)
        assertEquals(null, rule.onNodeWithTag("pinCountdown").accessibilityInfo()?.liveRegion?.takeIf { it != View.ACCESSIBILITY_LIVE_REGION_NONE })
    }
}
