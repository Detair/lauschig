package io.wolftown.lauschig.cast

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata as GmsMediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import io.wolftown.lauschig.absapi.library.coverUrl
import io.wolftown.lauschig.absapi.playback.sessionTrackUrl
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.playback.MediaItemFactory
import io.wolftown.lauschig.playback.PlayableKey
import okhttp3.HttpUrl

/** The (server id, playable key) of the title an item belongs to; null for an item that names none. */
internal fun MediaItem.titleKey(): Pair<String, String>? {
    val extras = mediaMetadata.extras ?: return null
    return (extras.getString(MediaItemFactory.EXTRA_SERVER_ID) ?: return null) to (extras.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return null)
}

/**
 * Where a receiver can load a server from, best first: the account's public address, then the local one. A receiver knows
 * neither NetBird nor the home DNS, so what the phone reaches says nothing about what it reaches.
 */
fun receiverAddresses(addresses: ServerAddresses): List<HttpUrl> = listOfNotNull(addresses.public, addresses.local).distinct()

/**
 * What a Cast receiver gets: one queue item per track of the session, built from the session alone and never from
 * [MediaItemFactory]'s URIs (they name a device file, or a stream that needs the token, and carry the owner in the fragment).
 *
 * **No token in anything here:** the track address is a capability that ends with the session and the cover route is public.
 * The extras stay in this process (the converter sends none of them): the player's listeners find their title with them.
 */
object ReceiverItems {
    /**
     * The queue for one load, or null if the title does not fit one message of the Cast protocol
     * ([com.google.android.gms.cast.Cast.MAX_MESSAGE_LENGTH], and `RemoteCastPlayer` sends one load, unchunked). Measured with the
     * real converter on a typical title: 781 bytes per file in full, 626 without cover and author, 364 also without Media3's
     * duplicate of the item in its custom data. So a title that does not fit goes compact: the first file and the one it starts
     * with stay in full, all others carry only what a receiver needs to play them and their title. On those the TV shows no cover and
     * no author. The items here stay complete: the phone's notification does not lose the cover.
     */
    fun forLoad(session: OpenCastSession, base: HttpUrl, local: List<MediaItem>, startIndex: Int, startPositionMs: Long): List<MediaItem>? {
        val full = build(session, base, local)
        if (fits(full, startIndex, startPositionMs)) return full
        val compact = build(session, base, local, inFull = setOf(0, startIndex))
        return compact.takeIf { fits(it, startIndex, startPositionMs) }
    }

    /** What the load's message costs, in the bytes the real converter and the SDK's own JSON make of it. */
    @androidx.annotation.OptIn(UnstableApi::class)
    internal fun loadBytes(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): Int {
        val converter = ReceiverConverter()
        val queue = MediaQueueData.Builder().setItems(items.map(converter::toMediaQueueItem)).setStartIndex(startIndex).setStartTime(startPositionMs).build()
        return MediaLoadRequestData.Builder().setQueueData(queue).setAutoplay(true).setCurrentTime(startPositionMs).build().toJson().toString().toByteArray().size
    }

    private fun fits(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        loadBytes(items, startIndex, startPositionMs) <= Cast.MAX_MESSAGE_LENGTH - MESSAGE_MARGIN_BYTES

    /** [local] is the same title's queue on this device, in the same order. [inFull]: the files with all their metadata, null for all. */
    fun build(session: OpenCastSession, base: HttpUrl, local: List<MediaItem>, inFull: Set<Int>? = null): List<MediaItem> {
        require(session.tracks.size == local.size) { "the session has ${session.tracks.size} tracks for ${local.size} files" }
        val cover = coverUrl(base, PlayableKey.parse(session.key).libraryItemId).toString().toUri()
        return session.tracks.mapIndexed { i, track ->
            val meta = local[i].mediaMetadata
            val extras = Bundle().apply {
                putString(MediaItemFactory.EXTRA_SERVER_ID, session.serverId)
                putString(MediaItemFactory.EXTRA_ITEM_ID, session.key)
                putInt(MediaItemFactory.EXTRA_FILE_INDEX, i)
                putLong(MediaItemFactory.EXTRA_FILE_DURATION_MS, (track.durationSec * 1000).toLong())
                if (inFull != null && i !in inFull) putBoolean(EXTRA_COMPACT, true)
            }
            MediaItem.Builder()
                .setMediaId("${session.key}#${track.index}")
                .setUri(sessionTrackUrl(base, session.sessionId, track.index).toString())
                .setMimeType(track.mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(meta.title)
                        .setArtist(meta.artist)
                        .setDisplayTitle(meta.title)
                        // The local item's subtitle may be a chapter; the receiver's stays the author.
                        .setSubtitle(meta.artist)
                        .setMediaType(meta.mediaType)
                        .setArtworkUri(cover)
                        .setIsPlayable(true)
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }
    }

    /** True for an item built here: it may never go back into the device's queue. */
    fun isReceiverItem(item: MediaItem): Boolean = item.localConfiguration?.uri?.path?.contains("/public/session/") == true

    internal const val EXTRA_COMPACT = "castCompact"

    /** The request's envelope (type, request id) is not in the JSON above; what it costs is not documented, so generously. */
    private const val MESSAGE_MARGIN_BYTES = 2_048
}

/** Media3's converter, except for the items [ReceiverItems] marked compact: only address, type and title (no cover, author, custom data). */
@androidx.annotation.OptIn(UnstableApi::class)
class ReceiverConverter : MediaItemConverter {
    private val default = DefaultMediaItemConverter()

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem = default.toMediaItem(mediaQueueItem)

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        if (mediaItem.mediaMetadata.extras?.getBoolean(ReceiverItems.EXTRA_COMPACT) != true) return default.toMediaQueueItem(mediaItem)
        val config = checkNotNull(mediaItem.localConfiguration)
        val metadata = GmsMediaMetadata(GmsMediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
        mediaItem.mediaMetadata.title?.let { metadata.putString(GmsMediaMetadata.KEY_TITLE, it.toString()) }
        val info = MediaInfo.Builder(mediaItem.mediaId).setContentUrl(config.uri.toString()).setContentType(config.mimeType).setMetadata(metadata).build()
        return MediaQueueItem.Builder(info).build()
    }
}
