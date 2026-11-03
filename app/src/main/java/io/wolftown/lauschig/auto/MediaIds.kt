package io.wolftown.lauschig.auto

import io.wolftown.lauschig.local.DeviceLibrary
import java.net.URLDecoder
import java.net.URLEncoder

/** A node of the car's browse tree (see the phase-4A plan). Everything a car or Assistant can name. */
sealed interface CarNode {
    data object Root : CarNode
    /** Root for callers that ask for "recent" media (playback resumption): the last title. */
    data object Recent : CarNode
    data object Continue : CarNode
    data object Downloads : CarNode
    data object SeriesList : CarNode
    /** A series by name (trimmed, case-insensitive, across libraries like the app's series pages). */
    data class Series(val name: String) : CarNode
    data object Libraries : CarNode
    /** A server library, or [DeviceLibrary.LIBRARY_ID] for the titles on this device. */
    data class Library(val libraryId: String) : CarNode
    data class Podcast(val itemId: String) : CarNode
    /** Signed out: the only entry of the root, a hint to sign in on the phone (neither playable nor browsable). */
    data object SignIn : CarNode
    /** Something playable: a book, an episode (`item~episode`) or a device title under [DeviceLibrary.SERVER_ID]. */
    data class Title(val serverId: String, val key: String) : CarNode
}

/**
 * Media ids of the car's browse tree. Ids come back from other apps (Android Auto, Assistant, SystemUI), so [parse]
 * validates every part: a malformed id, a `local:` key under a server id or a server key under the device is unknown.
 * Free text (series names) is URL-encoded, so a `/` in a name never splits the id.
 */
object MediaIds {
    private const val ROOT = "root"
    private const val RECENT = "recent"
    private const val CONTINUE = "continue"
    private const val DOWNLOADS = "downloads"
    private const val SERIES_LIST = "series"
    private const val LIBRARIES = "libraries"
    private const val SERIES = "series/"
    private const val LIBRARY = "library/"
    private const val PODCAST = "podcast/"
    private const val TITLE = "title/"
    private const val SIGN_IN = "signin"

    private val SERVER_ID = Regex("[0-9a-f]{16}")
    private val ABS_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val SERVER_KEY = Regex("[A-Za-z0-9_-]{1,128}(~[A-Za-z0-9_-]{1,128})?")

    fun of(node: CarNode): String = when (node) {
        CarNode.Root -> ROOT
        CarNode.Recent -> RECENT
        CarNode.Continue -> CONTINUE
        CarNode.Downloads -> DOWNLOADS
        CarNode.SeriesList -> SERIES_LIST
        CarNode.Libraries -> LIBRARIES
        CarNode.SignIn -> SIGN_IN
        is CarNode.Series -> SERIES + URLEncoder.encode(node.name, "UTF-8")
        is CarNode.Library -> LIBRARY + node.libraryId
        is CarNode.Podcast -> PODCAST + node.itemId
        is CarNode.Title -> "$TITLE${node.serverId}/${node.key}"
    }

    fun parse(id: String?): CarNode? = when {
        id == null -> null
        id == ROOT -> CarNode.Root
        id == RECENT -> CarNode.Recent
        id == CONTINUE -> CarNode.Continue
        id == DOWNLOADS -> CarNode.Downloads
        id == SERIES_LIST -> CarNode.SeriesList
        id == LIBRARIES -> CarNode.Libraries
        id == SIGN_IN -> CarNode.SignIn
        id.startsWith(SERIES) -> seriesName(id.removePrefix(SERIES))?.let(CarNode::Series)
        id.startsWith(LIBRARY) -> id.removePrefix(LIBRARY).takeIf { it == DeviceLibrary.LIBRARY_ID || ABS_ID.matches(it) }?.let(CarNode::Library)
        id.startsWith(PODCAST) -> id.removePrefix(PODCAST).takeIf(ABS_ID::matches)?.let(CarNode::Podcast)
        id.startsWith(TITLE) -> title(id.removePrefix(TITLE))
        else -> null
    }

    private fun seriesName(encoded: String): String? = try {
        URLDecoder.decode(encoded, "UTF-8").takeIf { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun title(rest: String): CarNode.Title? {
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val serverId = rest.substring(0, slash)
        val key = rest.substring(slash + 1)
        val ok = if (DeviceLibrary.isLocal(serverId)) DeviceLibrary.isValidId(key) else SERVER_ID.matches(serverId) && SERVER_KEY.matches(key)
        return if (ok) CarNode.Title(serverId, key) else null
    }
}

/** Page [page] of [pageSize] entries (Media3 paging); an unpaged legacy load asks with `Int.MAX_VALUE`. */
fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return emptyList()
    val from = page.toLong() * pageSize
    if (from >= size) return emptyList()
    val to = minOf(size.toLong(), from + pageSize).toInt()
    return subList(from.toInt(), to)
}
