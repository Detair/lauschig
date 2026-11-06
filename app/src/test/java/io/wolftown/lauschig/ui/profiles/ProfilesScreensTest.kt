package io.wolftown.lauschig.ui.profiles

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.accessibilityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class ProfilesScreensTest {
    @get:Rule val rule = createComposeRule()
    private val active = ProfileRow("aaaaaaaaaaaaaaaa", "Fabian", null, active = true, needsReauth = false)
    private val saved = ProfileRow("bbbbbbbbbbbbbbbb", "kind", null, active = false, needsReauth = false)
    private val calls = mutableListOf<String>()

    private fun showSheet(rows: List<ProfileRow>) = rule.setContent {
        LauschigTheme {
            ProfilesSheetContent(rows, onSwitch = { calls += "switch:${it.name}" }, onRemove = { calls += "remove:${it.name}" }, onAdd = { calls += "add" })
        }
    }

    @Test fun theSheet_listsTheProfiles_tapSwitches_addAtTheEnd() {
        showSheet(listOf(active, saved))
        rule.onNodeWithText("Fabian").assertExists()
        rule.onNodeWithText("kind").performClick()
        rule.onNodeWithTag("addProfile").performClick()
        assertEquals(listOf("switch:kind", "add"), calls)
    }

    @Test fun theSheet_theActiveOneIsMarked_forTheEyeAndForTalkBack() {
        showSheet(listOf(active, saved))
        rule.onNodeWithContentDescription("Active").assertExists()
        rule.onNodeWithTag("profile:Fabian").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        rule.onNodeWithTag("profile:kind").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    @Test fun theSheet_removingIsInTheMenuOfASavedProfile_notOfTheActiveOne() {
        showSheet(listOf(active, saved))
        rule.onNodeWithContentDescription("More options for Fabian").assertDoesNotExist()
        rule.onNodeWithContentDescription("More options for kind").performClick()
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("remove:kind"), calls)
    }

    @Test fun theSheet_theServerAndTheSignInHint() {
        showSheet(listOf(active.copy(host = "abs.example.org"), saved.copy(host = "nas.lan:13378", needsReauth = true)))
        rule.onNodeWithText("abs.example.org").assertExists()
        rule.onNodeWithText("nas.lan:13378").assertExists()
        rule.onNodeWithText("Sign in again").assertExists()
    }

    @Test fun theAvatar_initialsAndAStableTone() {
        assertEquals("F", profileInitials("Fabian"))
        assertEquals("AM", profileInitials("Anna Meier"))
        assertEquals("_", profileInitials("_x"))
        assertEquals("?", profileInitials("  "))
    }

    @Test fun theAvatarIsDecoration_theRowSaysTheName() {
        showSheet(listOf(active, saved))
        rule.onNodeWithText("F").assertDoesNotExist() // TalkBack does not spell the initials before the name
    }

    @Test fun theHeaderButton_nothingWithOneProfile() {
        rule.setContent { LauschigTheme { ProfileHeaderButton(listOf(active), onClick = { calls += "open" }) } }
        rule.onNodeWithTag("profileAvatar").assertDoesNotExist()
    }

    @Test fun theHeaderButton_theActiveProfile_fromTwoOn() {
        rule.setContent { LauschigTheme { ProfileHeaderButton(listOf(active, saved), onClick = { calls += "open" }) } }
        rule.onNodeWithContentDescription("Profiles, active: Fabian").performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test fun whileItRuns_theDisplaySaysToWhom() {
        rule.setContent { LauschigTheme { ProfileBusyDialog(ProfileWork.Switching("kind")) } }
        rule.onNodeWithText("Switching to kind …").assertExists()
    }

    /** The first line comes with the window, which TalkBack reads; the second appears in a window that is already there. */
    @Test fun whileItTakesLong_theDisplaySaysThat_onANodeThatWasThereBefore() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LauschigTheme { ProfileBusyDialog(ProfileWork.Switching("kind")) } }
        rule.mainClock.advanceTimeBy(1_000)
        val line = rule.onNodeWithTag("profileBusySlow")
        val before = line.fetchSemanticsNode().id
        assertEquals(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE, line.accessibilityInfo()!!.liveRegion)
        assertFalse(SemanticsProperties.ContentDescription in line.fetchSemanticsNode().config)

        rule.mainClock.advanceTimeBy(PROFILE_BUSY_SLOW_MS)
        assertEquals(before, line.fetchSemanticsNode().id)
        assertEquals(listOf("This is taking a little longer. Nearly there."), line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
    }

    @Test fun removing_hasItsOwnWords() {
        rule.setContent { LauschigTheme { ProfileBusyDialog(ProfileWork.Removing("kind")) } }
        rule.onNodeWithText("Removing kind …").assertExists()
    }

    private fun showRemoval(facts: RemovalFacts?) = rule.setContent {
        LauschigTheme { ProfileRemoveDialog(RemovalQuestion(saved, facts), onConfirm = { calls += "confirm" }, onCancel = { calls += "cancel" }) }
    }

    @Test fun theRemovalQuestion_namesProfileAndSize() {
        showRemoval(RemovalFacts(2_100_000_000, 0, false))
        rule.onNodeWithText("kind is signed out on this device.").assertExists()
        rule.onNodeWithTag("removeDownloads").assertExists()
        rule.onNodeWithTag("confirmRemove").performClick()
        assertEquals(listOf("confirm"), calls)
    }

    @Test fun theRemovalQuestion_noDownloads_noSize() {
        showRemoval(RemovalFacts(0, 0, false))
        rule.onNodeWithTag("removeDownloads").assertDoesNotExist()
    }

    @Test fun theRemovalQuestion_whatIsLost_isSaid() {
        showRemoval(RemovalFacts(0, 2, true))
        rule.onNodeWithTag("removeUnsynced").assertExists()
        rule.onNodeWithTag("removeCardMissing").assertExists()
    }

    @Test fun theRemovalQuestion_unknownChanges_areSaidToo() {
        showRemoval(RemovalFacts(0, null, false))
        rule.onNodeWithText("Whether changes are still missing on the server could not be checked.").assertExists()
    }

    @Test fun theRemovalQuestion_cannotBeAnsweredBeforeItIsKnownWhatGoes() {
        showRemoval(null)
        rule.onNodeWithTag("confirmRemove").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test fun theOutcome_namesTheProfile() {
        rule.setContent {
            LauschigTheme {
                val text = profileOutcomeText(ProfileOutcome(ProfileOutcome.Kind.SWITCHED, "kind", 1))
                androidx.compose.material3.Text(text)
            }
        }
        rule.onNodeWithText("Profile kind is active").assertExists()
        assertTrue(calls.isEmpty())
    }

    @Test fun theRowIsOneTapTargetWithAClickAction() {
        showSheet(listOf(active, saved))
        rule.onNodeWithTag("profile:kind").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
    }

    @Test fun thePicker_asks_listsTheProfiles_andOffersAnother() {
        val first = saved.copy(serverId = "cccccccccccccccc", name = "Fabian")
        rule.setContent {
            LauschigTheme {
                ProfilePickerScreen(listOf(first, saved), onSwitch = { calls += "switch:${it.name}" }, onRemove = { calls += "remove:${it.name}" }, onAdd = { calls += "add" })
            }
        }
        rule.onNodeWithText("Who's listening?").assertExists()
        rule.onNodeWithText("Fabian").performClick()
        rule.onNodeWithContentDescription("More options for kind").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithTag("addProfile").performClick()
        assertEquals(listOf("switch:Fabian", "remove:kind", "add"), calls)
    }
}
