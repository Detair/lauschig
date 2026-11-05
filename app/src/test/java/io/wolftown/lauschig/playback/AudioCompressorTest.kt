package io.wolftown.lauschig.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) // android.util.Log
@Config(sdk = [35])
class AudioCompressorTest {
    /** The effects created so far, with their session and state. */
    private open class Fx(val session: Int) : SessionEffect {
        var on = false
        var released = false
        override fun setEnabled(on: Boolean) { this.on = on }
        override fun release() { released = true }
    }

    private val created = mutableListOf<Fx>()
    private val compressor = AudioCompressor { session -> Fx(session).also { created += it } }

    /** Effects on a session now: created and not released. */
    private fun live() = created.filter { !it.released }

    @Test fun enabled_putsAnEnabledCompressorOnTheSession() {
        compressor.attach(42)
        assertTrue(live().isEmpty()) // off by default: nothing on the session
        compressor.setEnabled(true)
        val fx = live().single()
        assertEquals(42, fx.session)
        assertTrue(fx.on)
    }

    @Test fun disabled_takesItOffTheSession() {
        compressor.attach(42)
        compressor.setEnabled(true)
        compressor.setEnabled(false)
        assertTrue(live().isEmpty())
    }

    @Test fun withoutASession_waitsForOne() {
        compressor.setEnabled(true)
        assertTrue(live().isEmpty())
        compressor.attach(7)
        assertEquals(7, live().single().session)
    }

    @Test fun aNewSession_movesTheCompressor() {
        compressor.attach(42)
        compressor.setEnabled(true)
        compressor.attach(43)
        assertEquals(listOf(43), live().map { it.session })
    }

    @Test fun theUnsetSession_holdsNothing() {
        compressor.attach(42)
        compressor.setEnabled(true)
        compressor.attach(0)
        assertTrue(live().isEmpty())
    }

    @Test fun release_leavesNothingBehind() {
        compressor.attach(42)
        compressor.setEnabled(true)
        compressor.release()
        assertTrue(live().isEmpty())
    }

    @Test fun sameStateTwice_keepsTheOneEffect() {
        compressor.attach(42)
        compressor.setEnabled(true)
        compressor.setEnabled(true)
        compressor.attach(42)
        assertEquals(1, created.size)
    }

    @Test fun aDeviceWithoutTheEffect_onlyLogs_andTriesAgainOnTheNextSession() {
        var fail = true
        val c = AudioCompressor { s -> if (fail) error("no dynamics processing") else Fx(s).also { created += it } }
        c.attach(42)
        c.setEnabled(true) // must not throw
        assertTrue(live().isEmpty())
        fail = false
        c.attach(43)
        assertEquals(43, live().single().session)
    }

    // Review 2: an effect that was created but cannot be switched on is released, not leaked.
    @Test fun anEffectThatWillNotSwitchOn_isReleased() {
        val broken = mutableListOf<Fx>()
        val c = AudioCompressor { s -> object : Fx(s) { override fun setEnabled(on: Boolean) = error("refused") }.also { broken += it } }
        c.attach(42)
        c.setEnabled(true) // must not throw
        assertTrue(broken.single().released)
    }

    @Test fun setUpOrRelease_releasesWhenTheSetUpFails_andPassesTheError() {
        val released = mutableListOf<String>()
        val error = runCatching { setUpOrRelease("dp", { released += it }) { error("bad parameter value") } }.exceptionOrNull()
        assertEquals("bad parameter value", error?.message)
        assertEquals(listOf("dp"), released)
        assertEquals("ok", setUpOrRelease("ok", { released += it }) {})
        assertEquals(listOf("dp"), released)
    }

    // The real DynamicsProcessing under Robolectric: no native engine, but its Java side (config builder, band and
    // limiter checks) runs, so a wrong argument would throw here.
    @Test fun night_create_buildsItsConfig_andCanBeSwitchedAndReleased() {
        val fx = NightCompressor.create(42)
        fx.setEnabled(true)
        fx.setEnabled(false)
        fx.release()
    }

    @Test fun night_isAGentleSingleBandCompressorWithMakeUpGainAndALimiter() {
        val c = NightCompressor
        assertEquals(1, c.BANDS)
        assertTrue("ratio ${c.RATIO}", c.RATIO in 2f..4f)
        assertTrue("threshold ${c.THRESHOLD_DB}", c.THRESHOLD_DB in -30f..-18f)
        assertTrue("make-up ${c.MAKE_UP_DB}", c.MAKE_UP_DB in 3f..9f)
        assertTrue("limiter ${c.LIMITER_THRESHOLD_DB}", c.LIMITER_THRESHOLD_DB in -6f..-1f)
        assertTrue("soft attack ${c.ATTACK_MS}", c.ATTACK_MS >= 5f)
    }
}
