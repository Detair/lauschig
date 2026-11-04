package io.wolftown.lauschig.ui.theme

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RememberCoverSwatchTest {
    @get:Rule val rule = createComposeRule()

    /** Answers each model when the test completes its deferred; counts the loads. */
    private class FakeLoader : CoverSwatchLoader {
        val answers = mutableMapOf<Any, CompletableDeferred<Color?>>()
        val loads = mutableListOf<Any>()
        fun answer(model: Any, color: Color?) = answers.getOrPut(model) { CompletableDeferred() }.complete(color)
        override suspend fun load(context: Context, model: Any): Color? {
            loads += model
            return answers.getOrPut(model) { CompletableDeferred() }.await()
        }
    }

    // Unique per test run: the swatch memory is app-wide.
    private val run = System.nanoTime()
    private val a = "test://a-$run"
    private val b = "test://b-$run"
    private val broken = "test://broken-$run"

    @Test fun nullWhileLoading_thenTheSwatch_thePreviousStaysWhileTheNextLoads() {
        val loader = FakeLoader()
        var model by mutableStateOf<Any?>(a)
        var shown: Color? = Color.Magenta
        rule.setContent { CompositionLocalProvider(LocalCoverSwatchLoader provides loader) { shown = rememberCoverSwatch(model) } }
        rule.waitForIdle()
        assertNull("no colour before the first swatch is known", shown)

        loader.answer(a, Color.Red)
        rule.waitForIdle()
        assertEquals(Color.Red, shown)

        model = b
        rule.waitForIdle()
        assertEquals("the previous title's tint stays while the next cover loads", Color.Red, shown)
        loader.answer(b, Color.Blue)
        rule.waitForIdle()
        assertEquals(Color.Blue, shown)

        model = null
        rule.waitForIdle()
        assertNull("no cover: no tint", shown)

        model = a
        rule.runOnIdle { } // the remembered swatch is there in the first frame, no load
        assertEquals(Color.Red, shown)
        assertEquals(listOf<Any>(a, b), loader.loads)
    }

    @Test fun aFailedCover_isRememberedAsNoSwatch_insteadOfLoadedAgain() {
        val loader = FakeLoader()
        var show by mutableStateOf(true)
        var shown: Color? = Color.Magenta
        rule.setContent {
            CompositionLocalProvider(LocalCoverSwatchLoader provides loader) { if (show) shown = rememberCoverSwatch(broken) }
        }
        loader.answer(broken, null)
        rule.waitForIdle()
        assertNull(shown)
        // Leaves and comes back (a row scrolled away and back): not requested again.
        show = false
        rule.waitForIdle()
        show = true
        rule.waitForIdle()
        assertNull(shown)
        assertEquals(1, loader.loads.size)
    }

    @Test fun anUnspecifiedSwatch_meansNoTint() {
        val loader = FakeLoader()
        val plain = "test://plain-$run"
        var shown: Color? = Color.Magenta
        rule.setContent { CompositionLocalProvider(LocalCoverSwatchLoader provides loader) { shown = rememberCoverSwatch(plain) } }
        loader.answer(plain, Color.Unspecified)
        rule.waitForIdle()
        assertNull(shown)
    }
}
