package io.wolftown.lauschig.ui.common

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Lets a value through at once when its [key] differs from the previous one (something the user can see or did
 * changed: a title finished, a list got another entry), but holds back values with an unchanged key and releases the
 * newest of them at most once per [windowMs]. Playback writes the position every few seconds; those writes keep the
 * key and so only cost one list recomputation per window, while a tap on "mark finished" is never delayed.
 * Nothing is lost: the last held value is always emitted when its window ends.
 */
fun <T> Flow<T>.throttleUnchangedKey(windowMs: Long, key: (T) -> Any?): Flow<T> = channelFlow {
    val lock = Any()
    var lastKey: Any? = Unset
    var held: Any? = Unset
    var timerRunning = false

    suspend fun ProducerScope<T>.release() {
        delay(windowMs)
        @Suppress("UNCHECKED_CAST")
        val value = synchronized(lock) { held.also { held = Unset; timerRunning = false } } as Any?
        if (value !== Unset) @Suppress("UNCHECKED_CAST") send(value as T)
    }

    collect { value ->
        val k = key(value)
        val immediate = synchronized(lock) {
            if (k != lastKey) {
                lastKey = k
                held = Unset // a held value of the old key is superseded
                true
            } else {
                held = value
                false
            }
        }
        if (immediate) {
            send(value)
        } else {
            val start = synchronized(lock) { (!timerRunning).also { if (it) timerRunning = true } }
            if (start) launch { release() }
        }
    }
}

private object Unset
