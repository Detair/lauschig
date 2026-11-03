package io.wolftown.lauschig.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    private val r2 = Release(2, "0.2.0", 0)
    private val r3 = Release(3, "0.3.0", 0)
    private val r4 = Release(4, "0.4.0", 0)
    private val all = listOf(r4, r3, r2)

    @Test fun firstInstall_showsNothing() =
        assertTrue(releasesToShow(lastSeen = null, current = 2, updated = false, releases = all).isEmpty())

    @Test fun updateFromTheVersionBeforeWhatsNew_showsTheNewRelease() =
        assertEquals(listOf(r2), releasesToShow(lastSeen = null, current = 2, updated = true, releases = all))

    @Test fun updateWithStoredVersion_showsEverythingSince_newestFirst() {
        assertEquals(listOf(r2), releasesToShow(lastSeen = 1, current = 2, updated = true, releases = all))
        assertEquals(listOf(r4, r3), releasesToShow(lastSeen = 2, current = 4, updated = true, releases = all))
    }

    @Test fun upToDate_orDowngraded_showsNothing() {
        assertTrue(releasesToShow(lastSeen = 2, current = 2, updated = true, releases = all).isEmpty())
        assertTrue(releasesToShow(lastSeen = 4, current = 2, updated = true, releases = all).isEmpty())
    }

    @Test fun releasesNewerThanTheApp_areNotShown() =
        assertEquals(listOf(r3, r2), releasesToShow(lastSeen = 1, current = 3, updated = true, releases = all))

    @Test fun versionWithoutNotes_showsNothing() =
        assertTrue(releasesToShow(lastSeen = 4, current = 5, updated = true, releases = all).isEmpty())
}
