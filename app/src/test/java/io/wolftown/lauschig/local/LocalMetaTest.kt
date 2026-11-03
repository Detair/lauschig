package io.wolftown.lauschig.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMetaTest {
    private fun entry(name: String) = DocEntry("id:$name", name, isDirectory = false, size = 10, lastModified = 1)
    private fun group(names: List<String>, fallback: String = "Ordner", single: Boolean = false) =
        TitleGroup("dir:x", "x", fallback, names.map(::entry), covers = emptyList(), singleFile = single)
    private fun tags(title: String? = null, album: String? = null, artist: String? = null, albumArtist: String? = null, author: String? = null, composer: String? = null, ms: Long? = 60_000) =
        AudioTags(title, album, artist, albumArtist, author, composer, ms)

    @Test fun singleFile_usesTheTitleTag_thenAlbum_thenFallback() {
        val g = group(listOf("a.m4b"), single = true)
        assertEquals("Momo", LocalMeta.build(g, listOf(tags(title = "Momo", album = "Album")))!!.title)
        assertEquals("Album", LocalMeta.build(g, listOf(tags(album = "Album")))!!.title)
        assertEquals("Ordner", LocalMeta.build(g, listOf(tags()))!!.title)
    }

    @Test fun severalFiles_useTheAlbum_thenFallback_neverAChapterTitle() {
        val g = group(listOf("1.mp3", "2.mp3"))
        assertEquals("Buch", LocalMeta.build(g, listOf(tags(title = "Kapitel 1", album = "Buch"), tags(title = "Kapitel 2", album = "Buch")))!!.title)
        assertEquals("Ordner", LocalMeta.build(g, listOf(tags(), tags()))!!.title)
    }

    @Test fun author_fromAlbumArtist_thenArtist_thenAuthor_narratorFromComposer_blankIgnored() {
        val g = group(listOf("a.m4b"), single = true)
        val a = LocalMeta.build(g, listOf(tags(title = "T", albumArtist = " ", artist = "Ende", author = "X", composer = "Sprecher")))!!
        assertEquals("Ende", a.author)
        assertEquals("Sprecher", a.narrator)
        assertEquals("AA", LocalMeta.build(g, listOf(tags(title = "T", albumArtist = "AA", artist = "Ende")))!!.author)
        assertEquals("X", LocalMeta.build(g, listOf(tags(title = "T", author = "X")))!!.author)
        assertNull(LocalMeta.build(g, listOf(tags(title = "T", composer = "")))!!.narrator)
    }

    @Test fun withoutAnyTags_aSpacedDashInTheFallbackGivesAuthorAndTitle() {
        val m = LocalMeta.build(group(listOf("1.mp3"), fallback = "Tolkien - Der Hobbit"), listOf(tags()))!!
        assertEquals("Der Hobbit", m.title)
        assertEquals("Tolkien", m.author)
        // An author tag wins over the folder name, the title still comes from the folder.
        val t = LocalMeta.build(group(listOf("1.mp3"), fallback = "Tolkien - Der Hobbit"), listOf(tags(artist = "J. R. R. Tolkien")))!!
        assertEquals("Der Hobbit", t.title)
        assertEquals("J. R. R. Tolkien", t.author)
    }

    @Test fun duration_isTheSum_andUnreadableFilesAreLeftOut() {
        val m = LocalMeta.build(group(listOf("1.mp3", "2.mp3", "3.mp3")), listOf(tags(ms = 60_000), null, tags(ms = 30_500)))!!
        assertEquals(90.5, m.durationSec, 0.001)
        assertEquals(listOf("1.mp3", "3.mp3"), m.files.map { it.entry.name })
        assertEquals(listOf(60.0, 30.5), m.files.map { it.durationSec })
        // A zero duration counts as unreadable too.
        val z = LocalMeta.build(group(listOf("1.mp3", "2.mp3")), listOf(tags(ms = 0), tags(ms = 10_000)))!!
        assertEquals(listOf("2.mp3"), z.files.map { it.entry.name })
    }

    @Test fun groupWithoutAnyPlayableFile_isNoTitle() {
        assertNull(LocalMeta.build(group(listOf("1.mp3")), listOf(null)))
        assertNull(LocalMeta.build(group(listOf("1.mp3")), listOf(tags(ms = null))))
    }

    @Test fun severalFiles_giveOneChapterPerFile_namedByDistinctTitleTags() {
        val m = LocalMeta.build(group(listOf("01.mp3", "02.mp3")), listOf(tags(title = "Anfang", ms = 60_000), tags(title = "Ende", ms = 30_000)))!!
        assertEquals(listOf(Triple("Anfang", 0.0, 60.0), Triple("Ende", 60.0, 90.0)), m.chapters.map { Triple(it.title, it.startSec, it.endSec) })
    }

    @Test fun chapterNames_fallBackToFileNames_whenTagsAreMissingOrRepeated() {
        val same = LocalMeta.build(group(listOf("01 Start.mp3", "02 Weiter.mp3")), listOf(tags(title = "Buch"), tags(title = "Buch")))!!
        assertEquals(listOf("01 Start", "02 Weiter"), same.chapters.map { it.title })
        val missing = LocalMeta.build(group(listOf("a.mp3", "b.mp3")), listOf(tags(title = "A"), tags()))!!
        assertEquals(listOf("a", "b"), missing.chapters.map { it.title })
    }

    @Test fun oneFile_hasNoChapters() {
        assertEquals(emptyList<Any>(), LocalMeta.build(group(listOf("a.m4b"), single = true), listOf(tags(title = "T")))!!.chapters)
    }
}
