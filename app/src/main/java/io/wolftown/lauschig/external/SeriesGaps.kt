package io.wolftown.lauschig.external

import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.seriesSortKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

/** One volume of a series as the Audible catalog lists it. Serialised into the `external_series` cache. */
@Serializable
data class AudibleVolume(
    val asin: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val sequence: String? = null,
    val sort: Int? = null,
    @Serializable(with = LocalDateSerializer::class) val releaseDate: LocalDate? = null,
    val runtimeMin: Int? = null,
    val language: String? = null,
    val abridged: Boolean = false,
    val coverUrl: String? = null,
    val summary: String? = null,
)

/** A volume the user does not own. [upcoming]: release date lies after today. */
data class MissingVolume(val sequence: String, val sortKey: Double, val volume: AudibleVolume, val upcoming: Boolean)

object LocalDateSerializer : KSerializer<LocalDate?> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate?) = encoder.encodeString(value?.toString() ?: "")
    override fun deserialize(decoder: Decoder): LocalDate? = runCatching { LocalDate.parse(decoder.decodeString()) }.getOrNull()
}

private val FAR_FUTURE = LocalDate.of(2100, 1, 1)

/** Normalises ABS ("German", "Deutsch", "de", "ger") and Audible ("german") language strings to one code. */
fun normalizeLanguage(raw: String?): String? {
    val s = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val base = s.substringBefore('-').substringBefore('_')
    return when (base) {
        "german", "deutsch", "de", "ger", "deu" -> "de"
        "english", "englisch", "en", "eng" -> "en"
        "french", "francais", "français", "franzosisch", "französisch", "fr", "fre", "fra" -> "fr"
        "spanish", "spanisch", "espanol", "español", "es", "spa" -> "es"
        "italian", "italienisch", "italiano", "it", "ita" -> "it"
        else -> base
    }
}

/**
 * Volumes of [children] whose number [owned] does not cover. A number counts as owned when any own volume (in any
 * language) has the same sort key. Boxes ("1-3", "4,5") and unnumbered entries are ignored. If several catalog
 * entries share a number, the one in the language most of [owned] is in wins, then unabridged, then the earliest release.
 */
fun missingVolumes(owned: List<ItemEntity>, children: List<AudibleVolume>, today: LocalDate): List<MissingVolume> {
    val ownedKeys = owned.mapNotNull { it.seriesSortKey ?: seriesSortKey(it.seriesSequence) }.toSet()
    val preferredLanguage = owned.mapNotNull { normalizeLanguage(it.language) }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    return children.mapNotNull { v ->
        val seq = v.sequence?.trim().orEmpty()
        if (seq.isEmpty() || '-' in seq || ',' in seq) return@mapNotNull null
        val key = seriesSortKey(seq) ?: return@mapNotNull null
        if (key in ownedKeys) return@mapNotNull null
        if (v.releaseDate != null && v.releaseDate >= FAR_FUTURE) return@mapNotNull null
        Triple(key, seq, v)
    }.groupBy { it.first }.map { (key, candidates) ->
        val best = candidates.minWith(
            compareBy<Triple<Double, String, AudibleVolume>>(
                { if (preferredLanguage != null && normalizeLanguage(it.third.language) == preferredLanguage) 0 else 1 },
                { if (it.third.abridged) 1 else 0 },
                { it.third.releaseDate ?: LocalDate.MAX },
                { it.third.asin },
            ),
        )
        MissingVolume(best.second, key, best.third, upcoming = best.third.releaseDate?.isAfter(today) == true)
    }.sortedBy { it.sortKey }
}
