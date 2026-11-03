package io.wolftown.lauschig.auto

import android.net.Uri
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CoverTest {
    private val auth = "io.wolftown.lauschig.covers"
    private val s = "0123456789abcdef"

    @Test fun uris_roundTrip_andRejectAnythingElse() {
        val ref = CoverRef(s, "pod~ep1", 42)
        val uri = CoverUris.forTitle(auth, ref)
        assertEquals("content://$auth/cover/$s/pod~ep1/42", uri.toString())
        assertEquals(ref, CoverUris.parse(uri))
        assertEquals(CoverRef("device", "local:0123456789abcdef01234567", 0), CoverUris.parse(CoverUris.forTitle(auth, CoverRef("device", "local:0123456789abcdef01234567", 0))))
        assertTrue(CoverUris.forTitle(auth, ref).toString().startsWith(CoverUris.prefix(auth).toString()))
        listOf(
            "content://$auth/cover/$s/../x/1", "content://$auth/cover/$s/a/b/1", "content://$auth/cover/xyz/a/1",
            "content://$auth/cover/$s/a/x", "content://$auth/other/$s/a/1", "content://other/cover/$s/a/1",
            "content://$auth/cover/device/abc/1", "content://$auth/cover/$s/local:0123456789abcdef01234567/1",
        ).forEach { assertNull(it, CoverUris.parse(Uri.parse(it), auth)) }
    }

    private val tmp = File(System.getProperty("java.io.tmpdir"), "cover-test-${System.nanoTime()}")
    @After fun cleanUp() { tmp.deleteRecursively() }

    private val fetched = mutableListOf<String>()
    private val arrived = mutableListOf<String>()
    private val queued = ArrayDeque<Runnable>()
    private var now = 0L

    private fun runQueued() { while (queued.isNotEmpty()) queued.removeFirst().run() }

    private fun store(local: File? = null, answer: () -> CoverStore.Fetch = { CoverStore.Fetch.Found(byteArrayOf(1, 2, 3)) }) = CoverStore(
        localCover = { _, _ -> local },
        cacheDir = File(tmp, "cache"),
        fetch = { serverId, itemId -> fetched += "$serverId/$itemId"; answer() },
        executor = { queued.addLast(it) },
        clock = { now },
    ).apply { onFetched = { serverId, itemId -> arrived += "$serverId/$itemId" } }

    @Test fun localCover_winsWithoutFetching() {
        val local = File(tmp, "cover.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }
        assertEquals(local, store(local).file(CoverRef(s, "b", 1)))
        assertTrue(queued.isEmpty())
    }

    @Test fun aMiss_neverWaitsForTheNetwork_fetchesOnceInTheBackground_andAnnouncesIt() {
        val st = store()
        // The provider's call returns at once ("not found" for now) and queues one fetch, however often it is asked.
        assertNull(st.file(CoverRef(s, "pod~ep1", 3)))
        assertNull(st.file(CoverRef(s, "pod~ep2", 3)))
        assertTrue(fetched.isEmpty())
        assertEquals(1, queued.size)
        runQueued()
        assertEquals(listOf("$s/pod"), fetched)
        assertEquals(listOf("$s/pod"), arrived)
        // Episodes show their podcast's cover.
        val f = st.file(CoverRef(s, "pod~ep1", 3))!!
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
        assertEquals(f, st.file(CoverRef(s, "pod~ep2", 3)))
        // A new cover version replaces the cached file.
        assertNull(st.file(CoverRef(s, "pod", 4)))
        runQueued()
        assertFalse(f.exists())
        assertTrue(st.file(CoverRef(s, "pod", 4))!!.exists())
        assertEquals(2, fetched.size)
    }

    @Test fun offline_isNotRemembered_andDeviceTitlesAreNeverFetched() {
        val st = store { CoverStore.Fetch.Unavailable }
        assertNull(st.file(CoverRef(s, "b", 1))); runQueued()
        assertNull(st.file(CoverRef(s, "b", 1))); runQueued()
        assertEquals(2, fetched.size)
        assertTrue(arrived.isEmpty())
        assertNull(st.file(CoverRef("device", "local:0123456789abcdef01234567", 1)))
        assertTrue(queued.isEmpty())
    }

    @Test fun noCoverOnTheServer_isNotAskedForAgainForHalfAnHour() {
        // SystemUI and the notification reload the artwork on every metadata change.
        val st = store { CoverStore.Fetch.Missing }
        repeat(5) { assertNull(st.file(CoverRef(s, "b", 1))); runQueued() }
        assertEquals(1, fetched.size)
        now += CoverStore.MISS_TTL_MS + 1
        assertNull(st.file(CoverRef(s, "b", 1))); runQueued()
        assertEquals(2, fetched.size)
    }

    @Test fun aFailedRequest_isRetriedAfterTwoMinutes() {
        val st = store { CoverStore.Fetch.Failed }
        repeat(3) { assertNull(st.file(CoverRef(s, "b", 1))); runQueued() }
        assertEquals(1, fetched.size)
        now += CoverStore.ERROR_TTL_MS + 1
        assertNull(st.file(CoverRef(s, "b", 1))); runQueued()
        assertEquals(2, fetched.size)
        // An exception counts as a failed request.
        val throwing = store { error("boom") }
        assertNull(throwing.file(CoverRef(s, "c", 1))); runQueued()
        assertNull(throwing.file(CoverRef(s, "c", 1)))
        assertTrue(queued.isEmpty())
    }
}
