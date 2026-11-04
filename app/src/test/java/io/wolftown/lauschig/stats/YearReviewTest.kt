package io.wolftown.lauschig.stats

import io.wolftown.lauschig.db.entities.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

/** The year in review, slide by slide: numbers, empty slides left out, year bounds in the device zone, ties. */
class YearReviewTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val hour = 3600.0

    private fun at(text: String, zone: ZoneId = berlin): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private fun d(text: String) = LocalDate.parse(text)

    private var next = 0
    private fun stat(start: String, listened: Double, item: String = "li_1", author: String? = "Autor", title: String? = "T $item", startMs: Long = at(start)) =
        StatSession("s${next++}", item, title, author, startMs, startMs + (listened * 1000).toLong(), listened, 36000.0, 100.0)

    private fun build(
        sessions: List<StatSession>,
        year: Int = 2026,
        finished: List<FinishedBook> = emptyList(),
        groups: List<List<ItemEntity>> = emptyList(),
        achievements: List<AchievementState> = emptyList(),
        items: Map<String, ItemEntity> = emptyMap(),
        zone: ZoneId = berlin,
        incomplete: Boolean = false,
    ) = YearReview.build(year, sessions, finished, groups, achievements, items, zone, incomplete)

    private inline fun <reified T : ReviewSlide> YearReview.slide(): T? = slides.filterIsInstance<T>().firstOrNull()
    private fun YearReview.kinds(): List<KClass<out ReviewSlide>> = slides.map { it::class }

    /** One session a day from [from] for [count] days, at [time], each [sec] long. */
    private fun daily(from: String, count: Int, sec: Double, time: String = "20:00", item: String = "li_1", author: String? = "Autor") =
        (0 until count).map { stat("${d(from).plusDays(it.toLong())}T$time", sec, item, author) }

    // --- the review as a whole --------------------------------------------------------------------------------------

    @Test fun belowAnHour_thereIsNoReview_fromAnHourTheOpening_theAuthor_andTheClosing() {
        assertNull(build(listOf(stat("2026-03-01T10:00", hour - 1))))
        assertNull(build(emptyList()))
        val thin = build(listOf(stat("2026-03-01T10:00", hour)))!!
        // One day, one title: no days or rhythm (one day), no top titles (one title would only repeat the opening and the
        // closing), no pattern; the author has their half hour.
        assertEquals(listOf(ReviewSlide.Opening::class, ReviewSlide.TopPeople::class, ReviewSlide.Closing::class), thin.kinds())
        assertEquals(ReviewSlide.Opening(2026, hour, 1, incomplete = false), thin.slides.first())
        assertEquals("li_1", (thin.slides.last() as ReviewSlide.Closing).topTitle?.itemId)
    }

    @Test fun sessionsTheServerNoLongerNames_countNoTitles_andNoTopTitles() {
        val unnamed = build(listOf(stat("2026-03-01T10:00", hour, item = "", title = null)))!!
        assertEquals(0, (unnamed.slides.first() as ReviewSlide.Opening).titles)
        assertNull(unnamed.slide<ReviewSlide.TopTitles>())
        assertEquals(YearReview.OPENING_TITLE_SEC, ListeningStats.ACTIVE_DAY_SEC, 0.0)
    }

    @Test fun aSessionBelongsToTheYearOfItsLocalStartDay_inTheDeviceZone() {
        // 23:30 in Berlin on New Year's Eve is still 2026; 00:30 on 1 January is 2027 although it is 2026 in UTC.
        val eve = stat("2026-12-31T23:30", hour)
        val newYear = stat("2027-01-01T00:30", 2 * hour)
        assertEquals(2026, LocalDateTime.parse("2027-01-01T00:30").atZone(berlin).withZoneSameInstant(ZoneOffset.UTC).year)
        assertEquals(hour, (build(listOf(eve, newYear), 2026)!!.slides.first() as ReviewSlide.Opening).totalSec, 0.0)
        assertEquals(2 * hour, (build(listOf(eve, newYear), 2027)!!.slides.first() as ReviewSlide.Opening).totalSec, 0.0)
        // Read in UTC, both belong to 2026.
        assertEquals(3 * hour, (build(listOf(eve, newYear), 2026, zone = ZoneOffset.UTC)!!.slides.first() as ReviewSlide.Opening).totalSec, 0.0)
        assertNull(build(listOf(eve, newYear), 2027, zone = ZoneOffset.UTC))
    }

    @Test fun aYearBoundsAreLocal_alsoWhereDaylightSavingIsOnAtNewYear_andADstDayIsOneDay() {
        // Sydney is on summer time (UTC+11) at New Year: 00:30 on 1 January 2027 is 13:30 UTC on 31 December.
        val sydney = ZoneId.of("Australia/Sydney")
        val newYear = stat("2027-01-01T00:30", 2 * hour, startMs = at("2027-01-01T00:30", sydney))
        assertNull(build(listOf(newYear), 2026, zone = sydney))
        assertEquals(2 * hour, (build(listOf(newYear), 2027, zone = sydney)!!.slides.first() as ReviewSlide.Opening).totalSec, 0.0)
        assertEquals(2 * hour, (build(listOf(newYear), 2026, zone = ZoneOffset.UTC)!!.slides.first() as ReviewSlide.Opening).totalSec, 0.0)
        // Berlin's spring-forward day (29 March, 23 hours) and the autumn one (25 October, 25 hours) are one day each.
        val dst = listOf("2026-03-28T23:30", "2026-03-29T01:30", "2026-03-29T03:30", "2026-03-30T00:30", "2026-10-25T02:30", "2026-10-25T23:30")
            .map { stat(it, 1200.0) }
        val days = build(dst)!!.slide<ReviewSlide.Days>()!!
        assertEquals(ReviewSlide.Days(activeDays = 4, longestStreak = 3, streakEnd = d("2026-03-30")), days)
    }

    @Test fun theSlidesComeInTheirFixedOrder_whenEveryOneHasData() {
        val sessions = daily("2026-03-02", 10, 2 * hour, time = "01:00", item = "li_a", author = "Anna") +
            daily("2026-06-01", 3, hour, time = "10:00", item = "li_b", author = "Ben")
        val finished = listOf(FinishedBook("li_a", "A", "Anna", d("2026-03-11"), finishedAt = at("2026-03-11T23:50")))
        val hours = AchievementState(Achievement.HOURS, listOf(d("2026-03-02"), d("2026-03-06")), 23 * hour)
        val review = build(sessions, finished = finished, achievements = listOf(hours))!!
        assertEquals(
            listOf(
                ReviewSlide.Opening::class, ReviewSlide.Days::class, ReviewSlide.Rhythm::class, ReviewSlide.TopTitles::class,
                ReviewSlide.TopPeople::class, ReviewSlide.Finished::class, ReviewSlide.Achieved::class, ReviewSlide.Personality::class,
                ReviewSlide.Closing::class,
            ),
            review.kinds(),
        )
        assertEquals(
            ReviewSlide.Closing(2026, 23 * hour, 13, 1, TitleTime("li_a", "T li_a", "Anna", 20 * hour), "Anna", ListenerType.NIGHT_OWL),
            review.slides.last(),
        )
    }

    // --- opening ----------------------------------------------------------------------------------------------------

    @Test fun opening_countsTheTitlesHeardAMinute_andSaysWhenTheHistoryIsIncomplete() {
        val sessions = listOf(stat("2026-02-01T10:00", hour, "li_a"), stat("2026-02-02T10:00", 60.0, "li_b"), stat("2026-02-03T10:00", 59.0, "li_c"))
        val opening = build(sessions, incomplete = true)!!.slides.first()
        assertEquals(ReviewSlide.Opening(2026, hour + 119.0, titles = 2, incomplete = true), opening)
    }

    // --- days -------------------------------------------------------------------------------------------------------

    @Test fun days_activeDaysAndTheFirstOfTheLongestRuns_fromThreeDays() {
        val sessions = daily("2026-01-05", 3, 1200.0) + daily("2026-04-10", 3, 1200.0) + listOf(stat("2026-05-01T10:00", 30.0))
        val days = build(sessions)!!.slide<ReviewSlide.Days>()!!
        // 30 s on 1 May is no active day; two runs of three: the first one ends on 7 January.
        assertEquals(ReviewSlide.Days(activeDays = 6, longestStreak = 3, streakEnd = d("2026-01-07")), days)

        assertNull(build(daily("2026-01-05", 2, 1800.0))!!.slide<ReviewSlide.Days>())
        val apart = build(listOf("2026-01-05", "2026-01-07", "2026-01-09").map { stat("${it}T10:00", 1200.0) })!!
        assertEquals(ReviewSlide.Days(3, 1, streakEnd = null), apart.slide<ReviewSlide.Days>())
    }

    // --- rhythm -----------------------------------------------------------------------------------------------------

    @Test fun rhythm_needsTheDaysSlidesThreeDays_noRhythmOfTwoSessions() {
        // An hour in March and two in October, on two days: no days slide, so no rhythm either.
        val two = build(listOf(stat("2026-03-02T10:00", hour), stat("2026-10-05T10:00", hour), stat("2026-10-05T18:00", hour)))!!
        assertNull(two.slide<ReviewSlide.Days>())
        assertNull(two.slide<ReviewSlide.Rhythm>())
        // A third day brings both.
        val three = build(listOf(stat("2026-03-02T10:00", hour), stat("2026-10-05T10:00", hour), stat("2026-10-06T18:00", hour)))!!
        assertNotNull(three.slide<ReviewSlide.Days>())
        assertEquals(Month.OCTOBER, three.slide<ReviewSlide.Rhythm>()!!.topMonth)
    }

    @Test fun rhythm_needsTwoMonths_andAnHourInTheStrongest_noTopMonthOfTwoMinutes() {
        // All in one month: no "top month".
        assertNull(build(daily("2026-11-02", 6, 1200.0))!!.slide<ReviewSlide.Rhythm>())
        // Five minutes in every month: an hour in the year, yet no month reaches one.
        val spread = (1..12).map { stat("2026-${"%02d".format(it)}-10T10:00", 300.0) }
        assertNotNull(build(spread))
        assertNull(build(spread)!!.slide<ReviewSlide.Rhythm>())
        // A second month with only 59 s does not count as one.
        assertNull(build(daily("2026-11-02", 3, 1500.0) + stat("2026-10-02T10:00", 59.0))!!.slide<ReviewSlide.Rhythm>())
    }

    @Test fun rhythm_theStrongestMonth_tiesToTheEarlier_weekdayAndTimeOnlyWithFiveDays() {
        val sessions = daily("2026-03-02", 2, 1800.0, time = "19:00") + daily("2026-05-04", 2, 1800.0, time = "19:00")
        val rhythm = build(sessions)!!.slide<ReviewSlide.Rhythm>()!!
        assertEquals(Month.MARCH, rhythm.topMonth)
        assertEquals(hour, rhythm.topMonthSec, 0.0)
        assertEquals(12, rhythm.months.size)
        assertEquals(hour, rhythm.months[4], 0.0)
        // Four days are no pattern yet.
        assertNull(rhythm.weekday)
        assertNull(rhythm.time)

        val more = build(sessions + stat("2026-05-10T19:00", 1800.0))!!.slide<ReviewSlide.Rhythm>()!!
        assertEquals(Month.MAY, more.topMonth)
        // Monday 2 March, Tuesday 3 March, Monday 4 May, Tuesday 5 May, Sunday 10 May: equal – the earlier from Monday.
        assertEquals(DayOfWeek.MONDAY, more.weekday)
        assertEquals(ListeningTime.EVENING, more.time)
    }

    @Test fun rhythm_onTheWay_whenTheWeekdayCommuteHoursHoldHalf_inTheMorningOrBack() {
        // Monday 2 March … : 7:30 commute 40 min, 17:30 commute 30 min, Saturday 7 March 21:00 for an hour.
        val week = (0 until 5).flatMap { i ->
            val day = d("2026-03-02").plusDays(i.toLong())
            listOf(stat("${day}T07:30", 2400.0), stat("${day}T17:30", 1800.0))
        } + stat("2026-03-07T21:00", hour) + stat("2026-04-01T21:00", 120.0)
        assertEquals(ListeningTime.COMMUTE_MORNING, build(week)!!.slide<ReviewSlide.Rhythm>()!!.time)
        val homeward = week.map { if (localHour(it) == 7) it.copy(listenedSec = 600.0) else it }
        assertEquals(ListeningTime.COMMUTE_EVENING, build(homeward)!!.slide<ReviewSlide.Rhythm>()!!.time)
        // The same at the weekend is no commute: the morning wins as a part of the day.
        val weekend = (0 until 5).map { stat("${d("2026-03-07").plusDays(7L * it)}T07:30", 2400.0) } + stat("2026-04-01T21:00", 120.0)
        assertEquals(ListeningTime.MORNING, build(weekend)!!.slide<ReviewSlide.Rhythm>()!!.time)
        // Night: the night owl's hours, midnight to 4:00.
        val nights = (0 until 5).map { stat("${d("2026-03-02").plusDays(it.toLong())}T03:59", 1200.0) } + stat("2026-04-01T21:00", 120.0)
        assertEquals(ListeningTime.NIGHT, build(nights)!!.slide<ReviewSlide.Rhythm>()!!.time)
    }

    @Test fun theRhythmsWords_andTheTypes_useTheAchievementsWindows_oneStoryForOneHour() {
        assertEquals(Achievements.NIGHT_WINDOW, YearReview.DAY_PARTS.single { it.first == ListeningTime.NIGHT }.second)
        assertEquals(Achievements.EARLY_WINDOW.first, YearReview.DAY_PARTS.single { it.first == ListeningTime.MORNING }.second.first)
        fun at(time: String) = build(daily("2026-03-02", 5, hour, time = time) + stat("2026-04-01T13:00", 120.0))!!
        // 4:30: the early bird's hour – "mostly in the morning" and an early bird, never "at night".
        val early = at("04:30")
        assertEquals(ListeningTime.MORNING, early.slide<ReviewSlide.Rhythm>()!!.time)
        assertEquals(ListenerType.EARLY_BIRD, early.slide<ReviewSlide.Personality>()!!.type)
        // 1:00: night on both slides.
        val night = at("01:00")
        assertEquals(ListeningTime.NIGHT, night.slide<ReviewSlide.Rhythm>()!!.time)
        assertEquals(ListenerType.NIGHT_OWL, night.slide<ReviewSlide.Personality>()!!.type)
        // 23:00: the evening – and no night owl (the achievement counts from midnight too).
        val late = at("23:00")
        assertEquals(ListeningTime.EVENING, late.slide<ReviewSlide.Rhythm>()!!.time)
        assertNull(late.slide<ReviewSlide.Personality>())
        // 6:30 on weekdays: the commute (with the achievement's windows), not the early bird.
        val commute = at("06:30")
        assertEquals(ListeningTime.COMMUTE_MORNING, commute.slide<ReviewSlide.Rhythm>()!!.time)
        assertEquals(ListenerType.COMMUTER, commute.slide<ReviewSlide.Personality>()!!.type)
    }

    private fun localHour(s: StatSession) = java.time.Instant.ofEpochMilli(s.startedAt).atZone(berlin).hour

    // --- top titles and people --------------------------------------------------------------------------------------

    @Test fun topTitles_fiveAtMost_fromTenMinutes_episodesUnderTheirPodcast() {
        val sessions = listOf(
            stat("2026-02-01T10:00", 3000.0, "li_a"),
            stat("2026-02-02T10:00", 300.0, "li_pod~ep1"),
            stat("2026-02-03T10:00", 300.0, "li_pod~ep2"),
            stat("2026-02-04T10:00", 599.0, "li_short"),
            stat("2026-02-05T10:00", 900.0, "li_b"),
            stat("2026-02-06T10:00", 900.0, "li_c"),
            stat("2026-02-07T10:00", 700.0, "li_d"),
            stat("2026-02-08T10:00", 650.0, "li_e"),
        )
        val top = build(sessions)!!.slide<ReviewSlide.TopTitles>()!!.titles
        assertEquals(listOf("li_a", "li_b", "li_c", "li_d", "li_e"), top.map { it.itemId })
        // Ties by name; the podcast's two episodes (600 s together) would be sixth.
        assertEquals(900.0, top[1].seconds, 0.0)
        val podcast = build(sessions.filter { it.itemKey.startsWith("li_pod") } + stat("2026-02-09T10:00", 3000.0, "li_a"))!!
        assertEquals(listOf("li_a", "li_pod"), podcast.slide<ReviewSlide.TopTitles>()!!.titles.map { it.itemId })
    }

    @Test fun topPeople_fromHalfAnHour_severalAuthorsSplit_narratorFromTheCatalog_notTheAuthorAgain() {
        val items = mapOf(
            "li_a" to ItemEntity("srv", "li_a", "lib", "A", narrator = "Nora"),
            "li_b" to ItemEntity("srv", "li_b", "lib", "B", narrator = "Anna"),
        )
        val sessions = listOf(
            stat("2026-02-01T10:00", 2400.0, "li_a", author = "Anna, Ben"),
            stat("2026-02-02T10:00", 1800.0, "li_b", author = "Anna"),
        )
        val people = build(sessions, items = items)!!.slide<ReviewSlide.TopPeople>()!!
        assertEquals(AuthorTime("Anna", 4200.0), people.author)
        assertEquals("li_a", people.authorItemId)
        assertEquals(AuthorTime("Nora", 2400.0), people.narrator)

        // The narrator heard most is the author herself: no second line.
        val self = build(sessions, items = items + ("li_a" to items.getValue("li_a").copy(narrator = "Anna")))!!.slide<ReviewSlide.TopPeople>()!!
        assertNull(self.narrator)
        // Without the catalog no narrator; below half an hour no author.
        assertNull(build(sessions)!!.slide<ReviewSlide.TopPeople>()!!.narrator)
        assertNull(build(listOf(stat("2026-02-01T10:00", 1799.0, author = "Anna"), stat("2026-02-02T10:00", 1801.0, author = null)))!!.slide<ReviewSlide.TopPeople>())
    }

    // --- finished ---------------------------------------------------------------------------------------------------

    @Test fun finished_datedBooksOfTheYear_firstAndLast_andTheSeriesCompletedInIt() {
        val sessions = listOf(stat("2026-02-01T10:00", 2 * hour))
        val first = FinishedBook("li_1", "One", null, d("2026-01-10"), finishedAt = at("2026-01-10T20:00"))
        val last = FinishedBook("li_2", "Two", null, d("2026-11-03"), finishedAt = at("2026-11-03T08:00"))
        val middle = FinishedBook("li_3", "Three", null, d("2026-06-01"), finishedAt = at("2026-06-01T08:00"))
        val lastYear = FinishedBook("li_4", "Four", null, d("2025-12-31"), finishedAt = at("2025-12-31T22:00"))
        val undated = FinishedBook("li_5", "Five", null, null, progressDay = d("2026-05-05"))
        val vol = { id: String, n: String -> ItemEntity("srv", id, "lib", id, seriesName = "S", seriesSequence = n) }
        val series = listOf(vol("li_2", "1"), vol("li_5", "2"))
        val oldSeries = listOf(vol("li_4", "1"), vol("li_1", "2")).map { it.copy(seriesName = "Old") }
        val review = build(sessions, finished = listOf(last, middle, first, lastYear, undated), groups = listOf(series, oldSeries))!!
        // The undated book counts for its series (as the achievement dates it), not as a book of the year; the series
        // completed last ends with "Two" (3 November).
        assertEquals(ReviewSlide.Finished(books = 3, series = 2, first = first, last = last, seriesItemId = "li_2"), review.slide<ReviewSlide.Finished>())

        val one = build(sessions, finished = listOf(middle))!!.slide<ReviewSlide.Finished>()!!
        assertEquals(middle, one.first)
        assertEquals(middle, one.last)
        assertNull(build(sessions, finished = listOf(lastYear, undated))!!.slide<ReviewSlide.Finished>())
    }

    @Test fun finished_aSeriesCompletedByProgressDatedBooksAlone_stillHasItsSlide() {
        val sessions = listOf(stat("2026-02-01T10:00", 2 * hour))
        val vol = { id: String, n: String -> ItemEntity("srv", id, "lib", id, seriesName = "S", seriesSequence = n, seriesSortKey = n.toDouble()) }
        val one = FinishedBook("v1", "Eins", null, null, progressDay = d("2026-04-01"))
        val two = FinishedBook("v2", "Zwei", null, null, progressDay = d("2026-06-01"))
        val slide = build(sessions, finished = listOf(one, two), groups = listOf(listOf(vol("v1", "1"), vol("v2", "2"))))!!.slide<ReviewSlide.Finished>()
        assertEquals(ReviewSlide.Finished(books = 0, series = 1, first = null, last = null, seriesItemId = "v2"), slide)
        // A volume without any day: the series has no day, so no year.
        val dayless = FinishedBook("v2", "Zwei", null, null, progressDay = null)
        assertNull(build(sessions, finished = listOf(one, dayless), groups = listOf(listOf(vol("v1", "1"), vol("v2", "2"))))!!.slide<ReviewSlide.Finished>())
    }

    // --- achievements -----------------------------------------------------------------------------------------------

    @Test fun achieved_theAchievementsOfTheYear_eachOnceAtItsHighestTier_newestFirst_sixShown() {
        val states = listOf(
            AchievementState(Achievement.HOURS, listOf(d("2025-12-01"), d("2026-02-01"), d("2026-08-01")), 60 * hour),
            AchievementState(Achievement.STREAK, listOf(d("2026-03-01")), 3.0),
            AchievementState(Achievement.BOOKS, listOf(null), 1.0), // reached without a day: no year
            AchievementState(Achievement.MARATHON, listOf(d("2026-08-01")), 5 * hour),
            AchievementState(Achievement.NIGHT_OWL, listOf(d("2026-01-02")), null),
            AchievementState(Achievement.EARLY_BIRD, listOf(d("2026-01-03")), null),
            AchievementState(Achievement.WEEKEND, listOf(d("2026-01-04")), 6 * hour),
            AchievementState(Achievement.ONE_GO, listOf(d("2026-01-05")), null),
            AchievementState(Achievement.GENRES, emptyList(), 2.0),
        )
        val achieved = build(listOf(stat("2026-02-01T10:00", 2 * hour)), achievements = states)!!.slide<ReviewSlide.Achieved>()!!
        // Hours (10 and 50 h this year: once, at its highest), streak, marathon, four single ones: 7 achievements; the
        // grid shows six, "1 more".
        assertEquals(7, achieved.count)
        assertEquals(1, achieved.more)
        assertEquals(
            listOf(
                ReachedTier(Achievement.HOURS, 3, d("2026-08-01")),
                ReachedTier(Achievement.MARATHON, 1, d("2026-08-01")),
                ReachedTier(Achievement.STREAK, 1, d("2026-03-01")),
                ReachedTier(Achievement.ONE_GO, 1, d("2026-01-05")),
                ReachedTier(Achievement.WEEKEND, 1, d("2026-01-04")),
                ReachedTier(Achievement.EARLY_BIRD, 1, d("2026-01-03")),
            ),
            achieved.shown,
        )
        assertNull(build(listOf(stat("2026-02-01T10:00", 2 * hour)), achievements = states.take(1).map { it.copy(reached = it.reached.take(1)) })!!.slide<ReviewSlide.Achieved>())
    }

    // --- personality ------------------------------------------------------------------------------------------------

    @Test fun personality_needsFiveHoursOnFiveDays_andAPatternAboveItsShare() {
        // Every evening at 20:00: no pattern reaches its share.
        assertNull(build(daily("2026-03-02", 10, hour))!!.slide<ReviewSlide.Personality>())
        // Night owl: 1:00 every night – but four nights are no pattern yet.
        assertNull(build(daily("2026-03-02", 4, 2 * hour, time = "01:00"))!!.slide<ReviewSlide.Personality>())
        // Five nights of 59 minutes: not five hours.
        assertNull(build(daily("2026-03-02", 5, 59 * 60.0, time = "01:00"))!!.slide<ReviewSlide.Personality>())
        val owl = build(daily("2026-03-02", 5, hour, time = "01:00"))!!.slide<ReviewSlide.Personality>()!!
        assertEquals(ReviewSlide.Personality(ListenerType.NIGHT_OWL, 1.0, 0), owl)
        // Started at 3:59 is still night; 4:00 is early (as the achievements count them).
        assertEquals(ListenerType.NIGHT_OWL, build(daily("2026-03-02", 5, hour, time = "03:59"))!!.slide<ReviewSlide.Personality>()!!.type)
        assertEquals(ListenerType.EARLY_BIRD, build(daily("2026-03-07", 5, hour, time = "04:00"))!!.slide<ReviewSlide.Personality>()!!.type)
    }

    @Test fun personality_theLargestShareAboveItsThresholdWins_tiesInTheTypesOrder() {
        // Weekend: Saturdays 14:00, 2 h each (8 h of 10 h = 80 %); weekday evenings 2 h.
        val weekend = (0 until 4).map { stat("${d("2026-03-07").plusDays(7L * it)}T14:00", 2 * hour) } +
            daily("2026-03-09", 2, hour)
        assertEquals(ListenerType.WEEKEND, build(weekend)!!.slide<ReviewSlide.Personality>()!!.type)
        // Night 45 % (1.5 times its 30 %) against weekend 55 % (1.1 times its 50 %): the larger share wins, the weekend.
        val larger = daily("2026-03-02", 5, 1.8 * hour, time = "01:00") + // Monday to Friday nights: 9 h
            listOf(stat("2026-03-07T14:00", 2.75 * hour), stat("2026-03-08T14:00", 2.75 * hour), stat("2026-03-14T14:00", 2.75 * hour), stat("2026-03-15T14:00", 2.75 * hour))
        val type = build(larger)!!.slide<ReviewSlide.Personality>()!!
        assertEquals(ListenerType.WEEKEND, type.type)
        assertEquals(0.55, type.share, 1e-9)
        // Equal shares: the night owl comes first.
        val tie = daily("2026-03-02", 5, hour, time = "01:00") + listOf(stat("2026-03-07T14:00", 2.5 * hour), stat("2026-03-08T14:00", 2.5 * hour))
        assertEquals(ReviewSlide.Personality(ListenerType.NIGHT_OWL, 0.5, 0), build(tie)!!.slide<ReviewSlide.Personality>())
        // Marathon: days of three hours hold 40 %; the slide says on how many days.
        val marathon = daily("2026-05-04", 2, 3 * hour, time = "13:00") + daily("2026-05-11", 4, 1.5 * hour, time = "13:00")
        assertEquals(ReviewSlide.Personality(ListenerType.MARATHON, 0.5, 2), build(marathon)!!.slide<ReviewSlide.Personality>())
    }

    @Test fun personality_longSaturdays_areAWeekendListener_notAMarathon() {
        // Six Saturdays of four hours: weekend and marathon both hold everything – the weekend comes first.
        val saturdays = (0 until 6).map { stat("${d("2026-03-07").plusDays(7L * it)}T14:00", 4 * hour) }
        assertEquals(ReviewSlide.Personality(ListenerType.WEEKEND, 1.0, 0), build(saturdays)!!.slide<ReviewSlide.Personality>())
        // Long weekdays besides: then the long days hold more than the weekend.
        val weekdays = saturdays + daily("2026-04-06", 4, 4 * hour, time = "13:00")
        assertEquals(ListenerType.MARATHON, build(weekdays)!!.slide<ReviewSlide.Personality>()!!.type)
    }

    @Test fun offeredYear_fromDecemberToJanuary_theYearThatEnds() {
        assertEquals(2026, YearReview.offeredYear(d("2026-12-01")))
        assertEquals(2026, YearReview.offeredYear(d("2026-12-31")))
        assertEquals(2026, YearReview.offeredYear(d("2027-01-01")))
        assertEquals(2026, YearReview.offeredYear(d("2027-01-31")))
        assertNull(YearReview.offeredYear(d("2026-11-30")))
        assertNull(YearReview.offeredYear(d("2027-02-01")))
    }

    @Test fun yearTotal_sumsTheDaysOfThatYear() {
        val days = mapOf(d("2025-12-31") to 100.0, d("2026-01-01") to 200.0, d("2026-12-31") to 300.0, d("2027-01-01") to 400.0)
        assertEquals(500.0, YearReview.yearTotal(days, 2026), 0.0)
        assertEquals(0.0, YearReview.yearTotal(emptyMap(), 2026), 0.0)
        assertTrue(YearReview.yearTotal(days, 2027) > 0)
        assertFalse(YearReview.yearTotal(days, 2024) > 0)
    }
}
