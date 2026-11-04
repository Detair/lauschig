package io.wolftown.lauschig.stats

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** A calendar the test sets; [moveTo] is midnight or a zone change: it says so through [changes]. */
class FakeStatsCalendar(
    @Volatile var current: CalendarNow = CalendarNow(LocalDate.of(2026, 11, 4), ZoneId.of("Europe/Berlin"), DayOfWeek.MONDAY),
) : StatsCalendar {
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override fun now(): CalendarNow = current
    override val changes: Flow<Unit> = changed

    fun moveTo(next: CalendarNow) {
        current = next
        changed.tryEmit(Unit)
    }
}
