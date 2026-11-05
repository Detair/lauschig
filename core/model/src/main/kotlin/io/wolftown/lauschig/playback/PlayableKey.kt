package io.wolftown.lauschig.playback

/**
 * Key of anything playable in progress, download and session rows: a book is its plain library-item id, a podcast
 * episode is `itemId~episodeId`. ABS ids never contain `~`, so the first one splits the key.
 */
object PlayableKey {
    private const val SEPARATOR = '~'

    data class Parsed(val libraryItemId: String, val episodeId: String?)

    fun episode(itemId: String, episodeId: String): String = "$itemId$SEPARATOR$episodeId"

    fun parse(key: String): Parsed {
        val i = key.indexOf(SEPARATOR)
        return if (i < 0) Parsed(key, null) else Parsed(key.substring(0, i), key.substring(i + 1))
    }
}
