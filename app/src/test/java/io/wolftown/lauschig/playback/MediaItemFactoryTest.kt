package io.wolftown.lauschig.playback

import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MediaItemFactoryTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private fun f(i: Int, path: String?) = AudioFileEntity(s, "it", i, "ino$i", 10.0, 1, "mp3", path)
    private fun item(vararg files: AudioFileEntity) =
        ItemWithDetails(ItemEntity(s, "it", "lib", "T", author = "A"), emptyList(), files.toList(), null, null)

    @Test fun allLocal_usesFileUris_withoutNetwork() {
        val items = MediaItemFactory({ true }) { _, _ -> null }.build(item(f(0, "/d/0.mp3"), f(1, "/d/1.mp3")), null)
        assertEquals(listOf("file", "file"), items.map { it.localConfiguration!!.uri.scheme })
        assertEquals("T", items[0].mediaMetadata.title)
        assertEquals("A", items[0].mediaMetadata.artist)
    }

    @Test fun allLocal_ignoresBaseUrl() {
        val items = MediaItemFactory({ true }) { _, _ -> null }.build(item(f(0, "/d/0.mp3")), "https://abs.example/".toHttpUrl())
        assertEquals("file", items[0].localConfiguration!!.uri.scheme)
    }

    @Test(expected = PlaybackUnavailableException::class)
    fun offline_notAllLocal_throws() {
        MediaItemFactory({ true }) { _, _ -> null }.build(item(f(0, "/d/0.mp3"), f(1, null)), null)
    }

    @Test fun online_streamsMissingFiles() {
        val items = MediaItemFactory({ true }) { _, _ -> null }.build(item(f(0, "/d/0.mp3"), f(1, null)), "https://abs.example/".toHttpUrl())
        assertTrue(items[1].localConfiguration!!.uri.toString().startsWith("https://abs.example/"))
        assertTrue(items[1].localConfiguration!!.uri.toString().contains("ino1"))
        // The app can tell which server a queue streams from (and move it when the address changes).
        assertEquals("https://abs.example/", items[1].mediaMetadata.extras!!.getString(MediaItemFactory.EXTRA_STREAM_BASE))
        assertEquals(null, items[0].mediaMetadata.extras!!.getString(MediaItemFactory.EXTRA_STREAM_BASE))
    }

    @Test fun coverIsUsedWhenPresent() {
        val cover = File.createTempFile("cover", ".jpg").apply { writeText("x"); deleteOnExit() }
        val items = MediaItemFactory({ true }) { _, _ -> cover }.build(item(f(0, "/d/0.mp3")), null)
        assertEquals(cover.toURI().path, items[0].mediaMetadata.artworkUri!!.path)
    }

    @Test(expected = PlaybackUnavailableException::class)
    fun localPathButFileMissing_offline_throws() {
        MediaItemFactory { _, _ -> null }.build(item(f(0, "/nonexistent/0.mp3")), null)
    }

    @Test fun localPathButFileMissing_online_streams() {
        val items = MediaItemFactory { _, _ -> null }.build(item(f(0, "/nonexistent/0.mp3")), "https://abs.example/".toHttpUrl())
        assertEquals("https", items[0].localConfiguration!!.uri.scheme)
    }

    @Test fun existingFile_isLocal() {
        val real = File.createTempFile("audio", ".mp3").apply { deleteOnExit() }
        val items = MediaItemFactory { _, _ -> null }.build(item(f(0, real.path)), null)
        assertEquals("file", items[0].localConfiguration!!.uri.scheme)
    }

    @Test fun episode_streamsFromParentItemUrl_withEpisodeMetadata() {
        val key = PlayableKey.episode("it", "ep1")
        val ep = ItemWithDetails(
            ItemEntity(s, key, "lib", "Episode 1", author = "The Podcast"), emptyList(),
            listOf(AudioFileEntity(s, key, 1, "epino", 10.0, 1, "mp3")), null, null,
        )
        val m = MediaItemFactory({ true }) { _, _ -> null }.build(ep, "https://abs.example/".toHttpUrl())[0]
        assertTrue(m.localConfiguration!!.uri.toString(), m.localConfiguration!!.uri.toString().startsWith("https://abs.example/api/items/it/file/epino"))
        assertEquals("Episode 1", m.mediaMetadata.title)
        assertEquals("The Podcast", m.mediaMetadata.artist)
        assertEquals(key, m.mediaMetadata.extras!!.getString(MediaItemFactory.EXTRA_ITEM_ID))
    }

    /** Review N1: a streamed file names its title's owner in the fragment, which stays on the device. */
    @Test fun aStreamedFile_namesItsOwner_aLocalFileDoesNot_andTwoBuildsAreEqual() {
        val factory = MediaItemFactory({ true }) { _, _ -> null }
        val row = item(f(0, "/d/0.mp3"), f(1, null))
        val base = "https://abs.example/".toHttpUrl()
        val items = factory.build(row, base)
        assertEquals("file", items[0].localConfiguration!!.uri.scheme)
        assertEquals(null, items[0].localConfiguration!!.uri.fragment)
        assertEquals(row.item.serverId, items[1].localConfiguration!!.uri.fragment)
        assertEquals("the sources are compared by URI: nothing reloads without a change", items.map { it.localConfiguration!!.uri }, factory.build(row, base).map { it.localConfiguration!!.uri })
        assertEquals(row.item.serverId, factory.build(row, "https://other.example/".toHttpUrl())[1].localConfiguration!!.uri.fragment)
    }
}
