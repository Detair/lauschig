package io.wolftown.lauschig.sync

import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** The position of [itemId] was replaced by a newer server position; [fromSec] is where the user was. */
data class PositionJump(val itemId: String, val fromSec: Double, val toSec: Double)

/**
 * Pending jumps for the UI. Background syncs may produce jumps while nobody observes, so they are kept
 * until [consume]d. One entry per item: the latest `toSec` wins, the ORIGINAL `fromSec` of the first
 * unconsumed jump is kept. In memory only: process death drops pending jumps (acceptable in Phase 1).
 */
@Singleton
class PositionJumpBus @Inject constructor() {
    /** The jumps of a sync of [serverId]; if another account is signed in by now, nobody it concerns is there to ask. */
    suspend fun emitFor(serverId: String, accounts: AccountStore, jumps: List<PositionJump>) {
        if (jumps.isNotEmpty() && accounts.account.first()?.serverId == serverId) jumps.forEach(::emit)
    }

    private val _pending = MutableStateFlow<List<PositionJump>>(emptyList())
    val pending: StateFlow<List<PositionJump>> = _pending.asStateFlow()

    fun emit(jump: PositionJump) = _pending.update { list ->
        val old = list.firstOrNull { it.itemId == jump.itemId }
        val merged = if (old == null) jump else jump.copy(fromSec = old.fromSec)
        list.filter { it.itemId != jump.itemId } + merged
    }

    fun consume(itemId: String) = _pending.update { list -> list.filter { it.itemId != itemId } }

    /** The account was left: what is pending concerns it, not the next one. */
    fun clear() { _pending.value = emptyList() }
}
