package io.wolftown.lauschig.stats

import androidx.compose.runtime.Immutable
import io.wolftown.lauschig.db.entities.ItemEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What an achievement measures, for the progress line ("37 / 50 Std."). */
enum class AchievementMeasure { HOURS, DAYS, BOOKS, SERIES, DURATION, SESSIONS, GENRES, NONE }

/**
 * The catalogue („Erfolge“). [id] is stored (`achievement_seen`): never renamed, never renumbered. [tiers] are the
 * thresholds of the measure in its own unit (seconds for listening time, else a count); one tier is a single achievement.
 * **Tiers are append-only**: the seen table stores a tier's index, so a threshold may only be added at the end –
 * inserting one would shift the indices and tell everyone their seen tiers again (a test pins them all).
 *
 * A book only the progress marks finished counts on the day its progress last changed (`lastUpdate`): no stored state
 * and no clock, but a later progress write (played again, a position moved on another device) can move that day – and
 * the day of the books or series tier it carries – forward. Whether a tier is reached, and so the notice, never depends
 * on it.
 */
enum class Achievement(val id: String, val tiers: List<Double>, val measure: AchievementMeasure) {
    /** Total listening time. */
    HOURS("hours", listOf(1, 10, 50, 100, 250, 500, 1000).map { it * 3600.0 }, AchievementMeasure.HOURS),

    /** The longest run of days with at least a minute ([ListeningStats.ACTIVE_DAY_SEC]). */
    STREAK("streak", listOf(3.0, 7.0, 30.0, 100.0, 365.0), AchievementMeasure.DAYS),

    /** Finished books ([ListeningStats.finishedBooks]). */
    BOOKS("books", listOf(1.0, 5.0, 10.0, 25.0, 50.0, 100.0), AchievementMeasure.BOOKS),

    /** Complete series ([ListeningStats.finishedSeriesGroups]). */
    SERIES("series", listOf(1.0, 5.0, 10.0), AchievementMeasure.SERIES),

    /** Four hours on one day. */
    MARATHON("marathon", listOf(4 * 3600.0), AchievementMeasure.DURATION),

    /** A session of ten minutes started between midnight and 4:00. */
    NIGHT_OWL("night_owl", listOf(1.0), AchievementMeasure.NONE),

    /** A session of ten minutes started between 4:00 and 6:00. */
    EARLY_BIRD("early_bird", listOf(1.0), AchievementMeasure.NONE),

    /** Five hours over one Saturday and the Sunday after it. */
    WEEKEND("weekend", listOf(5 * 3600.0), AchievementMeasure.DURATION),

    /** A book finished within 72 hours of its first session. */
    ONE_GO("one_go", listOf(1.0), AchievementMeasure.NONE),

    /** Twenty weekday sessions of ten minutes started 6:00–9:00 or 16:00–19:00. */
    COMMUTER("commuter", listOf(20.0), AchievementMeasure.SESSIONS),

    /** Titles of five genres, each title heard for half an hour. */
    GENRES("genres", listOf(5.0), AchievementMeasure.GENRES),
    ;

    /** Counts towards a threshold and shows its progress (all but the three single events). */
    val tiered: Boolean get() = measure != AchievementMeasure.NONE

    /** More than one tier: the card says which one („Stufe 2 von 5“). */
    val leveled: Boolean get() = tiers.size > 1

    companion object {
        fun byId(id: String): Achievement? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What [Achievements.tierInputs] reduces the data to: equal inputs mean the same tiers are reached. [items] is compared
 * by reference first (the catalog part is reused while only sessions or progress change).
 */
data class TierInputs(
    val zone: ZoneId,
    val sessions: Int,
    /**
     * Order-independent sum of each session's (id, title, start) hash: a session added, replaced, or rewritten with
     * another title or start time.
     */
    val sessionIds: Int,
    val withListening: Int,
    /** Sessions long enough for a time window ([Achievements.WINDOW_SESSION_SEC]). */
    val windowLong: Int,
    val hoursTiers: Int,
    val longestRun: Int,
    val marathon: Boolean,
    val weekend: Boolean,
    /** Titles heard long enough for their genres. */
    val genreTitles: Int,
    val finished: List<Pair<String, Long?>>,
    val series: Int,
    val items: Map<String, ItemEntity>,
)

/**
 * One reached tier (1-based), as the seen table keys it. Tier 0 is never a reached tier: the table's baseline row
 * (`AchievementSeenEntity.BASELINE_ID`) uses it.
 */
data class AchievementKey(val id: String, val tier: Int)

/** An achievement as the data has it now. */
@Immutable
data class AchievementState(
    val achievement: Achievement,
    /**
     * When each reached tier was first reached, in tier order (day precision, device zone); null: reached, but no day is
     * known (a book only the progress marks finished, without a time).
     */
    val reached: List<LocalDate?>,
    /** The measure now (seconds, days, books …) for the progress to the next tier; null where there is none. */
    val value: Double?,
) {
    /** Reached tiers; 0 = locked. */
    val tier: Int get() = reached.size
    val unlocked: Boolean get() = reached.isNotEmpty()

    /** When the current tier was reached. */
    val unlockedAt: LocalDate? get() = reached.lastOrNull()
    val complete: Boolean get() = tier == achievement.tiers.size

    /** The threshold of the next tier; null once all are reached. */
    val nextTarget: Double? get() = achievement.tiers.getOrNull(tier)

    /** [value] of [nextTarget] in 0..1; null without a measure or once complete. */
    val progress: Float? get() {
        val v = value ?: return null
        val next = nextTarget ?: return null
        return (v / next).coerceIn(0.0, 1.0).toFloat()
    }

    val keys: List<AchievementKey> get() = (1..tier).map { AchievementKey(achievement.id, it) }
}

/**
 * The achievements, computed from the listening data alone (nothing about unlocking is stored): a replay of the merged
 * sessions ([ListeningStats.merge]) and the finished books ([ListeningStats.finishedBooks]) in time order; a tier is
 * reached on the day its rule first held. The same data always gives the same days – the only clock is the device zone,
 * in which sessions are bucketed by their start ([ListeningStats.days]) and time windows are read (the local time the
 * session started, not UTC). A book only the progress marks finished counts on the day its progress last changed.
 * Pure, for tests; callers run it off the main thread.
 */
object Achievements {
    /** Every tier of every achievement: what "12 / 28" counts. */
    val TOTAL_TIERS: Int = Achievement.entries.sumOf { it.tiers.size }

    /** A session counts for a time of day (night owl, early bird, commuter) with at least this much listening. */
    const val WINDOW_SESSION_SEC = 600.0

    /** One go: finished at most this long after the book's first session. */
    const val ONE_GO_MS = 72 * 60 * 60 * 1000L

    /** A title counts for its genres once heard this long. */
    const val GENRE_TITLE_SEC = 1800.0

    /** Sums of many small doubles must not fall a hair short of a threshold. */
    private const val EPS = 1e-6

    /** Night owl: a session started in [start, end) local time. */
    val NIGHT_WINDOW: Pair<LocalTime, LocalTime> = LocalTime.MIDNIGHT to LocalTime.of(4, 0)

    /** Early bird: a session started in [start, end) local time. */
    val EARLY_WINDOW: Pair<LocalTime, LocalTime> = LocalTime.of(4, 0) to LocalTime.of(6, 0)

    /** Commuter: weekday sessions started in one of these [start, end) windows. */
    val COMMUTE_WINDOWS: List<Pair<LocalTime, LocalTime>> = listOf(LocalTime.of(6, 0) to LocalTime.of(9, 0), LocalTime.of(16, 0) to LocalTime.of(19, 0))

    /** Every achievement in catalogue order. [items] (by id) give the genres; [seriesGroups] as [ListeningStats.finishedSeries]. */
    fun evaluate(
        sessions: List<StatSession>,
        finished: List<FinishedBook>,
        seriesGroups: List<List<ItemEntity>>,
        items: Map<String, ItemEntity>,
        zone: ZoneId,
    ): List<AchievementState> {
        // One order for every replay, whatever order the rows came in.
        val ordered = sessions.sortedWith(compareBy({ it.startedAt }, { it.id }))
        val days = ListeningStats.days(ordered, zone)
        val bookDays = finished.map { it.day ?: it.progressDay }
        return Achievement.entries.map { a ->
            when (a) {
                Achievement.HOURS -> hours(days)
                Achievement.STREAK -> streak(days)
                Achievement.BOOKS -> counted(a, bookDays)
                Achievement.SERIES -> series(seriesGroups, finished)
                Achievement.MARATHON -> marathon(days)
                Achievement.NIGHT_OWL -> firstSession(a, ordered, zone) { it in NIGHT_WINDOW }
                Achievement.EARLY_BIRD -> firstSession(a, ordered, zone) { it in EARLY_WINDOW }
                Achievement.WEEKEND -> weekend(days)
                Achievement.ONE_GO -> oneGo(ordered, finished)
                Achievement.COMMUTER -> commuter(ordered, zone)
                Achievement.GENRES -> genres(ordered, items, zone)
            }
        }
    }

    /**
     * Every unlocked one before every locked one. Unlocked: the current tier's day, newest first (reached without a day
     * after those). Ties – and all of the locked ones – by closeness to the next tier (a complete one counts as there),
     * then in catalogue order.
     */
    fun sorted(states: List<AchievementState>): List<AchievementState> = states.sortedWith(
        compareBy<AchievementState> { if (it.unlocked) 0 else 1 }
            .thenByDescending { it.unlockedAt }
            .thenByDescending { if (it.complete) 1f else it.progress ?: 0f }
            .thenBy { it.achievement.ordinal },
    )

    fun unlockedTiers(states: List<AchievementState>): Int = states.sumOf { it.tier }

    /**
     * What decides which tiers [evaluate] finds reached (not on which day), from the same data plus its [days] and the
     * number of finished series. Cheap (one pass over the sessions, no zone conversion per session; only the per-day
     * totals are sorted for the streak and weekend checks) and coarse on purpose: a playback tick on a day that counts
     * already moves none of it, so the notice skips the evaluation then.
     */
    fun tierInputs(
        sessions: List<StatSession>,
        finished: List<FinishedBook>,
        seriesCount: Int,
        items: Map<String, ItemEntity>,
        days: Map<LocalDate, Double>,
        zone: ZoneId,
    ): TierInputs {
        var ids = 0
        var listened = 0
        var windows = 0
        val perTitle = HashMap<String, Double>()
        for (s in sessions) {
            ids += 31 * (31 * s.id.hashCode() + s.itemKey.hashCode()) + s.startedAt.hashCode()
            if (s.listenedSec > 0) listened++
            if (s.listenedSec >= WINDOW_SESSION_SEC) windows++
            if (s.itemKey.isNotEmpty()) perTitle.merge(s.itemId, s.listenedSec, Double::plus)
        }
        val total = days.values.sum()
        return TierInputs(
            zone = zone,
            sessions = sessions.size,
            sessionIds = ids,
            withListening = listened,
            windowLong = windows,
            hoursTiers = Achievement.HOURS.tiers.count { total + EPS >= it },
            longestRun = ListeningStats.runLengths(days).maxOfOrNull { it.second } ?: 0,
            marathon = days.values.any { it + EPS >= Achievement.MARATHON.tiers.single() },
            weekend = bestWeekend(days) + EPS >= Achievement.WEEKEND.tiers.single(),
            genreTitles = perTitle.values.count { it + EPS >= GENRE_TITLE_SEC },
            finished = finished.map { it.itemId to it.finishedAt },
            series = seriesCount,
            items = items,
        )
    }

    /**
     * The day each tier was first reached by the measure along [events] (in time order: day, measure then; a null day is
     * not known). A higher tier is never reached before a lower one.
     */
    private fun tiers(a: Achievement, events: Sequence<Pair<LocalDate?, Double>>): List<LocalDate?> {
        val out = ArrayList<LocalDate?>()
        for ((day, value) in events) {
            while (out.size < a.tiers.size && value + EPS >= a.tiers[out.size]) out += day
            if (out.size == a.tiers.size) break
        }
        return out
    }

    private fun hours(days: Map<LocalDate, Double>): AchievementState {
        var sum = 0.0
        val events = days.map { (day, sec) -> sum += sec; day to sum }
        val reached = tiers(Achievement.HOURS, events.asSequence())
        return AchievementState(Achievement.HOURS, reached, days.values.sum())
    }

    private fun streak(days: Map<LocalDate, Double>): AchievementState {
        val runs = ListeningStats.runLengths(days)
        val reached = tiers(Achievement.STREAK, runs.asSequence().map { (day, run) -> day to run.toDouble() })
        return AchievementState(Achievement.STREAK, reached, (runs.maxOfOrNull { it.second } ?: 0).toDouble())
    }

    /** One per entry of [days], in day order; entries without a day come last. */
    private fun counted(a: Achievement, days: List<LocalDate?>): AchievementState {
        val ordered = days.sortedWith(nullsLast(naturalOrder()))
        val reached = tiers(a, ordered.asSequence().mapIndexed { i, day -> day to (i + 1).toDouble() })
        return AchievementState(a, reached, days.size.toDouble())
    }

    /** A series is complete on the day its last volume was finished; without a day for every volume it has none. */
    private fun series(groups: List<List<ItemEntity>>, finished: List<FinishedBook>): AchievementState {
        val dayOf = finished.associate { it.itemId to (it.day ?: it.progressDay) }
        val complete = ListeningStats.finishedSeriesGroups(groups, dayOf.keys)
        val days = complete.map { g -> g.map { dayOf[it.id] }.let { vol -> if (null in vol) null else vol.maxOf { it!! } } }
        return counted(Achievement.SERIES, days)
    }

    private fun marathon(days: Map<LocalDate, Double>): AchievementState {
        val reached = tiers(Achievement.MARATHON, days.asSequence().map { it.key to it.value })
        return AchievementState(Achievement.MARATHON, reached, days.values.maxOrNull() ?: 0.0)
    }

    /** Saturday plus the Sunday after it: held on the Saturday if it alone was enough, else on the Sunday. */
    private fun weekend(days: Map<LocalDate, Double>): AchievementState {
        val events = ArrayList<Pair<LocalDate, Double>>()
        for (saturday in saturdays(days)) {
            val sat = days[saturday] ?: 0.0
            events += saturday to sat
            events += saturday.plusDays(1) to sat + (days[saturday.plusDays(1)] ?: 0.0)
        }
        return AchievementState(Achievement.WEEKEND, tiers(Achievement.WEEKEND, events.asSequence()), bestWeekend(days))
    }

    /** The Saturdays of every weekend with listening, in date order. */
    private fun saturdays(days: Map<LocalDate, Double>) = days.keys.mapNotNull { day ->
        when (day.dayOfWeek) {
            DayOfWeek.SATURDAY -> day
            DayOfWeek.SUNDAY -> day.minusDays(1)
            else -> null
        }
    }.toSortedSet()

    /** The most listened over one Saturday and the Sunday after it. */
    private fun bestWeekend(days: Map<LocalDate, Double>): Double =
        saturdays(days).maxOfOrNull { (days[it] ?: 0.0) + (days[it.plusDays(1)] ?: 0.0) } ?: 0.0

    private operator fun Pair<LocalTime, LocalTime>.contains(time: LocalTime) = !time.isBefore(first) && time.isBefore(second)

    private fun localStart(s: StatSession, zone: ZoneId) = Instant.ofEpochMilli(s.startedAt).atZone(zone)

    /** The first session of [WINDOW_SESSION_SEC] whose local start time [matches]. */
    private fun firstSession(a: Achievement, ordered: List<StatSession>, zone: ZoneId, matches: (LocalTime) -> Boolean): AchievementState {
        val first = ordered.firstOrNull { s -> s.listenedSec >= WINDOW_SESSION_SEC && matches(localStart(s, zone).toLocalTime()) }
        return AchievementState(a, listOfNotNull(first?.let { ListeningStats.dayOf(it.startedAt, zone) }), null)
    }

    /** Monday to Friday, started in a commute window. */
    private fun commuter(ordered: List<StatSession>, zone: ZoneId): AchievementState {
        val rides = ordered.filter { s ->
            s.listenedSec >= WINDOW_SESSION_SEC &&
                localStart(s, zone).let { start -> start.dayOfWeek.value <= DayOfWeek.FRIDAY.value && COMMUTE_WINDOWS.any { start.toLocalTime() in it } }
        }
        val reached = tiers(Achievement.COMMUTER, rides.asSequence().mapIndexed { i, s -> ListeningStats.dayOf(s.startedAt, zone) to (i + 1).toDouble() })
        return AchievementState(Achievement.COMMUTER, reached, rides.size.toDouble())
    }

    /** The first book whose (first) finish came within [ONE_GO_MS] of its first session with listening. */
    private fun oneGo(ordered: List<StatSession>, finished: List<FinishedBook>): AchievementState {
        val firstStart = HashMap<String, Long>()
        for (s in ordered) if (s.listenedSec > 0 && s.itemKey.isNotEmpty()) firstStart.putIfAbsent(s.itemKey, s.startedAt)
        val hit = finished.asSequence()
            .filter { f -> f.finishedAt != null && f.day != null }
            .filter { f -> firstStart[f.itemId]?.let { f.finishedAt!! - it <= ONE_GO_MS } == true }
            .minByOrNull { it.finishedAt!! }
        return AchievementState(Achievement.ONE_GO, listOfNotNull(hit?.day), null)
    }

    /** A title (a book, or a podcast with its episodes) adds its genres once heard [GENRE_TITLE_SEC]. */
    private fun genres(ordered: List<StatSession>, items: Map<String, ItemEntity>, zone: ZoneId): AchievementState {
        val heard = HashMap<String, Double>()
        val genres = HashSet<String>()
        var reachedOn: LocalDate? = null
        val target = Achievement.GENRES.tiers.single()
        for (s in ordered) {
            if (s.itemKey.isEmpty()) continue
            val id = s.itemId
            val before = heard[id] ?: 0.0
            val after = before + s.listenedSec
            heard[id] = after
            if (before + EPS >= GENRE_TITLE_SEC || after + EPS < GENRE_TITLE_SEC) continue
            items[id]?.genres?.mapNotNullTo(genres) { g -> g.trim().lowercase().takeIf { it.isNotEmpty() } }
            if (reachedOn == null && genres.size >= target) reachedOn = ListeningStats.dayOf(s.startedAt, zone)
        }
        return AchievementState(Achievement.GENRES, listOfNotNull(reachedOn), genres.size.toDouble())
    }
}

/** The Stats page's section: the latest unlocks and how many tiers of all are reached. */
@Immutable
data class AchievementsPreview(val recent: List<AchievementState>, val unlockedTiers: Int, val totalTiers: Int = Achievements.TOTAL_TIERS) {
    companion object {
        const val RECENT = 3

        /** The [RECENT] most recently unlocked (as [Achievements.sorted] orders them). */
        fun of(states: List<AchievementState>) =
            AchievementsPreview(Achievements.sorted(states).filter { it.unlocked }.take(RECENT), Achievements.unlockedTiers(states))
    }
}
