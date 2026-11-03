package io.wolftown.lauschig.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasClickAction
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LauschigWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val title = WidgetState("0123456789abcdef", "li_1", "Die drei ???", "Kapitel 4", 3, playing = false, positionMs = 60_000, durationMs = 600_000)
    private val wide = DpSize(280.dp, 72.dp)
    private val small = DpSize(110.dp, 110.dp)

    @Test fun empty_saysNothingLoaded_andOpensTheApp() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { WidgetContent(WidgetView(WidgetState(), null, canResume = false)) }
        onNode(hasText("Nothing loaded – tap to open")).assertExists()
        onNode(hasTestTag(WidgetIntents.TAG_OPEN)).assertHasStartActivityClickAction(WidgetIntents.openApp(context, player = false))
        onNode(hasTestTag(WidgetIntents.TAG_PLAY)).assertDoesNotExist()
    }

    @Test fun wide_showsTitleChapterAndAllButtons() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { WidgetContent(WidgetView(title, null, canResume = true)) }
        onNode(hasText("Die drei ???")).assertExists()
        onNode(hasText("Kapitel 4")).assertExists()
        onNode(hasTestTag(WidgetIntents.TAG_BACK)).assertExists().assertHasClickAction()
        onNode(hasTestTag(WidgetIntents.TAG_FORWARD)).assertExists().assertHasClickAction()
        onNode(hasContentDescription("Play")).assertExists()
        onNode(hasTestTag(WidgetIntents.TAG_PLAY)).assertHasClickAction()
        onNode(hasContentDescription("Back 30 seconds")).assertExists()
        onNode(hasContentDescription("Forward 30 seconds")).assertExists()
        onNode(hasContentDescription("Open Die drei ??? in Lauschig")).assertExists()
    }

    @Test fun playing_showsPause() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { WidgetContent(WidgetView(title.copy(playing = true), null, canResume = true)) }
        onNode(hasContentDescription("Pause")).assertExists()
        onNode(hasContentDescription("Play")).assertDoesNotExist()
    }

    @Test fun small_showsCoverAndPlayPauseOnly() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(small)
        provideComposable { WidgetContent(WidgetView(title, null, canResume = true)) }
        onNode(hasTestTag(WidgetIntents.TAG_PLAY)).assertExists()
        onNode(hasTestTag(WidgetIntents.TAG_BACK)).assertDoesNotExist()
        onNode(hasText("Die drei ???")).assertDoesNotExist()
    }

    @Test fun nothingToResume_playOpensTheApp() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { WidgetContent(WidgetView(title, null, canResume = false)) }
        onNode(hasTestTag(WidgetIntents.TAG_PLAY)).assertHasStartActivityClickAction(WidgetIntents.openApp(context, player = false))
    }

    @Test fun mediaButtonIntents_targetTheServiceWithTheirOwnData() {
        val play = WidgetIntents.mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        val back = WidgetIntents.mediaButton(context, KeyEvent.KEYCODE_MEDIA_REWIND)
        assertEquals(Intent.ACTION_MEDIA_BUTTON, play.action)
        assertEquals(ComponentName(context, PlaybackService::class.java), play.component)
        @Suppress("DEPRECATION")
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, (play.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT))!!.keyCode)
        // Same action and component: only the data keeps their PendingIntents apart.
        assertNotEquals(play.data, back.data)
    }

    @Test fun openPlayer_carriesTheOpenExtra() {
        assertEquals(MainActivity.OPEN_PLAYER, WidgetIntents.openApp(context, player = true).getStringExtra(MainActivity.EXTRA_OPEN))
        assertEquals(null, WidgetIntents.openApp(context, player = false).getStringExtra(MainActivity.EXTRA_OPEN))
    }
}
