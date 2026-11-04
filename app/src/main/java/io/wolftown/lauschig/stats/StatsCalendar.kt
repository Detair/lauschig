package io.wolftown.lauschig.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.core.text.util.LocalePreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/** "Today" for the statistics: the date, the zone it is in and the first day of the week, as the device has them now. */
@Immutable
data class CalendarNow(val today: LocalDate, val zone: ZoneId, val weekStart: DayOfWeek)

/** Where the statistics get "today" from; [changes] says when to ask again. */
interface StatsCalendar {
    fun now(): CalendarNow

    /** Emits when [now] may have changed: at midnight, and when the time, the zone or the language changes. */
    val changes: Flow<Unit>
}

class SystemStatsCalendar @Inject constructor(@ApplicationContext private val context: Context) : StatsCalendar {
    override fun now(): CalendarNow {
        val zone = ZoneId.systemDefault()
        return CalendarNow(LocalDate.now(zone), zone, firstDayOfWeek(Locale.getDefault()))
    }

    private val midnight: Flow<Unit> = flow {
        while (true) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val next = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, next).toMillis() + MIDNIGHT_SLACK_MS)
            emit(Unit)
        }
    }

    private val broadcasts: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        // System broadcasts reach a not-exported receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    override val changes: Flow<Unit> = merge(midnight, broadcasts)

    private companion object {
        const val MIDNIGHT_SLACK_MS = 1_000L
    }
}

/**
 * The first day of the week: the user's choice where Android has one (Android 14+ regional preferences, via
 * [LocalePreferences]), else the locale's (Monday in Germany, Sunday in the US).
 */
fun firstDayOfWeek(locale: Locale): DayOfWeek = when (LocalePreferences.getFirstDayOfWeek(locale)) {
    LocalePreferences.FirstDayOfWeek.MONDAY -> DayOfWeek.MONDAY
    LocalePreferences.FirstDayOfWeek.TUESDAY -> DayOfWeek.TUESDAY
    LocalePreferences.FirstDayOfWeek.WEDNESDAY -> DayOfWeek.WEDNESDAY
    LocalePreferences.FirstDayOfWeek.THURSDAY -> DayOfWeek.THURSDAY
    LocalePreferences.FirstDayOfWeek.FRIDAY -> DayOfWeek.FRIDAY
    LocalePreferences.FirstDayOfWeek.SATURDAY -> DayOfWeek.SATURDAY
    LocalePreferences.FirstDayOfWeek.SUNDAY -> DayOfWeek.SUNDAY
    else -> WeekFields.of(locale).firstDayOfWeek
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StatsCalendarModule {
    @Binds abstract fun statsCalendar(impl: SystemStatsCalendar): StatsCalendar
}
