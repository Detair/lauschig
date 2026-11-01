package io.wolftown.lauschig.playback

import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineMapperTest {
    private fun file(i: Int, dur: Double) = AudioFileEntity("s", "it", i, "ino$i", dur, 1, "mp3")
    private fun chapter(i: Int, start: Double, end: Double) = ChapterEntity("s", "it", i, start, end, "c$i")

    @Test fun globalPosition_mapsAcrossFileBoundaries() {
        val m = TimelineMapper(listOf(file(0, 600.0), file(1, 300.0), file(2, 900.0)))
        assertEquals(1 to 150_000L, m.toFile(750.0))
        assertEquals(0 to 0L, m.toFile(0.0))
        assertEquals(1 to 0L, m.toFile(600.0))
        assertEquals(2 to 0L, m.toFile(900.0))
        assertEquals(2 to 899_000L, m.toFile(1799.0))
        assertEquals(900.0, m.toGlobal(2, 0), 0.0)
        assertEquals(750.0, m.toGlobal(1, 150_000), 0.0001)
        assertEquals(1800.0, m.totalSec, 0.0)
    }

    @Test fun globalPosition_clampsOutOfRange() {
        val m = TimelineMapper(listOf(file(0, 600.0), file(1, 300.0)))
        assertEquals(0 to 0L, m.toFile(-5.0))
        assertEquals(1 to 300_000L, m.toFile(5000.0))
        assertEquals(900.0, m.toGlobal(7, 0), 0.0)
    }

    @Test fun toGlobal_clampsOffsetToFileDuration() {
        val m = TimelineMapper(listOf(file(0, 600.0), file(1, 300.0)))
        assertEquals(600.0, m.toGlobal(0, 899_000), 0.0)
        assertEquals(900.0, m.toGlobal(1, 5_000_000), 0.0)
    }

    @Test fun chapterAt_usesChapterStarts_notFiles() {
        val m = TimelineMapper(listOf(file(0, 1000.0)))
        val ch = listOf(chapter(0, 0.0, 100.0), chapter(1, 100.0, 300.0), chapter(2, 300.0, 600.0), chapter(3, 600.0, 900.0), chapter(4, 900.0, 1000.0))
        assertEquals(0, m.chapterAt(0.0, ch))
        assertEquals(0, m.chapterAt(99.9, ch))
        assertEquals(1, m.chapterAt(100.0, ch))
        assertEquals(3, m.chapterAt(750.0, ch))
        assertEquals(4, m.chapterAt(1000.0, ch))
        assertEquals(-1, m.chapterAt(5.0, emptyList()))
    }

    @Test fun chapterAt_chaptersStartingAfterZero_andGaps() {
        val m = TimelineMapper(listOf(file(0, 1000.0)))
        val ch = listOf(chapter(0, 30.0, 100.0), chapter(1, 200.0, 300.0))
        assertEquals(0, m.chapterAt(5.0, ch)) // before the first chapter
        assertEquals(0, m.chapterAt(150.0, ch)) // gap belongs to the preceding chapter
        assertEquals(1, m.chapterAt(250.0, ch))
    }

    @Test fun chapterAt_unsortedChapters_returnIndexInGivenList() {
        val m = TimelineMapper(listOf(file(0, 1000.0)))
        val ch = listOf(chapter(1, 200.0, 300.0), chapter(0, 0.0, 200.0))
        assertEquals(1, m.chapterAt(50.0, ch))
        assertEquals(0, m.chapterAt(250.0, ch))
    }

    @Test fun chapterAt_worksAcrossFileBoundaries() {
        val m = TimelineMapper(listOf(file(0, 600.0), file(1, 600.0)))
        val ch = listOf(chapter(0, 0.0, 500.0), chapter(1, 500.0, 700.0), chapter(2, 700.0, 1200.0))
        val (fi, off) = m.toFile(650.0)
        assertEquals(1, m.chapterAt(m.toGlobal(fi, off), ch))
    }
}
