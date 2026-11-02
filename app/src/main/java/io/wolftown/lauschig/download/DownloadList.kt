package io.wolftown.lauschig.download

import io.wolftown.lauschig.db.dao.DownloadEntryRow
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.local.NaturalOrder

/** Sort orders of the download management page. */
enum class DownloadSort { NAME, SIZE, ADDED, PLAYED }

/** [usedBytes]: on disk for all downloads; [finishedCount]/[finishedBytes]: complete downloads of titles marked heard. */
data class DownloadTotals(val usedBytes: Long, val finishedCount: Int, val finishedBytes: Long)

/** Pure list logic of the download management page. */
object DownloadList {
    fun displayTitle(row: DownloadEntryRow): String = row.title?.takeIf { it.isNotBlank() } ?: row.itemKey

    private val byName: Comparator<DownloadEntryRow> = compareBy(NaturalOrder) { displayTitle(it) }

    /**
     * [query] (trimmed, case-insensitive) matches the title or subtitle. NAME: natural order; SIZE: most bytes on disk
     * first; ADDED: newest first; PLAYED: most recently heard first, never heard last. Ties go by name.
     */
    fun arrange(rows: List<DownloadEntryRow>, sort: DownloadSort, query: String): List<DownloadEntryRow> {
        val q = query.trim()
        val hits = if (q.isEmpty()) rows else rows.filter { r ->
            displayTitle(r).contains(q, ignoreCase = true) || r.subtitle?.contains(q, ignoreCase = true) == true
        }
        val order = when (sort) {
            DownloadSort.NAME -> byName
            DownloadSort.SIZE -> compareByDescending<DownloadEntryRow> { it.bytesDone }.then(byName)
            DownloadSort.ADDED -> compareByDescending<DownloadEntryRow> { it.requestedAt }.then(byName)
            DownloadSort.PLAYED -> compareBy<DownloadEntryRow, Long?>(nullsLast(reverseOrder())) { it.lastPlayedAt }.then(byName)
        }
        return hits.sortedWith(order)
    }

    /** Complete downloads of titles marked as heard: what "delete finished" removes. */
    fun finishedKeys(rows: List<DownloadEntryRow>): List<String> = rows.filter(::isFinishedDownload).map { it.itemKey }

    fun totals(rows: List<DownloadEntryRow>): DownloadTotals {
        val finished = rows.filter(::isFinishedDownload)
        return DownloadTotals(rows.sumOf { it.bytesDone }, finished.size, finished.sumOf { it.bytesDone })
    }

    private fun isFinishedDownload(r: DownloadEntryRow) = r.state == DownloadState.COMPLETE && r.isFinished
}
