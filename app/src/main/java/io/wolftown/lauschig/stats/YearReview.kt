package io.wolftown.lauschig.stats

import androidx.compose.runtime.Immutable
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.entities.ItemEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId

/** When the user mostly listens (the rhythm slide's line): a part of the day, or on the way (weekday commute hours). */
enum class ListeningTime { MORNING, AFTERNOON, EVENING, NIGHT, COMMUTE_MORNING, COMMUTE_EVENING }

/** The personality slide's types, in the order a tie is decided. */
enum class ListenerType { NIGHT_OWL, EARLY_BIRD, COMMUTER, WEEKEND, MARATHON }

/** One tier reached in the year ([Achievement] tiers are 1-based). */
@Immutable
data class ReachedTier(val achievement: Achievement, val tier: Int, val day: LocalDate)

/** One slide of the year in review; only slides with enough data exist (see [YearReview.build]). */
@Immutable
sealed interface ReviewSlide {
    /** „Dein Hörjahr 2026“: the year's listening and how many titles; [incomplete]: not every session is loaded yet. */
    data class Opening(val year: Int, val totalSec: Double, val titles: Int, val incomplete: Boolean) : ReviewSlide

    /** Days with listening and the longest run of them, which ended on [streakEnd] (null for a run below two days). */
    data class Days(val activeDays: Int, val longestStreak: Int, val streakEnd: LocalDate?) : ReviewSlide

    /**
     * The year's months ([months]: seconds of January to December), the strongest one, the favourite weekday and when
     * in the day (both null with too few days for a pattern).
     */
    data class Rhythm(val months: List<Double>, val topMonth: Month, val weekday: DayOfWeek?, val time: ListeningTime?) : ReviewSlide {
        val topMonthSec: Double get() = months[topMonth.value - 1]
    }

    /** The most heard titles (at most [YearReview.TOP_TITLES]), each with its time. */
    data class TopTitles(val titles: List<TitleTime>) : ReviewSlide

    /** The most heard author (with their most heard title, [authorItemId]) and, where the catalog knows it, narrator. */
    data class TopPeople(val author: AuthorTime, val authorItemId: String?, val narrator: AuthorTime?) : ReviewSlide

    /** Books finished in the year, series completed in it, the first and the last finished book (the same for one). */
    data class Finished(val books: Int, val series: Int, val first: FinishedBook, val last: FinishedBook) : ReviewSlide

    /** Tiers reached in the year ([tiers] in all) and up to [YearReview.ACHIEVEMENTS_SHOWN] of them, newest first. */
    data class Achieved(val tiers: Int, val shown: List<ReachedTier>) : ReviewSlide

    /** The strongest pattern: [share] of the year's time it holds; [days] with a marathon (for [ListenerType.MARATHON]). */
    data class Personality(val type: ListenerType, val share: Double, val days: Int) : ReviewSlide

    /** Everything in one view; parts without their slide's data are null (or 0). */
    data class Closing(
        val year: Int,
        val totalSec: Double,
        val activeDays: Int,
        val finished: Int,
        val topTitle: TitleTime?,
        val topAuthor: String?,
        val type: ListenerType?,
    ) : ReviewSlide
}

/** A year in review: its slides in their fixed order. */
@Immutable
data class YearReview(val year: Int, val slides: List<ReviewSlide>) {
    companion object {
        /** The review exists from one hour of listening in the year (the entry points offer it then). */
        const val MIN_YEAR_SEC = 3600.0

        /** The days slide needs this many days with listening. */
        const val MIN_DAYS = 3

        /** The weekday and the time of day need this many days with listening (fewer is no pattern). */
        const val PATTERN_DAYS = 5

        /** A title counts among the top titles from ten minutes. */
        const val TITLE_MIN_SEC = 600.0
        const val TOP_TITLES = 5

        /** An author or narrator is named from half an hour. */
        const val PERSON_MIN_SEC = 1800.0

        /** The strongest month needs an hour (and a second month with listening). */
        const val MONTH_MIN_SEC = 3600.0

        const val ACHIEVEMENTS_SHOWN = 6

        /** A personality needs five hours on [PATTERN_DAYS] days. */
        const val PERSONALITY_MIN_SEC = 5 * 3600.0

        /** A marathon day (for the personality): three hours. */
        const val MARATHON_DAY_SEC = 3 * 3600.0

        /** Night: sessions started in [22:00, 04:00). */
        val NIGHT: Pair<LocalTime, LocalTime> = LocalTime.of(22, 0) to LocalTime.of(4, 0)

        /** Early: sessions started in [04:00, 07:00). */
        val EARLY: Pair<LocalTime, LocalTime> = LocalTime.of(4, 0) to LocalTime.of(7, 0)

        /** The share of the year's time each type needs; the type that exceeds its own the most wins. */
        val PERSONALITY_SHARE: Map<ListenerType, Double> = mapOf(
            ListenerType.NIGHT_OWL to 0.30,
            ListenerType.EARLY_BIRD to 0.25,
            ListenerType.COMMUTER to 0.40,
            ListenerType.WEEKEND to 0.50,
            ListenerType.MARATHON to 0.40,
        )

        /** "Mostly on the way" when the weekday commute hours hold this share. */
        const val COMMUTE_SHARE = 0.5

        private val people = Catalog()

        /**
         * The year the Start screen offers to review on [today]: from 1 December the year that is ending, until
         * 31 January the one that has just ended; null otherwise.
         */
        fun offeredYear(today: LocalDate): Int? = when (today.month) {
            Month.DECEMBER -> today.year
            Month.JANUARY -> today.year - 1
            else -> null
        }

        /** The listening of [year] in [days] (local dates of the session starts, see [ListeningStats.days]). */
        fun yearTotal(days: Map<LocalDate, Double>, year: Int): Double = days.entries.sumOf { (day, sec) -> if (day.year == year) sec else 0.0 }

        /**
         * The review of [year], or null below [MIN_YEAR_SEC]. A session counts for the year of its start's local date in
         * [zone] (as the statistics count days); books by their dated finish ([FinishedBook.day], as the Stats year),
         * series and achievements by the day the achievements give them. Pure, for tests.
         */
        fun build(
            year: Int,
            sessions: List<StatSession>,
            finished: List<FinishedBook>,
            seriesGroups: List<List<ItemEntity>>,
            achievements: List<AchievementState>,
            items: Map<String, ItemEntity>,
            zone: ZoneId,
            incomplete: Boolean = false,
        ): YearReview? {
            val inYear = sessions.filter { ListeningStats.dayOf(it.startedAt, zone).year == year }
            val days = ListeningStats.days(inYear, zone)
            val total = days.values.sum()
            if (total < MIN_YEAR_SEC) return null
            val activeDays = days.count { it.value >= ListeningStats.ACTIVE_DAY_SEC }

            val perTitle = titles(inYear)
            val topTitles = perTitle.filter { it.seconds >= TITLE_MIN_SEC }.take(TOP_TITLES)
            val author = topAuthor(inYear)
            val people = author?.let { a ->
                ReviewSlide.TopPeople(a, authorItem(inYear, a.name), topNarrator(inYear, items)?.takeIf { !it.name.equals(a.name, ignoreCase = true) })
            }
            val books = finished.filter { it.day?.year == year && it.finishedAt != null }.sortedBy { it.finishedAt }
            val seriesDone = seriesCompletedIn(year, seriesGroups, finished)
            val reached = reachedIn(year, achievements)
            val personality = personality(inYear, days, zone, activeDays)

            val slides = buildList {
                add(ReviewSlide.Opening(year, total, perTitle.count { it.seconds >= ListeningStats.ACTIVE_DAY_SEC }, incomplete))
                if (activeDays >= MIN_DAYS) add(daysSlide(days))
                rhythm(inYear, days, zone, activeDays)?.let(::add)
                if (topTitles.isNotEmpty()) add(ReviewSlide.TopTitles(topTitles))
                people?.let(::add)
                if (books.isNotEmpty()) add(ReviewSlide.Finished(books.size, seriesDone, books.first(), books.last()))
                if (reached.isNotEmpty()) add(ReviewSlide.Achieved(reached.size, shownTiers(reached)))
                personality?.let(::add)
                add(
                    ReviewSlide.Closing(
                        year, total, activeDays, books.size, topTitles.firstOrNull(), people?.author?.name, personality?.type,
                    ),
                )
            }
            return YearReview(year, slides)
        }

        /** Every title of the year (a book, or a podcast with its episodes), most heard first; ties by name. */
        private fun titles(inYear: List<StatSession>): List<TitleTime> =
            inYear.filter { it.itemKey.isNotEmpty() }.groupBy { it.itemId }
                .map { (id, list) ->
                    val named = list.maxBy { it.startedAt }
                    TitleTime(id, named.title, named.author, list.sumOf { it.listenedSec })
                }
                .sortedWith(compareByDescending<TitleTime> { it.seconds }.thenBy { it.title?.lowercase() ?: it.itemId })

        /** Seconds per person (several authors split like the catalog), the most heard first; ties by name. */
        private fun ranked(inYear: List<StatSession>, names: (StatSession) -> String?): List<AuthorTime> {
            val out = HashMap<String, Pair<String, Double>>()
            for (s in inYear) for (name in people.splitPeople(names(s))) {
                val key = name.lowercase()
                val (shown, sec) = out[key] ?: (name to 0.0)
                out[key] = shown to sec + s.listenedSec
            }
            return out.values.map { AuthorTime(it.first, it.second) }
                .sortedWith(compareByDescending<AuthorTime> { it.seconds }.thenBy { it.name.lowercase() })
        }

        private fun topAuthor(inYear: List<StatSession>): AuthorTime? =
            ranked(inYear) { it.author }.firstOrNull()?.takeIf { it.seconds >= PERSON_MIN_SEC }

        /** The narrator from the catalog (sessions do not name one); null where it knows none. */
        private fun topNarrator(inYear: List<StatSession>, items: Map<String, ItemEntity>): AuthorTime? =
            ranked(inYear) { s -> items[s.itemId]?.narrator }.firstOrNull()?.takeIf { it.seconds >= PERSON_MIN_SEC }

        /** The author's most heard title of the year (the slide's cover). */
        private fun authorItem(inYear: List<StatSession>, author: String): String? =
            inYear.filter { s -> s.itemKey.isNotEmpty() && people.splitPeople(s.author).any { it.equals(author, ignoreCase = true) } }
                .groupBy { it.itemId }.maxByOrNull { (_, list) -> list.sumOf { it.listenedSec } }?.key

        private fun daysSlide(days: Map<LocalDate, Double>): ReviewSlide.Days {
            val runs = ListeningStats.runLengths(days)
            // The longest run; of equal ones the first.
            val best = runs.fold<Pair<LocalDate, Int>, Pair<LocalDate, Int>?>(null) { acc, r -> if (acc == null || r.second > acc.second) r else acc }
            val longest = best?.second ?: 0
            return ReviewSlide.Days(runs.size, longest, best?.first?.takeIf { longest >= 2 })
        }

        private fun localStart(s: StatSession, zone: ZoneId) = Instant.ofEpochMilli(s.startedAt).atZone(zone)

        private operator fun Pair<LocalTime, LocalTime>.contains(t: LocalTime): Boolean =
            if (first <= second) !t.isBefore(first) && t.isBefore(second) else !t.isBefore(first) || t.isBefore(second)

        private fun commuting(s: StatSession, zone: ZoneId): Pair<LocalTime, LocalTime>? {
            val start = localStart(s, zone)
            if (start.dayOfWeek.value > DayOfWeek.FRIDAY.value) return null
            return Achievements.COMMUTE_WINDOWS.firstOrNull { start.toLocalTime() in it }
        }

        private fun rhythm(inYear: List<StatSession>, days: Map<LocalDate, Double>, zone: ZoneId, activeDays: Int): ReviewSlide.Rhythm? {
            val months = (1..12).map { m -> days.entries.sumOf { (d, sec) -> if (d.monthValue == m) sec else 0.0 } }
            if (months.count { it >= ListeningStats.ACTIVE_DAY_SEC } < 2) return null
            val top = months.indices.maxBy { months[it] } // the first of equal ones
            if (months[top] < MONTH_MIN_SEC) return null
            val pattern = activeDays >= PATTERN_DAYS
            return ReviewSlide.Rhythm(
                months, Month.of(top + 1),
                weekday = if (pattern) favouriteWeekday(days) else null,
                time = if (pattern) listeningTime(inYear, zone) else null,
            )
        }

        /** The weekday with the most listening; ties: the earlier one from Monday. */
        private fun favouriteWeekday(days: Map<LocalDate, Double>): DayOfWeek {
            val per = DoubleArray(7)
            for ((d, sec) in days) per[d.dayOfWeek.value - 1] += sec
            return DayOfWeek.of(per.indices.maxBy { per[it] } + 1)
        }

        /**
         * On the way when the weekday commute hours ([Achievements.COMMUTE_WINDOWS]) hold [COMMUTE_SHARE] – in the
         * morning or on the way back, whichever holds more; otherwise the part of the day with the most listening
         * (morning 5–12, afternoon 12–17, evening 17–22, night 22–5; ties in that order). By each session's start.
         */
        private fun listeningTime(inYear: List<StatSession>, zone: ZoneId): ListeningTime {
            val total = inYear.sumOf { it.listenedSec }
            val commute = HashMap<Pair<LocalTime, LocalTime>, Double>()
            val parts = DoubleArray(4)
            for (s in inYear) {
                commuting(s, zone)?.let { commute.merge(it, s.listenedSec, Double::plus) }
                val h = localStart(s, zone).hour
                parts[when (h) { in 5..11 -> 0; in 12..16 -> 1; in 17..21 -> 2; else -> 3 }] += s.listenedSec
            }
            if (total > 0 && commute.values.sum() >= COMMUTE_SHARE * total) {
                val (morning, evening) = Achievements.COMMUTE_WINDOWS
                return if ((commute[morning] ?: 0.0) >= (commute[evening] ?: 0.0)) ListeningTime.COMMUTE_MORNING else ListeningTime.COMMUTE_EVENING
            }
            return listOf(ListeningTime.MORNING, ListeningTime.AFTERNOON, ListeningTime.EVENING, ListeningTime.NIGHT)[parts.indices.maxBy { parts[it] }]
        }

        /** Series whose last volume was finished in [year], dated as the "series" achievement dates them. */
        private fun seriesCompletedIn(year: Int, groups: List<List<ItemEntity>>, finished: List<FinishedBook>): Int {
            val dayOf = finished.associate { it.itemId to (it.day ?: it.progressDay) }
            return ListeningStats.finishedSeriesGroups(groups, dayOf.keys).count { g ->
                val volumes = g.map { dayOf[it.id] }
                null !in volumes && volumes.maxOf { it!! }.year == year
            }
        }

        /** Every tier reached on a day of [year] (a tier without a day belongs to no year). */
        private fun reachedIn(year: Int, states: List<AchievementState>): List<ReachedTier> = states.flatMap { s ->
            s.reached.mapIndexedNotNull { i, day -> day?.takeIf { it.year == year }?.let { ReachedTier(s.achievement, i + 1, it) } }
        }

        /** Per achievement its highest tier of the year; the newest first (same day: catalogue order). */
        private fun shownTiers(reached: List<ReachedTier>): List<ReachedTier> =
            reached.groupBy { it.achievement }.map { (_, tiers) -> tiers.maxBy { it.tier } }
                .sortedWith(compareByDescending<ReachedTier> { it.day }.thenBy { it.achievement.ordinal })
                .take(ACHIEVEMENTS_SHOWN)

        /**
         * The type whose share of the year's time exceeds its own threshold ([PERSONALITY_SHARE]) the most; ties in
         * [ListenerType] order. Needs [PERSONALITY_MIN_SEC] on [PATTERN_DAYS] days; null when no pattern reaches its
         * threshold. By each session's start (local time in [zone]); marathon by the days' totals.
         */
        private fun personality(inYear: List<StatSession>, days: Map<LocalDate, Double>, zone: ZoneId, activeDays: Int): ReviewSlide.Personality? {
            val total = days.values.sum()
            if (total < PERSONALITY_MIN_SEC || activeDays < PATTERN_DAYS) return null
            var night = 0.0
            var early = 0.0
            var commute = 0.0
            var weekend = 0.0
            for (s in inYear) {
                val start = localStart(s, zone)
                val t = start.toLocalTime()
                if (t in NIGHT) night += s.listenedSec
                if (t in EARLY) early += s.listenedSec
                if (commuting(s, zone) != null) commute += s.listenedSec
                if (start.dayOfWeek == DayOfWeek.SATURDAY || start.dayOfWeek == DayOfWeek.SUNDAY) weekend += s.listenedSec
            }
            val marathonDays = days.values.filter { it >= MARATHON_DAY_SEC }
            val shares = mapOf(
                ListenerType.NIGHT_OWL to night / total,
                ListenerType.EARLY_BIRD to early / total,
                ListenerType.COMMUTER to commute / total,
                ListenerType.WEEKEND to weekend / total,
                ListenerType.MARATHON to marathonDays.sum() / total,
            )
            val best = ListenerType.entries.maxBy { shares.getValue(it) / PERSONALITY_SHARE.getValue(it) }
            val share = shares.getValue(best)
            if (share < PERSONALITY_SHARE.getValue(best)) return null
            return ReviewSlide.Personality(best, share, if (best == ListenerType.MARATHON) marathonDays.size else 0)
        }
    }
}
