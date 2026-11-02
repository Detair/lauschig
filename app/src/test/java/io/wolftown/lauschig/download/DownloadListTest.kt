package io.wolftown.lauschig.download

import io.wolftown.lauschig.db.dao.DownloadEntryRow
import io.wolftown.lauschig.db.entities.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadListTest {
    private fun row(
        key: String, title: String? = key, subtitle: String? = null, state: DownloadState = DownloadState.COMPLETE,
        done: Long = 0, total: Long = done, added: Long = 0, played: Long? = null, finished: Boolean = false,
    ) = DownloadEntryRow(key, state, null, added, title, subtitle, done, total, played, finished)

    private val rows = listOf(
        row("a", "Band 10", "Ende", done = 300, added = 1, played = 50),
        row("b", "band 2", "Funke", done = 900, added = 3, played = null),
        row("c", "Ärger im Wald", "Ende", done = 100, added = 2, played = 70, finished = true),
        row("d", null, null, state = DownloadState.RUNNING, done = 50, total = 500, added = 4),
    )

    private fun keys(sort: DownloadSort, q: String = "") = DownloadList.arrange(rows, sort, q).map { it.itemKey }

    @Test fun byName_isNatural_caseAndAccentInsensitive_untitledByKey() =
        assertEquals(listOf("c", "b", "a", "d"), keys(DownloadSort.NAME))

    @Test fun bySize_largestOnDiskFirst() = assertEquals(listOf("b", "a", "c", "d"), keys(DownloadSort.SIZE))

    @Test fun byAdded_newestFirst() = assertEquals(listOf("d", "b", "c", "a"), keys(DownloadSort.ADDED))

    @Test fun byPlayed_recentFirst_neverPlayedLastByName() = assertEquals(listOf("c", "a", "b", "d"), keys(DownloadSort.PLAYED))

    @Test fun search_matchesTitleOrSubtitle_trimmedAndCaseInsensitive() {
        assertEquals(listOf("a", "c"), keys(DownloadSort.ADDED, "  ende ").sorted())
        assertEquals(listOf("b"), keys(DownloadSort.NAME, "BAND 2"))
        assertEquals(emptyList<String>(), keys(DownloadSort.NAME, "xyz"))
        assertEquals(4, keys(DownloadSort.NAME, "   ").size)
    }

    @Test fun totals_sumTheBytesOnDisk_andCountFinishedCompleteTitles() {
        val t = DownloadList.totals(rows + row("e", state = DownloadState.RUNNING, done = 10, total = 20, finished = true))
        assertEquals(1360L, t.usedBytes)
        assertEquals(1, t.finishedCount)
        assertEquals(100L, t.finishedBytes)
        assertEquals(listOf("c"), DownloadList.finishedKeys(rows))
    }
}
