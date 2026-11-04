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

    /** How many collectors listen to [changes] now (a screen should be one). */
    val listeners: Int get() = changed.subscriptionCount.value

    /** [listeners] as it changes: subscribing happens asynchronously, after the first state may already be out. */
    val listenerCount: Flow<Int> = changed.subscriptionCount

    fun moveTo(next: CalendarNow) {
        current = next
        changed.tryEmit(Unit)
    }
}
