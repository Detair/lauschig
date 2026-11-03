package io.wolftown.lauschig.auto

import org.junit.Assert.assertEquals
import org.junit.Test

class CarAccessTest {
    private val own = "io.wolftown.lauschig"
    private fun level(pkg: String, trusted: Boolean = false) = CarAccess.levelFor(pkg, own, trusted)

    @Test fun ownApp_carHosts_assistant_systemUi_andTrustedCallers_getFullAccess() {
        assertEquals(CarAccess.Level.FULL, level(own))
        assertEquals(CarAccess.Level.FULL, level("com.google.android.projection.gearhead"))
        assertEquals(CarAccess.Level.FULL, level("com.android.car.media"))
        assertEquals(CarAccess.Level.FULL, level("com.google.android.googlequicksearchbox"))
        assertEquals(CarAccess.Level.FULL, level("com.android.systemui"))
        assertEquals(CarAccess.Level.FULL, level("com.android.bluetooth", trusted = true))
    }

    @Test fun unknownApps_mayOnlyControlPlayback() {
        assertEquals(CarAccess.Level.CONTROL, level("com.example.tasker"))
        assertEquals(CarAccess.Level.CONTROL, level("com.google.android.projection.gearhead.evil"))
    }

    @Test fun coverGrants_onlyForOtherFullAccessCallers() {
        assertEquals(true, CarAccess.grantsCovers("com.google.android.projection.gearhead", own, trusted = false))
        assertEquals(false, CarAccess.grantsCovers(own, own, trusted = true))
        assertEquals(false, CarAccess.grantsCovers("com.example.tasker", own, trusted = false))
    }
}
