package io.wolftown.lauschig.catalog

import io.wolftown.lauschig.db.entities.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private val catalog = Catalog()

    private fun item(
        id: String,
        title: String = id,
        author: String? = null,
        narrator: String? = null,
        series: String? = null,
        seq: Double? = null,
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        subtitle: String? = null,
    ) = ItemEntity(
        serverId = "s", id = id, libraryId = "l", title = title, subtitle = subtitle, author = author,
        narrator = narrator, seriesName = series, seriesSequence = seq?.let { if (it % 1 == 0.0) it.toInt().toString() else it.toString() },
        seriesSortKey = seq, genres = genres, tags = tags,
    )

    @Test fun splitPeople_trimsAndDropsEmpty() {
        assertEquals(listOf("A B", "C"), catalog.splitPeople(" A B,  C , ,"))
        assertEquals(emptyList<String>(), catalog.splitPeople(null))
        assertEquals(emptyList<String>(), catalog.splitPeople("  "))
    }

    @Test fun byPerson_matchesEachCommaSeparatedName_exactly() {
        val items = listOf(
            item("a", author = "Stephen Hawking, Lucy Hawking", narrator = "Stephen Hawking, Lucy Hawking"),
            item("b", author = "Anne", narrator = "Anne"),
            item("c", author = "Ann", narrator = "Ann"),
        )
        assertEquals(listOf("a"), catalog.byPerson(items, PersonRole.AUTHOR, "lucy hawking").map { it.id })
        assertEquals(listOf("a"), catalog.byPerson(items, PersonRole.NARRATOR, "Stephen Hawking").map { it.id })
        assertEquals(listOf("c"), catalog.byPerson(items, PersonRole.AUTHOR, "Ann").map { it.id })
        assertEquals(listOf("b"), catalog.byPerson(items, PersonRole.NARRATOR, "Anne").map { it.id })
    }

    @Test fun bySeries_ordersAndMarksGaps() {
        val items = listOf(
            item("v4", series = "Saga", seq = 4.0),
            item("none", title = "Zeta", series = "Saga"),
            item("v1", series = "Saga", seq = 1.0),
            item("v2", series = "saga ", seq = 2.0),
            item("v15", series = "Saga", seq = 1.5),
            item("other", series = "Other", seq = 9.0),
        )
        val result = catalog.bySeries(items, "Saga")
        val shape = result.map {
            when (it) {
                is SeriesEntry.Volume -> it.item.id
                is SeriesEntry.Missing -> "missing:" + it.sequence
            }
        }
        assertEquals(listOf("v1", "v15", "v2", "missing:3", "v4", "none"), shape)
    }

    @Test fun bySeries_noGapsForDecimalsOrEdges() {
        val items = listOf(item("a", series = "S", seq = 0.0), item("b", series = "S", seq = 0.5), item("c", series = "S", seq = 2.0))
        val shape = catalog.bySeries(items, "S").map { if (it is SeriesEntry.Volume) it.item.id else "m" + (it as SeriesEntry.Missing).sequence }
        assertEquals(listOf("a", "b", "m1", "c"), shape)
    }

    @Test fun bySeries_hugeSortKey_returnsPromptlyWithoutGaps() {
        val items = listOf(item("a", series = "S", seq = 1.0), item("b", series = "S", seq = 20231015.0))
        val result = catalog.bySeries(items, "S")
        assertEquals(2, result.size)
        assertTrue(result.all { it is SeriesEntry.Volume })
    }

    @Test fun bySeries_nonFiniteKeys_doNotCrashOrCreateGaps() {
        val items = listOf(
            item("a", series = "S", seq = 1.0), item("n", series = "S", seq = Double.NaN),
            item("i", series = "S", seq = Double.POSITIVE_INFINITY), item("c", series = "S", seq = 3.0),
        )
        val result = catalog.bySeries(items, "S")
        assertEquals(1, result.count { it is SeriesEntry.Missing })
        assertEquals(4, result.count { it is SeriesEntry.Volume })
    }

    @Test fun byGenreAndTag_exactIgnoringCase() {
        val items = listOf(item("a", genres = listOf("Fantasy")), item("b", genres = listOf("Fantasy-Epos"), tags = listOf("Fav")))
        assertEquals(listOf("a"), catalog.byGenre(items, "fantasy").map { it.id })
        assertEquals(listOf("b"), catalog.byTag(items, "fav").map { it.id })
    }

    @Test fun facets_countAndSkipEmpty() {
        val items = listOf(
            item("a", title = "Beta", author = "X, Y", series = "S", seq = 2.0, genres = listOf("G", " ")),
            item("b", title = "Alpha", author = "Y,, ", series = "S", seq = 1.0, genres = listOf("G")),
            item("c", title = "Gamma", author = "", narrator = "N"),
        )
        val f = catalog.facets(items)
        assertEquals(listOf("Y" to 2, "X" to 1), f.authors.map { it.name to it.count })
        assertEquals(listOf("N" to 1), f.narrators.map { it.name to it.count })
        assertEquals(listOf("G" to 2), f.genres.map { it.name to it.count })
        assertTrue(f.tags.isEmpty())
        // series cover = lowest sort key; author cover = first by title
        assertEquals("b", f.series.single().coverItemId)
        assertEquals("b", f.authors.first { it.name == "Y" }.coverItemId)
        assertEquals("a", f.authors.first { it.name == "X" }.coverItemId)
    }

    @Test fun search_matchesWordPrefix_ignoringCaseAndUmlauts() {
        val items = listOf(
            item("1", title = "Der Schlüssel"),
            item("2", title = "Anderes", author = "Heinz Schlüter"),
            item("3", title = "Nichts"),
        )
        assertEquals(listOf("1"), catalog.search(items, "schlus").titles.map { it.id })
        assertEquals(listOf("1"), catalog.search(items, "SCHLÜS").titles.map { it.id })
        assertTrue(catalog.search(items, "ssel").titles.isEmpty())
        assertTrue(catalog.search(items, "  ").titles.isEmpty())
    }

    @Test fun search_titleHitsFirst_andFindsFacets() {
        val items = listOf(
            item("a", title = "Aaa", author = "Max Mustermann", series = "Max Reihe", seq = 1.0, genres = listOf("Maxi")),
            item("b", title = "Max und Moritz", narrator = "Erika Sprecher"),
        )
        val r = catalog.search(items, "max")
        assertEquals(listOf("b", "a"), r.titles.map { it.id })
        assertEquals(listOf("Max Mustermann"), r.authors.map { it.name })
        assertEquals(listOf("Max Reihe"), r.series.map { it.name })
        assertEquals(listOf("Maxi"), r.genres.map { it.name })
        assertTrue(r.narrators.isEmpty())
        assertEquals(listOf("Erika Sprecher"), catalog.search(items, "sprech").narrators.map { it.name })
    }

    @Test fun search_withPrecomputedFacets_isIdenticalAndDoesNotRecomputeThem() {
        val items = listOf(
            item("a", title = "Aaa", author = "Max Mustermann", series = "Max Reihe", seq = 1.0, genres = listOf("Maxi")),
            item("b", title = "Max und Moritz", narrator = "Erika Sprecher"),
        )
        val facets = catalog.facets(items)
        for (q in listOf("max", "sprech", "maxi", "zzz", "  ")) {
            assertEquals(catalog.search(items, q), catalog.search(items, facets, q))
        }
        // Facets handed in are the ones filtered: with empty facets no person/series/genre can be found (proves they are not rebuilt).
        val none = catalog.search(items, Facets(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), "max")
        assertEquals(listOf("b", "a"), none.titles.map { it.id })
        assertTrue(none.authors.isEmpty() && none.series.isEmpty() && none.genres.isEmpty())
    }
}
