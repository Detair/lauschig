package io.wolftown.lauschig.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The position of [itemId] was replaced by a newer server position; [fromSec] is where the user was. */
data class PositionJump(val itemId: String, val fromSec: Double, val toSec: Double)

/** Hands [PositionJump]s from sync to the UI. Events are dropped when nobody listens. */
@Singleton
class PositionJumpBus @Inject constructor() {
    private val _jumps = MutableSharedFlow<PositionJump>(extraBufferCapacity = 32)
    val jumps: SharedFlow<PositionJump> = _jumps.asSharedFlow()

    fun emit(jump: PositionJump) {
        _jumps.tryEmit(jump)
    }
}
