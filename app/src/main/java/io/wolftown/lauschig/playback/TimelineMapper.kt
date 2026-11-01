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

    /** The offset is clamped to the file's duration, so a foreign or overlong offset never runs into later files. */
    fun toGlobal(fileIndex: Int, offsetMs: Long): Double {
        if (durations.isEmpty()) return 0.0
        if (fileIndex > durations.lastIndex) return totalSec
        val i = fileIndex.coerceAtLeast(0)
        return starts[i] + offsetMs.coerceIn(0L, (durations[i] * 1000.0).toLong()) / 1000.0
    }

    /**
     * Index into [chapters] (in the given list order) of the chapter with the latest start at or before [globalSec].
     * Chapters need not be sorted. Before the first chapter (it starts > 0) the earliest chapter is returned;
     * gaps belong to the preceding chapter. -1 if there are none.
     */
    fun chapterAt(globalSec: Double, chapters: List<ChapterEntity>): Int {
        if (chapters.isEmpty()) return -1
        var best = -1
        chapters.forEachIndexed { i, c ->
            if (c.startSec <= globalSec && (best < 0 || c.startSec >= chapters[best].startSec)) best = i
        }
        if (best >= 0) return best
        return chapters.indices.minBy { chapters[it].startSec }
    }
}
