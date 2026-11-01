package io.wolftown.lauschig.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionJumpBusTest {
    @Test fun jumpWhileNoSubscriber_isDeliveredLater() {
        val bus = PositionJumpBus()
        bus.emit(PositionJump("a", 10.0, 100.0))
        assertEquals(listOf(PositionJump("a", 10.0, 100.0)), bus.pending.value)
        bus.consume("a")
        assertEquals(emptyList<PositionJump>(), bus.pending.value)
    }

    @Test fun twoJumpsSameItem_keepOriginalFrom() {
        val bus = PositionJumpBus()
        bus.emit(PositionJump("a", 10.0, 100.0))
        bus.emit(PositionJump("a", 100.0, 300.0))
        assertEquals(listOf(PositionJump("a", 10.0, 300.0)), bus.pending.value)
    }
}
