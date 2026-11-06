package io.wolftown.lauschig.cast

import android.app.Application
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.playback.MediaItemFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReceiverItemsTest {
    private val base = "https://abs.example".toHttpUrl()
    private val accessToken = "access-token-abc"
    private val refreshToken = "refresh-token-def"

    private fun localItem(key: String, i: Int, uri: String, subtitle: String = "Autorin") = MediaItem.Builder()
        .setMediaId("$key#$i")
        .setUri(uri.toUri())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Der Titel").setArtist("Autorin").setSubtitle(subtitle).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .setArtworkUri("content://io.wolftown.lauschig.covers/x".toUri())
                .setExtras(
                    Bundle().apply {
                        putString(MediaItemFactory.EXTRA_SERVER_ID, "srv")
                        putString(MediaItemFactory.EXTRA_ITEM_ID, key)
                        putString(MediaItemFactory.EXTRA_STREAM_BASE, "https://abs.example/")
                        putInt(MediaItemFactory.EXTRA_FILE_INDEX, i)
                    },
                )
                .build(),
        )
        .build()

    private val session = OpenCastSession(
        "srv", "li", "sess-1",
        listOf(ReceiverTrack(1, 240.0, "audio/mpeg"), ReceiverTrack(2, 120.5, "audio/mp4")),
    )
    private val local = listOf(
        localItem("li", 1, "file:///data/user/0/io.wolftown.lauschig/files/downloads/srv/li/1.mp3"),
        localItem("li", 2, "https://abs.example/api/items/li/file/2/download#srv", subtitle = "Kapitel 7"),
    )

    @Test fun oneItemPerTrack_atTheSessionsPublicAddress() {
        val items = ReceiverItems.build(session, base, local)
        assertEquals(
            listOf("https://abs.example/public/session/sess-1/track/1", "https://abs.example/public/session/sess-1/track/2"),
            items.map { it.localConfiguration!!.uri.toString() },
        )
        assertEquals(listOf("audio/mpeg", "audio/mp4"), items.map { it.localConfiguration!!.mimeType })
        assertEquals(listOf("li#1", "li#2"), items.map { it.mediaId })
    }

    @Test fun titleAuthorAndAnAbsoluteCover_notTheDevicesProvider() {
        val second = ReceiverItems.build(session, base, local)[1]
        assertEquals("Der Titel", second.mediaMetadata.title)
        assertEquals("Autorin", second.mediaMetadata.artist)
        // Not the chapter the local item shows.
        assertEquals("Autorin", second.mediaMetadata.subtitle)
        assertEquals("https://abs.example/api/items/li/cover", second.mediaMetadata.artworkUri.toString())
    }

    @Test fun anEpisode_showsItsPodcastsCover() {
        val episode = OpenCastSession("srv", "pod~ep", "s", listOf(ReceiverTrack(1, 60.0, "audio/mpeg")))
        val item = ReceiverItems.build(episode, base, listOf(localItem("pod~ep", 1, "file:///x.mp3"))).single()
        assertEquals("https://abs.example/api/items/pod/cover", item.mediaMetadata.artworkUri.toString())
        assertEquals("https://abs.example/public/session/s/track/1", item.localConfiguration!!.uri.toString())
    }

    /** What the listeners of the player need, and what lets the app move a queue: only the first stays. */
    @Test fun theExtrasNameTheTitle_butNoStreamBase() {
        val extras = ReceiverItems.build(session, base, local)[1].mediaMetadata.extras!!
        assertEquals("srv", extras.getString(MediaItemFactory.EXTRA_SERVER_ID))
        assertEquals("li", extras.getString(MediaItemFactory.EXTRA_ITEM_ID))
        assertEquals(120_500L, extras.getLong(MediaItemFactory.EXTRA_FILE_DURATION_MS))
        assertFalse(extras.containsKey(MediaItemFactory.EXTRA_STREAM_BASE))
    }

    /**
     * The hard rule, over everything Media3's real converter sends to a receiver: its JSON holds every field. No token, no
     * owner fragment, no device file; and the account's id stays on the device as well.
     */
    @Test fun whatTheReceiverGets_holdsNoTokenNoFragmentNoDeviceFile() {
        val sent = ReceiverItems.build(session, base, local).map { DefaultMediaItemConverter().toMediaQueueItem(it).toJson().toString() }
        assertTrue(sent.first(), sent.all { "public\\/session\\/sess-1\\/track" in it || "public/session/sess-1/track" in it })
        sent.forEach { json ->
            listOf(accessToken, refreshToken, "token", "Bearer", "file:", "content:", "#srv", "download", "serverId", "itemId", "streamBase").forEach { bad ->
                assertFalse("\"$bad\" in $json", json.contains(bad, ignoreCase = false))
            }
        }
    }

    @Test fun theItemsKnowEachOther() {
        assertTrue(ReceiverItems.build(session, base, local).all(ReceiverItems::isReceiverItem))
        assertFalse(local.any(ReceiverItems::isReceiverItem))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aServerThatChangedTheFiles_isNoQueueForThisTimeline() {
        ReceiverItems.build(session, base, local.take(1))
    }

    @Test fun thePublicAddressComesFirst_thenTheLocalOne() {
        val public = "https://abs.example".toHttpUrl()
        val home = "http://192.168.1.2:13378".toHttpUrl()
        assertEquals(listOf(public, home), receiverAddresses(ServerAddresses(home, public)))
        assertEquals(listOf(home), receiverAddresses(ServerAddresses(home, null)))
        assertEquals(listOf(public), receiverAddresses(ServerAddresses(public, public)))
    }
}
