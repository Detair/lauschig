package io.wolftown.lauschig.auto

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import io.wolftown.lauschig.playback.ServiceTitleStarter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarStartsTest {
    private val s = "0123456789abcdef"
    private val resolved = mutableListOf<Triple<String, String, Boolean>>()
    private val searched = mutableListOf<Pair<String, SearchHints>>()
    private var result: ServiceTitleStarter.Resolved = ServiceTitleStarter.Resolved.Ready(listOf(MediaItem.fromUri("file:///a.mp3")), 0, 5_000, streaming = false)
    private var found: CarNode.Title? = null
    private var last: CarNode.Title? = null
    private var loaded: CarNode.Title? = null

    private val starts = CarStarts(
        resolveTitle = { sid, key, rewind -> resolved += Triple(sid, key, rewind); result },
        searchTitle = { q, hints -> searched += q to hints; found },
        lastTitle = { last },
        accountServer = { s },
        current = { loaded },
    )

    private fun byId(id: String) = MediaItem.Builder().setMediaId(id).build()
    private fun bySearch(query: String, extras: Bundle? = null) = MediaItem.Builder()
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(extras).build()).build()

    @Test fun ownAppItems_withUris_passThrough() {
        assertFalse(starts.needsResolution(listOf(MediaItem.fromUri(Uri.parse("file:///x.mp3")), MediaItem.fromUri("https://a/b"))))
        assertTrue(starts.needsResolution(listOf(MediaItem.fromUri("file:///x.mp3"), byId("title/$s/b"))))
        assertFalse(starts.needsResolution(emptyList()))
    }

    @Test fun browsedTitle_resolvesWithTheRewindRule() = runTest {
        val out = starts.forRequest(listOf(byId("title/$s/b")), C.INDEX_UNSET) as CarStarts.Outcome.Ready
        assertEquals(listOf(Triple(s, "b", true)), resolved)
        assertEquals(CarNode.Title(s, "b"), out.title)
        assertEquals(5_000L, out.offsetMs)
        // The request's start index picks the title; the rest of a car list is never queued.
        starts.forRequest(listOf(byId("title/$s/a"), byId("title/$s/c")), 1)
        assertEquals(Triple(s, "c", true), resolved.last())
        starts.forRequest(listOf(byId("title/device/local:0123456789abcdef01234567")), 0)
        assertEquals("device", resolved.last().first)
    }

    @Test fun foreignOrUnknownIds_failWithoutTouchingThePlayer() = runTest {
        assertEquals(CarStarts.Reason.NOT_FOUND, (starts.forRequest(listOf(byId("title/fedcba9876543210/b")), 0) as CarStarts.Outcome.Failed).reason)
        assertEquals(CarStarts.Reason.NOT_FOUND, (starts.forRequest(listOf(byId("series/Testreihe")), 0) as CarStarts.Outcome.Failed).reason)
        assertEquals(CarStarts.Reason.NOT_FOUND, (starts.forRequest(listOf(byId("garbage")), 0) as CarStarts.Outcome.Failed).reason)
        assertTrue(resolved.isEmpty())
    }

    @Test fun playFromSearch_usesQueryAndAssistantHints() = runTest {
        found = CarNode.Title(s, "hit")
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_TITLE, "Mehrteilig")
            putString(MediaStore.EXTRA_MEDIA_ARTIST, "Bert Bauer")
        }
        val out = starts.forRequest(listOf(bySearch("mehrteilig", extras)), C.INDEX_UNSET) as CarStarts.Outcome.Ready
        assertEquals(CarNode.Title(s, "hit"), out.title)
        assertEquals("mehrteilig" to SearchHints(title = "Mehrteilig", artist = "Bert Bauer"), searched.single())
        found = null
        assertEquals(CarStarts.Reason.NOTHING_FOUND, (starts.forRequest(listOf(bySearch("zzz")), 0) as CarStarts.Outcome.Failed).reason)
    }

    @Test fun unplayableTitle_reportsTheReason() = runTest {
        result = ServiceTitleStarter.Resolved.Failed(ServiceTitleStarter.Failure.OFFLINE)
        assertEquals(CarStarts.Reason.OFFLINE, (starts.forRequest(listOf(byId("title/$s/b")), 0) as CarStarts.Outcome.Failed).reason)
    }

    @Test fun resumption_playsTheLastTitleOfThisDevice() = runTest {
        assertEquals(CarStarts.Reason.NOTHING_TO_RESUME, (starts.forResumption() as CarStarts.Outcome.Failed).reason)
        last = CarNode.Title(s, "last")
        val out = starts.forResumption() as CarStarts.Outcome.Ready
        assertEquals(CarNode.Title(s, "last"), out.title)
        assertEquals(Triple(s, "last", true), resolved.single())
    }

    @Test fun theTitleAlreadyLoaded_isNotReloaded() = runTest {
        // Tapping the playing title in the car, or "Spiel Lauschig" while it is loaded: just play on, no reload.
        loaded = CarNode.Title(s, "b")
        assertEquals(CarStarts.Outcome.AlreadyLoaded(CarNode.Title(s, "b")), starts.forRequest(listOf(byId("title/$s/b")), 0))
        found = CarNode.Title(s, "b")
        assertEquals(CarStarts.Outcome.AlreadyLoaded(CarNode.Title(s, "b")), starts.forRequest(listOf(bySearch("")), 0))
        assertTrue(resolved.isEmpty())
        // Another title loads as usual.
        assertTrue(starts.forRequest(listOf(byId("title/$s/other")), 0) is CarStarts.Outcome.Ready)
    }
}
