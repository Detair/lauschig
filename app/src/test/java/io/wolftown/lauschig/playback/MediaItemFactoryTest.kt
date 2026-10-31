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
}
