package io.wolftown.lauschig.ui.detail

import java.text.Normalizer

/**
 * The chapter filter of the player's contents sheet. Word-prefix like the catalog search: every word of the query must
 * begin a word of the chapter title, ignoring case, accents and umlauts („mädchen“, „madchen“ and „maedchen“ find
 * „Mädchen“, „strasse“ finds „Straße“), whether a title or query comes composed or decomposed (NFC/NFD). A number also
 * matches the chapter's own number – „12“ finds chapter 12 whatever its title – and leading zeros do not count („7“
 * finds „Track 007“). Letters and digits match with or without a space between them („cd2“ finds „CD 2“ and „CD2“,
 * „cd 2“ finds both too). Hits stay in book order.
 *
 * The words of the chapters are prepared once per chapter list ([index]); a keystroke only compares prefixes ([hits]).
 */
object ChapterSearch {
    /** The prepared words of a chapter list, by [ChapterRow.index]. */
    class Index internal constructor(internal val words: Map<Int, Set<String>>)

    /** The words of [chapters] (pairs of [ChapterRow.index] and title). Not trivially cheap for long lists: off the main thread. */
    fun index(chapters: List<Pair<Int, String>>): Index =
        Index(chapters.associate { (i, title) -> i to (titleWords(title) + (i + 1).toString()) })

    /** True once the query has a word (letters or digits): the list is filtered (and shows numbers). */
    fun isActive(query: String): Boolean = tokens(query).isNotEmpty()

    /** The [ChapterRow.index]es that match [query]; null for a query without words (nothing is filtered). */
    fun hits(index: Index, query: String): Set<Int>? {
        val terms = tokens(query).map { forms(joined(it)) }
        if (terms.isEmpty()) return null
        return index.words.filterValues { words -> terms.all { term -> term.any { t -> words.any { it.startsWith(t) } } } }.keys
    }

    /** [hits] applied to [chapters], preparing the words on the spot (tests, short lists). */
    fun filter(chapters: List<ChapterRow>, query: String): List<ChapterRow> {
        val hits = hits(index(chapters.map { it.index to it.title }), query) ?: return chapters
        return chapters.filter { it.index in hits }
    }

    /**
     * All words a title can be found by: each word (digits without leading zeros), the letter and digit runs of a mixed
     * word („cd2“ → cd, 2), a letter word joined with a number after it („cd 2“ → cd2), each in all its [forms].
     */
    private fun titleWords(title: String): Set<String> {
        val tokens = tokens(title)
        val words = HashSet<String>()
        tokens.forEachIndexed { i, token ->
            words += forms(joined(token))
            val runs = runs(token)
            if (runs.size > 1) runs.forEach { words += forms(it) }
            val next = tokens.getOrNull(i + 1)
            if (next != null && token.none { it.isDigit() } && next.all { it.isDigit() }) words += forms(token + trimZeros(next))
        }
        return words
    }

    /**
     * Lowercase words, split at anything that is not a letter, mark or digit – after composing (NFC), so a decomposed
     * „ä“ (a + U+0308) stays one word.
     */
    private fun tokens(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    private fun runs(word: String): List<String> = RUNS.findAll(word).map { trimZeros(it.value) }.toList()

    /** The word with every digit run without its leading zeros („track007“ → track7). */
    private fun joined(word: String): String = runs(word).joinToString("")

    /** The word without diacritics, and with German umlauts written out (ä → ae); ß is ss in both. */
    private fun forms(word: String): Set<String> = setOf(strip(word), strip(word.replace(UMLAUTS) { UMLAUT_SPELLING.getValue(it.value) }))

    private fun strip(word: String): String {
        val decomposed = DIACRITICS.replace(Normalizer.normalize(word, Normalizer.Form.NFD), "")
        return buildString { decomposed.forEach { ch -> append(LIGATURES[ch] ?: ch.toString()) } }
    }

    private fun trimZeros(word: String): String =
        if (word.length > 1 && word.all { it.isDigit() }) word.trimStart('0').ifEmpty { "0" } else word

    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val RUNS = Regex("[\\p{L}\\p{M}]+|\\p{N}+")
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val UMLAUTS = Regex("[äöü]")
    private val UMLAUT_SPELLING = mapOf("ä" to "ae", "ö" to "oe", "ü" to "ue")

    /** Letters NFD does not take apart. */
    private val LIGATURES = mapOf('ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l", 'đ' to "d")
}
