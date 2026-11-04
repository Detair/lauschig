package io.wolftown.lauschig.stats

import androidx.compose.runtime.Immutable
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.playback.ProgressTracker
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.TreeMap

/** One listening session as the statistics count it: the server's and this device's merged, named from the catalog. */
@Immutable
data class StatSession(
    val id: String,
    /** Playable key (book id or `itemId~episodeId`); empty when the server no longer names the item. */
    val itemKey: String,
    val title: String?,
    val author: String?,
    val startedAt: Long,
    val updatedAt: Long,
    val listenedSec: Double,
    val durationSec: Double,
    val currentTimeSec: Double,
) {
    /** The library item: a book, or an episode's podcast. */
    val itemId: String get() = if (itemKey.isEmpty()) "" else PlayableKey.parse(itemKey).libraryItemId
    val isEpisode: Boolean get() = itemKey.isNotEmpty() && PlayableKey.parse(itemKey).episodeId != null
}

enum class StatsPeriod { WEEK, MONTH, YEAR, ALL }

/** What one bar of a period covers. */
enum class BarUnit { DAY, MONTH, YEAR }

/** The days of a period, [start] inclusive, [end] exclusive. [StatsPeriod.ALL] runs from the first listening to today. */
@Immutable
data class PeriodRange(val period: StatsPeriod, val start: LocalDate, val end: LocalDate)

/** One bar: the [unit] starting at [start]; [current] for today, this month or this year. */
@Immutable
data class Bar(val start: LocalDate, val seconds: Double, val current: Boolean)

/** Days in a row with at least [ListeningStats.ACTIVE_DAY_SEC]: the one running now and the longest ever. */
@Immutable
data class Streaks(val current: Int, val longest: Int)

/** A book heard to its end; [day] is when, null when only the progress says so (finished before the known sessions). */
@Immutable
data class FinishedBook(val itemId: String, val title: String?, val author: String?, val day: LocalDate?)

@Immutable
data class FinishedSeries(val name: String, val volumes: Int, val coverItemId: String)

/** Listening time of one item (a book, or a podcast with all its episodes). */
@Immutable
data class TitleTime(val itemId: String, val title: String?, val author: String?, val seconds: Double)

@Immutable
data class AuthorTime(val name: String, val seconds: Double)

/** The small card on Start: this week's listening, the streak and the week's days. */
@Immutable
data class StatsCard(val weekSec: Double, val streak: Int, val days: List<Bar>)

@Immutable
data class PeriodSummary(
    val range: PeriodRange,
    val totalSec: Double,
    /** Days of the period with at least [ListeningStats.ACTIVE_DAY_SEC]. */
    val activeDays: Int,
    val unit: BarUnit,
    val bars: List<Bar>,
    /** Newest first; in [StatsPeriod.ALL] also the books only the progress marks finished (last). */
    val finishedBooks: List<FinishedBook>,
    val topTitles: List<TitleTime>,
    val topAuthors: List<AuthorTime>,
)

/**
 * The listening statistics, computed from the cached sessions alone (never from the server's own statistics: they are
 * not offline and count days in the server's time zone). Pure, for tests; callers run it off the main thread.
 *
 * - A session counts for the local date (device zone) of its start, also when it runs past midnight.
 * - A day is active with at least [ACTIVE_DAY_SEC]; a streak includes today once today is active, otherwise it ends
 *   yesterday (it is not broken before the day is over).
 * - A book is finished on the day of the last update of its first session that ends at [ProgressTracker.FINISHED_FRACTION]
 *   of its duration; books only the progress marks finished count in [StatsPeriod.ALL]. Episodes are never books.
 */
object ListeningStats {
    const val ACTIVE_DAY_SEC = 60.0
    const val TOP_LIMIT = 5

    private val people = Catalog()

    /**
     * Server and device sessions by id (local sessions are uploaded under their own id): the local copy wins while it is
     * not synced, otherwise the one with more listening time, keeping the server's names – like the history
     * (`HistoryAggregation`). Titles of the device library belong to no account and are left out. Names and a
     * missing duration come from [catalog] (by library item id) where it knows the item.
     */
    fun merge(
        server: List<ListeningSessionEntity>,
        local: List<LocalSessionEntity>,
        catalog: Map<String, ItemEntity> = emptyMap(),
    ): List<StatSession> {
        val byId = HashMap<String, StatSession>(server.size + local.size)
        for (s in server) byId[s.id] = StatSession(s.id, s.itemKey, s.title, s.author, s.startedAt, s.updatedAt, s.timeListeningSec, s.durationSec, s.currentTimeSec)
        for (l in local) {
            if (DeviceLibrary.isLocal(l.serverId, l.itemId)) continue
            val known = byId[l.id]
            if (known != null && l.synced && known.listenedSec >= l.timeListeningSec) continue
            byId[l.id] = StatSession(
                l.id, l.itemId, known?.title, known?.author, l.startedAt, l.updatedAt, l.timeListeningSec,
                known?.durationSec ?: 0.0, l.currentTimeSec,
            )
        }
        if (catalog.isEmpty()) return byId.values.toList()
        return byId.values.map { s ->
            val item = catalog[s.itemId] ?: return@map s
            s.copy(
                title = item.title.takeIf { it.isNotBlank() } ?: s.title,
                author = item.author?.takeIf { it.isNotBlank() } ?: s.author,
                durationSec = if (s.durationSec > 0 || s.isEpisode) s.durationSec else item.durationSec,
            )
        }
    }

    /** Listening seconds per local date of the session start, in date order. */
    fun days(sessions: List<StatSession>, zone: ZoneId): Map<LocalDate, Double> {
        val out = TreeMap<LocalDate, Double>()
        for (s in sessions) out.merge(dayOf(s.startedAt, zone), s.listenedSec, Double::plus)
        return out
    }

    fun dayOf(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** The period of [period] that holds [anchor]; weeks start on [weekStart]. [StatsPeriod.ALL] ignores the anchor's period. */
    fun range(period: StatsPeriod, anchor: LocalDate, weekStart: DayOfWeek, firstDay: LocalDate? = null): PeriodRange = when (period) {
        StatsPeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(weekStart)).let { PeriodRange(period, it, it.plusDays(7)) }
        StatsPeriod.MONTH -> anchor.withDayOfMonth(1).let { PeriodRange(period, it, it.plusMonths(1)) }
        StatsPeriod.YEAR -> anchor.withDayOfYear(1).let { PeriodRange(period, it, it.plusYears(1)) }
        StatsPeriod.ALL -> PeriodRange(period, minOf(firstDay ?: anchor, anchor), anchor.plusDays(1))
    }

    /** A day of the period before [range]. */
    fun previousAnchor(range: PeriodRange): LocalDate = range.start.minusDays(1)

    /** The first day of the period after [range]. */
    fun nextAnchor(range: PeriodRange): LocalDate = range.end

    /** True if the next period has begun by [today] (never into the future). */
    fun hasNext(range: PeriodRange, today: LocalDate): Boolean = range.period != StatsPeriod.ALL && !nextAnchor(range).isAfter(today)

    /** True if something was listened before [range] began ([firstDay]: the first day with a session, null for none). */
    fun hasPrevious(range: PeriodRange, firstDay: LocalDate?): Boolean =
        range.period != StatsPeriod.ALL && firstDay != null && firstDay.isBefore(range.start)

    fun streaks(days: Map<LocalDate, Double>, today: LocalDate): Streaks {
        val active = days.filterValues { it >= ACTIVE_DAY_SEC }.keys.toSortedSet()
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in active) {
            run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }
        var current = 0
        var day = if (today in active) today else today.minusDays(1)
        while (day in active) {
            current++
            day = day.minusDays(1)
        }
        return Streaks(current, longest)
    }

    /**
     * Every finished book: by its first finishing session (newest first), then those only [progress] marks finished
     * (by title, without a day).
     */
    fun finishedBooks(
        sessions: List<StatSession>,
        progress: List<ProgressEntity>,
        zone: ZoneId,
        /** Names the books only the progress knows (no session of them is cached). */
        catalog: Map<String, ItemEntity> = emptyMap(),
    ): List<FinishedBook> {
        val first = HashMap<String, StatSession>()
        for (s in sessions) {
            if (s.itemKey.isEmpty() || s.isEpisode || DeviceLibrary.isLocalKey(s.itemKey)) continue
            if (s.durationSec <= 0 || s.listenedSec <= 0 || s.currentTimeSec / s.durationSec < ProgressTracker.FINISHED_FRACTION) continue
            val known = first[s.itemKey]
            if (known == null || s.updatedAt < known.updatedAt) first[s.itemKey] = s
        }
        val dated = first.values.sortedByDescending { it.updatedAt }.map { FinishedBook(it.itemKey, it.title, it.author, dayOf(it.updatedAt, zone)) }
        val names = sessions.associateBy { it.itemKey }
        val undated = progress.asSequence()
            .filter { it.isFinished && it.itemId !in first && PlayableKey.parse(it.itemId).episodeId == null && !DeviceLibrary.isLocalKey(it.itemId) }
            .map { p ->
                val session = names[p.itemId]
                val item = catalog[p.itemId]
                FinishedBook(p.itemId, session?.title ?: item?.title, session?.author ?: item?.author, day = null)
            }
            .sortedBy { it.title?.lowercase() ?: it.itemId }
            .toList()
        return dated + undated
    }

    /**
     * Series of the library whose numbered volumes ([Catalog.seriesGroups]) are all in [finished]; a series needs two
     * volumes at least (a single numbered volume is mostly a series the library holds one book of). Has no date.
     */
    fun finishedSeries(groups: List<List<ItemEntity>>, finished: Set<String>): List<FinishedSeries> =
        groups.filter { g -> g.size >= 2 && g.all { it.id in finished } }
            .map { g -> FinishedSeries(g.first().seriesName!!.trim(), g.size, g.first().id) }
            .sortedBy { it.name.lowercase() }

    /** How far back the start card looks for listening before it hides. */
    const val CARD_DAYS = 30

    /**
     * This week (from [weekStart]) for the start card, or null when less than [ACTIVE_DAY_SEC] were heard in the last
     * [CARD_DAYS] days including today (nothing to show, and a few seconds by accident do not bring it back).
     */
    fun startCard(days: Map<LocalDate, Double>, today: LocalDate, weekStart: DayOfWeek): StatsCard? {
        val recent = (0 until CARD_DAYS).sumOf { days[today.minusDays(it.toLong())] ?: 0.0 }
        if (recent < ACTIVE_DAY_SEC) return null
        val start = range(StatsPeriod.WEEK, today, weekStart).start
        val week = (0 until 7).map { start.plusDays(it.toLong()) }.map { Bar(it, days[it] ?: 0.0, it == today) }
        return StatsCard(week.sumOf { it.seconds }, streaks(days, today).current, week)
    }

    /** The numbers of one period: totals, bars, finished books, the most heard titles and authors. */
    fun summary(
        sessions: List<StatSession>,
        progress: List<ProgressEntity>,
        range: PeriodRange,
        today: LocalDate,
        zone: ZoneId,
        finished: List<FinishedBook> = finishedBooks(sessions, progress, zone),
    ): PeriodSummary {
        val inRange = sessions.filter { s -> dayOf(s.startedAt, zone).let { !it.isBefore(range.start) && it.isBefore(range.end) } }
        val perDay = days(inRange, zone)
        val unit = when (range.period) {
            StatsPeriod.WEEK, StatsPeriod.MONTH -> BarUnit.DAY
            StatsPeriod.YEAR -> BarUnit.MONTH
            StatsPeriod.ALL -> BarUnit.YEAR
        }
        val bars = when (unit) {
            BarUnit.DAY -> generateSequence(range.start) { it.plusDays(1) }.takeWhile { it.isBefore(range.end) }
                .map { day -> Bar(day, perDay[day] ?: 0.0, day == today) }.toList()
            BarUnit.MONTH -> (0 until 12).map { m ->
                val month = range.start.plusMonths(m.toLong())
                Bar(month, perDay.filterKeys { it.year == month.year && it.month == month.month }.values.sum(), month.year == today.year && month.month == today.month)
            }
            BarUnit.YEAR -> (range.start.year..today.year).map { y ->
                Bar(LocalDate.of(y, 1, 1), perDay.filterKeys { it.year == y }.values.sum(), y == today.year)
            }
        }
        val titles = inRange.filter { it.itemKey.isNotEmpty() }.groupBy { it.itemId }
            .map { (id, list) ->
                val named = list.maxBy { it.startedAt }
                TitleTime(id, named.title, named.author, list.sumOf { it.listenedSec })
            }
            .sortedWith(compareByDescending<TitleTime> { it.seconds }.thenBy { it.title?.lowercase() ?: it.itemId })
            .take(TOP_LIMIT)
        val authors = HashMap<String, Pair<String, Double>>()
        for (s in inRange) for (name in people.splitPeople(s.author)) {
            val key = name.lowercase()
            val (shown, sec) = authors[key] ?: (name to 0.0)
            authors[key] = shown to sec + s.listenedSec
        }
        val topAuthors = authors.values.map { AuthorTime(it.first, it.second) }
            .sortedWith(compareByDescending<AuthorTime> { it.seconds }.thenBy { it.name.lowercase() })
            .take(TOP_LIMIT)
        val finishedHere = finished.filter { f ->
            val day = f.day
            if (day == null) range.period == StatsPeriod.ALL else !day.isBefore(range.start) && day.isBefore(range.end)
        }
        return PeriodSummary(
            range = range,
            totalSec = perDay.values.sum(),
            activeDays = perDay.values.count { it >= ACTIVE_DAY_SEC },
            unit = unit,
            bars = bars,
            finishedBooks = finishedHere,
            topTitles = titles,
            topAuthors = topAuthors,
        )
    }
}
