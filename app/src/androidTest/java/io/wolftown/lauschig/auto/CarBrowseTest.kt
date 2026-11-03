package io.wolftown.lauschig.auto

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.wolftown.lauschig.playback.MediaItemFactory
import io.wolftown.lauschig.playback.PlaybackService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The media session as Android Auto, SystemUI and the Assistant see it, against the logged-in test server of the
 * emulator (a library with the 3-volume series "Testreihe"). Both client stacks: a Media3 [MediaBrowser] and the
 * legacy [MediaBrowserCompat] that Android Auto uses (the MediaBrowserService path, like SystemUI). Plays for a few
 * seconds and pauses again; the title's speed is restored.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CarBrowseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val component = ComponentName(context, PlaybackService::class.java)
    private lateinit var browser: MediaBrowser

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Polls [check] (on the main thread) until it returns non-null. */
    private fun <T : Any> eventually(timeoutMs: Long = 20_000, check: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            main(check)?.let { return it }
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met within $timeoutMs ms")
            Thread.sleep(250)
        }
    }

    private fun children(id: String, page: Int = 0, pageSize: Int = 500): List<MediaItem> {
        val r = main { browser.getChildren(id, page, pageSize, null) }.get(20, TimeUnit.SECONDS)
        assertEquals("children of $id", LibraryResult.RESULT_SUCCESS, r.resultCode)
        return r.value!!
    }

    @Before fun connect() {
        val token = SessionToken(context, component)
        val future = main { MediaBrowser.Builder(context, token).buildAsync() }
        browser = future.get(20, TimeUnit.SECONDS)
    }

    @After fun release() {
        main { browser.pause(); browser.release() }
    }

    /** The 3 volumes of "Testreihe", once the service sees the server (before that, lists are offline-filtered). */
    private fun volumes(): List<MediaItem> {
        val end = System.currentTimeMillis() + 20_000
        while (true) {
            val r = main { browser.getChildren(MediaIds.of(CarNode.Series("Testreihe")), 0, 50, null) }.get(20, TimeUnit.SECONDS)
            val list = r.value.orEmpty()
            if (r.resultCode == LibraryResult.RESULT_SUCCESS && list.size == 3) return list
            if (System.currentTimeMillis() > end) throw AssertionError("Testreihe has ${list.size} volumes (result ${r.resultCode})")
            Thread.sleep(500)
        }
    }

    @Test fun a_media3Browser_rootAndEveryRootNode() {
        val root = main { browser.getLibraryRoot(null) }.get(20, TimeUnit.SECONDS)
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        assertEquals("root", root.value!!.mediaId)
        assertTrue(root.params!!.extras.getBoolean(CarMediaItems.CONTENT_STYLE_SUPPORTED))

        val top = children("root")
        assertEquals(listOf("continue", "downloads", "series", "libraries"), top.map { it.mediaId })
        assertTrue(top.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        top.forEach { children(it.mediaId) } // every node answers

        val libraries = children("libraries")
        assertTrue("libraries", libraries.isNotEmpty())
        val firstLibrary = children(libraries.first().mediaId)
        assertTrue("titles of ${libraries.first().mediaId}", firstLibrary.isNotEmpty())
        // Paging: the first page of one item is the first item.
        assertEquals(firstLibrary.first().mediaId, children(libraries.first().mediaId, 0, 1).single().mediaId)

        val series = children("series")
        assertTrue(series.any { it.mediaMetadata.title == "Testreihe" })
        val v = volumes()
        assertTrue(v.all { it.mediaMetadata.isPlayable == true && it.mediaId.startsWith("title/") })
        assertTrue(v.all { it.mediaMetadata.artworkUri.toString().startsWith("content://${context.packageName}.covers/cover/") })

        // Covers through the provider (this app needs no grant): an image, or "not found" for a title without one
        // (the test server's titles have none); never another error. Anything that is not a cover URI is not found.
        v.forEach { item ->
            try {
                context.contentResolver.openInputStream(item.mediaMetadata.artworkUri!!)?.use { assertTrue(it.read() >= 0) }
            } catch (e: FileNotFoundException) {
                // no cover on the server
            }
        }
        val authority = "${context.packageName}.covers"
        listOf("content://$authority/cover/../x/1", "content://$authority/cover/0123456789abcdef/a/b/1").forEach { bad ->
            try {
                context.contentResolver.openInputStream(Uri.parse(bad))
                throw AssertionError("opened $bad")
            } catch (e: FileNotFoundException) {
                // expected
            }
        }

        val item = main { browser.getItem(v[1].mediaId) }.get(20, TimeUnit.SECONDS)
        assertEquals(v[1].mediaMetadata.title, item.value!!.mediaMetadata.title)
    }

    @Test fun b_media3Browser_searchAndPlayFromTheTree() {
        volumes() // waits until the server is online for the service
        val searched = main { browser.search("Testreihe", null) }.get(20, TimeUnit.SECONDS)
        assertEquals(LibraryResult.RESULT_SUCCESS, searched.resultCode)
        val hits = main { browser.getSearchResult("Testreihe", 0, 20, null) }.get(20, TimeUnit.SECONDS).value!!
        assertEquals(MediaIds.of(CarNode.Series("Testreihe")), hits.first().mediaId)
        assertTrue(hits.drop(1).all { it.mediaId.startsWith("title/") })

        val volume = volumes()[1]
        val key = (MediaIds.parse(volume.mediaId) as CarNode.Title).key
        val speedBefore = main {
            browser.setMediaItem(volume)
            browser.prepare()
            browser.play()
            browser.playbackParameters.speed
        }
        // The session resolved the browsed id into the title's file queue.
        eventually { browser.currentMediaItem?.mediaMetadata?.extras?.getString(MediaItemFactory.EXTRA_ITEM_ID)?.takeIf { it == key } }
        eventually { browser.isPlaying.takeIf { it } }
        assertTrue(main { browser.mediaItemCount } >= 1)

        // Car buttons: +30 s (from a known spot: repeated runs move the short test titles towards their end).
        main { browser.seekTo(0, 10_000) }
        eventually { browser.currentPosition.takeIf { it in 9_000..20_000 } }
        val before = main { browser.currentPosition }
        main { browser.sendCustomCommand(SessionCommand(CarButtons.SEEK_FORWARD, Bundle.EMPTY), Bundle.EMPTY) }.get(10, TimeUnit.SECONDS)
        eventually { browser.currentPosition.takeIf { it - before >= 25_000 } ?: browser.currentMediaItemIndex.takeIf { it > 0 }?.toLong() }
        main { browser.sendCustomCommand(SessionCommand(CarButtons.SEEK_BACK, Bundle.EMPTY), Bundle.EMPTY) }.get(10, TimeUnit.SECONDS)

        // Speed button cycles; restore the title's own speed afterwards.
        main { browser.sendCustomCommand(SessionCommand(CarButtons.SPEED, Bundle.EMPTY), Bundle.EMPTY) }.get(10, TimeUnit.SECONDS)
        eventually { browser.playbackParameters.speed.takeIf { it == CarControls.nextSpeed(speedBefore) } }
        main { browser.setPlaybackSpeed(speedBefore) }
        main { browser.pause() }
    }

    private fun compatBrowser(hints: Bundle?): MediaBrowserCompat {
        val latch = CountDownLatch(1)
        var ok = false
        lateinit var b: MediaBrowserCompat
        main {
            b = MediaBrowserCompat(context, component, object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() { ok = true; latch.countDown() }
                override fun onConnectionFailed() { latch.countDown() }
            }, hints)
            b.connect()
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        assertTrue("legacy browser connected", ok)
        return b
    }

    private fun MediaBrowserCompat.load(id: String): List<MediaBrowserCompat.MediaItem> {
        val latch = CountDownLatch(1)
        var result: List<MediaBrowserCompat.MediaItem>? = null
        main {
            subscribe(id, object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                    if (result == null) { result = children.toList(); latch.countDown() }
                }
                override fun onError(parentId: String) { latch.countDown() }
            })
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        main { unsubscribe(id) }
        assertNotNull("children of $id", result)
        return result!!
    }

    private fun MediaControllerCompat.sleepLabel() = playbackState?.customActions?.firstOrNull { it.action == CarButtons.SLEEP }?.name?.toString()

    @Test fun c_legacyBrowser_likeAndroidAuto_browseSearchPlayAndButtons() {
        volumes()
        val b = compatBrowser(Bundle())
        try {
            assertEquals("root", main { b.root })
            assertTrue(main { b.extras }!!.getBoolean(CarMediaItems.CONTENT_STYLE_SUPPORTED))
            val top = b.load("root")
            assertEquals(listOf("continue", "downloads", "series", "libraries"), top.map { it.mediaId })
            assertTrue(top.all { it.isBrowsable })
            top.forEach { b.load(it.mediaId!!) }
            val volumes = b.load(MediaIds.of(CarNode.Series("Testreihe")))
            assertEquals(3, volumes.size)
            assertTrue(volumes.all { it.isPlayable })
            assertTrue(volumes.all { it.description.iconUri.toString().startsWith("content://") })

            val searchLatch = CountDownLatch(1)
            var found: List<MediaBrowserCompat.MediaItem> = emptyList()
            main {
                b.search("Testreihe", Bundle(), object : MediaBrowserCompat.SearchCallback() {
                    override fun onSearchResult(query: String, extras: Bundle?, items: MutableList<MediaBrowserCompat.MediaItem>) { found = items.toList(); searchLatch.countDown() }
                    override fun onError(query: String, extras: Bundle?) { searchLatch.countDown() }
                })
            }
            assertTrue(searchLatch.await(20, TimeUnit.SECONDS))
            assertTrue("search hits: ${found.map { it.mediaId }}", found.size >= 4)

            val controller = main { MediaControllerCompat(context, b.sessionToken) }
            // What AA does on a tap: play from media id.
            main { controller.transportControls.playFromMediaId(volumes[0].mediaId, Bundle()) }
            val title0 = volumes[0].description.title.toString()
            eventually { controller.metadata?.getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)?.toString()?.takeIf { it == title0 } }
            eventually { controller.playbackState?.state?.takeIf { it == PlaybackStateCompat.STATE_PLAYING } }

            // Car-safe buttons: ±30 s in place of previous/next, plus speed and sleep timer.
            val state = main { controller.playbackState }!!
            val actions = state.customActions.map { it.action }
            assertTrue(actions.toString(), actions.containsAll(listOf(CarButtons.SEEK_BACK, CarButtons.SEEK_FORWARD, CarButtons.SPEED, CarButtons.SLEEP)))
            assertEquals(0L, state.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)

            // Sleep timer button: on (15 min) shows in its label, four more taps turn it off again.
            main { controller.transportControls.sendCustomAction(CarButtons.SLEEP, Bundle()) }
            eventually { controller.sleepLabel()?.takeIf { "15" in it } }
            repeat(4) {
                main { controller.transportControls.sendCustomAction(CarButtons.SLEEP, Bundle()) }
                Thread.sleep(400)
            }
            eventually { controller.sleepLabel()?.takeIf { !it.contains(":") } }

            // Voice: "Spiel Testreihe in Lauschig" → a volume of the series (from a paused player, so the start is seen).
            main { controller.transportControls.pause() }
            eventually { controller.playbackState?.state?.takeIf { it == PlaybackStateCompat.STATE_PAUSED } }
            main { controller.transportControls.playFromSearch("Testreihe", Bundle()) }
            val titles = volumes.map { it.description.title.toString() }.toSet()
            eventually { controller.metadata?.getText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)?.toString()?.takeIf { it in titles } }
            eventually { controller.playbackState?.state?.takeIf { it == PlaybackStateCompat.STATE_PLAYING } }
            main { controller.transportControls.pause() }
            eventually { controller.playbackState?.state?.takeIf { it == PlaybackStateCompat.STATE_PAUSED } }
        } finally {
            main { b.disconnect() }
        }
    }

    @Test fun d_recentRoot_offersTheLastTitle() {
        val b = compatBrowser(Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true) })
        try {
            assertEquals("recent", main { b.root })
            val recent = b.load("recent")
            assertEquals(1, recent.size)
            assertTrue(recent.single().isPlayable)
            assertFalse(recent.single().isBrowsable)
        } finally {
            main { b.disconnect() }
        }
    }

    @Test fun e_playWithoutAQueue_resumesTheLastTitle() {
        // The last title on this device: a volume played here and paused (not finished).
        val volume = volumes()[1]
        val key = (MediaIds.parse(volume.mediaId) as CarNode.Title).key
        main { browser.setMediaItem(volume); browser.prepare(); browser.play() }
        eventually { browser.currentMediaItem?.mediaMetadata?.extras?.getString(MediaItemFactory.EXTRA_ITEM_ID)?.takeIf { it == key } }
        main { browser.seekTo(0, 20_000) }
        eventually { browser.isPlaying.takeIf { it } }
        Thread.sleep(1_000)
        main { browser.pause() }
        eventually { (!browser.isPlaying).takeIf { it } }
        Thread.sleep(500) // the pause reaches LastPlayedStore and the progress row

        // What a Bluetooth/car "play" or SystemUI's resumption card does when the player holds nothing.
        main { browser.clearMediaItems() }
        eventually { browser.mediaItemCount.takeIf { it == 0 }?.let { true } }
        main { browser.play() }
        eventually { browser.currentMediaItem?.mediaMetadata?.extras?.getString(MediaItemFactory.EXTRA_ITEM_ID)?.takeIf { it == key } }
        eventually { browser.isPlaying.takeIf { it } }
        // At the saved position (about 21 s, minus the short-pause rewind), not at the start.
        assertTrue(main { browser.currentPosition } > 5_000)
        main { browser.pause() }
    }
}
