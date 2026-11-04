package io.wolftown.lauschig.stats

import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.db.entities.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * One account's listening as the statistics and the achievements count it: the merged sessions (named from the
 * catalog), their days in [zone], the finished books and series, the catalog's series groups and items.
 */
class ListeningData(
    val serverId: String,
    val sessions: List<StatSession>,
    val days: Map<LocalDate, Double>,
    val finished: List<FinishedBook>,
    /** Numbered volumes per series of the book libraries ([Catalog.seriesGroups]). */
    val seriesGroups: List<List<ItemEntity>>,
    val series: List<FinishedSeries>,
    val items: Map<String, ItemEntity>,
    val zone: ZoneId,
) {
    /** The first day with a session; null without any. */
    val firstDay: LocalDate? get() = days.keys.firstOrNull()

    /** Every achievement in catalogue order ([Achievements.evaluate]). */
    fun achievements(): List<AchievementState> = Achievements.evaluate(sessions, finished, seriesGroups, items, zone)

    /** What decides which tiers are reached ([Achievements.tierInputs]); equal for two values = the same tiers. */
    val tierInputs: TierInputs by lazy { Achievements.tierInputs(sessions, finished, series.size, items, days, zone) }
}

/**
 * [ListeningData] from Room only (offline-first). The catalog part (names, series) changes rarely and is grouped on its
 * own; a burst of session or progress writes (a playing title, a sync writing pages) is computed once. Callers move it
 * off the main thread (`flowOn`).
 */
class ListeningDataSource @Inject constructor(
    private val sessions: StatsSessions,
    private val progress: ProgressDao,
    private val catalogDao: CatalogDao,
    private val libraries: LibraryDao,
    private val catalog: Catalog,
) {
    private class Raw(val rows: SessionRows, val progress: List<ProgressEntity>, val items: Map<String, ItemEntity>, val groups: List<List<ItemEntity>>, val zone: ZoneId)

    fun observe(serverId: String, zone: Flow<ZoneId>): Flow<ListeningData> {
        // Only the columns the statistics use (no description): this is read again on every catalog change.
        val titles = catalogDao.observeForStats(serverId).map { rows -> rows.map { it.toEntity(serverId) } }
        val catalogView = combine(titles, libraries.observeAll(serverId)) { items, libs ->
            val podcastLibs = libs.filter { it.effectiveKind == LibraryKind.PODCAST }.mapTo(HashSet()) { it.id }
            items.associateBy { it.id } to catalog.seriesGroups(items.filter { it.libraryId !in podcastLibs })
        }
        return combine(sessions.observeRows(serverId), progress.observeAll(serverId), catalogView, zone.distinctUntilChanged()) { rows, prog, (byId, groups), z ->
            Raw(rows, prog, byId, groups, z)
        }
            .conflate()
            .map { raw ->
                val merged = ListeningStats.merge(raw.rows.server, raw.rows.local, raw.items)
                val days = ListeningStats.days(merged, raw.zone)
                val finished = ListeningStats.finishedBooks(merged, raw.progress, raw.zone, raw.items)
                ListeningData(
                    serverId, merged, days, finished, raw.groups,
                    ListeningStats.finishedSeries(raw.groups, finished.mapTo(HashSet()) { it.itemId }), raw.items, raw.zone,
                )
            }
    }
}
