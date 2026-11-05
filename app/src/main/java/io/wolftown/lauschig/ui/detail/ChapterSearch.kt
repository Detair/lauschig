package io.wolftown.lauschig.ui.detail

import java.text.Normalizer

/**
 * The chapter filter of the player's contents sheet. Word-prefix like the catalog search: every word of the query must
 * begin a word of the chapter title, ignoring case, accents and umlauts („mädchen“, „madchen“ and „maedchen“ find
 * „Mädchen“, „strasse“ finds „Straße“). A number also matches the chapter's own number – „12“ finds chapter 12 whatever
 * its title – and leading zeros do not count („7“ finds „Track 007“). Hits stay in book order.
 */
object ChapterSearch {
    /** True once the query is more than blanks: the list is filtered (and shows numbers). */
    fun isActive(query: String): Boolean = query.isNotBlank()

    fun filter(chapters: List<ChapterRow>, query: String): List<ChapterRow> {
        val terms = words(query).map { forms(it) }
        if (terms.isEmpty()) return chapters
        return chapters.filter { c ->
            val words = buildSet {
                words(c.title).forEach { addAll(forms(it)) }
                add((c.index + 1).toString())
            }
            terms.all { term -> term.any { t -> words.any { it.startsWith(t) } } }
        }
    }

    /**
     * Lowercase words of [text], split at anything that is not a letter or digit; a word mixing letters and digits also
     * gives its runs („cd2“ → cd2, cd, 2). Digit runs lose their leading zeros.
     */
    private fun words(text: String): List<String> = text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }.flatMap { word ->
        val runs = RUNS.findAll(word).map { it.value }.toList()
        (if (runs.size > 1) listOf(word) + runs else listOf(word)).map(::trimZeros)
    }

    /** The word without diacritics, and with German umlauts written out (ä → ae); ß is ss in both. */
    private fun forms(word: String): Set<String> = setOf(strip(word), strip(word.replace(UMLAUTS) { UMLAUT_SPELLING.getValue(it.value) }))

    private fun strip(word: String): String {
        val decomposed = DIACRITICS.replace(Normalizer.normalize(word, Normalizer.Form.NFD), "")
        return buildString { decomposed.forEach { ch -> append(LIGATURES[ch] ?: ch.toString()) } }
    }

    private fun trimZeros(word: String): String =
        if (word.length > 1 && word.all { it.isDigit() }) word.trimStart('0').ifEmpty { "0" } else word

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val RUNS = Regex("\\p{L}+|\\p{N}+")
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val UMLAUTS = Regex("[äöü]")
    private val UMLAUT_SPELLING = mapOf("ä" to "ae", "ö" to "oe", "ü" to "ue")

    /** Letters NFD does not take apart. */
    private val LIGATURES = mapOf('ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l", 'đ' to "d")
}
