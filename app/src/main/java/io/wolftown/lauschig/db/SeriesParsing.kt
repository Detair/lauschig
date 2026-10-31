package io.wolftown.lauschig.db

private val LEADING_NUMBER = Regex("""^\s*(\d+(?:\.\d+)?)""")
private val SEQUENCE_LIKE = Regex("""^\d+(?:\.\d+)?(?:\s*-\s*\d+(?:\.\d+)?)?$""")

/** "3" -> 3.0, "3.5" -> 3.5, "1-2" -> 1.0, anything unparsable -> null. */
fun seriesSortKey(sequence: String?): Double? =
    sequence?.let { LEADING_NUMBER.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

/**
 * Parses ABS's minified `seriesName` ("Name #3, Other #1") into the first series and its sequence.
 * Only the first comma-separated segment is used; the sequence is the text after the last " #" of
 * that segment, and only if it looks like a sequence. Limitation: a comma inside a series title
 * cuts the title short (rare, accepted).
 */
fun parseMinifiedSeries(raw: String?): Pair<String, String?>? {
    val segment = raw?.split(',')?.first()?.trim().orEmpty()
    if (segment.isEmpty()) return null
    val idx = segment.lastIndexOf(" #")
    if (idx > 0) {
        val seq = segment.substring(idx + 2).trim()
        val name = segment.substring(0, idx).trim()
        if (name.isNotEmpty() && SEQUENCE_LIKE.matches(seq)) return name to seq
    }
    return segment to null
}
