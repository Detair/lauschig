package io.wolftown.lauschig.ui.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets a value through at once when its [key] differs from the previous one (something the key covers changed), but
 * holds back values with an unchanged key and releases the newest of them at most once per [windowMs]. Playback writes
 * the position every few seconds; those writes keep the key and so only cost one recomputation per window, while a tap
 * on "mark finished" is never delayed.
 *
 * What the key does not cover (e.g. another title's position, edited metadata) may wait up to [windowMs] but is never
 * lost: the newest held value is always emitted when its window ends. Deciding and sending happen under one lock, so a
 * released older value can never overtake a newer one.
 */
fun <T> Flow<T>.throttleUnchangedKey(windowMs: Long, key: (T) -> Any?): Flow<T> = channelFlow {
    val lock = Mutex()
    var lastKey: Any? = Unset
    var held: Any? = Unset
    var timerRunning = false

    collect { value ->
        val k = key(value)
        lock.withLock {
            if (k != lastKey) {
                lastKey = k
                held = Unset // a held value of the old key is superseded
                send(value)
            } else {
                held = value
                if (!timerRunning) {
                    timerRunning = true
                    launch {
                        delay(windowMs)
                        lock.withLock {
                            val v = held
                            held = Unset
                            timerRunning = false
                            @Suppress("UNCHECKED_CAST")
                            if (v !== Unset) send(v as T)
                        }
                    }
                }
            }
        }
    }
}

private object Unset
