package io.wolftown.lauschig.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerVolumeTest {
    private val applied = mutableListOf<Float>()
    private val volume = PlayerVolume { applied += it }

    @Test fun thePlayerGets_theRequestedVolumeTimesTheSwitchGain() {
        volume.request(0.5f)
        volume.setSwitchGain(0.5f)
        volume.request(0.8f)
        volume.setSwitchGain(1f)
        assertEquals(listOf(0.5f, 0.25f, 0.4f, 0.8f), applied)
        assertEquals(0.8f, volume.requested, 0f)
    }

    @Test fun valuesAreClampedToZeroToOne() {
        volume.request(1.5f)
        volume.setSwitchGain(-1f)
        assertEquals(listOf(1f, 0f), applied)
    }
}
