package io.wolftown.lauschig.auto

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.playback.ServiceTitleStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryCallbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val own = context.packageName
    private val s = "0123456789abcdef"
    private val db = Room.inMemoryDatabaseBuilder(context, LauschigDatabase::class.java).allowMainThreadQueries().build()
    private val library = CarLibrary(db.itemDao(), db.libraryDao(), db.progressDao(), db.episodeDao(), db.downloadDao(), db.catalogDao(), Catalog(), TestCarTexts)
    private val resolved = mutableListOf<String>()
    private val started = mutableListOf<CarNode.Title>()
    private val fileItems = listOf(MediaItem.fromUri("file:///d/0.m4b"))
    private val starts = CarStarts(
        resolveTitle = { _, key, _ -> resolved += key; ServiceTitleStarter.Resolved.Ready(fileItems, 0, 42_000, streaming = false) },
        searchTitle = { _, _ -> null },
        lastTitle = { null },
        accountServer = { s },
    )
    private val callback = LibraryCallback(
        context = context,
        scope = CoroutineScope(Dispatchers.Unconfined),
        library = library,
        search = CarSearch(library),
        starts = starts,
        mediaItems = CarMediaItems("$own.covers"),
        carContext = { CarContext(s, online = true) },
        lastTitle = { null },
        buttons = { emptyList() },
        onCommand = { true },
        onStart = { started += it.title },
        onPlayLoaded = {},
        onSubscribed = {},
    )
    private val player = ExoPlayer.Builder(context).build()
    private val session: MediaLibrarySession = MediaLibrarySession.Builder(context, player, callback).setId("test-${System.nanoTime()}").build()

    @After fun tearDown() {
        session.release()
        player.release()
        db.close()
    }

    private fun controller(pkg: String, trusted: Boolean = false) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(pkg, 1, 1, 0, 0, trusted, Bundle.EMPTY, true)

    private val app get() = controller(own, trusted = true)
    private val car get() = controller("com.google.android.projection.gearhead")
    private val stranger get() = controller("com.example.tasker")
    private fun byId(key: String) = MediaItem.Builder().setMediaId("title/$s/$key").build()

    private fun failed(f: java.util.concurrent.Future<*>) = try { f.get(5, TimeUnit.SECONDS); false } catch (e: ExecutionException) { true }

    @Test fun theAppsOwnQueue_passesThroughUnchanged() {
        val r = callback.onSetMediaItems(session, app, fileItems, 0, 5_000).get(5, TimeUnit.SECONDS)
        assertEquals(fileItems, r.mediaItems)
        assertEquals(0, r.startIndex)
        assertEquals(5_000L, r.startPositionMs)
        assertTrue(resolved.isEmpty())
        // Its in-place replacements (download finished) too.
        assertEquals(fileItems, callback.onAddMediaItems(session, app, fileItems).get(5, TimeUnit.SECONDS))
    }

    @Test fun aCarStartsTitlesById_resolvedToTheFileQueue() {
        val r = callback.onSetMediaItems(session, car, listOf(byId("b")), -1, -9).get(5, TimeUnit.SECONDS)
        assertEquals(fileItems, r.mediaItems)
        assertEquals(42_000L, r.startPositionMs)
        assertEquals(listOf("b"), resolved)
        assertEquals(listOf(CarNode.Title(s, "b")), started)
    }

    @Test fun nobodyElseMayHandThePlayerUris_orAddToItsQueue() {
        assertTrue(failed(callback.onSetMediaItems(session, car, fileItems, 0, 0)))
        assertTrue(failed(callback.onAddMediaItems(session, car, fileItems)))
        assertTrue(failed(callback.onAddMediaItems(session, app, listOf(byId("b")))))
        assertTrue(resolved.isEmpty())
    }

    @Test fun unknownApps_mayOnlyControlPlayback() {
        assertTrue(failed(callback.onSetMediaItems(session, stranger, listOf(byId("b")), 0, 0)))
        assertTrue(resolved.isEmpty())
        val r = callback.onConnectAsync(session, stranger).get(5, TimeUnit.SECONDS)
        assertTrue(r.isAccepted)
        assertFalse(r.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
        assertFalse(r.availableSessionCommands.contains(SessionCommand(CarButtons.SLEEP, Bundle.EMPTY)))
        assertFalse(r.availablePlayerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(r.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
    }

    @Test fun carsGetTheLibraryAndTheCarButtons() {
        val r = callback.onConnectAsync(session, car).get(5, TimeUnit.SECONDS)
        assertTrue(r.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
        CarButtons.ACTIONS.forEach { assertTrue(it, r.availableSessionCommands.contains(SessionCommand(it, Bundle.EMPTY))) }
        assertTrue(r.availablePlayerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
    }

    @Test fun playbackResumption_saysThatAnAnswerIsComing_beforeTheAnswer_onlyForPlayback() {
        val events = mutableListOf<String>()
        val c = LibraryCallback(
            context = context, scope = CoroutineScope(Dispatchers.Unconfined), library = library, search = CarSearch(library), starts = starts,
            mediaItems = CarMediaItems("$own.covers"), carContext = { CarContext(s, online = true) }, lastTitle = { null },
            buttons = { emptyList() }, onCommand = { true }, onStart = {}, onPlayLoaded = {}, onSubscribed = {},
            onResumption = { _, _ -> events += "answer" },
            onResumptionAsked = { events += "asked" },
        )
        assertTrue(failed(c.onPlaybackResumption(session, app, false))) // SystemUI's card: no title to play, nobody waits
        assertEquals(emptyList<String>(), events)
        assertTrue(failed(c.onPlaybackResumption(session, app, true)))
        assertEquals(listOf("asked", "answer"), events)
    }

    @Test fun playbackResumption_reportsSuccessAndFailure() {
        val answers = mutableListOf<Pair<Boolean, String?>>()
        fun callbackWith(last: CarNode.Title?) = LibraryCallback(
            context = context, scope = CoroutineScope(Dispatchers.Unconfined), library = library, search = CarSearch(library),
            starts = CarStarts(
                resolveTitle = { _, _, _ -> ServiceTitleStarter.Resolved.Ready(fileItems, 0, 0, streaming = false) },
                searchTitle = { _, _ -> null }, lastTitle = { last }, accountServer = { s },
            ),
            mediaItems = CarMediaItems("$own.covers"), carContext = { CarContext(s, online = true) }, lastTitle = { last },
            buttons = { emptyList() }, onCommand = { true }, onStart = {}, onPlayLoaded = {}, onSubscribed = {},
            onResumption = { ok, message -> answers += ok to message },
        )
        callbackWith(CarNode.Title(s, "b")).onPlaybackResumption(session, app, true).get(5, TimeUnit.SECONDS)
        assertEquals(listOf(true to null), answers)
        // Nothing to resume: a failure with the message the play key's toast shows.
        assertTrue(failed(callbackWith(null).onPlaybackResumption(session, app, true)))
        assertEquals(false to context.getString(io.wolftown.lauschig.R.string.car_error_nothing_to_resume), answers[1])
    }

    @Test fun signedOut_theCarSeesOnlyTheSignInHint_andNothingStarts() {
        val answers = mutableListOf<Pair<Boolean, String?>>()
        val device = CarNode.Title(io.wolftown.lauschig.local.DeviceLibrary.SERVER_ID, "local:aaaaaaaaaaaaaaaaaaaaaaaa")
        val signedOut = LibraryCallback(
            context = context, scope = CoroutineScope(Dispatchers.Unconfined), library = library, search = CarSearch(library),
            starts = CarStarts(
                resolveTitle = { _, key, _ -> resolved += key; ServiceTitleStarter.Resolved.Ready(fileItems, 0, 0, streaming = false) },
                searchTitle = { _, _ -> device }, lastTitle = { device }, accountServer = { null },
            ),
            mediaItems = CarMediaItems("$own.covers"), carContext = { CarContext(null, online = true) }, lastTitle = { null },
            buttons = { emptyList() }, onCommand = { true }, onStart = { started += it.title }, onPlayLoaded = {}, onSubscribed = {},
            onResumption = { ok, message -> answers += ok to message },
        )
        val root = signedOut.onGetChildren(session, car, "root", 0, 100, null).get(5, TimeUnit.SECONDS).value!!
        assertEquals(listOf("signin"), root.map { it.mediaId })
        assertEquals(TestCarTexts.signIn, root.single().mediaMetadata.title.toString())
        assertEquals(false, root.single().mediaMetadata.isPlayable)
        assertEquals(false, root.single().mediaMetadata.isBrowsable)
        // Voice search, a cached id and a play key: nothing starts.
        assertTrue(failed(signedOut.onSetMediaItems(session, car, listOf(MediaItem.Builder().setMediaId(MediaIds.of(device)).build()), 0, 0)))
        assertTrue(failed(signedOut.onPlaybackResumption(session, app, true)))
        assertEquals(false to context.getString(io.wolftown.lauschig.R.string.car_sign_in), answers.single())
        assertTrue(resolved.isEmpty())
        assertTrue(started.isEmpty())
    }
}
