package io.wolftown.lauschig.auto

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.PlaybackService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The media session as Android Auto sees it while the phone is SIGNED OUT: the root holds only the hint „In Lauschig auf
 * dem Handy anmelden“, every folder is empty, search finds nothing and nothing starts (a browsed id, a play key). Runs
 * only while the device is signed out (the test cannot sign in again, credentials never live in test code); signed in it
 * is skipped and [CarBrowseTest] covers the tree.
 */
@RunWith(AndroidJUnit4::class)
class CarSignedOutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var browser: MediaBrowser

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun children(id: String): List<MediaItem> {
        val r = main { browser.getChildren(id, 0, 100, null) }.get(20, TimeUnit.SECONDS)
        assertEquals("children of $id", LibraryResult.RESULT_SUCCESS, r.resultCode)
        return r.value!!
    }

    @Before fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        browser = main { MediaBrowser.Builder(context, token).buildAsync() }.get(20, TimeUnit.SECONDS)
    }

    @After fun release() {
        main { browser.release() }
    }

    @Test fun signedOut_onlyTheHint_nothingFound_nothingStarts() {
        val root = children(MediaIds.of(CarNode.Root))
        assumeTrue("signed in: covered by CarBrowseTest", root.map { it.mediaId } == listOf(MediaIds.of(CarNode.SignIn)))
        val hint = root.single().mediaMetadata
        assertEquals(context.getString(io.wolftown.lauschig.R.string.car_sign_in), hint.title.toString())
        assertFalse(hint.isPlayable == true)
        assertFalse(hint.isBrowsable == true)

        // A car that cached the tree finds every folder empty, the device titles too.
        listOf(CarNode.Continue, CarNode.Downloads, CarNode.SeriesList, CarNode.Libraries, CarNode.Library(DeviceLibrary.LIBRARY_ID))
            .forEach { assertEquals("$it", emptyList<MediaItem>(), children(MediaIds.of(it))) }

        main { browser.search("Anfang", null) }.get(20, TimeUnit.SECONDS)
        assertEquals(0, main { browser.getSearchResult("Anfang", 0, 20, null) }.get(20, TimeUnit.SECONDS).value.orEmpty().size)

        // A browsed id of a device title and a play key without a queue: nothing loads, nothing plays.
        val device = MediaItem.Builder().setMediaId(MediaIds.of(CarNode.Title(DeviceLibrary.SERVER_ID, "local:aaaaaaaaaaaaaaaaaaaaaaaa"))).build()
        main { browser.setMediaItem(device); browser.prepare(); browser.play() }
        Thread.sleep(3_000)
        assertFalse(main { browser.isPlaying })
        main { browser.clearMediaItems(); browser.play() }
        Thread.sleep(3_000)
        assertFalse(main { browser.isPlaying })
        assertEquals(0, main { browser.mediaItemCount })
    }
}
