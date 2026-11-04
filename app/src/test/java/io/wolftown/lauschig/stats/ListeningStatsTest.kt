package io.wolftown.lauschig.stats

import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

class ListeningStatsTest {
    private val s = "srv"
    private val berlin = ZoneId.of("Europe/Berlin")

    /** Epoch ms of a local time in Berlin. */
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()

    private fun server(
        id: String, start: String, listened: Double, item: String = "li_1", updated: String = start,
        duration: Double = 3600.0, current: Double = 100.0, title: String? = "Buch $item", author: String? = "Autor",
    ) = ListeningSessionEntity(s, id, item, title, author, at(start), at(updated), listened, duration, current)

    private fun local(id: String, start: String, listened: Double, synced: Boolean, item: String = "li_1", current: Double = 100.0) =
        LocalSessionEntity(id, s, item, 0.0, current, listened, at(start), at(start) + 1000, synced)

    private fun stat(id: String, start: String, listened: Double, item: String = "li_1", author: String? = "Autor", updated: String = start, duration: Double = 3600.0, current: Double = 100.0) =
        StatSession(id, item, "T $item", author, at(start), at(updated), listened, duration, current)

    private fun d(text: String) = LocalDate.parse(text)

    // --- merge -------------------------------------------------------------------------------------------------------

    @Test fun merge_sameId_unsyncedLocalWins_syncedTakesTheLargerTime_keepsTheServersTitle() {
        val merged = ListeningStats.merge(
            server = listOf(
                server("a", "2026-11-02T10:00", 100.0),          // local copy not synced yet: it wins (fresher)
                server("b", "2026-11-02T11:00", 300.0),          // synced, server grew: server wins
                server("c", "2026-11-02T12:00", 50.0),           // synced, cache older than the last upload: local wins
                server("d", "2026-11-02T13:00", 40.0),           // another device only
            ),
            local = listOf(
                local("a", "2026-11-02T10:00", 80.0, synced = false),
                local("b", "2026-11-02T11:00", 200.0, synced = true),
                local("c", "2026-11-02T12:00", 70.0, synced = true),
                local("e", "2026-11-02T14:00", 30.0, synced = false), // this device only, not uploaded yet
            ),
        ).associateBy { it.id }

        assertEquals(setOf("a", "b", "c", "d", "e"), merged.keys)
        assertEquals(80.0, merged.getValue("a").listenedSec, 0.0)
        assertEquals("Buch li_1", merged.getValue("a").title) // the server's title stays with the local times
        assertEquals(300.0, merged.getValue("b").listenedSec, 0.0)
        assertEquals(70.0, merged.getValue("c").listenedSec, 0.0)
        assertEquals(40.0, merged.getValue("d").listenedSec, 0.0)
        assertEquals(30.0, merged.getValue("e").listenedSec, 0.0)
    }

    @Test fun merge_catalogNamesTheItem_episodesByTheirPodcast_deviceTitlesNeverCount() {
        val catalog = mapOf(
            "li_1" to ItemEntity(s, "li_1", "lib", "Der Anfang", author = "Neu Autor", durationSec = 1200.0),
            "pod" to ItemEntity(s, "pod", "pods", "Der Podcast", author = "Pod"),
        )
        val merged = ListeningStats.merge(
            server = listOf(server("a", "2026-11-02T10:00", 10.0, title = "Alter Titel", author = "Alt", duration = 0.0)),
            local = listOf(
                local("e", "2026-11-02T11:00", 10.0, synced = false, item = "pod~ep1"),
                local("x", "2026-11-02T12:00", 10.0, synced = false, item = "local:0123456789abcdef01234567"),
            ),
            catalog = catalog,
        ).associateBy { it.id }

        assertEquals(setOf("a", "e"), merged.keys)
        assertEquals("Der Anfang", merged.getValue("a").title)
        assertEquals("Neu Autor", merged.getValue("a").author)
        assertEquals(1200.0, merged.getValue("a").durationSec, 0.0) // the session had none
        assertEquals("Der Podcast", merged.getValue("e").title)
    }

    // --- days --------------------------------------------------------------------------------------------------------

    @Test fun days_areLocalDatesOfTheStart_acrossMidnightAndDstChanges() {
        val days = ListeningStats.days(
            listOf(
                stat("1", "2026-10-25T00:30", 60.0),  // 22:30 UTC the day before: still the 25th in Berlin
                stat("2", "2026-10-25T02:30", 60.0),  // the hour that exists twice (summer time ends)
                stat("3", "2026-10-25T23:50", 1800.0), // runs past midnight: counts for its start day
                stat("4", "2026-10-26T00:10", 30.0),
                stat("5", "2026-03-29T03:30", 45.0),  // the day summer time starts (02:00 → 03:00)
            ),
            berlin,
        )

        assertEquals(mapOf(d("2026-03-29") to 45.0, d("2026-10-25") to 1920.0, d("2026-10-26") to 30.0), days)
        assertEquals(listOf(d("2026-03-29"), d("2026-10-25"), d("2026-10-26")), days.keys.toList()) // in date order
    }

    // --- periods -----------------------------------------------------------------------------------------------------

    @Test fun week_startsOnTheLocalesFirstDay_mondayInGermany_sundayInTheUs() {
        val wednesday = d("2026-11-04")
        val de = WeekFields.of(Locale.GERMANY).firstDayOfWeek
        val us = WeekFields.of(Locale.US).firstDayOfWeek
        assertEquals(DayOfWeek.MONDAY, de)

        assertEquals(PeriodRange(StatsPeriod.WEEK, d("2026-11-02"), d("2026-11-09")), ListeningStats.range(StatsPeriod.WEEK, wednesday, de))
        assertEquals(PeriodRange(StatsPeriod.WEEK, d("2026-11-01"), d("2026-11-08")), ListeningStats.range(StatsPeriod.WEEK, wednesday, us))
        // A Monday anchor is its own week's start; Sunday belongs to the week before it in Germany.
        assertEquals(d("2026-11-02"), ListeningStats.range(StatsPeriod.WEEK, d("2026-11-02"), de).start)
        assertEquals(d("2026-11-02"), ListeningStats.range(StatsPeriod.WEEK, d("2026-11-08"), de).start)
    }

    @Test fun ranges_ofMonthYearAndAll_andTheirNeighbours() {
        val today = d("2026-11-04")
        val month = ListeningStats.range(StatsPeriod.MONTH, today, DayOfWeek.MONDAY)
        assertEquals(PeriodRange(StatsPeriod.MONTH, d("2026-11-01"), d("2026-12-01")), month)
        assertEquals(d("2026-10-01"), ListeningStats.range(StatsPeriod.MONTH, ListeningStats.previousAnchor(month), DayOfWeek.MONDAY).start)
        assertEquals(d("2026-12-01"), ListeningStats.nextAnchor(month))
        val year = ListeningStats.range(StatsPeriod.YEAR, today, DayOfWeek.MONDAY)
        assertEquals(PeriodRange(StatsPeriod.YEAR, d("2026-01-01"), d("2027-01-01")), year)
        assertEquals(PeriodRange(StatsPeriod.ALL, d("2024-03-10"), d("2026-11-05")), ListeningStats.range(StatsPeriod.ALL, today, DayOfWeek.MONDAY, firstDay = d("2024-03-10")))
        assertEquals(PeriodRange(StatsPeriod.ALL, today, d("2026-11-05")), ListeningStats.range(StatsPeriod.ALL, today, DayOfWeek.MONDAY, firstDay = null))
    }

    @Test fun navigation_neverIntoTheFuture_notBeforeTheFirstListening() {
        val today = d("2026-11-04")
        val week = ListeningStats.range(StatsPeriod.WEEK, today, DayOfWeek.MONDAY)
        assertFalse(ListeningStats.hasNext(week, today))
        val lastWeek = ListeningStats.range(StatsPeriod.WEEK, ListeningStats.previousAnchor(week), DayOfWeek.MONDAY)
        assertEquals(d("2026-10-26"), lastWeek.start)
        assertTrue(ListeningStats.hasNext(lastWeek, today))
        assertTrue(ListeningStats.hasPrevious(lastWeek, firstDay = d("2026-10-20")))
        assertFalse(ListeningStats.hasPrevious(week, firstDay = d("2026-11-02")))  // the first listening is in this week
        assertFalse(ListeningStats.hasPrevious(week, firstDay = null))            // nothing listened at all
        val all = ListeningStats.range(StatsPeriod.ALL, today, DayOfWeek.MONDAY, d("2024-01-01"))
        assertFalse(ListeningStats.hasPrevious(all, d("2024-01-01")))
        assertFalse(ListeningStats.hasNext(all, today))
    }

    // --- streaks -----------------------------------------------------------------------------------------------------

    private fun days(vararg pairs: Pair<String, Double>) = pairs.associate { d(it.first) to it.second }

    @Test fun streak_includesToday_whenTodayCounts() {
        val streaks = ListeningStats.streaks(days("2026-11-02" to 600.0, "2026-11-03" to 60.0, "2026-11-04" to 120.0), today = d("2026-11-04"))
        assertEquals(Streaks(current = 3, longest = 3), streaks)
    }

    @Test fun streak_todayNotYetListened_keepsYesterdaysStreak() {
        val streaks = ListeningStats.streaks(days("2026-11-02" to 600.0, "2026-11-03" to 600.0, "2026-11-04" to 30.0), today = d("2026-11-04"))
        assertEquals(2, streaks.current) // 30 s today do not count yet, but the day is not over
    }

    @Test fun streak_aGapBreaksIt_theLongestIsKept_shortDaysDoNotCount() {
        val streaks = ListeningStats.streaks(
            days(
                "2026-10-01" to 100.0, "2026-10-02" to 100.0, "2026-10-03" to 100.0, "2026-10-04" to 100.0, // 4 in a row
                "2026-10-05" to 59.0,                                                                       // under 60 s: a gap
                "2026-10-06" to 100.0,
                "2026-11-01" to 100.0, "2026-11-02" to 100.0,                                               // ended the day before yesterday
            ),
            today = d("2026-11-04"),
        )
        assertEquals(Streaks(current = 0, longest = 4), streaks)
        assertEquals(Streaks(0, 0), ListeningStats.streaks(emptyMap(), d("2026-11-04")))
    }

    // --- summary -----------------------------------------------------------------------------------------------------

    @Test fun summary_week_totalsActiveDaysBarsAndTheCurrentDay() {
        val sessions = listOf(
            stat("1", "2026-11-02T08:00", 1800.0),
            stat("2", "2026-11-02T20:00", 600.0),
            stat("3", "2026-11-04T07:00", 30.0),   // under a minute: time counts, the day does not
            stat("4", "2026-11-01T22:00", 3600.0), // the Sunday before: last week
        )
        val range = ListeningStats.range(StatsPeriod.WEEK, d("2026-11-04"), DayOfWeek.MONDAY)
        val summary = ListeningStats.summary(sessions, emptyList(), range, today = d("2026-11-04"), zone = berlin)

        assertEquals(2430.0, summary.totalSec, 0.0)
        assertEquals(1, summary.activeDays)
        assertEquals(BarUnit.DAY, summary.unit)
        assertEquals(7, summary.bars.size)
        assertEquals(listOf(2400.0, 0.0, 30.0, 0.0, 0.0, 0.0, 0.0), summary.bars.map { it.seconds })
        assertEquals(listOf(false, false, true, false, false, false, false), summary.bars.map { it.current })
        assertEquals(d("2026-11-02"), summary.bars.first().start)
    }

    @Test fun summary_yearHasMonthBars_allHasYearBars() {
        val sessions = listOf(stat("1", "2025-12-31T23:00", 100.0), stat("2", "2026-01-15T10:00", 200.0), stat("3", "2026-11-03T10:00", 300.0))
        val today = d("2026-11-04")
        val year = ListeningStats.summary(sessions, emptyList(), ListeningStats.range(StatsPeriod.YEAR, today, DayOfWeek.MONDAY), today, berlin)
        assertEquals(BarUnit.MONTH, year.unit)
        assertEquals(12, year.bars.size)
        assertEquals(200.0, year.bars[0].seconds, 0.0)
        assertEquals(300.0, year.bars[10].seconds, 0.0)
        assertTrue(year.bars[10].current)
        assertEquals(500.0, year.totalSec, 0.0)

        val all = ListeningStats.summary(sessions, emptyList(), ListeningStats.range(StatsPeriod.ALL, today, DayOfWeek.MONDAY, d("2025-12-31")), today, berlin)
        assertEquals(BarUnit.YEAR, all.unit)
        assertEquals(listOf(100.0, 500.0), all.bars.map { it.seconds })
        assertEquals(listOf(false, true), all.bars.map { it.current })
        assertEquals(600.0, all.totalSec, 0.0)
        assertEquals(3, all.activeDays)
    }

    @Test fun summary_month_hasOneBarPerDay() {
        val range = ListeningStats.range(StatsPeriod.MONTH, d("2026-02-10"), DayOfWeek.MONDAY)
        val summary = ListeningStats.summary(listOf(stat("1", "2026-02-28T10:00", 100.0)), emptyList(), range, d("2026-11-04"), berlin)
        assertEquals(28, summary.bars.size)
        assertEquals(100.0, summary.bars.last().seconds, 0.0)
        assertTrue(summary.bars.none { it.current }) // a past month has no current day
    }

    @Test fun topTitlesAndAuthors_inThePeriod_episodesUnderTheirPodcast_authorsSplit() {
        val sessions = listOf(
            stat("1", "2026-11-02T08:00", 1000.0, item = "li_a", author = "Anna, Ben"),
            stat("2", "2026-11-03T08:00", 500.0, item = "li_a", author = "Anna, Ben"),
            stat("3", "2026-11-03T09:00", 1200.0, item = "li_b", author = "Ben"),
            stat("4", "2026-11-03T10:00", 300.0, item = "pod~e1", author = "Pod"),
            stat("5", "2026-11-03T11:00", 400.0, item = "pod~e2", author = "Pod"),
            stat("6", "2026-11-03T12:00", 50.0, item = "", author = null),    // the server no longer knows the item
            stat("7", "2026-10-20T12:00", 9999.0, item = "li_c", author = "Cara"), // another week
        )
        val range = ListeningStats.range(StatsPeriod.WEEK, d("2026-11-04"), DayOfWeek.MONDAY)
        val summary = ListeningStats.summary(sessions, emptyList(), range, d("2026-11-04"), berlin)

        assertEquals(listOf("li_a" to 1500.0, "li_b" to 1200.0, "pod" to 700.0), summary.topTitles.map { it.itemId to it.seconds })
        assertEquals("T li_a", summary.topTitles.first().title)
        assertEquals(listOf("Ben" to 2700.0, "Anna" to 1500.0, "Pod" to 700.0), summary.topAuthors.map { it.name to it.seconds })
    }

    // --- finished books and series -------------------------------------------------------------------------------------

    @Test fun finishedBooks_byTheirFirstFinishingSession_progressOnly_onlyInAll_episodesNever() {
        val sessions = listOf(
            stat("1", "2026-11-01T10:00", 100.0, item = "li_a", current = 3000.0),
            stat("2", "2026-11-02T10:00", 100.0, item = "li_a", updated = "2026-11-03T00:20", current = 3582.0), // 99.5 %: finished, on the day it ended
            stat("3", "2026-11-04T10:00", 100.0, item = "li_a", current = 3600.0),                               // a later re-listen does not count again
            stat("4", "2026-11-03T10:00", 100.0, item = "pod~e1", current = 3600.0),                             // an episode is no book
            stat("5", "2026-11-03T11:00", 0.0, item = "li_e", current = 3600.0),                                 // nothing listened: not a finish
            stat("6", "2026-11-04T09:00", 100.0, item = "li_z", duration = 0.0, current = 50.0),                 // unknown duration
        )
        val progress = listOf(
            ProgressEntity(s, "li_a", 3600.0, 3600.0, isFinished = true, lastUpdate = 1),
            ProgressEntity(s, "li_old", 3600.0, 3600.0, isFinished = true, lastUpdate = 1), // finished before any session we know
            ProgressEntity(s, "pod~e2", 10.0, 10.0, isFinished = true, lastUpdate = 1),
            ProgressEntity(s, "li_open", 10.0, 3600.0, isFinished = false, lastUpdate = 1),
        )

        val finished = ListeningStats.finishedBooks(sessions, progress, berlin)

        assertEquals(listOf("li_a" to d("2026-11-03"), "li_old" to null), finished.map { it.itemId to it.day })
        val week = ListeningStats.summary(sessions, progress, ListeningStats.range(StatsPeriod.WEEK, d("2026-11-04"), DayOfWeek.MONDAY), d("2026-11-04"), berlin)
        assertEquals(listOf("li_a"), week.finishedBooks.map { it.itemId })
        val all = ListeningStats.summary(sessions, progress, ListeningStats.range(StatsPeriod.ALL, d("2026-11-04"), DayOfWeek.MONDAY, d("2026-11-01")), d("2026-11-04"), berlin)
        assertEquals(listOf("li_a", "li_old"), all.finishedBooks.map { it.itemId })
    }

    @Test fun finishedSeries_everyNumberedVolumeFinished_atLeastTwo() {
        fun vol(id: String, series: String, key: Double?, lib: String = "lib") = ItemEntity(s, id, lib, id, seriesName = series, seriesSortKey = key)
        val groups = listOf(
            listOf(vol("a1", "Reihe A", 1.0), vol("a2", "Reihe A", 2.0)),
            listOf(vol("b1", "Reihe B", 1.0), vol("b2", "Reihe B", 2.0), vol("b3", "Reihe B", 3.0)),
            listOf(vol("c1", "Einzeln", 1.0)),
        )
        val series = ListeningStats.finishedSeries(groups, finished = setOf("a1", "a2", "b1", "b3", "c1"))

        assertEquals(listOf("Reihe A" to 2), series.map { it.name to it.volumes })
        assertNull(ListeningStats.finishedSeries(emptyList(), emptySet()).firstOrNull())
    }

    @Test fun finishedSeries_ofTheSameName_inTwoLibraries_areTwoEntries_withDistinctKeys() {
        fun vol(id: String, lib: String, key: Double) = ItemEntity(s, id, lib, id, seriesName = "Die drei ???", seriesSortKey = key)
        val groups = listOf(
            listOf(vol("h1", "hoerbuch", 1.0), vol("h2", "hoerbuch", 2.0)),
            listOf(vol("s1", "hoerspiel", 1.0), vol("s2", "hoerspiel", 2.0)),
        )
        val series = ListeningStats.finishedSeries(groups, finished = setOf("h1", "h2", "s1", "s2"))

        assertEquals(2, series.size)
        assertEquals(2, series.map { it.key }.toSet().size)
        assertEquals(setOf("hoerbuch", "hoerspiel"), series.map { it.libraryId }.toSet())
    }

    // --- start card --------------------------------------------------------------------------------------------------

    @Test fun startCard_thisWeekWithItsDays_andTheStreak() {
        val card = ListeningStats.startCard(days("2026-11-02" to 3600.0, "2026-11-03" to 600.0, "2026-11-04" to 1800.0, "2026-11-01" to 999.0), d("2026-11-04"), DayOfWeek.MONDAY)!!
        assertEquals(6000.0, card.weekSec, 0.0) // Sunday the 1st belongs to last week
        assertEquals(4, card.streak)
        assertEquals(listOf(3600.0, 600.0, 1800.0, 0.0, 0.0, 0.0, 0.0), card.days.map { it.seconds })
        assertEquals(2, card.days.indexOfFirst { it.current })
    }

    @Test fun startCard_shownWhileSomethingWasHeardInTheLast30Days_only() {
        val today = d("2026-11-04")
        // Ten days ago: nothing this week, but the card stays (and says so).
        val quiet = ListeningStats.startCard(days("2026-10-25" to 7200.0), today, DayOfWeek.MONDAY)!!
        assertEquals(0.0, quiet.weekSec, 0.0)
        assertEquals(0, quiet.streak)
        // 29 days ago still counts, 30 days ago no longer; a few seconds by accident do not count at all.
        assertTrue(ListeningStats.startCard(days("2026-10-06" to 600.0), today, DayOfWeek.MONDAY) != null)
        assertNull(ListeningStats.startCard(days("2026-10-05" to 600.0), today, DayOfWeek.MONDAY))
        assertNull(ListeningStats.startCard(days("2026-11-04" to 20.0, "2026-11-01" to 20.0), today, DayOfWeek.MONDAY))
        assertNull(ListeningStats.startCard(emptyMap(), today, DayOfWeek.MONDAY))
    }
}
