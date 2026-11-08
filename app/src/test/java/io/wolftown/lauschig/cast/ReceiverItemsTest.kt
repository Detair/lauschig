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
import org.junit.Assert.assertNull
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

    // ---- Large titles: one load, one message.

    private fun aLongTitle(files: Int, title: String = "Die Säulen der Erde: Vollständige Lesung", base: String = "https://audiobooks.example.org"): Triple<OpenCastSession, List<MediaItem>, okhttp3.HttpUrl> {
        val key = "4f6f0c0e-1a2b-4c3d-8e9f-0a1b2c3d4e5f"
        val session = OpenCastSession("0123456789abcdef", key, "7a1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", List(files) { ReceiverTrack(it + 1, 600.0, "audio/mpeg") })
        val local = List(files) { i ->
            MediaItem.Builder().setMediaId("$key#$i").setUri("https://a/b/$i#s".toUri()).setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist("Ken Follett").setExtras(Bundle().apply { putString(MediaItemFactory.EXTRA_SERVER_ID, "s"); putString(MediaItemFactory.EXTRA_ITEM_ID, key) }).build(),
            ).build()
        }
        return Triple(session, local, base.toHttpUrl())
    }

    private fun sentJson(item: MediaItem) = ReceiverConverter().toMediaQueueItem(item).toJson().toString()

    @Test fun aTitleThatFits_goesInFull() {
        val (session, local, base) = aLongTitle(40)
        val items = ReceiverItems.forLoad(session, base, local, 3, 1_000)!!
        items.forEach { assertTrue(sentJson(it), sentJson(it).contains("cover") && sentJson(it).contains("Ken Follett")) }
    }

    /** The TV keeps the cover and the author for the first file and the one it starts with, and loses them after. */
    @Test fun aTitleThatWouldNotFit_goesCompact_theFirstAndTheStartFileKeepTheirCover() {
        val (session, local, base) = aLongTitle(150)
        assertTrue("does not fit in full", ReceiverItems.loadBytes(ReceiverItems.build(session, base, local), 7, 0) > com.google.android.gms.cast.Cast.MAX_MESSAGE_LENGTH)
        val items = ReceiverItems.forLoad(session, base, local, 7, 1_000)!!
        assertEquals(150, items.size)
        assertTrue(ReceiverItems.loadBytes(items, 7, 1_000) <= com.google.android.gms.cast.Cast.MAX_MESSAGE_LENGTH)
        listOf(0, 7).forEach { assertTrue(sentJson(items[it]).contains("cover") && sentJson(items[it]).contains("Ken Follett")) }
        val compact = sentJson(items[8])
        assertFalse(compact, compact.contains("cover") || compact.contains("Ken Follett") || compact.contains("customData"))
        assertTrue(compact, compact.contains("Die Säulen der Erde"))
        assertTrue(compact, compact.contains("public\\/session\\/7a1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0\\/track\\/9"))
        assertTrue(compact, compact.contains("audio\\/mpeg"))
    }

    /** What the phone shows comes from the item itself: the notification keeps cover and author on every file. */
    @Test fun aCompactItem_staysComplete_onThePhone() {
        val (session, local, base) = aLongTitle(150)
        val item = ReceiverItems.forLoad(session, base, local, 7, 0)!![8]
        assertEquals("Ken Follett", item.mediaMetadata.artist)
        assertTrue(item.mediaMetadata.artworkUri.toString().endsWith("/cover"))
        assertEquals("s0123456789abcdef".drop(1), item.mediaMetadata.extras!!.getString(MediaItemFactory.EXTRA_SERVER_ID))
    }

    @Test fun aCompactItem_comesBack_asAnItem() {
        val (session, local, base) = aLongTitle(150)
        val item = ReceiverItems.forLoad(session, base, local, 7, 0)!![8]
        val converter = ReceiverConverter()
        assertEquals(item.localConfiguration!!.uri, converter.toMediaItem(converter.toMediaQueueItem(item)).localConfiguration!!.uri)
    }

    @Test fun whatDoesNotFitEvenCompact_isNoQueue() {
        val (session, local, base) = aLongTitle(1_000)
        assertNull(ReceiverItems.forLoad(session, base, local, 0, 0))
    }

    /** The limit this title reaches, computed: a long title on a long address still has room for a good book on CDs. */
    @Test fun theLimit_forATypicalTitle_isOverOneHundredAndFiftyFiles() {
        val fitting = (100..400).last { n -> aLongTitle(n).let { (s, l, b) -> ReceiverItems.forLoad(s, b, l, 0, 0) != null } }
        println("CAST FILE LIMIT typical title: $fitting")
        assertTrue("limit $fitting", fitting >= 150)
    }

    /** The compact items obey the hard rule too. */
    @Test fun aCompactItem_holdsNoTokenNoFragmentNoDeviceFile() {
        val (session, local, base) = aLongTitle(150)
        ReceiverItems.forLoad(session, base, local, 7, 0)!!.map(::sentJson).forEach { json ->
            listOf("token", "Bearer", "file:", "content:", "#s", "download", "serverId", "itemId", "streamBase").forEach { bad -> assertFalse("\"$bad\" in $json", json.contains(bad)) }
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
