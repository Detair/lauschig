package io.wolftown.lauschig.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class ChapterSearchTest {
    private fun rows(vararg titles: String) = titles.mapIndexed { i, t -> ChapterRow(i, t, 60.0, isCurrent = false) }

    private fun hits(chapters: List<ChapterRow>, query: String) = ChapterSearch.filter(chapters, query).map { it.index + 1 }

    @Test fun blankQuery_keepsEverything() {
        val list = rows("Eins", "Zwei", "Drei")
        assertEquals(list, ChapterSearch.filter(list, ""))
        assertEquals(list, ChapterSearch.filter(list, "   "))
        assertFalse(ChapterSearch.isActive(" "))
        assertTrue(ChapterSearch.isActive("a"))
    }

    @Test fun wordsMatchByPrefix_ignoringCase_inBookOrder() {
        val list = rows("Die Rückkehr", "Ein Brief", "Die letzte Reise", "Briefe aus Rom")
        assertEquals(listOf(2, 4), hits(list, "brief"))
        assertEquals(listOf(1, 3), hits(list, "DIE"))
        // Every word must match, each one some word of the title.
        assertEquals(listOf(3), hits(list, "reise die"))
        // Not inside a word.
        assertEquals(emptyList<Int>(), hits(list, "kehr"))
    }

    @Test fun accentsAndUmlauts_doNotMatter() {
        val list = rows("Mädchen im Moor", "Café Größenwahn", "Straße der Ölmühle", "Maedchen ohne Namen")
        assertEquals(listOf(1, 4), hits(list, "mädchen"))
        // Without the umlaut: the title written with it (the one written "ae" needs "ae" or "ä").
        assertEquals(listOf(1), hits(list, "madchen"))
        assertEquals(listOf(1, 4), hits(list, "maedchen"))
        assertEquals(listOf(2), hits(list, "cafe grossen"))
        assertEquals(listOf(2), hits(list, "größ"))
        assertEquals(listOf(3), hits(list, "strasse olm"))
        assertEquals(listOf(3), hits(list, "STRAßE oelm"))
    }

    @Test fun aNumberFindsTheChapterOfThatNumber_whateverItsTitle() {
        val list = rows(*Array(130) { "Ohne Nummer" })
        assertEquals(listOf(12, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129), hits(list, "12"))
        assertEquals(listOf(57), hits(list, "57"))
        assertEquals(listOf(7, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79), hits(list, "07"))
    }

    @Test fun numbersInTitles_matchToo_leadingZerosIgnored() {
        val list = rows("Track 001", "Track 002", "Kapitel 12: Der Sturm", "CD2 Teil 3", "1984")
        assertEquals(listOf(1), hits(list, "1 track"))
        assertEquals(listOf(2, 4), hits(list, "2"))
        assertEquals(listOf(3), hits(list, "kapitel 12"))
        assertEquals(listOf(4), hits(list, "cd2"))
        assertEquals(listOf(4), hits(list, "cd 3"))
        assertEquals(listOf(5), hits(list, "198"))
    }

    @Test fun punctuationIsNoWord_andAQueryOfOnlyPunctuationIsNoSearch() {
        val list = rows("„Hallo!“, sagte sie.", "Ende – oder?")
        assertEquals(listOf(1), hits(list, "hallo,"))
        assertEquals(listOf(2), hits(list, "– oder"))
        assertEquals(listOf(1, 2), hits(list, "?"))
        // Not active: no numbers, no jump to the top.
        assertFalse(ChapterSearch.isActive("?"))
        assertFalse(ChapterSearch.isActive(" – … "))
        assertNull(ChapterSearch.hits(ChapterSearch.index(list.map { it.index to it.title }), "?!"))
    }

    // Fix round 1, E2: a title (or a query) that comes decomposed – „a“ + U+0308 – is one word, found as usual.
    @Test fun decomposedTitles_andDecomposedQueries_matchLikeComposedOnes() {
        val nfd = Normalizer.normalize("Käse und Brot", Normalizer.Form.NFD)
        val list = rows(nfd, "Mädchen im Moor")
        assertEquals("the title really is decomposed", 'a', nfd[1])
        assertEquals(listOf(1), hits(list, "käse"))
        assertEquals(listOf(1), hits(list, "kase"))
        assertEquals(listOf(1), hits(list, "kaese"))
        assertEquals(listOf(1), hits(list, "käse brot"))
        // And the other way round: a decomposed query on a composed title.
        assertEquals(listOf(2), hits(list, Normalizer.normalize("mädchen", Normalizer.Form.NFD)))
        assertEquals(listOf(1), hits(list, Normalizer.normalize("KÄSE", Normalizer.Form.NFD)))
    }

    // Fix round 1, E4: letters and digits with or without a space between them.
    @Test fun lettersAndDigits_matchWithOrWithoutASpace() {
        val list = rows("CD 2 – Teil 1", "CD2 Teil 3", "CD 1 Teil 2", "Track 007")
        assertEquals(listOf(1, 2), hits(list, "cd2"))
        assertEquals(listOf(1, 2, 3), hits(list, "cd 2"))
        assertEquals(listOf(1), hits(list, "teil1"))
        assertEquals(listOf(4), hits(list, "track7"))
        assertEquals(listOf(4), hits(list, "track007"))
    }

    // Fix round 1, E3: the words are prepared once; the same index answers every keystroke.
    @Test fun oneIndex_answersEveryQuery() {
        val list = rows("Prolog", "Der Sturm", "Die Rückkehr")
        val index = ChapterSearch.index(list.map { it.index to it.title })
        assertEquals(setOf(1, 2), ChapterSearch.hits(index, "d"))
        assertEquals(setOf(2), ChapterSearch.hits(index, "die r"))
        assertEquals(setOf(0), ChapterSearch.hits(index, "1"))
        assertEquals(emptySet<Int>(), ChapterSearch.hits(index, "xyz"))
    }
}
