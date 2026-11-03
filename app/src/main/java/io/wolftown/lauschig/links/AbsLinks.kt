package io.wolftown.lauschig.links

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A page of the Audiobookshelf web UI Lauschig can show. */
sealed interface AbsLink {
    /** `/item/<libraryItemId>` */
    data class Item(val itemId: String) : AbsLink

    /** `/library/<libraryId>/series/<seriesId>` */
    data class Series(val libraryId: String, val seriesId: String) : AbsLink
}

sealed interface LinkMatch {
    data class Ok(val link: AbsLink, val server: HttpUrl) : LinkMatch
    /** Not one of the account's server addresses (or no account). */
    data object ForeignServer : LinkMatch
    /** The account's server, but no page Lauschig has (settings, an author page, …), or no URL at all. */
    data object Unsupported : LinkMatch
}

object AbsLinks {
    private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** The first http(s) URL in shared text ("Schau mal: https://… !"), without trailing punctuation. */
    fun firstUrl(text: String): String? = URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')

    /**
     * [url] must have the origin (scheme, host case-insensitively, effective port) of one of [addresses], the
     * signed-in account's local and public address. The route may sit under a path prefix
     * (`/audiobookshelf/item/<id>`); a trailing slash, the query and the fragment do not matter.
     */
    fun parse(url: String, addresses: List<HttpUrl>): LinkMatch {
        val u = url.trim().toHttpUrlOrNull() ?: return LinkMatch.Unsupported
        val server = addresses.firstOrNull { it.scheme == u.scheme && it.host.equals(u.host, ignoreCase = true) && it.port == u.port }
            ?: return LinkMatch.ForeignServer
        val segments = u.pathSegments.filter { it.isNotEmpty() }
        val n = segments.size
        val link = when {
            n >= 2 && segments[n - 2] == "item" && ID.matches(segments[n - 1]) -> AbsLink.Item(segments[n - 1])
            n >= 4 && segments[n - 4] == "library" && segments[n - 2] == "series" && ID.matches(segments[n - 3]) && ID.matches(segments[n - 1]) ->
                AbsLink.Series(segments[n - 3], segments[n - 1])
            else -> null
        } ?: return LinkMatch.Unsupported
        return LinkMatch.Ok(link, server)
    }
}
