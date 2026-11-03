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

    private var now = 0L

    private fun store(local: File? = null, online: Boolean = true, bytes: ByteArray? = byteArrayOf(1, 2, 3)) = CoverStore(
        localCover = { _, _ -> local },
        cacheDir = File(tmp, "cache"),
        fetch = { serverId, itemId ->
            if (!online) CoverStore.Fetch.Unavailable
            else { fetched += "$serverId/$itemId"; bytes?.let(CoverStore.Fetch::Found) ?: CoverStore.Fetch.Missing }
        },
        clock = { now },
    )

    @Test fun localCover_winsWithoutFetching() {
        val local = File(tmp, "cover.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }
        assertEquals(local, store(local).file(CoverRef(s, "b", 1)))
        assertTrue(fetched.isEmpty())
    }

    @Test fun serverCover_fetchedOnce_perVersion_episodesUseThePodcast() {
        val st = store()
        val f = st.file(CoverRef(s, "pod~ep1", 3))!!
        assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes())
        assertEquals(f, st.file(CoverRef(s, "pod~ep2", 3)))
        assertEquals(listOf("$s/pod"), fetched)
        // A new cover version replaces the cached file.
        val g = st.file(CoverRef(s, "pod", 4))!!
        assertFalse(f.exists())
        assertTrue(g.exists())
        assertEquals(2, fetched.size)
    }

    @Test fun offlineOrMissing_hasNoCover_andDeviceTitlesAreNeverFetched() {
        assertNull(store(online = false).file(CoverRef(s, "b", 1)))
        assertNull(store().file(CoverRef("device", "local:0123456789abcdef01234567", 1)))
        assertTrue(fetched.isEmpty())
    }

    @Test fun aTitleWithoutCover_isNotAskedForAgainForAWhile() {
        // SystemUI and the notification reload the artwork on every metadata change.
        val st = store(bytes = null)
        repeat(5) { assertNull(st.file(CoverRef(s, "b", 1))) }
        assertEquals(listOf("$s/b"), fetched)
        now += CoverStore.MISS_TTL_MS + 1
        assertNull(st.file(CoverRef(s, "b", 1)))
        assertEquals(2, fetched.size)
    }
}
