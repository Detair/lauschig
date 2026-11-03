package io.wolftown.lauschig.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGroupingTest {
    private fun file(name: String, id: String = name) = DocEntry(id, name, isDirectory = false, size = 1, lastModified = 1)
    private fun dir(name: String, path: String, files: List<DocEntry> = emptyList(), dirs: List<DirNode> = emptyList(), id: String = "d:$path") =
        DirNode(DocEntry(id, name, isDirectory = true), path, files, dirs)

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test fun naturalOrder_comparesNumbersByValue() {
        assertEquals(listOf("Track 2.mp3", "Track 10.mp3", "Track 100.mp3"), sorted("Track 10.mp3", "Track 100.mp3", "Track 2.mp3"))
    }

    @Test fun naturalOrder_ignoresLeadingZerosAndCase() {
        assertEquals(listOf("01 a.mp3", "2 B.mp3", "03 c.mp3"), sorted("03 c.mp3", "2 B.mp3", "01 a.mp3"))
        assertEquals(listOf("alpha", "Beta", "gamma"), sorted("gamma", "Beta", "alpha"))
    }

    @Test fun naturalOrder_umlautsSortWithTheirBaseLetter_andTiesAreStable() {
        assertEquals(listOf("Apfel", "Ärger", "Birne"), sorted("Birne", "Ärger", "Apfel"))
        // Equal apart from zeros/case: still a total, deterministic order.
        assertEquals(sorted("1.mp3", "01.mp3"), sorted("01.mp3", "1.mp3"))
        assertTrue(NaturalOrder.compare("1.mp3", "01.mp3") != 0)
    }

    @Test fun isAudio_byExtension_caseInsensitive() {
        assertTrue(LocalGrouping.isAudio(file("a.MP3")))
        assertTrue(LocalGrouping.isAudio(file("b.m4b")))
        assertTrue(LocalGrouping.isAudio(file("c.opus")))
        assertFalse(LocalGrouping.isAudio(file("cover.jpg")))
        assertFalse(LocalGrouping.isAudio(file("notes.txt")))
        assertFalse(LocalGrouping.isAudio(file("noext")))
    }

    @Test fun directoryWithMp3s_isOneTitle_filesInNaturalOrder_namedAfterTheDirectory() {
        val book = dir("Der Hobbit", "Der Hobbit", files = listOf(file("10.mp3"), file("2.mp3"), file("1.mp3"), file("cover.jpg")))
        val groups = LocalGrouping.group(dir("Hörbücher", "", dirs = listOf(book)), rootName = "Hörbücher")
        val g = groups.single()
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), g.files.map { it.name })
        assertEquals("Der Hobbit", g.fallbackName)
        assertEquals("Der Hobbit", g.relativePath)
        assertEquals("dir:d:Der Hobbit", g.groupKey)
        assertEquals(listOf("cover.jpg"), g.covers.map { it.name })
        assertFalse(g.singleFile)
    }

    @Test fun everyM4b_isATitleOfItsOwn_namedAfterTheFile_whenItHasSiblings() {
        val series = dir("Harry Potter", "Harry Potter", files = listOf(file("HP 2.m4b"), file("HP 1.m4b")))
        val groups = LocalGrouping.group(dir("root", "", dirs = listOf(series)), rootName = "root")
        assertEquals(listOf("HP 1", "HP 2"), groups.map { it.fallbackName })
        assertTrue(groups.all { it.singleFile && it.files.size == 1 })
        assertEquals(listOf("file:HP 1.m4b", "file:HP 2.m4b"), groups.map { it.groupKey })
        assertEquals("Harry Potter/HP 1.m4b", groups.first().relativePath)
    }

    @Test fun soleM4bInASubfolder_takesTheFolderName_butAtTheRootTheFileName() {
        val book = dir("Tolkien - Der Hobbit", "Tolkien - Der Hobbit", files = listOf(file("book.m4b")))
        val groups = LocalGrouping.group(dir("root", "", files = listOf(file("Momo.m4b")), dirs = listOf(book)), rootName = "root")
        assertEquals(setOf("Momo", "Tolkien - Der Hobbit"), groups.map { it.fallbackName }.toSet())
    }

    @Test fun m4bAndMp3InOneDirectory_giveTheM4bTitlesPlusOneTitleOfTheRest() {
        val d = dir("Mix", "Mix", files = listOf(file("a.mp3"), file("b.m4b"), file("c.mp3")))
        val groups = LocalGrouping.group(dir("root", "", dirs = listOf(d)), rootName = "root")
        assertEquals(2, groups.size)
        assertEquals(listOf("a.mp3", "c.mp3"), groups.single { !it.singleFile }.files.map { it.name })
        assertEquals("Mix", groups.single { !it.singleFile }.fallbackName)
        assertEquals("b", groups.single { it.singleFile }.fallbackName)
    }

    @Test fun looseFilesAtTheRoot_areOneTitleNamedAfterThePickedFolder() {
        val groups = LocalGrouping.group(dir("Die Abenteuer", "", files = listOf(file("02.mp3"), file("01.mp3"))), rootName = "Die Abenteuer")
        assertEquals("Die Abenteuer", groups.single().fallbackName)
        assertEquals(listOf("01.mp3", "02.mp3"), groups.single().files.map { it.name })
    }

    @Test fun discFolders_areMergedIntoTheParentTitle_inOrder() {
        val cd2 = dir("CD 2", "Krimi/CD 2", files = listOf(file("02.mp3", "c2-2"), file("01.mp3", "c2-1")))
        val cd10 = dir("CD10", "Krimi/CD10", files = listOf(file("01.mp3", "c10-1")))
        val cd1 = dir("cd1", "Krimi/cd1", files = listOf(file("01.mp3", "c1-1")))
        val side = dir("Seite A", "Kassette/Seite A", files = listOf(file("a.mp3", "sa")))
        val sideB = dir("Seite B", "Kassette/Seite B", files = listOf(file("b.mp3", "sb")))
        val krimi = dir("Krimi", "Krimi", files = listOf(file("folder.png", "cov")), dirs = listOf(cd2, cd10, cd1))
        val kassette = dir("Kassette", "Kassette", dirs = listOf(sideB, side))
        val groups = LocalGrouping.group(dir("root", "", dirs = listOf(krimi, kassette)), rootName = "root")
        val k = groups.single { it.fallbackName == "Krimi" }
        assertEquals(listOf("c1-1", "c2-1", "c2-2", "c10-1"), k.files.map { it.documentId })
        assertEquals(listOf("cov"), k.covers.map { it.documentId })
        assertEquals(listOf("sa", "sb"), groups.single { it.fallbackName == "Kassette" }.files.map { it.documentId })
        assertEquals(2, groups.size)
    }

    @Test fun volumeFolders_areNotMerged_theyAreBooksOfTheirOwn() {
        val b1 = dir("Band 1", "Serie/Band 1", files = listOf(file("1.mp3", "b1")))
        val b2 = dir("Band 2", "Serie/Band 2", files = listOf(file("1.mp3", "b2")))
        val groups = LocalGrouping.group(dir("root", "", dirs = listOf(dir("Serie", "Serie", dirs = listOf(b1, b2)))), rootName = "root")
        assertEquals(listOf("Band 1", "Band 2"), groups.map { it.fallbackName })
    }

    @Test fun hiddenEntries_andDirectoriesWithoutAudio_giveNoTitle() {
        val hidden = dir(".trash", ".trash", files = listOf(file("x.mp3")))
        val empty = dir("Bilder", "Bilder", files = listOf(file("cover.jpg")))
        val groups = LocalGrouping.group(dir("root", "", files = listOf(file(".hidden.mp3")), dirs = listOf(hidden, empty)), rootName = "root")
        assertTrue(groups.isEmpty())
    }

    @Test fun coverCandidates_preferCoverOverFolder_andIgnoreOtherImages() {
        val d = dir("B", "B", files = listOf(file("1.mp3"), file("Folder.JPG"), file("scan.jpg"), file("cover.png")))
        val g = LocalGrouping.group(dir("root", "", dirs = listOf(d)), rootName = "root").single()
        assertEquals(listOf("cover.png", "Folder.JPG"), g.covers.map { it.name })
    }

    @Test fun splitAuthorTitle_onlyOnTheSpacedDash() {
        assertEquals("Tolkien" to "Der Hobbit", LocalGrouping.splitAuthorTitle("Tolkien - Der Hobbit"))
        assertEquals(null to "Spider-Man", LocalGrouping.splitAuthorTitle("Spider-Man"))
        assertEquals(null to "Folge 1", LocalGrouping.splitAuthorTitle("Folge 1"))
        assertEquals(null to "- Titel", LocalGrouping.splitAuthorTitle("- Titel"))
    }
}
