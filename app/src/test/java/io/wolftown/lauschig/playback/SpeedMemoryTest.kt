package io.wolftown.lauschig.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedMemoryTest {
    @Test fun newTitle_usesItsStoredSpeed_elseTheDefault() {
        val m = SpeedMemory()
        assertEquals(1.5f, m.onTitle("b1", stored = 1.5f, default = 1.2f))
        assertEquals(1.2f, m.onTitle("b2", stored = null, default = 1.2f))
    }

    @Test fun ownApply_isNotStored_userChangeIsStoredForTheCurrentTitle() {
        val m = SpeedMemory()
        m.onTitle("b1", stored = null, default = 1f)
        assertNull(m.onSpeedChanged(1f)) // the apply itself
        assertEquals("b1", m.onSpeedChanged(1.25f))
        assertNull(m.onSpeedChanged(1.25f)) // same value again
        assertEquals("b1", m.onSpeedChanged(1.5f))
    }

    @Test fun changes_beforeATitleOrWhileTheNextOneLoads_areNotStored() {
        val m = SpeedMemory()
        assertNull(m.onSpeedChanged(1.5f))
        m.onTitle("b1", stored = null, default = 1f)
        m.beginTitle()
        assertNull(m.onSpeedChanged(2f))
    }
}
