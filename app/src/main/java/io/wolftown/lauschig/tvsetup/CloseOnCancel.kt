package io.wolftown.lauschig.tvsetup

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * The sockets a blocking call currently waits on. Blocking socket calls ignore coroutine cancellation and thread
 * interrupts; closing the socket from another thread is the one way to end them at once.
 */
internal class OpenSockets {
    private val open = LinkedHashSet<Closeable>()
    private var closed = false

    /** Registers [closeable]; after cancellation it is closed right away instead. */
    fun add(closeable: Closeable) {
        val late = synchronized(this) {
            if (!closed) open.add(closeable)
            closed
        }
        if (late) closeQuietly(closeable)
    }

    /** Takes [closeable] out of reach of cancellation, for work that must finish anyway. */
    fun remove(closeable: Closeable) {
        synchronized(this) { open.remove(closeable) }
    }

    /** True once cancellation closed everything. */
    val cancelled: Boolean get() = synchronized(this) { closed }

    fun closeAll() {
        val all = synchronized(this) {
            closed = true
            open.toList().also { open.clear() }
        }
        all.forEach(::closeQuietly)
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Runs the blocking [block] in this coroutine and closes whatever it registered in [OpenSockets] when the coroutine is
 * cancelled. The guard is a second coroutine that waits for cancellation on another thread of the dispatcher, because
 * this one is busy in [block]. Callers run on [kotlinx.coroutines.Dispatchers.IO], which always has another thread.
 */
internal suspend fun <T> closeOnCancel(block: (OpenSockets) -> T): T = coroutineScope {
    val sockets = OpenSockets()
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            sockets.closeAll()
        }
    }
    try {
        block(sockets)
    } finally {
        guard.cancel()
    }
}
