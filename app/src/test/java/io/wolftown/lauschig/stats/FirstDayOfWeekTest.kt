package io.wolftown.lauschig.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirstDayOfWeekTest {
    @Test fun theLocalesDay_unlessTheUserChoseOne() {
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek(Locale.GERMANY))
        assertEquals(DayOfWeek.SUNDAY, firstDayOfWeek(Locale.US))
        // Android 14+ stores the regional preference as the locale's "fw" extension.
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek(Locale.forLanguageTag("en-US-u-fw-mon")))
        assertEquals(DayOfWeek.SUNDAY, firstDayOfWeek(Locale.forLanguageTag("de-DE-u-fw-sun")))
    }
}
