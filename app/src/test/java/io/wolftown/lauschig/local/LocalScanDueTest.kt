package io.wolftown.lauschig.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanDueTest {
    private val h = 60 * 60 * 1000L

    @Test fun neverScanned_isDue() = assertTrue(LocalFolders.isDue(null, 10))
    @Test fun recentScan_isNotDue() = assertFalse(LocalFolders.isDue(10 * h, 15 * h))
    @Test fun sixHoursLater_isDue() = assertTrue(LocalFolders.isDue(10 * h, 16 * h))
    @Test fun stampFromTheFuture_isDue() = assertTrue(LocalFolders.isDue(20 * h, 10 * h))
}
