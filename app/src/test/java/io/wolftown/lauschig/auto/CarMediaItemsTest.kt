package io.wolftown.lauschig.auto

import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.playback.SleepMode
import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class CarMediaItemsTest {
    private val s = "0123456789abcdef"
    private val items = CarMediaItems("io.wolftown.lauschig.covers")
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test fun playableTitle_hasCoverUri_progressAndDownloadBadges() {
        val m = items.toMediaItem(
            CarEntry(CarNode.Title(s, "b"), "Band 2", CarEntry.Type.BOOK, subtitle = "noch 5 min", cover = CoverRef(s, "b", 3), progress = 0.25f, downloaded = true),
        )
        assertEquals("title/$s/b", m.mediaId)
        assertEquals("Band 2", m.mediaMetadata.displayTitle)
        assertEquals("noch 5 min", m.mediaMetadata.subtitle)
        assertEquals("content://io.wolftown.lauschig.covers/cover/$s/b/3", m.mediaMetadata.artworkUri.toString())
        assertTrue(m.mediaMetadata.isPlayable!!)
        assertFalse(m.mediaMetadata.isBrowsable!!)
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, m.mediaMetadata.mediaType)
        val extras = m.mediaMetadata.extras!!
        assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED, extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
        assertEquals(0.25, extras.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)
        assertEquals(MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED, extras.getLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS))
    }

    @Test fun aCoverAlreadyOnTheDevice_getsANewUri_soTheCarLoadsItAfterAnEarlierMiss() {
        // Android Auto remembers a failed load per URI: the list built before the cover arrived and the one after differ.
        val entry = CarEntry(CarNode.Title(s, "b"), "Band 2", CarEntry.Type.BOOK, cover = CoverRef(s, "b", 3))
        val missing = CarMediaItems("io.wolftown.lauschig.covers") { false }.toMediaItem(entry)
        val present = CarMediaItems("io.wolftown.lauschig.covers") { it == CoverRef(s, "b", 3) }.toMediaItem(entry)
        assertEquals("content://io.wolftown.lauschig.covers/cover/$s/b/3", missing.mediaMetadata.artworkUri.toString())
        assertEquals("content://io.wolftown.lauschig.covers/cover/$s/b/3?ready=1", present.mediaMetadata.artworkUri.toString())
    }

    @Test fun folder_statesHowItsChildrenAreShown() {
        val m = items.toMediaItem(CarEntry(CarNode.Library("l"), "Hörspiele", CarEntry.Type.LIBRARY, childStyle = CarEntry.Style.GRID))
        assertTrue(m.mediaMetadata.isBrowsable!!)
        assertFalse(m.mediaMetadata.isPlayable!!)
        assertNull(m.mediaMetadata.artworkUri)
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, m.mediaMetadata.extras!!.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
    }

    @Test fun texts_inGerman() {
        val t = ResourceCarTexts(res)
        assertEquals("Weiterhören", t.continueListening)
        assertEquals("noch 2 h 5 min", t.remaining(2 * 3600.0 + 5 * 60 + 30))
        assertEquals("noch 1 min", t.remaining(10.0))
        assertEquals("Band 3", t.volume("3"))
    }

    @Test fun buttons_backAndForwardSlots_speedAndSleepFollowTheState_chaptersOnlyWithChapters() {
        val b = CarButtons(res)
        val plain = b.buttons(CarButtons.State(1.2f, null, hasChapters = false))
        assertEquals(listOf(CarButtons.SEEK_BACK, CarButtons.SEEK_FORWARD, CarButtons.SPEED, CarButtons.SLEEP), plain.map { it.sessionCommand!!.customAction })
        assertEquals(CommandButton.SLOT_BACK, plain[0].slots[0])
        assertEquals(CommandButton.SLOT_FORWARD, plain[1].slots[0])
        assertEquals(CommandButton.ICON_PLAYBACK_SPEED_1_2, plain[2].icon)
        assertEquals("Tempo 1,2×", plain[2].displayName)
        assertEquals("Sleep-Timer aus", plain[3].displayName)
        val running = b.buttons(CarButtons.State(1.3f, SleepMode.Minutes(15), hasChapters = true))
        assertEquals(CommandButton.ICON_PLAYBACK_SPEED, running[2].icon)
        assertEquals("Sleep-Timer: 15 min", running[3].displayName)
        assertEquals(listOf(CarButtons.CHAPTER_BACK, CarButtons.CHAPTER_NEXT), running.drop(4).map { it.sessionCommand!!.customAction })
    }

    @Test fun buttons_seekFollowsTheSettings_withTheMatchingIconOrTheGenericOne() {
        val b = CarButtons(res)
        val defaults = b.buttons(CarButtons.State(1f, null, hasChapters = false))
        assertEquals(CommandButton.ICON_SKIP_BACK_15, defaults[0].icon)
        assertEquals("15 Sekunden zurück", defaults[0].displayName)
        assertEquals(CommandButton.ICON_SKIP_FORWARD_30, defaults[1].icon)
        assertEquals("30 Sekunden vor", defaults[1].displayName)
        val custom = b.buttons(CarButtons.State(1f, null, hasChapters = false, seekBackSec = 5, seekForwardSec = 45))
        assertEquals(CommandButton.ICON_SKIP_BACK_5, custom[0].icon)
        assertEquals("5 Sekunden zurück", custom[0].displayName)
        assertEquals(CommandButton.ICON_SKIP_FORWARD, custom[1].icon) // no 45 s icon
        assertEquals("45 Sekunden vor", custom[1].displayName)
        assertEquals(CommandButton.ICON_SKIP_BACK_10, CarButtons.seekIcon(10, forward = false))
        assertEquals(CommandButton.ICON_SKIP_BACK_30, CarButtons.seekIcon(30, forward = false))
        assertEquals(CommandButton.ICON_SKIP_FORWARD_15, CarButtons.seekIcon(15, forward = true))
        assertEquals(CommandButton.ICON_SKIP_FORWARD, CarButtons.seekIcon(60, forward = true))
    }
}
