package io.wolftown.lauschig.external

import io.wolftown.lauschig.db.entities.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SeriesGapsTest {
    private val today = LocalDate.of(2026, 11, 1)

    private fun own(seq: String, language: String? = "German") = ItemEntity(
        serverId = "s", id = "own$seq", libraryId = "l", title = "own $seq", seriesName = "Saga",
        seriesSequence = seq, seriesSortKey = seq.toDoubleOrNull(), language = language,
    )

    private fun vol(
        asin: String, seq: String?, language: String? = "german", abridged: Boolean = false,
        date: LocalDate? = LocalDate.of(2020, 1, 1),
    ) = AudibleVolume(
        asin = asin, title = "t$asin", subtitle = null, authors = emptyList(), narrators = emptyList(),
        sequence = seq, sort = seq?.toDoubleOrNull()?.toInt(), releaseDate = date, runtimeMin = null,
        language = language, abridged = abridged, coverUrl = null, summary = null,
    )

    @Test fun gaps_returnsOnlyNumbersNotOwned_sorted() {
        val r = missingVolumes(listOf(own("1"), own("3")), listOf(vol("c", "3"), vol("b", "2"), vol("d", "4.5"), vol("a", "1")), today)
        assertEquals(listOf("2", "4.5"), r.map { it.sequence })
        assertEquals(listOf(2.0, 4.5), r.map { it.sortKey })
    }

    @Test fun gaps_ownedSequenceInOtherLanguage_notMissing() {
        val r = missingVolumes(listOf(own("2", language = "German")), listOf(vol("en2", "2", language = "english")), today)
        assertTrue(r.isEmpty())
    }

    @Test fun gaps_boxesAndEmptySequences_discarded() {
        val r = missingVolumes(emptyList(), listOf(vol("box", "1-3"), vol("box2", "4,5"), vol("none", ""), vol("nul", null), vol("ok", "6")), today)
        assertEquals(listOf("ok"), r.map { it.volume.asin })
    }

    @Test fun gaps_multipleCandidates_prefersOwnedLanguage_thenUnabridged_thenEarliest() {
        val owned = listOf(own("1", "Deutsch"), own("5", "de"), own("6", "English"))
        val r = missingVolumes(
            owned,
            listOf(
                vol("en", "2", language = "english"),
                vol("de-abr", "2", abridged = true, date = LocalDate.of(2000, 1, 1)),
                vol("de-late", "2", date = LocalDate.of(2022, 1, 1)),
                vol("de-early", "2", date = LocalDate.of(2018, 1, 1)),
            ),
            today,
        )
        assertEquals(listOf("de-early"), r.map { it.volume.asin })
    }

    @Test fun gaps_futureRelease_isUpcoming_andFarFutureDropped() {
        val r = missingVolumes(
            emptyList(),
            listOf(
                vol("past", "1", date = LocalDate.of(2026, 11, 1)),
                vol("soon", "2", date = LocalDate.of(2026, 12, 24)),
                vol("never", "3", date = LocalDate.of(2100, 1, 1)),
                vol("undated", "4", date = null),
            ),
            today,
        )
        assertEquals(listOf("past", "soon", "undated"), r.map { it.volume.asin })
        assertEquals(listOf(false, true, false), r.map { it.upcoming })
        assertFalse(r.any { it.volume.asin == "never" })
    }

    @Test fun language_normalisesNames() {
        for (s in listOf("German", "Deutsch", "de", "ger", "german", "de-DE")) assertEquals(s, "de", normalizeLanguage(s))
        assertEquals("en", normalizeLanguage("English"))
        assertEquals(null, normalizeLanguage(" "))
    }
}
