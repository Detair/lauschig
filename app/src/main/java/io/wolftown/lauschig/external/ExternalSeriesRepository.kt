package io.wolftown.lauschig.external

import io.wolftown.lauschig.absapi.AbsJson
import io.wolftown.lauschig.db.dao.ExternalSeriesDao
import io.wolftown.lauschig.db.entities.ExternalSeriesEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import retrofit2.HttpException
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Missing volumes of a series from the Audible catalog, cached for [TTL_MS]. Every failure ends in "no data". */
@Singleton
class ExternalSeriesRepository @Inject constructor(
    private val apis: AudibleApis,
    private val dao: ExternalSeriesDao,
    private val settings: AppSettings,
    private val clock: Clock,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val listSerializer = ListSerializer(AudibleVolume.serializer())

    /** Null = no data (switch off, no ASIN, catalog unreachable and nothing cached). */
    suspend fun missingFor(serverId: String, libraryId: String, seriesName: String, owned: List<ItemEntity>): List<MissingVolume>? {
        if (!settings.missingVolumesEnabled.first()) return null
        val volumes = volumesFor(serverId, libraryId, seriesName, owned) ?: return null
        return missingVolumes(owned, volumes, LocalDate.now(clock))
    }

    private suspend fun volumesFor(serverId: String, libraryId: String, seriesName: String, owned: List<ItemEntity>): List<AudibleVolume>? {
        fresh(dao.get(serverId, libraryId, seriesName))?.let { return it.takeIf(List<AudibleVolume>::isNotEmpty) }
        // One load per series at a time; whoever waits finds the result in the cache afterwards.
        val lock = locks.getOrPut("$serverId\u0000$libraryId\u0000$seriesName") { Mutex() }
        return lock.withLock {
            val cached = try {
                dao.get(serverId, libraryId, seriesName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            fresh(cached)?.let { return@withLock it.takeIf(List<AudibleVolume>::isNotEmpty) }
            val asins = owned.mapNotNull { it.asin?.takeIf(String::isNotBlank) }.distinct().take(MAX_ASINS)
            val loaded = try {
                load(asins, seriesName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val stale = decode(cached).orEmpty()
            val result = loaded?.takeIf { it.isNotEmpty() }
            // Success: full TTL. No data / failure: keep the old list but do not ask again for NEGATIVE_TTL_MS.
            val stampedAt = if (result != null) clock.millis() else clock.millis() - TTL_MS + NEGATIVE_TTL_MS
            val toStore = result ?: stale
            try {
                dao.upsert(
                    ExternalSeriesEntity(serverId, libraryId, seriesName, AbsJson.encodeToString(listSerializer, toStore), stampedAt),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Cache write failed: the loaded data is still good for this call.
            }
            toStore.takeIf { it.isNotEmpty() }
        }
    }

    private fun fresh(row: ExternalSeriesEntity?): List<AudibleVolume>? =
        row?.takeIf { clock.millis() - it.fetchedAt in 0 until TTL_MS }?.let { decode(it) }

    private fun decode(row: ExternalSeriesEntity?): List<AudibleVolume>? =
        row?.let { runCatching { AbsJson.decodeFromString(listSerializer, it.json) }.getOrNull() }

    /**
     * Walks the owned ASINs until one resolves to a non-empty series. Unknown ASINs (404, no series) move on to the next;
     * network and server errors throw, since another ASIN would not help.
     */
    private suspend fun load(asins: List<String>, seriesName: String): List<AudibleVolume>? {
        val primary = settings.audibleRegion.first()
        for (asin in asins) {
            for (region in listOf(primary, AudibleRegions.alternative(primary))) {
                val api = apis.forRegion(region)
                val product = try {
                    api.product(asin).product
                } catch (e: HttpException) {
                    if (e.code() == 404) null else throw e
                }
                val series = pickSeries(product?.series, seriesName)?.takeIf { !it.asin.isNullOrBlank() } ?: continue
                val volumes = children(api, series.asin!!)
                if (volumes.isNotEmpty()) return volumes
            }
        }
        return null
    }

    private fun pickSeries(all: List<AudibleSeriesRef>?, name: String): AudibleSeriesRef? {
        val list = all.orEmpty()
        return list.firstOrNull { norm(it.title) == norm(name) } ?: list.firstOrNull()
    }

    private suspend fun children(api: AudibleCatalog, seriesAsin: String): List<AudibleVolume> {
        val rels = api.seriesChildren(seriesAsin).product?.relationships.orEmpty()
            .filter { it.relationshipToProduct == "child" && !it.asin.isNullOrBlank() }
            .associateBy { it.asin!! }
        if (rels.isEmpty()) return emptyList()
        return api.productsChunked(rels.keys.toList()).mapNotNull { p ->
            val asin = p.asin ?: return@mapNotNull null
            val rel = rels[asin] ?: return@mapNotNull null
            AudibleVolume(
                asin = asin,
                title = p.title ?: return@mapNotNull null,
                subtitle = p.subtitle,
                authors = p.authors.orEmpty().mapNotNull { it.name },
                narrators = p.narrators.orEmpty().mapNotNull { it.name },
                sequence = rel.sequence,
                sort = rel.sort?.toDoubleOrNull()?.toInt(),
                releaseDate = p.releaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                runtimeMin = p.runtimeLengthMin,
                language = p.language,
                abridged = p.formatType.equals("abridged", ignoreCase = true),
                coverUrl = p.productImages?.let { it["500"] ?: it.values.firstOrNull() },
                summary = p.merchandisingSummary,
            )
        }
    }

    private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    companion object {
        val TTL_MS: Long = TimeUnit.DAYS.toMillis(7)
        val NEGATIVE_TTL_MS: Long = TimeUnit.HOURS.toMillis(6)
        private const val MAX_ASINS = 5
    }
}
