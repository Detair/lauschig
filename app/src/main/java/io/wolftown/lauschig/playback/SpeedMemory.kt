package io.wolftown.lauschig.playback

import kotlin.math.abs

/**
 * Speed per title: a loaded title gets its stored speed or the default ("Standardtempo"), and every later change of
 * the player speed is stored for that title. The speed the memory applied itself is not stored, so a title only gets
 * its own speed once the user picks one.
 */
class SpeedMemory {
    private var key: String? = null
    private var applied: Float? = null

    /** Another title is being loaded; changes until [onTitle] belong to nobody. */
    fun beginTitle() {
        key = null
        applied = null
    }

    /** Returns the speed to apply for [key]. */
    fun onTitle(key: String, stored: Float?, default: Float): Float {
        val speed = stored ?: default
        this.key = key
        applied = speed
        return speed
    }

    /** The player speed changed; returns the title to store it for, or null. */
    fun onSpeedChanged(speed: Float): String? {
        val k = key ?: return null
        val a = applied
        if (a != null && abs(a - speed) < 0.001f) return null
        applied = speed
        return k
    }
}
