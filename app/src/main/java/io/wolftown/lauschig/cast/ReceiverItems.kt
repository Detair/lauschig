package io.wolftown.lauschig.cast

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
    /** [local] is the same title's queue on this device, in the same order. */
    fun build(session: OpenCastSession, base: HttpUrl, local: List<MediaItem>): List<MediaItem> {
        require(session.tracks.size == local.size) { "the session has ${session.tracks.size} tracks for ${local.size} files" }
        val cover = coverUrl(base, PlayableKey.parse(session.key).libraryItemId).toString().toUri()
        return session.tracks.mapIndexed { i, track ->
            val meta = local[i].mediaMetadata
            val extras = Bundle().apply {
                putString(MediaItemFactory.EXTRA_SERVER_ID, session.serverId)
                putString(MediaItemFactory.EXTRA_ITEM_ID, session.key)
                putInt(MediaItemFactory.EXTRA_FILE_INDEX, i)
                putLong(MediaItemFactory.EXTRA_FILE_DURATION_MS, (track.durationSec * 1000).toLong())
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
}
