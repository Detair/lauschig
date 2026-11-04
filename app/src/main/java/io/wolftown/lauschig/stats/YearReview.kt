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

/**
 * When the user mostly listens (the rhythm slide's line): a part of the day ([YearReview.DAY_PARTS]), or on the way
 * (weekday commute hours).
 */
enum class ListeningTime { MORNING, AFTERNOON, EVENING, NIGHT, COMMUTE_MORNING, COMMUTE_EVENING }

/** The personality slide's types, in the order a tie is decided. */
enum class ListenerType { NIGHT_OWL, EARLY_BIRD, COMMUTER, WEEKEND, MARATHON }

/** What a slide is about, in the slides' fixed order (a story's place is kept by it when the slides are rebuilt). */
enum class SlideKind { OPENING, DAYS, RHYTHM, TOP_TITLES, TOP_PEOPLE, FINISHED, ACHIEVED, PERSONALITY, CLOSING }

/** One tier reached in the year ([Achievement] tiers are 1-based). */
@Immutable
data class ReachedTier(val achievement: Achievement, val tier: Int, val day: LocalDate)

/** One slide of the year in review; only slides with enough data exist (see [YearReview.build]). */
@Immutable
sealed interface ReviewSlide {
    /**
     * „Dein Hörjahr 2026“: the year's listening and how many titles were heard [YearReview.OPENING_TITLE_SEC] at least
     * (0: the line is left out); [incomplete]: not every session is loaded yet.
     */
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

    /** The most heard titles (two at least, [YearReview.TOP_TITLES] at most), each with its time. */
    data class TopTitles(val titles: List<TitleTime>) : ReviewSlide

    /** The most heard author (with their most heard title, [authorItemId]) and, where the catalog knows it, narrator. */
    data class TopPeople(val author: AuthorTime, val authorItemId: String?, val narrator: AuthorTime?) : ReviewSlide

    /**
     * Books finished in the year (by their dated finish) with the first and the last (the same for one; null without
     * any), and series completed in it; [seriesItemId] is the last volume of the last series completed (its cover tints
     * the slide when no book has a date).
     */
    data class Finished(
        val books: Int,
        val series: Int,
        val first: FinishedBook?,
        val last: FinishedBook?,
        val seriesItemId: String? = null,
    ) : ReviewSlide

    /**
     * The achievements with a tier reached in [year] ([count], each achievement once however many of its tiers) and up
     * to [YearReview.ACHIEVEMENTS_SHOWN] of them – their highest tier of the year – newest first.
     */
    data class Achieved(val year: Int, val count: Int, val shown: List<ReachedTier>) : ReviewSlide {
        /** Reached in the year but not in [shown]. */
        val more: Int get() = count - shown.size
    }

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

/** What [this] slide is about. */
val ReviewSlide.kind: SlideKind
    get() = when (this) {
        is ReviewSlide.Opening -> SlideKind.OPENING
        is ReviewSlide.Days -> SlideKind.DAYS
        is ReviewSlide.Rhythm -> SlideKind.RHYTHM
        is ReviewSlide.TopTitles -> SlideKind.TOP_TITLES
        is ReviewSlide.TopPeople -> SlideKind.TOP_PEOPLE
        is ReviewSlide.Finished -> SlideKind.FINISHED
        is ReviewSlide.Achieved -> SlideKind.ACHIEVED
        is ReviewSlide.Personality -> SlideKind.PERSONALITY
        is ReviewSlide.Closing -> SlideKind.CLOSING
    }

/** A year in review: its slides in their fixed order. */
@Immutable
data class YearReview(val year: Int, val slides: List<ReviewSlide>) {
    val kinds: List<SlideKind> get() = slides.map { it.kind }

    companion object {
        /** The review exists from one hour of listening in the year (the entry points offer it then). */
        const val MIN_YEAR_SEC = 3600.0

        /** The days slide and the rhythm slide need this many days with listening. */
        const val MIN_DAYS = 3

        /** The weekday and the time of day need this many days with listening (fewer is no pattern). */
        const val PATTERN_DAYS = 5

        /** A title counts for the opening's "with 12 titles" from a minute (as a day counts as heard). */
        const val OPENING_TITLE_SEC = ListeningStats.ACTIVE_DAY_SEC

        /** A title counts among the top titles from ten minutes. */
        const val TITLE_MIN_SEC = 600.0
        const val TOP_TITLES = 5

        /** The top titles need two: one title would only repeat the opening and the closing. */
        const val MIN_TOP_TITLES = 2

        /** An author or narrator is named from half an hour. */
        const val PERSON_MIN_SEC = 1800.0

        /** The strongest month needs an hour (and a second month with listening). */
        const val MONTH_MIN_SEC = 3600.0

        const val ACHIEVEMENTS_SHOWN = 6

        /** A personality needs five hours on [PATTERN_DAYS] days. */
        const val PERSONALITY_MIN_SEC = 5 * 3600.0

        /** A marathon day (for the personality): three hours. */
        const val MARATHON_DAY_SEC = 3 * 3600.0

        /**
         * The parts of the day the rhythm names, by session start: the night is the night owl's window and the morning
         * begins with the early bird's ([Achievements.NIGHT_WINDOW], [Achievements.EARLY_WINDOW]) – the slides and the
         * achievements of the same names never disagree about an hour. Ties in this order.
         */
        val DAY_PARTS: List<Pair<ListeningTime, Pair<LocalTime, LocalTime>>> = listOf(
            ListeningTime.MORNING to (Achievements.EARLY_WINDOW.first to LocalTime.NOON),
            ListeningTime.AFTERNOON to (LocalTime.NOON to LocalTime.of(17, 0)),
            ListeningTime.EVENING to (LocalTime.of(17, 0) to LocalTime.MIDNIGHT),
            ListeningTime.NIGHT to Achievements.NIGHT_WINDOW,
        )

        /**
         * The share of the year's time each type needs. Its time is what the achievement of the same name counts: night
         * owl and early bird by session start in [Achievements.NIGHT_WINDOW] (0–4) and [Achievements.EARLY_WINDOW]
         * (4–6), commuter in [Achievements.COMMUTE_WINDOWS] on weekdays; weekend = Saturday and Sunday; marathon = days
         * of [MARATHON_DAY_SEC]. Of the types above their share, the one with the **largest share** wins; shares are
         * compared in steps of [SHARE_STEP] (0.1 %), equal steps in [ListenerType] order (weekend before marathon when the
         * long days are the weekend days).
         *
         * Night owl and early bird need 20 %: their windows are short (four and two hours), and someone who listens there
         * three times a week for half an hour, with an hour a day in all, holds about 21 %.
         */
        val PERSONALITY_SHARE: Map<ListenerType, Double> = mapOf(
            ListenerType.NIGHT_OWL to 0.20,
            ListenerType.EARLY_BIRD to 0.20,
            ListenerType.COMMUTER to 0.40,
            ListenerType.WEEKEND to 0.50,
            ListenerType.MARATHON to 0.40,
        )

        /**
         * Shares are compared in thousandths: two shares that are equal in fact (the long days are exactly the weekend
         * days) may differ in the last bit of a sum of fractional seconds, and the order of the sessions would decide.
         */
        const val SHARE_STEP = 0.001

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
            val topPeople = author?.let { a ->
                ReviewSlide.TopPeople(a, authorItem(inYear, a.name), topNarrator(inYear, items)?.takeIf { !it.name.equals(a.name, ignoreCase = true) })
            }
            val books = finished.filter { it.day?.year == year && it.finishedAt != null }.sortedBy { it.finishedAt }
            val seriesDone = seriesCompletedIn(year, seriesGroups, finished)
            val reached = reachedIn(year, achievements)
            val personality = personality(inYear, days, zone, activeDays)

            val slides = buildList {
                add(ReviewSlide.Opening(year, total, perTitle.count { it.seconds >= OPENING_TITLE_SEC }, incomplete))
                if (activeDays >= MIN_DAYS) add(daysSlide(days))
                rhythm(inYear, days, zone, activeDays)?.let(::add)
                if (topTitles.size >= MIN_TOP_TITLES) add(ReviewSlide.TopTitles(topTitles))
                topPeople?.let(::add)
                if (books.isNotEmpty() || seriesDone.isNotEmpty()) {
                    add(ReviewSlide.Finished(books.size, seriesDone.size, books.firstOrNull(), books.lastOrNull(), seriesDone.lastOrNull()?.second))
                }
                if (reached.isNotEmpty()) add(ReviewSlide.Achieved(year, reached.size, reached.take(ACHIEVEMENTS_SHOWN)))
                personality?.let(::add)
                add(
                    ReviewSlide.Closing(
                        year, total, activeDays, books.size, topTitles.firstOrNull(), topPeople?.author?.name, personality?.type,
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

        /** [first, second) local time; a window whose end is not after its start runs over midnight. */
        private operator fun Pair<LocalTime, LocalTime>.contains(t: LocalTime): Boolean =
            if (first < second) !t.isBefore(first) && t.isBefore(second) else !t.isBefore(first) || t.isBefore(second)

        private fun commuting(s: StatSession, zone: ZoneId): Pair<LocalTime, LocalTime>? {
            val start = localStart(s, zone)
            if (start.dayOfWeek.value > DayOfWeek.FRIDAY.value) return null
            return Achievements.COMMUTE_WINDOWS.firstOrNull { start.toLocalTime() in it }
        }

        /** Needs the days slide's [MIN_DAYS], two months with listening and an hour in the strongest one. */
        private fun rhythm(inYear: List<StatSession>, days: Map<LocalDate, Double>, zone: ZoneId, activeDays: Int): ReviewSlide.Rhythm? {
            if (activeDays < MIN_DAYS) return null
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
         * morning or on the way back, whichever holds more; otherwise the part of the day ([DAY_PARTS]) with the most
         * listening. By each session's start.
         */
        private fun listeningTime(inYear: List<StatSession>, zone: ZoneId): ListeningTime {
            val total = inYear.sumOf { it.listenedSec }
            val commute = HashMap<Pair<LocalTime, LocalTime>, Double>()
            val parts = DoubleArray(DAY_PARTS.size)
            for (s in inYear) {
                commuting(s, zone)?.let { commute.merge(it, s.listenedSec, Double::plus) }
                val t = localStart(s, zone).toLocalTime()
                val part = DAY_PARTS.indexOfFirst { (_, window) -> t in window }
                if (part >= 0) parts[part] += s.listenedSec
            }
            if (total > 0 && commute.values.sum() >= COMMUTE_SHARE * total) {
                val (morning, evening) = Achievements.COMMUTE_WINDOWS
                return if ((commute[morning] ?: 0.0) >= (commute[evening] ?: 0.0)) ListeningTime.COMMUTE_MORNING else ListeningTime.COMMUTE_EVENING
            }
            return DAY_PARTS[parts.indices.maxBy { parts[it] }].first
        }

        /**
         * The series whose last volume was finished in [year], dated as the "series" achievement dates them (a book's
         * finishing session, else its progress's day) – in the order they were completed, each with that last volume.
         */
        private fun seriesCompletedIn(year: Int, groups: List<List<ItemEntity>>, finished: List<FinishedBook>): List<Pair<LocalDate, String>> {
            val dayOf = finished.associate { it.itemId to (it.day ?: it.progressDay) }
            return ListeningStats.finishedSeriesGroups(groups, dayOf.keys).mapNotNull { g ->
                if (g.any { dayOf[it.id] == null }) return@mapNotNull null
                val last = g.maxWith(compareBy<ItemEntity> { dayOf[it.id]!! }.thenBy { it.seriesSortKey ?: 0.0 })
                val day = dayOf[last.id]!!
                (day to last.id).takeIf { day.year == year }
            }.sortedBy { it.first }
        }

        /**
         * Per achievement with a tier reached on a day of [year] (a tier without a day belongs to no year): its highest
         * such tier – newest first, the same day in catalogue order.
         */
        private fun reachedIn(year: Int, states: List<AchievementState>): List<ReachedTier> = states.mapNotNull { s ->
            s.reached.withIndex().lastOrNull { (_, day) -> day?.year == year }?.let { (i, day) -> ReachedTier(s.achievement, i + 1, day!!) }
        }.sortedWith(compareByDescending<ReachedTier> { it.day }.thenBy { it.achievement.ordinal })

        /**
         * The type with the largest share of the year's time among those above their own [PERSONALITY_SHARE] (both
         * compared in [SHARE_STEP]s); ties in [ListenerType] order. Needs [PERSONALITY_MIN_SEC] on [PATTERN_DAYS] days;
         * null when no pattern reaches its share. By each session's start (local time in [zone]); marathon by the days'
         * totals.
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
                if (t in Achievements.NIGHT_WINDOW) night += s.listenedSec
                if (t in Achievements.EARLY_WINDOW) early += s.listenedSec
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
            fun steps(share: Double): Long = Math.round(share / SHARE_STEP)
            val best = ListenerType.entries.filter { steps(shares.getValue(it)) >= steps(PERSONALITY_SHARE.getValue(it)) }
                .maxByOrNull { steps(shares.getValue(it)) } ?: return null // the first of equal steps
            return ReviewSlide.Personality(best, shares.getValue(best), if (best == ListenerType.MARATHON) marathonDays.size else 0)
        }
    }
}
