package io.wolftown.lauschig.auto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.wolftown.lauschig.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The media session's library callback: Android Auto, Assistant and SystemUI browse, search and start titles here.
 * Glue only; the rules live in [CarLibrary], [CarSearch], [CarStarts] and [CarAccess]. Everything reads Room, nothing
 * here waits for the network (a streamed start waits at most for the connection check, like the app). Only
 * [onConnectAsync] and [onCustomCommand] answer on the calling (main) thread, synchronously.
 */
@OptIn(UnstableApi::class)
class LibraryCallback(
    private val context: Context,
    /**
     * Where requests run and their futures complete: NOT the main thread. A legacy browser's connect (Android Auto,
     * Assistant) blocks the main thread until [onGetLibraryRoot] completes, so a future that needs the main thread
     * deadlocks the whole app. Room, DataStore and the session's notify/error calls are all safe off the main thread.
     */
    private val scope: CoroutineScope,
    private val library: CarLibrary,
    private val search: CarSearch,
    private val starts: CarStarts,
    private val mediaItems: CarMediaItems,
    private val carContext: suspend () -> CarContext,
    private val lastTitle: suspend () -> CarNode.Title?,
    /** The media buttons right now (speed, sleep timer and chapters change them). */
    private val buttons: () -> List<CommandButton>,
    /** Runs a car button; false if it did nothing. Main thread. */
    private val onCommand: (action: String) -> Boolean,
    /** A title is about to start from the car, voice or resumption (download on play for streams). */
    private val onStart: (CarStarts.Outcome.Ready) -> Unit,
    /** The requested title is already loaded: play on where it is (main thread is posted to by the caller). */
    private val onPlayLoaded: () -> Unit,
    /** A browser subscribed to a folder; the service notifies it when the library changes. */
    private val onSubscribed: (parentId: String) -> Unit,
) : MediaLibrarySession.Callback {
    private val own = context.packageName
    private val coverAuthority = CoverUris.authority(own)

    private fun level(controller: MediaSession.ControllerInfo) = CarAccess.levelFor(controller.packageName, own, controller.isTrusted)

    override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
        if (level(controller) == CarAccess.Level.CONTROL) {
            // Play, pause and seek, but no library, no search, no car buttons and no queue of its own.
            Log.i(TAG, "playback control only for ${controller.packageName}")
            return Futures.immediateFuture(
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS)
                    .setAvailablePlayerCommands(
                        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                            .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS).build(),
                    )
                    .build(),
            )
        }
        if (CarAccess.grantsCovers(controller.packageName, own, controller.isTrusted)) grantCovers(controller.packageName)
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        CarButtons.ACTIONS.forEach { commands.add(CarButtons.command(it)) }
        return Futures.immediateFuture(
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setMediaButtonPreferences(buttons())
                .build(),
        )
    }

    /** Lets [packageName] open our cover URIs (one prefix grant; quick, no I/O). */
    private fun grantCovers(packageName: String) {
        try {
            context.grantUriPermission(
                packageName, CoverUris.prefix(coverAuthority),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Log.w(TAG, "cover grant for $packageName failed", e)
        }
    }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        val recent = params?.isRecent == true
        val result = LibraryParams.Builder().setRecent(recent).setExtras(CarMediaItems.rootHints()).build()
        if (!recent) return Futures.immediateFuture(LibraryResult.ofItem(mediaItems.root(CarNode.Root), result))
        // A recent root only makes sense with something to resume (SystemUI's is answered by Media3 itself).
        return future {
            if (lastTitle() == null) LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
            else LibraryResult.ofItem(mediaItems.root(CarNode.Recent), result)
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val node = MediaIds.parse(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        val ctx = carContext()
        val entries = if (node == CarNode.Recent) listOfNotNull(lastTitle()?.let { library.item(it, ctx) })
        else library.children(node, ctx) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItemList(entries.page(page, pageSize).map(mediaItems::toMediaItem), params)
    }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
        val item = when (val node = MediaIds.parse(mediaId)) {
            null -> null
            CarNode.Root, CarNode.Recent -> mediaItems.root(node)
            else -> library.item(node, carContext())?.let(mediaItems::toMediaItem)
        }
        if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(item, null)
    }

    override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        onSubscribed(parentId)
        return super.onSubscribe(session, browser, parentId, params)
    }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = future {
        val count = search.search(query, carContext()).size
        session.notifySearchResultChanged(browser, query, count, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        LibraryResult.ofItemList(search.search(query, carContext()).page(page, pageSize).map(mediaItems::toMediaItem), params)
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        // The app's own queue (files with URIs) is never touched; nobody else may hand the player URIs.
        if (!starts.needsResolution(mediaItems)) {
            return if (controller.packageName == own) Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
            else Futures.immediateFailedFuture(SecurityException("only titles of the library can be started"))
        }
        if (level(controller) != CarAccess.Level.FULL) return Futures.immediateFailedFuture(SecurityException("not allowed to start titles"))
        return future { ready(mediaSession, controller, starts.forRequest(mediaItems, startIndex)) }
    }

    /** Adding to the player queue from outside would mix titles in the file queue of one title: only the app adds. */
    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
        if (controller.packageName == own && !starts.needsResolution(mediaItems)) Futures.immediateFuture(mediaItems)
        else Futures.immediateFailedFuture(UnsupportedOperationException())

    override fun onPlaybackResumption(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        if (!isForPlayback) {
            // Boot time, SystemUI's resumption card: metadata only, no server needed.
            val title = lastTitle() ?: throw IllegalStateException("nothing to resume")
            val entry = library.item(title, carContext()) ?: throw IllegalStateException("last title is gone")
            MediaSession.MediaItemsWithStartPosition(listOf(mediaItems.toMediaItem(entry)), 0, C.TIME_UNSET)
        } else {
            ready(mediaSession, controller, starts.forResumption())
        }
    }

    private fun ready(session: MediaSession, controller: MediaSession.ControllerInfo, out: CarStarts.Outcome): MediaSession.MediaItemsWithStartPosition = when (out) {
        is CarStarts.Outcome.Ready -> {
            Log.i(TAG, "${controller.packageName} starts ${MediaIds.of(out.title)}")
            onStart(out)
            MediaSession.MediaItemsWithStartPosition(out.items, out.index, out.offsetMs)
        }
        is CarStarts.Outcome.AlreadyLoaded -> {
            // Same title: no reload (no rebuffer, no jump back). The request itself is skipped.
            Log.i(TAG, "${controller.packageName} plays the loaded ${MediaIds.of(out.title)} on")
            onPlayLoaded()
            throw CancellationException("already loaded")
        }
        is CarStarts.Outcome.Failed -> {
            Log.i(TAG, "${controller.packageName} cannot start: ${out.reason}")
            try {
                session.sendError(controller, SessionError(SessionError.ERROR_NOT_SUPPORTED, context.getString(message(out.reason))))
            } catch (e: Exception) {
                Log.w(TAG, "error report failed", e)
            }
            throw IllegalStateException(out.reason.name)
        }
    }

    private fun message(reason: CarStarts.Reason): Int = when (reason) {
        CarStarts.Reason.NOTHING_FOUND -> R.string.car_error_nothing_found
        CarStarts.Reason.NOT_FOUND -> R.string.car_error_not_found
        CarStarts.Reason.OFFLINE -> R.string.error_not_downloaded_offline
        CarStarts.Reason.LOCAL_UNAVAILABLE -> R.string.error_local_unavailable
        CarStarts.Reason.NOT_PLAYABLE -> R.string.error_item_not_playable
        CarStarts.Reason.NOTHING_TO_RESUME -> R.string.car_error_nothing_to_resume
    }

    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        val done = customCommand.customAction in CarButtons.ACTIONS && onCommand(customCommand.customAction)
        return Futures.immediateFuture(SessionResult(if (done) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED))
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> = scopeFuture(scope, block)

    private companion object {
        const val TAG = "LibraryCallback"
    }
}

/** A coroutine on [scope] as a future that always completes; a cancelled future cancels the coroutine. */
internal fun <T> scopeFuture(scope: CoroutineScope, block: suspend () -> T): ListenableFuture<T> {
    val f = SettableFuture.create<T>()
    val job = scope.launch {
        try {
            f.set(block())
        } catch (e: CancellationException) {
            f.cancel(false)
            throw e
        } catch (e: Throwable) {
            if (e !is IllegalStateException) Log.w("LibraryCallback", "library request failed", e)
            f.setException(e)
        }
    }
    // A scope cancelled before the coroutine ran (service destroyed) never runs the block: answer anyway.
    job.invokeOnCompletion { if (!f.isDone) f.cancel(false) }
    f.addListener({ if (f.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
    return f
}
