package io.wolftown.lauschig.auto

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.MediaItemFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class ChapterTrackerTest {
    private val s = "0123456789abcdef"

    private fun title(chapters: List<Triple<Double, Double, String>>) = ItemWithDetails(
        ItemEntity(s, "b", "l", "Band 1", author = "Anna", durationSec = 400.0),
        chapters.mapIndexed { i, (st, en, t) -> ChapterEntity(s, "b", i, st, en, t) },
        listOf(AudioFileEntity(s, "b", 0, "ino", 400.0, 1, "m4b", "/d/0.m4b")),
        null, null,
    )

    private class Fake(var items: MutableList<MediaItem>, var position: Long) {
        val seeks = mutableListOf<Pair<Int, Long>>()
        var replaced = 0
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getCurrentMediaItem" -> items.firstOrNull()
                "getCurrentMediaItemIndex" -> 0
                "getCurrentPosition" -> position
                "seekTo" -> { if (args?.size == 2) seeks += (args[0] as Int) to (args[1] as Long); null }
                "replaceMediaItem" -> { items[args!![0] as Int] = args[1] as MediaItem; replaced++; null }
                else -> when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
        } as Player
    }

    private val threeChapters = listOf(Triple(0.0, 100.0, "Eins"), Triple(100.0, 250.0, "Zwei"), Triple(250.0, 400.0, "Drei"))

    @Test fun publishesTheChapterAsSubtitle_once_andJumpsBetweenChapters() = runTest {
        val item = title(threeChapters)
        val fake = Fake(MediaItemFactory({ true }) { _, _ -> null }.build(item, null).toMutableList(), position = 150_000)
        val hasChapters = mutableListOf<Boolean>()
        val tracker = ChapterTracker(fake.player, { _, _ -> item }, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), onTitle = { hasChapters += it })
        tracker.start()
        assertEquals(listOf(false, true), hasChapters) // reset, then the loaded title has chapters
        assertEquals("Zwei", fake.items[0].mediaMetadata.subtitle.toString())
        assertEquals("file:///d/0.m4b", fake.items[0].localConfiguration!!.uri.toString()) // same media, updated in place
        assertEquals(1, fake.replaced)
        // Nothing changes while the chapter stays the same.
        tracker.onPositionDiscontinuity(Player.PositionInfo(null, 0, null, null, 0, 0, 0, -1, -1), Player.PositionInfo(null, 0, null, null, 0, 0, 0, -1, -1), Player.DISCONTINUITY_REASON_SEEK)
        assertEquals(1, fake.replaced)

        assertTrue(tracker.seekChapter(back = false))
        assertTrue(tracker.seekChapter(back = true))
        assertEquals(listOf(0 to 250_000L, 0 to 100_000L), fake.seeks)
        tracker.release()
    }

    @Test fun aTitleWithoutChapters_keepsItsSubtitle_andHasNoChapterJumps() = runTest {
        val item = title(emptyList())
        val fake = Fake(MediaItemFactory({ true }) { _, _ -> null }.build(item, null).toMutableList(), position = 10_000)
        val hasChapters = mutableListOf<Boolean>()
        val tracker = ChapterTracker(fake.player, { _, _ -> item }, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), onTitle = { hasChapters += it })
        tracker.start()
        assertEquals(listOf(false, false), hasChapters)
        assertEquals("Anna", fake.items[0].mediaMetadata.subtitle.toString())
        assertEquals(0, fake.replaced)
        assertEquals(false, tracker.seekChapter(back = false))
    }
}
