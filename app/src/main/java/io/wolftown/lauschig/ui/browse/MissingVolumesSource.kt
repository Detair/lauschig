package io.wolftown.lauschig.ui.browse

import io.wolftown.lauschig.catalog.SeriesEntry
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.external.ExternalSeriesRepository
import io.wolftown.lauschig.external.MissingVolume
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.settings.AppSettings
import io.wolftown.lauschig.ui.common.ItemSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Audible volumes for a series page. Only asks while online and with the setting on; otherwise (and on any failure)
 * the result is null and the page keeps its plain gap placeholders. The setting stays off until the user agreed
 * ([undecided], [answer]).
 */
class MissingVolumesSource(
    private val online: Flow<Boolean>,
    private val enabled: Flow<Boolean>,
    /** True until the user has answered whether Audible may be asked. */
    val undecided: Flow<Boolean> = flowOf(false),
    private val decide: suspend (Boolean) -> Unit = {},
    private val lookup: suspend (serverId: String, libraryId: String, seriesName: String, owned: List<ItemEntity>) -> List<MissingVolume>?,
) {
    @Inject constructor(api: ApiProvider, settings: AppSettings, repository: ExternalSeriesRepository) : this(
        api.connection.map { it is ConnectionState.Online }.distinctUntilChanged(),
        settings.missingVolumesEnabled,
        settings.missingVolumesUndecided,
        settings::setMissingVolumesEnabled,
        repository::missingFor,
    )

    suspend fun answer(allow: Boolean) = decide(allow)

    /**
     * Looks up each library's part of the series (the cache is per library) and merges the answers by volume number.
     * Emits once the lookups are done, or null right away when offline/disabled/nothing to ask for.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun forSeries(serverId: String, ownedByLibrary: Map<String, List<ItemEntity>>): Flow<List<MissingVolume>?> =
        combine(online, enabled) { on, set -> on && set }.distinctUntilChanged().flatMapLatest { go ->
            if (!go || ownedByLibrary.isEmpty()) flowOf(null)
            else flow {
                val results = ownedByLibrary.map { (libraryId, owned) ->
                    val name = owned.firstNotNullOfOrNull { it.seriesName?.trim()?.takeIf(String::isNotEmpty) } ?: return@map null
                    lookup(serverId, libraryId, name, owned)
                }
                emit(results.filterNotNull().flatten().distinctBy { it.sortKey }.sortedBy { it.sortKey }.takeIf { results.any { it != null } })
            }
        }
}

/** Replaces matching 2a gaps by Audible volumes and inserts the others in order; volumes the page already owns never appear. */
internal fun mergeMissing(
    entries: List<SeriesEntry>,
    missing: List<MissingVolume>?,
    summary: (ItemEntity) -> ItemSummary,
): List<BrowseEntry> {
    val byKey = missing.orEmpty().associateBy { it.sortKey }
    val ownedKeys = entries.mapNotNull { (it as? SeriesEntry.Volume)?.item?.seriesSortKey }.toSet()
    val keyed = ArrayList<Pair<Double, BrowseEntry>>()
    val unkeyed = ArrayList<BrowseEntry>()
    val usedKeys = HashSet<Double>()
    for (e in entries) when (e) {
        is SeriesEntry.Volume -> {
            val tile = BrowseEntry.Tile(summary(e.item))
            e.item.seriesSortKey?.let { keyed += it to tile } ?: unkeyed.add(tile)
        }
        is SeriesEntry.Missing -> {
            val key = e.sequence.toDoubleOrNull() ?: continue // gaps are whole numbers; anything else is not placeable
            val v = byKey[key]
            if (v != null) usedKeys += key
            keyed += key to BrowseEntry.Missing(v?.sequence ?: e.sequence, v)
        }
    }
    byKey.values.filter { it.sortKey !in usedKeys && it.sortKey !in ownedKeys }.forEach { keyed += it.sortKey to BrowseEntry.Missing(it.sequence, it) }
    return keyed.sortedBy { it.first }.map { it.second } + unkeyed
}
