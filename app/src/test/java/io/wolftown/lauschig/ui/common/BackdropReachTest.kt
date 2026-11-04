package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropReachTest {
    private val insets = PaddingValues(start = 12.dp, top = 24.dp, end = 30.dp, bottom = 48.dp)

    @Test fun withoutBanner_reachesOverTheStatusBarAndTheSides() {
        assertEquals(BackdropReach(top = 24.dp, left = 12.dp, right = 30.dp), backdropReach(insets, LayoutDirection.Ltr, bannerShown = false))
    }

    @Test fun belowABanner_neverReachesUp() {
        assertEquals(0.dp, backdropReach(insets, LayoutDirection.Ltr, bannerShown = true).top)
    }

    @Test fun overTheNavigationBar_onlyWithoutTheFramesBarsThere() {
        assertEquals(0.dp, backdropReach(insets, LayoutDirection.Ltr, bannerShown = false).bottom)
        assertEquals(48.dp, backdropReach(insets, LayoutDirection.Ltr, bannerShown = false, barsBelow = false).bottom)
    }

    @Test fun rightToLeft_sidesFollowTheScreen() {
        val r = backdropReach(insets, LayoutDirection.Rtl, bannerShown = false)
        assertEquals(30.dp, r.left)
        assertEquals(12.dp, r.right)
    }
}
