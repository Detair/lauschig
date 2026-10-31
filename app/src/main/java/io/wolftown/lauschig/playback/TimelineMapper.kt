package io.wolftown.lauschig.playback

import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity

/** Maps between the book-wide ("global") timeline in seconds and (file index, offset in file). Files must be ordered. */
class TimelineMapper(files: List<AudioFileEntity>) {
    private val durations = files.sortedBy { it.index }.map { it.durationSec }
    private val starts = DoubleArray(durations.size).also { s ->
        var acc = 0.0
        durations.forEachIndexed { i, d -> s[i] = acc; acc += d }
    }
    val totalSec: Double = durations.sum()

    /** Out-of-range input is clamped to the start of the first / the end of the last file. */
    fun toFile(globalSec: Double): Pair<Int, Long> {
        if (durations.isEmpty()) return 0 to 0L
        val g = globalSec.coerceIn(0.0, totalSec)
        var i = durations.lastIndex
        for (k in 0 until durations.lastIndex) if (g < starts[k + 1]) { i = k; break }
        val offsetMs = ((g - starts[i]) * 1000.0).toLong().coerceIn(0L, (durations[i] * 1000.0).toLong())
        return i to offsetMs
    }

    fun toGlobal(fileIndex: Int, offsetMs: Long): Double {
        if (durations.isEmpty()) return 0.0
        if (fileIndex > durations.lastIndex) return totalSec
        val i = fileIndex.coerceAtLeast(0)
        return starts[i] + offsetMs.coerceAtLeast(0L) / 1000.0
    }

    /** Index into [chapters] of the last chapter starting at or before [globalSec]; -1 if there are none. */
    fun chapterAt(globalSec: Double, chapters: List<ChapterEntity>): Int {
        if (chapters.isEmpty()) return -1
        var found = 0
        chapters.forEachIndexed { i, c -> if (c.startSec <= globalSec) found = i }
        return found
    }
}
