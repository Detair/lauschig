package io.wolftown.lauschig.stats

import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The achievements, family by family: exact thresholds, local time windows, replay dates, undated books, ties. */
class AchievementsTest {
    private val s = "srv"
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String, zone: ZoneId = berlin): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun d(text: String) = LocalDate.parse(text)

    private fun stat(
        id: String, start: String, listened: Double, item: String = "li_1", updated: String = start, duration: Double = 36000.0,
        current: Double = 100.0, startMs: Long = at(start),
    ) = StatSession(id, item, "T $item", "Autor", startMs, if (updated == start) startMs else at(updated), listened, duration, current)

    private fun evaluate(
        sessions: List<StatSession> = emptyList(),
        finished: List<FinishedBook> = emptyList(),
        groups: List<List<ItemEntity>> = emptyList(),
        items: Map<String, ItemEntity> = emptyMap(),
        zone: ZoneId = berlin,
    ) = Achievements.evaluate(sessions, finished, groups, items, zone).associateBy { it.achievement }

    private fun book(id: String, day: String?, progressDay: String? = null, finishedAt: Long? = null) =
        FinishedBook(id, "T $id", null, day?.let(::d), finishedAt = finishedAt, progressDay = progressDay?.let(::d))

    // --- catalogue ---------------------------------------------------------------------------------------------------

    @Test fun catalogue_idsAreStable_andTheTiersAsPlanned() {
        // Stored in achievement_seen: never renamed, never renumbered.
        assertEquals(
            listOf("hours", "streak", "books", "series", "marathon", "night_owl", "early_bird", "weekend", "one_go", "commuter", "genres"),
            Achievement.entries.map { it.id },
        )
        // The seen table stores a tier's index: tiers are append-only. Changing any of these must be a deliberate act.
        assertEquals(
            mapOf(
                "hours" to listOf(1, 10, 50, 100, 250, 500, 1000).map { it * 3600.0 },
                "streak" to listOf(3.0, 7.0, 30.0, 100.0, 365.0),
                "books" to listOf(1.0, 5.0, 10.0, 25.0, 50.0, 100.0),
                "series" to listOf(1.0, 5.0, 10.0),
                "marathon" to listOf(4 * 3600.0),
                "night_owl" to listOf(1.0),
                "early_bird" to listOf(1.0),
                "weekend" to listOf(5 * 3600.0),
                "one_go" to listOf(1.0),
                "commuter" to listOf(20.0),
                "genres" to listOf(5.0),
            ),
            Achievement.entries.associate { it.id to it.tiers },
        )
        assertEquals(28, Achievements.TOTAL_TIERS)
        // A progress line for every one that counts towards something; "Stufe x von y" only with more than one tier.
        assertEquals(setOf(Achievement.NIGHT_OWL, Achievement.EARLY_BIRD, Achievement.ONE_GO), Achievement.entries.filterNot { it.tiered }.toSet())
        assertEquals(setOf(Achievement.HOURS, Achievement.STREAK, Achievement.BOOKS, Achievement.SERIES), Achievement.entries.filter { it.leveled }.toSet())
        assertEquals(Achievement.NIGHT_OWL, Achievement.byId("night_owl"))
        assertNull(Achievement.byId("gone"))
        // Nothing listened: every one locked, in catalogue order.
        val none = Achievements.evaluate(emptyList(), emptyList(), emptyList(), emptyMap(), berlin)
        assertEquals(Achievement.entries.toList(), none.map { it.achievement })
        assertTrue(none.none { it.unlocked })
    }

    // --- hours -------------------------------------------------------------------------------------------------------

    @Test fun hours_aTierHoldsAtExactlyItsThreshold_onTheDayTheSumCrossedIt() {
        val just = evaluate(listOf(stat("1", "2026-10-01T10:00", 1800.0), stat("2", "2026-10-02T10:00", 1799.0))).getValue(Achievement.HOURS)
        assertFalse(just.unlocked)
        assertEquals(3599.0, just.value!!, 0.0)
        assertEquals(3600.0, just.nextTarget!!, 0.0)

        val hours = evaluate(
            listOf(
                stat("1", "2026-10-01T10:00", 1800.0),
                stat("2", "2026-10-02T10:00", 1799.0),
                stat("3", "2026-10-03T23:59", 1.0),        // the 3600th second: 1 h on 3 October
                stat("4", "2026-10-20T10:00", 32400.0),    // 10 h on 20 October
                stat("5", "2026-11-03T10:00", 3600.0),     // later listening never moves a date
            ),
        ).getValue(Achievement.HOURS)
        assertEquals(listOf(d("2026-10-03"), d("2026-10-20")), hours.reached)
        assertEquals(2, hours.tier)
        assertEquals(d("2026-10-20"), hours.unlockedAt)
        assertEquals(50 * 3600.0, hours.nextTarget!!, 0.0)
        assertEquals(11 * 3600.0, hours.value!!, 0.0)
        assertEquals(11f / 50f, hours.progress!!, 1e-6f)
    }

    @Test fun hours_manySmallSessions_addUpWithoutRoundingAwayTheTier() {
        // 36 000 sessions of 0.1 s are 3600 s; floating point must not leave it at 3599.99…
        val sessions = (0 until 36_000).map { i -> stat("s$i", "2026-10-01T10:00", 0.1, startMs = at("2026-10-01T10:00") + i) }
        assertTrue(evaluate(sessions).getValue(Achievement.HOURS).unlocked)
    }

    // --- streak ------------------------------------------------------------------------------------------------------

    @Test fun streak_theLongestRun_eachTierOnTheDayTheRunReachedIt_shortDaysBreakIt() {
        val sessions = listOf(
            stat("1", "2026-10-01T10:00", 60.0), stat("2", "2026-10-02T10:00", 60.0),
            stat("3", "2026-10-03T10:00", 59.0),                                          // under a minute: a gap
            stat("4", "2026-10-04T10:00", 60.0), stat("5", "2026-10-05T10:00", 60.0), stat("6", "2026-10-06T10:00", 60.0), // 3 in a row
        ) + (0 until 7).map { i -> stat("w$i", "2026-10-1${i}T10:00", 600.0) }               // 7 in a row from 10 October
        val streak = evaluate(sessions).getValue(Achievement.STREAK)

        assertEquals(listOf(d("2026-10-06"), d("2026-10-16")), streak.reached)
        assertEquals(7.0, streak.value!!, 0.0)
        assertEquals(30.0, streak.nextTarget!!, 0.0)
        assertFalse(evaluate(sessions.take(5)).getValue(Achievement.STREAK).unlocked)
    }

    // --- books -------------------------------------------------------------------------------------------------------

    @Test fun books_countEveryFinishedBook_progressOnlyOnesOnTheDayTheirProgressChanged_unknownLast() {
        val finished = listOf(
            book("a", "2026-03-01"), book("b", "2026-03-05"), book("c", "2026-03-02"),
            book("old", null, progressDay = "2025-01-10"), // only the progress says so: dated by its last change
            book("x", null),                                // not even that: counts, without a date
        )
        val books = evaluate(finished = finished).getValue(Achievement.BOOKS)

        // The 1st book was the old one; the 5th has no known date, so the 5-books tier has none.
        assertEquals(listOf(d("2025-01-10"), null), books.reached)
        assertTrue(books.unlocked)
        assertNull(books.unlockedAt)
        assertEquals(5.0, books.value!!, 0.0)
        assertEquals(listOf(d("2025-01-10")), evaluate(finished = finished.take(4)).getValue(Achievement.BOOKS).reached)
    }

    @Test fun books_fromTheStatistics_episodesNeverCount() {
        val sessions = listOf(
            stat("1", "2026-11-01T10:00", 100.0, item = "li_a", duration = 3600.0, current = 3600.0),
            stat("2", "2026-11-02T10:00", 100.0, item = "pod~e1", duration = 3600.0, current = 3600.0),
        )
        val progress = listOf(ProgressEntity(s, "pod~e2", 10.0, 10.0, isFinished = true, lastUpdate = at("2026-11-02T11:00")))
        val finished = ListeningStats.finishedBooks(sessions, progress, berlin)

        val books = evaluate(sessions, finished).getValue(Achievement.BOOKS)
        assertEquals(listOf(d("2026-11-01")), books.reached)
        assertEquals(1.0, books.value!!, 0.0)
    }

    // --- series ------------------------------------------------------------------------------------------------------

    @Test fun series_completeOnTheDayItsLastVolumeWasFinished() {
        fun vol(id: String, series: String, key: Double) = ItemEntity(s, id, "lib", id, seriesName = series, seriesSortKey = key)
        val groups = listOf(
            listOf(vol("a1", "A", 1.0), vol("a2", "A", 2.0)),
            listOf(vol("b1", "B", 1.0), vol("b2", "B", 2.0)),
            listOf(vol("c1", "C", 1.0), vol("c2", "C", 2.0)),
            listOf(vol("d1", "D", 1.0)), // one numbered volume is no series yet
        )
        val finished = listOf(
            book("a1", "2026-05-01"), book("a2", "2026-02-01"),            // A complete on 1 May
            book("b1", "2026-01-01"), book("b2", null, progressDay = "2026-03-01"), // B on 1 March
            book("c1", "2026-01-01"),                                       // C not complete
            book("d1", "2026-01-01"),
        )
        val series = evaluate(finished = finished, groups = groups).getValue(Achievement.SERIES)
        assertEquals(listOf(d("2026-03-01")), series.reached)
        assertEquals(2.0, series.value!!, 0.0)

        // A volume without any known date leaves its series without one.
        val undated = evaluate(finished = finished.filterNot { it.itemId == "a1" || it.itemId == "a2" || it.itemId == "b2" } + book("b2", null), groups = groups)
            .getValue(Achievement.SERIES)
        assertEquals(listOf<LocalDate?>(null), undated.reached)
    }

    // --- marathon and weekend ----------------------------------------------------------------------------------------

    @Test fun marathon_fourHoursOnOneDay_byTheDayASessionStarted() {
        val short = evaluate(listOf(stat("1", "2026-10-01T08:00", 7200.0), stat("2", "2026-10-01T20:00", 7199.0))).getValue(Achievement.MARATHON)
        assertFalse(short.unlocked)
        assertEquals(14399.0, short.value!!, 0.0)

        val marathon = evaluate(
            listOf(
                stat("1", "2026-10-01T08:00", 7200.0), stat("2", "2026-10-01T20:00", 7199.0),
                stat("3", "2026-10-05T23:00", 14400.0), // runs past midnight: the 5th, the day it started
                stat("4", "2026-10-09T08:00", 20000.0),
            ),
        ).getValue(Achievement.MARATHON)
        assertEquals(listOf(d("2026-10-05")), marathon.reached)
        assertEquals(20000.0, marathon.value!!, 0.0) // the best day
    }

    @Test fun weekend_fiveHoursOverASaturdayAndTheSundayAfter() {
        // 31 Oct 2026 is a Saturday.
        val split = evaluate(listOf(stat("1", "2026-10-31T10:00", 10800.0), stat("2", "2026-11-01T10:00", 7200.0))).getValue(Achievement.WEEKEND)
        assertEquals(listOf(d("2026-11-01")), split.reached) // held once Sunday added its 2 h

        val saturday = evaluate(listOf(stat("1", "2026-10-31T10:00", 18000.0))).getValue(Achievement.WEEKEND)
        assertEquals(listOf(d("2026-10-31")), saturday.reached)

        // Friday + Saturday and Sunday + Monday are no weekend.
        val notAWeekend = evaluate(
            listOf(
                stat("1", "2026-10-30T10:00", 10000.0), stat("2", "2026-10-31T10:00", 8000.0),
                stat("3", "2026-11-08T10:00", 9000.0), stat("4", "2026-11-09T10:00", 9000.0),
            ),
        ).getValue(Achievement.WEEKEND)
        assertFalse(notAWeekend.unlocked)
        assertEquals(9000.0, notAWeekend.value!!, 0.0) // the best weekend so far (8 Nov alone)
    }

    // --- time windows ------------------------------------------------------------------------------------------------

    @Test fun nightOwl_aSessionOfTenMinutesStartedBetweenMidnightAndFour_localTime() {
        fun owl(vararg sessions: StatSession, zone: ZoneId = berlin) = evaluate(sessions.toList(), zone = zone).getValue(Achievement.NIGHT_OWL)
        assertEquals(listOf(d("2026-10-02")), owl(stat("1", "2026-10-02T03:59", 600.0)).reached)
        assertEquals(listOf(d("2026-10-02")), owl(stat("1", "2026-10-02T00:00", 600.0)).reached)
        assertFalse(owl(stat("1", "2026-10-02T04:00", 600.0)).unlocked)
        assertFalse(owl(stat("1", "2026-10-02T23:59", 600.0)).unlocked)
        assertFalse(owl(stat("1", "2026-10-02T02:00", 599.0)).unlocked)
        assertNull(owl().value)

        // The device zone at the start, not UTC: 23:30 UTC on 1 July is 01:30 on 2 July in Berlin …
        val utcLate = Instant.parse("2026-07-01T23:30:00Z").toEpochMilli()
        assertEquals(listOf(d("2026-07-02")), owl(stat("1", "x", 600.0, startMs = utcLate)).reached)
        // … and 19:30 the day before in New York.
        assertFalse(owl(stat("1", "x", 600.0, startMs = utcLate), zone = ZoneId.of("America/New_York")).unlocked)
        // The day summer time starts (02:00 → 03:00): 01:30 UTC is 03:30 local, 02:30 UTC is already 04:30.
        assertTrue(owl(stat("1", "x", 600.0, startMs = Instant.parse("2026-03-29T01:30:00Z").toEpochMilli())).unlocked)
        assertFalse(owl(stat("1", "x", 600.0, startMs = Instant.parse("2026-03-29T02:30:00Z").toEpochMilli())).unlocked)
        // The day it ends, 02:30 happens twice; both are night.
        assertTrue(owl(stat("1", "x", 600.0, startMs = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli())).unlocked)
    }

    @Test fun earlyBird_aSessionOfTenMinutesStartedBetweenFourAndSix_theFirstOneDatesIt() {
        fun bird(vararg sessions: StatSession) = evaluate(sessions.toList()).getValue(Achievement.EARLY_BIRD)
        assertTrue(bird(stat("1", "2026-10-02T04:00", 600.0)).unlocked)
        assertTrue(bird(stat("1", "2026-10-02T05:59", 600.0)).unlocked)
        assertFalse(bird(stat("1", "2026-10-02T06:00", 600.0)).unlocked)
        assertFalse(bird(stat("1", "2026-10-02T03:59", 600.0)).unlocked)
        assertEquals(listOf(d("2026-09-01")), bird(stat("2", "2026-10-02T05:00", 900.0), stat("1", "2026-09-01T05:00", 600.0)).reached)
        // Spring forward: 02:30 UTC is 04:30 local – early, not night.
        assertTrue(bird(stat("1", "x", 600.0, startMs = Instant.parse("2026-03-29T02:30:00Z").toEpochMilli())).unlocked)
    }

    @Test fun commuter_twentyWeekdaySessionsStartedInTheMorningOrLateAfternoonWindow() {
        // 5 Oct 2026 is a Monday; four weeks of Mondays to Fridays.
        val weekdays = (0 until 28).map { LocalDate.of(2026, 10, 5).plusDays(it.toLong()) }.filter { it.dayOfWeek.value <= 5 }
        val morning = weekdays.take(10).mapIndexed { i, day -> stat("m$i", "${day}T08:59", 600.0) }
        val evening = weekdays.take(9).mapIndexed { i, day -> stat("e$i", "${day}T16:00", 1200.0) }
        val ignored = listOf(
            stat("x1", "2026-10-10T08:00", 1800.0), // Saturday
            stat("x2", "2026-10-06T09:00", 1800.0), // just after the morning window
            stat("x3", "2026-10-06T15:59", 1800.0), // just before the afternoon window
            stat("x4", "2026-10-06T19:00", 1800.0), // just after it
            stat("x5", "2026-10-06T07:00", 599.0),  // under ten minutes
        )
        val nineteen = evaluate(morning + evening + ignored).getValue(Achievement.COMMUTER)
        assertFalse(nineteen.unlocked)
        assertEquals(19.0, nineteen.value!!, 0.0)

        val twentieth = stat("last", "2026-10-30T18:59", 600.0)
        val commuter = evaluate(morning + evening + ignored + twentieth).getValue(Achievement.COMMUTER)
        assertEquals(listOf(d("2026-10-30")), commuter.reached)
    }

    @Test fun commuter_readsTheLocalStartAcrossDstChanges() {
        fun utc(text: String) = Instant.parse(text).toEpochMilli()
        val commuter = evaluate(
            listOf(
                stat("1", "x", 600.0, startMs = utc("2026-03-27T04:30:00Z")), // Friday before summer time: 05:30 – too early
                stat("2", "x", 600.0, startMs = utc("2026-03-30T04:30:00Z")), // Monday after: 06:30 – counts
                stat("3", "x", 600.0, startMs = utc("2026-03-29T05:30:00Z")), // the change day is a Sunday
                stat("4", "x", 600.0, startMs = utc("2026-10-23T15:30:00Z")), // Friday before winter time: 17:30 – counts
                stat("5", "x", 600.0, startMs = utc("2026-10-26T17:30:00Z")), // Monday after: 18:30 – counts (19:30 in summer)
                stat("6", "x", 600.0, startMs = utc("2026-10-26T04:30:00Z")), // Monday after: 05:30 – too early
            ),
        ).getValue(Achievement.COMMUTER)
        assertEquals(3.0, commuter.value!!, 0.0)
    }

    @Test fun weekendAndMarathon_countTheLocalDay_onTheShortAndTheLongDay() {
        fun utc(text: String) = Instant.parse(text).toEpochMilli()
        // 23:30 UTC on Saturday 28 March is 00:30 on Sunday in Berlin, the 23-hour day.
        val spring = listOf(
            stat("1", "x", 3 * 3600.0, startMs = utc("2026-03-28T09:00:00Z")),
            stat("2", "x", 2 * 3600.0, startMs = utc("2026-03-28T23:30:00Z")),
            stat("3", "x", 2 * 3600.0, startMs = utc("2026-03-29T01:30:00Z")), // 03:30 summer time, the same Sunday
        )
        val springStates = evaluate(spring)
        assertEquals(listOf(d("2026-03-29")), springStates.getValue(Achievement.WEEKEND).reached) // not on Saturday (UTC)
        assertEquals(listOf(d("2026-03-29")), springStates.getValue(Achievement.MARATHON).reached) // 4 h on the short day
        // The 25-hour day: 02:30 happens twice, both count for Sunday 25 October.
        val autumn = evaluate(
            listOf(
                stat("1", "x", 2 * 3600.0, startMs = utc("2026-10-25T00:30:00Z")), // 02:30 summer time
                stat("2", "x", 2 * 3600.0, startMs = utc("2026-10-25T01:30:00Z")), // 02:30 winter time
                stat("3", "x", 1 * 3600.0, startMs = utc("2026-10-24T21:00:00Z")), // 23:00 on Saturday
            ),
        )
        assertEquals(listOf(d("2026-10-25")), autumn.getValue(Achievement.MARATHON).reached)
        assertEquals(listOf(d("2026-10-25")), autumn.getValue(Achievement.WEEKEND).reached)
    }

    // --- one go ------------------------------------------------------------------------------------------------------

    @Test fun oneGo_aBookFinishedWithin72HoursOfItsFirstSession() {
        fun oneGo(firstStart: String, finishedAt: String) = evaluate(
            sessions = listOf(
                stat("0", "2026-09-01T10:00", 0.0, item = "li_a"), // opened, nothing heard: no start
                stat("1", firstStart, 600.0, item = "li_a"),
                stat("2", "2026-10-02T10:00", 600.0, item = "li_a"),
            ),
            finished = listOf(book("li_a", finishedAt.take(10), finishedAt = at(finishedAt))),
        ).getValue(Achievement.ONE_GO)

        assertEquals(listOf(d("2026-10-04")), oneGo("2026-10-01T10:00", "2026-10-04T10:00").reached) // exactly 72 h
        assertFalse(oneGo("2026-10-01T10:00", "2026-10-04T10:01").unlocked)
        assertNull(oneGo("2026-10-01T10:00", "2026-10-04T10:00").value)
        // Only the progress says it is finished: no moment, never in one go.
        val undated = evaluate(listOf(stat("1", "2026-10-01T10:00", 600.0, item = "li_b")), listOf(book("li_b", null, progressDay = "2026-10-01")))
        assertFalse(undated.getValue(Achievement.ONE_GO).unlocked)
    }

    // --- genres ------------------------------------------------------------------------------------------------------

    @Test fun genres_titlesOfFiveGenres_eachHeardHalfAnHour() {
        fun item(id: String, vararg genres: String) = id to ItemEntity(s, id, "lib", id, genres = genres.toList())
        val items = mapOf(
            item("a", "Krimi"), item("b", "krimi ", "Kinder"), item("c", "Fantasy"), item("d", "Sachbuch"),
            item("e", "Humor"), item("f", "Science-Fiction"), item("pod", "Wissen"),
        )
        val sessions = listOf(
            stat("1", "2026-10-01T10:00", 1800.0, item = "a"),        // Krimi
            stat("2", "2026-10-02T10:00", 1000.0, item = "b"),
            stat("3", "2026-10-03T10:00", 800.0, item = "b"),         // b reaches 30 min: Kinder (Krimi again, any case)
            stat("4", "2026-10-04T10:00", 1799.0, item = "e"),        // 29:59 – not yet
            stat("5", "2026-10-05T10:00", 1800.0, item = "pod~ep1"),  // a podcast by its own genres: Wissen
            stat("6", "2026-10-06T10:00", 1800.0, item = "c"),        // Fantasy: 4
            stat("7", "2026-10-07T10:00", 1800.0, item = "gone"),     // not in the catalog: no genre
            stat("8", "2026-10-08T10:00", 1.0, item = "e"),           // e reaches 30 min: Humor is the 5th
            stat("9", "2026-10-09T10:00", 1800.0, item = "f"),
        )
        val genres = evaluate(sessions, items = items).getValue(Achievement.GENRES)
        assertEquals(listOf(d("2026-10-08")), genres.reached)
        assertEquals(6.0, genres.value!!, 0.0)

        val four = evaluate(sessions.take(7), items = items).getValue(Achievement.GENRES)
        assertFalse(four.unlocked)
        assertEquals(4.0, four.value!!, 0.0)
    }

    // --- what the tiers depend on (the notice skips an evaluation when it did not move) --------------------------------

    private fun inputs(sessions: List<StatSession>, finished: List<FinishedBook> = emptyList(), items: Map<String, ItemEntity> = emptyMap(), zone: ZoneId = berlin) =
        Achievements.tierInputs(sessions, finished, seriesCount = 0, items = items, days = ListeningStats.days(sessions, zone), zone = zone)

    @Test fun tierInputs_aPlaybackTickOnACountedDay_movesNothing_crossingALineDoes() {
        val base = listOf(stat("1", "2026-11-01T10:00", 1000.0), stat("2", "2026-11-01T12:00", 300.0))
        fun with(second: Double) = listOf(base[0], stat("2", "2026-11-01T12:00", second))
        assertEquals(inputs(base), inputs(with(310.0)))
        assertTrue(inputs(base) != inputs(with(2600.0)))                          // 1 h in total
        assertTrue(inputs(base) != inputs(with(600.0)))                           // ten minutes: a time window may hold
        assertTrue(inputs(base) != inputs(base + stat("3", "2026-11-01T13:00", 5.0))) // a new session
        assertTrue(inputs(base) != inputs(base + stat("3", "2026-11-02T13:00", 60.0))) // a new day in the run
        assertTrue(inputs(base) != inputs(base, zone = ZoneId.of("America/New_York")))
        assertTrue(inputs(base) != inputs(base, finished = listOf(book("li_1", "2026-11-01", finishedAt = at("2026-11-01T13:00")))))
        // The same session id rewritten with another title or start time (a corrected row on the server).
        assertTrue(inputs(base) != inputs(listOf(base[0], stat("2", "2026-11-01T12:00", 300.0, item = "li_2"))))
        assertTrue(inputs(base) != inputs(listOf(base[0], stat("2", "2026-11-01T12:30", 300.0))))
    }

    @Test fun tierInputs_equal_meansTheSameTiersReached_overManyRandomTicks() {
        val random = java.util.Random(7)
        val items = mapOf("a" to ItemEntity(s, "a", "lib", "a", genres = listOf("Krimi")), "b" to ItemEntity(s, "b", "lib", "b", genres = listOf("Humor")))
        fun keys(list: List<StatSession>) = Achievements.evaluate(list, emptyList(), emptyList(), items, berlin).flatMap { it.keys }
        var sessions = (0 until 6).map { i -> stat("s$i", "2026-10-0${i + 1}T0${random.nextInt(10)}:00", random.nextInt(4000).toDouble(), item = if (i % 2 == 0) "a" else "b") }
        repeat(400) {
            val i = random.nextInt(sessions.size)
            val grown = sessions.mapIndexed { j, x -> if (j == i) x.copy(listenedSec = x.listenedSec + random.nextInt(900)) else x }
            if (inputs(grown, items = items) == inputs(sessions, items = items)) assertEquals(keys(sessions), keys(grown))
            sessions = grown
        }
    }

    // --- determinism and order ---------------------------------------------------------------------------------------

    @Test fun theSameData_alwaysTheSameDates_whateverTheOrder() {
        val sessions = (0 until 60).map { i -> stat("s$i", "2026-10-01T05:00", 700.0, item = "li_${i % 7}", startMs = at("2026-10-01T05:00") + i * 3_600_000L) } +
            // Two sessions starting at the same moment.
            listOf(stat("t1", "2026-10-02T01:00", 650.0), stat("t2", "2026-10-02T01:00", 650.0))
        val finished = listOf(book("li_1", "2026-10-02"), book("li_2", null, progressDay = "2026-09-01"))
        val first = Achievements.evaluate(sessions, finished, emptyList(), emptyMap(), berlin)
        repeat(5) { round ->
            val shuffled = sessions.shuffled(java.util.Random(round.toLong()))
            assertEquals(first, Achievements.evaluate(shuffled, finished.shuffled(java.util.Random(round.toLong())), emptyList(), emptyMap(), berlin))
        }
    }

    @Test fun sorted_recentlyUnlockedFirst_thenTheClosestToTheirNextTier_tiesInCatalogueOrder() {
        fun st(a: Achievement, vararg reached: String?, value: Double? = null) = AchievementState(a, reached.map { it?.let(::d) }, value)
        val sorted = Achievements.sorted(
            listOf(
                st(Achievement.HOURS, "2026-01-01", "2026-03-01", value = 40 * 3600.0),
                st(Achievement.STREAK, value = 2.0),                          // 2 of 3 days: closest of the locked ones
                st(Achievement.BOOKS, "2026-05-01", value = 1.0),
                st(Achievement.SERIES, null, value = 1.0),                    // reached, date unknown: after the dated ones
                st(Achievement.MARATHON, "2026-05-01", value = 14400.0),      // same day as BOOKS, complete
                st(Achievement.NIGHT_OWL),
                st(Achievement.EARLY_BIRD),
                st(Achievement.WEEKEND, value = 3600.0),                      // 1 of 5 h
                st(Achievement.COMMUTER, value = 4.0),                        // 4 of 20: same closeness as the weekend
            ),
        )
        assertEquals(
            listOf(
                Achievement.MARATHON, Achievement.BOOKS, Achievement.HOURS, Achievement.SERIES,
                Achievement.STREAK, Achievement.WEEKEND, Achievement.COMMUTER, Achievement.NIGHT_OWL, Achievement.EARLY_BIRD,
            ),
            sorted.map { it.achievement },
        )
    }

    @Test fun state_tiersProgressAndCompletion() {
        val done = AchievementState(Achievement.SERIES, listOf(d("2026-01-01"), d("2026-02-01"), d("2026-03-01")), 12.0)
        assertTrue(done.complete)
        assertNull(done.nextTarget)
        assertNull(done.progress)
        assertEquals(listOf(AchievementKey("series", 1), AchievementKey("series", 2), AchievementKey("series", 3)), done.keys)
        val owl = AchievementState(Achievement.NIGHT_OWL, emptyList(), null)
        assertNull(owl.progress)
        assertEquals(emptyList<AchievementKey>(), owl.keys)
        assertEquals(3, Achievements.unlockedTiers(listOf(done, owl)))
    }
}
