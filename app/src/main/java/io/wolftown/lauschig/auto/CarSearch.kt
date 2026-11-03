package io.wolftown.lauschig.auto

import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.local.NaturalOrder
import java.text.Normalizer

/** What the Assistant understood besides the raw query (`MediaStore.EXTRA_MEDIA_TITLE`, `_ARTIST`, `_ALBUM`). */
data class SearchHints(val title: String? = null, val artist: String? = null, val album: String? = null)

/**
 * Search and "play from search" for the car and the Assistant, over the account's titles (no podcasts) and the device
 * titles in Room. Word-prefix matching that ignores case, accents and punctuation, and understands what a voice query
 * says for the symbols of series names ("Die drei ???" = "die drei Fragezeichen"). Offline only playable titles count.
 */
class CarSearch(private val library: CarLibrary) {

    /** Search results: matching series as folders first, then titles; at most [MAX_RESULTS]. */
    suspend fun search(query: String, ctx: CarContext): List<CarEntry> {
        val tokens = words(query)
        if (tokens.isEmpty()) return emptyList()
        val snap = library.snapshot(ctx)
        val series = library.seriesEntries(snap).filter { e -> matchesAll(tokens, words(e.title)) }
        val titleHits = ArrayList<ItemEntity>()
        val otherHits = ArrayList<ItemEntity>()
        for (item in snap.playableTitles) {
            when {
                matchesAll(tokens, words(item.title)) -> titleHits += item
                matchesAll(tokens, allWords(item)) -> otherHits += item
            }
        }
        val order = compareBy<ItemEntity, String>(NaturalOrder) { it.title }
        val titles = (titleHits.sortedWith(order) + otherHits.sortedWith(order)).map { library.titleEntry(it, snap) }
        return (series + titles).take(MAX_RESULTS)
    }

    /**
     * The one title a voice query means (see the phase-4A plan): empty → the last title on this device ([last]), else
     * the first to continue; a series (optionally with a volume number) → that volume or the one to
     * listen to now; else the best title match (title over author/series words, in progress first). Null: nothing.
     * [ctx] should come after the connection check ([awaitOnline]), else streamed titles are not found.
     */
    suspend fun resolve(query: String, hints: SearchHints, ctx: CarContext, last: suspend () -> CarNode.Title?): CarNode.Title? {
        val q = query.trim().ifEmpty { listOfNotNull(hints.title ?: hints.album, hints.artist).joinToString(" ") }
        if (q.isBlank()) {
            // The last title, even if it cannot stream right now: the start then says so ("not downloaded") instead of
            // silently playing another book.
            last()?.let { return it }
            return library.children(CarNode.Continue, ctx)?.firstOrNull()?.node as? CarNode.Title
        }
        val tokens = words(q)
        if (tokens.isEmpty()) return null
        val snap = library.snapshot(ctx)
        val candidates = snap.playableTitles
        seriesPick(tokens, candidates, snap)?.let { return it.title() }
        return bestTitle(tokens, candidates, snap)?.title()
    }

    private fun seriesPick(tokens: List<String>, candidates: List<ItemEntity>, snap: CarLibrary.Snapshot): ItemEntity? {
        val (rest, number) = splitVolumeNumber(tokens)
        if (rest.isEmpty()) return null
        val groups = candidates.filter { !DeviceLibrary.isLocal(it.serverId) && !it.seriesName.isNullOrBlank() }
            .groupBy { words(it.seriesName) }
        val exact = groups[rest]
        val match = exact ?: groups.filterKeys { matchesAll(rest, it) }.values
            .maxWithOrNull(compareBy<List<ItemEntity>> { v -> v.maxOf { snap.progressOf(it)?.lastUpdate ?: 0L } }.thenBy { it.size })
            ?: return null
        val volumes = match.sortedWith(compareBy<ItemEntity, Double?>(nullsLast()) { it.seriesSortKey }.thenBy(NaturalOrder) { it.title })
        if (number != null) return volumes.firstOrNull { isVolume(it, number) }
        return volumeToListen(volumes, snap)
    }

    /** The volume in progress heard last, else the first unfinished one, else the first. */
    private fun volumeToListen(volumes: List<ItemEntity>, snap: CarLibrary.Snapshot): ItemEntity =
        volumes.filter { inProgress(snap.progressOf(it)) }.maxByOrNull { snap.progressOf(it)!!.lastUpdate }
            ?: volumes.firstOrNull { snap.progressOf(it)?.isFinished != true }
            ?: volumes.first()

    private fun bestTitle(tokens: List<String>, candidates: List<ItemEntity>, snap: CarLibrary.Snapshot): ItemEntity? {
        fun score(item: ItemEntity): Int {
            val title = words(item.title)
            return when {
                title == tokens -> 3
                matchesAll(tokens, title) -> 2
                matchesAll(tokens, allWords(item)) -> 1
                else -> 0
            }
        }
        val scored = candidates.map { it to score(it) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.map { it.first }.sortedWith(
            compareByDescending<ItemEntity> { snap.progressOf(it)?.takeIf(::inProgress)?.lastUpdate ?: -1L }
                .thenBy { snap.progressOf(it)?.isFinished == true }
                .thenBy(NaturalOrder) { it.title },
        ).first()
    }

    private fun ItemEntity.title() = CarNode.Title(serverId, id)

    private fun inProgress(p: ProgressEntity?) = p != null && !p.isFinished && p.currentTimeSec > 0 && !p.hideFromContinue

    private fun isVolume(item: ItemEntity, number: Long): Boolean =
        item.seriesSortKey == number.toDouble() || item.seriesSequence?.trim()?.toLongOrNull() == number

    private fun allWords(item: ItemEntity) =
        words(item.title) + words(item.subtitle) + words(item.author) + words(item.narrator) + words(item.seriesName)

    private fun matchesAll(tokens: List<String>, words: List<String>) = tokens.all { t -> words.any { it.startsWith(t) } }

    companion object {
        const val MAX_RESULTS = 50

        /** Words that only announce a number ("Folge 12", "Band 3"). */
        private val NUMBER_WORDS = setOf("folge", "band", "teil", "episode", "nummer", "nr", "book", "part", "volume", "vol")
        private val MARKS = Regex("\\p{Mn}+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val QUESTION_MARKS = Regex("\\?{3}")
        private val EXCLAMATION_MARKS = Regex("!{3}")
        private val DIGITS = Regex("\\d{1,9}")

        /** A trailing number (optionally announced by "Folge", "Band", …) and the words before it. */
        fun splitVolumeNumber(tokens: List<String>): Pair<List<String>, Long?> {
            val last = tokens.lastOrNull() ?: return tokens to null
            if (!DIGITS.matches(last)) return tokens to null
            var rest = tokens.dropLast(1)
            if (rest.lastOrNull() in NUMBER_WORDS) rest = rest.dropLast(1)
            return rest to last.toLong()
        }

        /** Lowercased words without accents; "???" and "!!!" become the words a voice query uses for them. */
        fun words(text: String?): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            val spoken = EXCLAMATION_MARKS.replace(QUESTION_MARKS.replace(text, " fragezeichen "), " ausrufezeichen ")
            val folded = MARKS.replace(Normalizer.normalize(spoken, Normalizer.Form.NFD), "").lowercase()
            return folded.split(NON_WORD).filter { it.isNotEmpty() }
        }
    }
}
