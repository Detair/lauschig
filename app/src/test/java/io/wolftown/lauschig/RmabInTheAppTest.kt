package io.wolftown.lauschig

import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import io.wolftown.lauschig.connection.ConnectionModule
import io.wolftown.lauschig.db.DatabaseModule
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.net.AccountBindingsModule
import io.wolftown.lauschig.playback.PlaybackModule
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The build switch `lauschig.rmab` as the real app is wired: from MainActivity through the navigation into the settings,
 * with the objects Hilt hands out. A normal build has no ReadMeABook section and names it nowhere on the page; a build
 * with the switch has the section. (What the switch does in detail: rmab/RmabSwitchTest and rmab/RmabSwitchUiTest.)
 */
@HiltAndroidTest
@UninstallModules(AccountBindingsModule::class, ConnectionModule::class, DatabaseModule::class, PlaybackModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w400dp-h2400dp")
class RmabInTheAppTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var db: LauschigDatabase
    @Inject lateinit var playerScope: ColdStartPlayerScope

    @Before fun inject() {
        hilt.inject()
        // The settings page looks at scheduled work; the test application has no WorkManager of its own.
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    }

    @After fun close() {
        // As in ColdStartTest: the player's coroutines finish on the main looper before the database closes.
        val job = playerScope.scope.coroutineContext.job
        job.cancel()
        val deadline = System.nanoTime() + 5_000_000_000
        while (!job.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(job.isCompleted)
        db.close()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theSettings_haveTheSection_onlyInABuildWithTheSwitch() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("openSettings").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("openSettings").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("settingsScreen").fetchSemanticsNodes().isNotEmpty() }
            // The section sits below "Serien": once that switch is there, the page has been laid out down to it.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("missingVolumesSwitch").fetchSemanticsNodes().isNotEmpty() }
            if (BuildConfig.RMAB) {
                compose.onAllNodesWithTag("rmabConnect").assertCountEquals(1)
            } else {
                compose.onAllNodesWithTag("rmabConnect").assertCountEquals(0)
                compose.onAllNodesWithText("ReadMeABook", substring = true, ignoreCase = true).assertCountEquals(0)
                compose.onAllNodesWithText("RMAB", substring = true, ignoreCase = true).assertCountEquals(0)
            }
        }
    }
}
