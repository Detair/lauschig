package io.wolftown.lauschig.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    @Test fun firstDownload_onApi33_withoutPermission_asks() =
        assertTrue(shouldAskNotificationPermission(sdkInt = 33, granted = false, alreadyAsked = false))

    @Test fun belowApi33_neverAsks() =
        assertFalse(shouldAskNotificationPermission(sdkInt = 32, granted = false, alreadyAsked = false))

    @Test fun alreadyGranted_doesNotAsk() =
        assertFalse(shouldAskNotificationPermission(sdkInt = 35, granted = true, alreadyAsked = false))

    @Test fun askedOnce_doesNotAskAgain() =
        assertFalse(shouldAskNotificationPermission(sdkInt = 35, granted = false, alreadyAsked = true))
}
