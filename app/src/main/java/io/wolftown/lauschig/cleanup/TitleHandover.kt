package io.wolftown.lauschig.cleanup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Titles on their way into the player, for the automatic clean-up ([FinishedDownloadCleaner]): every start path notes
 * its title here first – the queue's advance and "play from the queue" before the title leaves the queue, the app's
 * player and the service's starter before they load it – and the service notes what its player holds. A picked title is
 * held for [GRACE_MS] (by then the player holds it); the service's title until the service holds another one.
 * Process-wide: the service runs in the app's process.
 */
object TitleHandover {
    const val GRACE_MS = 60_000L

    /** [picked]: key → when it was picked ([clock]); [serviceTitle]: the key the service's player holds. */
    data class State(val picked: Map<String, Long> = emptyMap(), val serviceTitle: String? = null)

    @Volatile var clock: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** A start path picked [key]; call before it leaves the queue or before loading starts. */
    fun pick(key: String) {
        val now = clock()
        _state.update { s -> s.copy(picked = s.picked.filterValues { now - it < GRACE_MS } + (key to now)) }
    }

    /** The service's player holds [key] now (null: nothing). */
    fun serviceHolds(key: String?) = _state.update { it.copy(serviceTitle = key) }

    /** Tests. */
    fun reset() { _state.value = State() }
}
