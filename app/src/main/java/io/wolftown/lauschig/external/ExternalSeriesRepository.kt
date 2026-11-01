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
        fresh(dao.get(serverId, libraryId, seriesName))?.let { return it }
        // One load per series at a time; whoever waits finds the result in the cache afterwards.
        val lock = locks.getOrPut("$serverId\u0000$libraryId\u0000$seriesName") { Mutex() }
        return lock.withLock {
            val cached = dao.get(serverId, libraryId, seriesName)
            fresh(cached)?.let { return@withLock it }
            val loaded = try {
                val asin = owned.firstNotNullOfOrNull { it.asin?.takeIf(String::isNotBlank) } ?: return@withLock decode(cached)
                load(asin, seriesName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (loaded.isNullOrEmpty()) return@withLock decode(cached)
            dao.upsert(
                ExternalSeriesEntity(serverId, libraryId, seriesName, AbsJson.encodeToString(listSerializer, loaded), clock.millis()),
            )
            loaded
        }
    }

    private fun fresh(row: ExternalSeriesEntity?): List<AudibleVolume>? =
        row?.takeIf { clock.millis() - it.fetchedAt in 0 until TTL_MS }?.let { decode(it) }

    private fun decode(row: ExternalSeriesEntity?): List<AudibleVolume>? =
        row?.let { runCatching { AbsJson.decodeFromString(listSerializer, it.json) }.getOrNull() }

    /** Tries the configured region, then its alternative when the ASIN is unknown there. Throws on network/server errors. */
    private suspend fun load(asin: String, seriesName: String): List<AudibleVolume>? {
        val primary = settings.audibleRegion.first()
        for (region in listOf(primary, AudibleRegions.alternative(primary))) {
            val api = apis.forRegion(region)
            val product = try {
                api.product(asin).product
            } catch (e: HttpException) {
                if (e.code() == 404) null else throw e
            }
            val series = pickSeries(product?.series, seriesName)?.takeIf { !it.asin.isNullOrBlank() } ?: continue
            return children(api, series.asin!!)
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
    }
}
