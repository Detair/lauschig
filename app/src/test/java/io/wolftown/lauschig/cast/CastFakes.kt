package io.wolftown.lauschig.cast

import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.cast.CastPlayer
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.MediaItemFactory
import kotlinx.coroutines.CompletableDeferred
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** An item of this device's queue, as [MediaItemFactory] builds it. */
internal fun localItem(serverId: String, key: String, i: Int, uri: String = "https://abs.example/api/items/$key/file/$i/download#$serverId") = MediaItem.Builder()
    .setMediaId("$key#$i")
    .setUri(uri.toUri())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle("Titel $key").setArtist("Autorin").setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(
                Bundle().apply {
                    putString(MediaItemFactory.EXTRA_SERVER_ID, serverId)
                    putString(MediaItemFactory.EXTRA_ITEM_ID, key)
                    putInt(MediaItemFactory.EXTRA_FILE_INDEX, i)
                    putLong(MediaItemFactory.EXTRA_FILE_DURATION_MS, 100_000L)
                },
            )
            .build(),
    )
    .build()

internal fun deviceItem(key: String = "local:0123456789abcdef01234567") = localItem(DeviceLibrary.SERVER_ID, key, 0, "content://documents/tree/x/$key")

@OptIn(UnstableApi::class)
internal abstract class FakeBase(private val info: DeviceInfo) : SimpleBasePlayer(Looper.getMainLooper()) {
    var items: List<MediaItem> = emptyList()
    var index = 0
    var position = 0L
    var pwr = false
    var playback = Player.STATE_IDLE
    var speed = 1f
    var extraCommands = Player.Commands.EMPTY

    protected open fun commands(): Player.Commands = Player.Commands.Builder().addAllCommands().build()

    /** What the player reports right now; a receiver reports its queue only once it has one. */
    protected open fun shown(): List<MediaItem> = items

    override fun getState(): State {
        val list = shown()
        val b = State.Builder()
            .setAvailableCommands(commands())
            .setDeviceInfo(info)
            .setPlayWhenReady(pwr, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setPlaylist(list.mapIndexed { i, it -> MediaItemData.Builder("${uidPrefix()}-$i-${it.mediaId}").setMediaItem(it).setDurationUs(100_000_000L).setIsSeekable(true).build() })
        return if (list.isEmpty()) b.setPlaybackState(Player.STATE_IDLE).build()
        else b.setPlaybackState(playback).setCurrentMediaItemIndex(index.coerceIn(0, list.lastIndex)).setContentPositionMs(PositionSupplier { position }).build()
    }

    protected open fun uidPrefix() = "p"

    fun changed() = invalidateState()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> { pwr = playWhenReady; return Futures.immediateVoidFuture() }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex >= 0) index = mediaItemIndex
        position = positionMs
        seeks += index to positionMs
        return Futures.immediateVoidFuture()
    }
    val seeks = ArrayList<Pair<Int, Long>>()
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> { speed = playbackParameters.speed; return Futures.immediateVoidFuture() }
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}

/** This device's player: the state ExoPlayer would be in, without playing anything. */
@OptIn(UnstableApi::class)
internal class FakeDevice : FakeBase(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build()) {
    var prepared = 0
    var stopped = 0
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems
        index = startIndex.coerceAtLeast(0)
        position = startPositionMs.coerceAtLeast(0)
        return Futures.immediateVoidFuture()
    }
    override fun handlePrepare(): ListenableFuture<*> { prepared++; if (items.isNotEmpty()) playback = Player.STATE_READY; return Futures.immediateVoidFuture() }
    override fun handleStop(): ListenableFuture<*> { stopped++; playback = Player.STATE_IDLE; return Futures.immediateVoidFuture() }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> { items = items.take(fromIndex) + items.drop(toIndex); return Futures.immediateVoidFuture() }
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        items = items.take(fromIndex) + mediaItems + items.drop(toIndex)
        return Futures.immediateVoidFuture()
    }
}

/** `RemoteCastPlayer`'s behaviour that matters here: empty until the receiver's status shows the queue, speed at most 2×. */
@OptIn(UnstableApi::class)
internal class FakeReceiver : FakeBase(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(20).build()) {
    /** Every queue sent to the receiver. */
    val sent = ArrayList<Sent>()
    data class Sent(val items: List<MediaItem>, val index: Int, val position: Long, val playWhenReady: Boolean)
    private var queueShown = false
    var volumeCommand = true
    var replaced = 0

    override fun commands(): Player.Commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
        Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_RELEASE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
    ).addIf(Player.COMMAND_SET_VOLUME, volumeCommand).addIf(Player.COMMAND_GET_VOLUME, volumeCommand).build()

    override fun shown(): List<MediaItem> = if (queueShown) items else emptyList()
    override fun uidPrefix() = "rx"

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val i = startIndex.coerceAtLeast(0)
        val p = startPositionMs.coerceAtLeast(0)
        sent += Sent(mediaItems, i, p, pwr)
        items = mediaItems
        index = i
        position = p
        queueShown = false
        playback = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    /** The receiver's first status: it holds the queue and is ready. */
    fun loaded(state: Int = Player.STATE_READY) {
        queueShown = true
        playback = state
        speed = 1f
        changed()
    }

    fun queueGone() {
        queueShown = false
        playback = Player.STATE_IDLE
        changed()
    }

    override fun handleStop(): ListenableFuture<*> { playback = Player.STATE_IDLE; return Futures.immediateVoidFuture() }
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed.coerceIn(0.5f, 2f)
        return Futures.immediateVoidFuture()
    }
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> { replaced++; return Futures.immediateVoidFuture() }
    val volumes = ArrayList<Float>()
    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> { volumes += volume; return Futures.immediateVoidFuture() }
    val deviceVolumes = ArrayList<Int>()
    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> { deviceVolumes += deviceVolume; return Futures.immediateVoidFuture() }
}

/** Media3's `CastPlayer` (`CastPlayerImpl.updateActivePlayer`), line by line, with a session that a test starts and ends. */
@OptIn(UnstableApi::class)
internal class FakeCastPlayer(private val device: Player, private val receiver: Player, private val transfer: CastPlayer.TransferCallback) : ForwardingSimpleBasePlayer(device) {
    fun sessionAvailable(available: Boolean) {
        val previous = player
        val next = if (available) receiver else device
        if (previous === next) return
        transfer.transferState(previous, next)
        if (previous.playbackState != Player.STATE_IDLE) next.prepare()
        previous.stop()
        setPlayer(next)
    }
}

internal class FakeLink(var onEnd: (Boolean) -> Unit = {}) : ReceiverLink {
    var now = ReceiverActivity.BUSY
    var listener: ReceiverListener? = null
    val ends = ArrayList<Boolean>()
    override fun activity() = now
    override fun endSession(stopReceiver: Boolean) {
        ends += stopReceiver
        onEnd(stopReceiver)
    }
    override fun setReceiverListener(listener: ReceiverListener?) { this.listener = listener }
}

internal class FakeSessions(var bases: List<HttpUrl> = listOf("https://abs.example".toHttpUrl())) : CastSessionSource {
    val opens = ArrayList<Pair<String, String>>()
    val closed = ArrayList<String>()
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var tracksFor: (String) -> Int = { 2 }
    private var next = 0

    override suspend fun open(serverId: String, key: String): OpenCastSession {
        opens += serverId to key
        gate?.await()
        failure?.let { throw it }
        val tracks = List(tracksFor(key)) { ReceiverTrack(it + 1, 100.0, "audio/mpeg") }
        return OpenCastSession(serverId, key, "sess-${++next}", tracks)
    }
    override fun close(session: OpenCastSession) { closed += session.sessionId }
    override suspend fun addresses(serverId: String) = bases
}
