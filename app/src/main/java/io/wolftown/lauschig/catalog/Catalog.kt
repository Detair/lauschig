package io.wolftown.lauschig.catalog

import io.wolftown.lauschig.db.entities.ItemEntity
import java.text.Normalizer
import kotlin.math.abs
import javax.inject.Inject

enum class PersonRole { AUTHOR, NARRATOR }

/** One entry of a series page: a volume we have, or a whole-numbered volume missing between the first and last. */
sealed interface SeriesEntry {
    data class Volume(val item: ItemEntity) : SeriesEntry
    data class Missing(val sequence: String) : SeriesEntry
}

/** A browsable group (person, series, genre, tag); [coverItemId] picks the tile cover. */
data class Facet(val name: String, val count: Int, val coverItemId: String?)

data class Facets(
    val authors: List<Facet>,
    val narrators: List<Facet>,
    val series: List<Facet>,
    val genres: List<Facet>,
    val tags: List<Facet>,
)

data class SearchResult(
    val titles: List<ItemEntity>,
    val authors: List<Facet>,
    val narrators: List<Facet>,
    val series: List<Facet>,
    val genres: List<Facet>,
    val tags: List<Facet>,
) {
    val isEmpty: Boolean
        get() = titles.isEmpty() && authors.isEmpty() && narrators.isEmpty() && series.isEmpty() && genres.isEmpty() && tags.isEmpty()
}

/**
 * Stateless catalog evaluation over a list of items (everything is read from Room, nothing from the network).
 * Pure functions, so callers run them on Dispatchers.Default.
 */
class Catalog @Inject constructor() {

    /** ABS joins several people as "A, B"; trimmed, empty entries dropped. */
    fun splitPeople(names: String?): List<String> =
        names?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** Exact (case-insensitive) match on one of the split names; never a substring. */
    fun byPerson(items: List<ItemEntity>, role: PersonRole, name: String): List<ItemEntity> {
        val key = name.trim().lowercase()
        return items.filter { i -> people(i, role).any { it.lowercase() == key } }.sortedBy { it.title.lowercase() }
    }

    /**
     * Volumes ordered by `seriesSortKey`, titles of unnumbered volumes last. Missing whole numbers strictly between
     * the smallest and largest whole-numbered key are inserted as [SeriesEntry.Missing]; decimals never create gaps.
     */
    fun bySeries(items: List<ItemEntity>, name: String): List<SeriesEntry> {
        val key = name.trim().lowercase()
        val volumes = items.filter { it.seriesName?.trim()?.lowercase() == key }
        val keyed = volumes.filter { it.seriesSortKey != null }.sortedWith(compareBy({ it.seriesSortKey }, { it.title.lowercase() }))
        val unkeyed = volumes.filter { it.seriesSortKey == null }.sortedBy { it.title.lowercase() }

        // Sequences are free text on the server ("20231015" happens): only finite, plausibly small keys mark gaps.
        val present = keyed.mapNotNull { it.seriesSortKey }
            .filter { it.isFinite() && it % 1.0 == 0.0 && abs(it) <= MAX_GAP_KEY }.map { it.toLong() }.toSet()
        val missing: List<Long> =
            if (present.size < 2 || present.max() - present.min() > MAX_GAP_SPAN) emptyList()
            else (present.min() + 1 until present.max()).asSequence().filter { it !in present }.take(MAX_GAPS).toList()

        val entries = ArrayList<Pair<Double, SeriesEntry>>(keyed.size + missing.size)
        keyed.forEach { entries += it.seriesSortKey!! to SeriesEntry.Volume(it) }
        missing.forEach { entries += it.toDouble() to SeriesEntry.Missing(it.toString()) }
        // sortedBy is stable: volumes sharing a key keep the title order from above.
        return entries.sortedBy { it.first }.map { it.second } + unkeyed.map { SeriesEntry.Volume(it) }
    }

    fun byGenre(items: List<ItemEntity>, genre: String): List<ItemEntity> = byList(items, genre) { it.genres }

    fun byTag(items: List<ItemEntity>, tag: String): List<ItemEntity> = byList(items, tag) { it.tags }

    /** All facets, most items first, then name; empty names are skipped. */
    fun facets(items: List<ItemEntity>): Facets {
        val byTitle = items.sortedBy { it.title.lowercase() }
        return Facets(
            authors = buildFacets(byTitle) { people(it, PersonRole.AUTHOR) },
            narrators = buildFacets(byTitle) { people(it, PersonRole.NARRATOR) },
            series = buildFacets(byTitle, coverOrder = compareBy<ItemEntity> { it.seriesSortKey ?: Double.MAX_VALUE }) {
                listOfNotNull(it.seriesName?.trim()?.takeIf(String::isNotEmpty))
            },
            genres = buildFacets(byTitle) { clean(it.genres) },
            tags = buildFacets(byTitle) { clean(it.tags) },
        )
    }

    /**
     * Word-prefix search, ignoring case and diacritics. Every query word must prefix a word of the title, subtitle,
     * author, narrator or series; hits in the title come first, then by title. Facets match on their own name.
     */
    fun search(items: List<ItemEntity>, query: String): SearchResult {
        val tokens = words(query)
        if (tokens.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        val titleHits = ArrayList<ItemEntity>()
        val otherHits = ArrayList<ItemEntity>()
        for (item in items) {
            val titleWords = words(item.title)
            if (matches(tokens, titleWords)) {
                titleHits += item
            } else {
                val all = titleWords + words(item.subtitle) + words(item.author) + words(item.narrator) + words(item.seriesName)
                if (matches(tokens, all)) otherHits += item
            }
        }
        val order = compareBy<ItemEntity> { it.title.lowercase() }
        val facets = facets(items)
        fun List<Facet>.hits() = filter { matches(tokens, words(it.name)) }
        return SearchResult(
            titles = titleHits.sortedWith(order) + otherHits.sortedWith(order),
            authors = facets.authors.hits(),
            narrators = facets.narrators.hits(),
            series = facets.series.hits(),
            genres = facets.genres.hits(),
            tags = facets.tags.hits(),
        )
    }

    private fun people(item: ItemEntity, role: PersonRole) =
        splitPeople(if (role == PersonRole.AUTHOR) item.author else item.narrator)

    private fun clean(values: List<String>) = values.map { it.trim() }.filter { it.isNotEmpty() }

    private fun byList(items: List<ItemEntity>, value: String, field: (ItemEntity) -> List<String>): List<ItemEntity> {
        val key = value.trim().lowercase()
        return items.filter { i -> field(i).any { it.trim().lowercase() == key } }.sortedBy { it.title.lowercase() }
    }

    /** [items] must be in title order: the first one seen provides the display name and, unless overridden, the cover. */
    private fun buildFacets(
        items: List<ItemEntity>,
        coverOrder: Comparator<ItemEntity>? = null,
        names: (ItemEntity) -> List<String>,
    ): List<Facet> {
        class Acc(val name: String, var count: Int, var cover: ItemEntity)
        val acc = LinkedHashMap<String, Acc>()
        for (item in items) {
            for (name in names(item).distinctBy { it.lowercase() }) {
                val a = acc.getOrPut(name.lowercase()) { Acc(name, 0, item) }
                a.count++
                if (coverOrder != null && coverOrder.compare(item, a.cover) < 0) a.cover = item
            }
        }
        return acc.values.map { Facet(it.name, it.count, it.cover.id) }
            .sortedWith(compareByDescending<Facet> { it.count }.thenBy { it.name.lowercase() })
    }

    private fun matches(tokens: List<String>, words: List<String>) = tokens.all { t -> words.any { it.startsWith(t) } }

    /** Lowercased, diacritics stripped, split at anything that is not a letter or digit. */
    private fun words(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val folded = DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
        return folded.split(NON_WORD).filter { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_GAPS = 1000

        /** Gaps are only marked when the whole-numbered keys span at most this many numbers; wider means it is not a volume count. */
        const val MAX_GAP_SPAN = 500L
        const val MAX_GAP_KEY = 1e9
        val DIACRITICS = Regex("\\p{Mn}+")
        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}
