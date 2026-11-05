package io.wolftown.lauschig.diagnostics

/**
 * Removes what must never leave the device from a line of the diagnostic log: tokens, passwords, Authorization and
 * Cookie headers, server addresses and host names, IP addresses, e-mail addresses and the user's own names (server
 * hosts, user names, home Wi-Fi names; [setLiterals]). URLs keep only their scheme and the words of their path that are
 * Audiobookshelf API vocabulary ([PATH_WORDS]); everything else in a URL becomes `<…>`, the query always goes.
 *
 * It only sees free text (log messages, exception messages). What else goes into the log is chosen field by field
 * ([DiagnosticLog]): time, level, tag, exception class names and stack frames – code names, never data.
 */
object LogMasker {
    @Volatile private var literals: List<Regex> = emptyList()

    /**
     * Values to mask wherever they appear, case-insensitive: the account's server hosts, user names, home Wi-Fi names.
     * Values shorter than [MIN_LITERAL] are ignored (they would mangle ordinary words).
     */
    fun setLiterals(values: Collection<String>) {
        literals = values.map { it.trim() }.filter { it.length >= MIN_LITERAL }.distinct().sortedByDescending { it.length }
            .map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
    }

    fun mask(text: String): String {
        var s = text
        s = HEADER.replace(s) { m -> "${m.groupValues[1]}: $REDACTED" }
        s = URL.replace(s) { m ->
            // A sentence's full stop or comma after a URL is not part of it.
            val trail = m.value.takeLastWhile { it in ".,;:!?" }
            maskUrl(m.value.dropLast(trail.length)) + trail
        }
        s = AUTH_SCHEME.replace(s) { m -> "${m.groupValues[1]} $REDACTED" }
        s = JWT.replace(s, TOKEN)
        s = JSON_SECRET.replace(s) { m -> "\"${m.groupValues[1]}\":\"$REDACTED\"" }
        s = KEY_VALUE.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}$REDACTED" }
        for (r in literals) s = r.replace(s, LITERAL)
        s = EMAIL.replace(s, "<email>")
        s = IPV6.replace(s) { m -> if (looksLikeIpv6(m.groupValues[1])) IP else m.value }
        s = IPV4.replace(s, IP)
        s = DOMAIN.replace(s) { m -> if (isCodeOrFile(m.value)) m.value else HOST }
        s = LONG_SECRET.replace(s, TOKEN)
        return s
    }

    private fun maskUrl(url: String): String {
        val scheme = url.substringBefore("://").lowercase()
        if (scheme !in WEB_SCHEMES) return "$scheme://$ELLIPSIS"
        val rest = url.substringAfter("://")
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (pathStart < 0) return "$scheme://$HOST"
        val pathAndMore = rest.substring(pathStart)
        val path = pathAndMore.substringBefore('?').substringBefore('#')
        val words = path.split('/').map { seg -> if (seg.isEmpty() || seg.lowercase() in PATH_WORDS) seg else ELLIPSIS }.joinToString("/")
        val more = if (pathAndMore.length > path.length) "?$ELLIPSIS" else ""
        return "$scheme://$HOST$words$more"
    }

    private fun looksLikeIpv6(candidate: String): Boolean {
        val colons = candidate.count { it == ':' }
        return colons >= 2 && ("::" in candidate || colons >= 5) && candidate.any { it.isLetterOrDigit() }
    }

    /** Java/Kotlin names (packages, classes) and file names look like host names but are not. */
    private fun isCodeOrFile(token: String): Boolean {
        val lower = token.lowercase()
        if (CODE_PREFIXES.any { lower.startsWith(it) }) return true
        val labels = token.split('.')
        if (labels.any { it.firstOrNull()?.isUpperCase() == true }) return true
        return labels.last().lowercase() in FILE_EXTENSIONS
    }

    private const val MIN_LITERAL = 3
    private const val REDACTED = "<redacted>"
    private const val TOKEN = "<token>"
    private const val HOST = "<server>"
    private const val IP = "<ip>"
    private const val LITERAL = "<private>"
    private const val ELLIPSIS = "<…>"

    private val WEB_SCHEMES = setOf("http", "https", "ws", "wss")

    private val HEADER = Regex(
        """(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token|x-access-token)\s*:\s*[^\r\n]*""",
    )
    private val AUTH_SCHEME = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*""")

    private const val SECRET_KEYS =
        "access_?token|refresh_?token|id_?token|token|api_?key|apikey|password|passwd|pwd|pass|secret|client_?secret|" +
            "code_?verifier|session_?id|sessionid|connect\\.sid|sid|username|user_?name|email|e-mail"
    private val JSON_SECRET = Regex("""(?i)"($SECRET_KEYS)"\s*:\s*"(?:[^"\\]|\\.)*"""")
    private val KEY_VALUE = Regex("""(?i)\b($SECRET_KEYS)(\s*[=:]\s*)(?!<)("[^"]*"|'[^']*'|[^\s"'&,;)}\]]+)""")

    private val URL = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]{1,15}://[^\s"'<>\])}]*""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?![\d.])""")
    private val IPV6 = Regex("""(?<![\w:.])\[?((?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4})\]?(?::\d{1,5})?(?![\w:])""")
    private val DOMAIN = Regex("""(?<![\w.@/<-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z][A-Za-z0-9-]*[A-Za-z0-9](?::\d{1,5})?(?![\w-])""")
    /** Anything left that looks like a key: 40+ letters and digits (both) without a break. Ids (UUIDs: 36) stay. */
    private val LONG_SECRET = Regex("""(?<![\w-])(?=[A-Za-z_-]*\d)(?=[\d_-]*[A-Za-z])[A-Za-z0-9_-]{40,}(?![\w-])""")

    private val CODE_PREFIXES = listOf(
        "io.wolftown.", "java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.", "okhttp3.", "okio.", "com.google.",
        "com.android.", "dagger.", "coil3.", "retrofit2.", "dalvik.", "libcore.", "sun.", "org.json.", "org.chromium.",
    )

    private val FILE_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "jpg", "jpeg", "png", "webp", "gif",
        "txt", "json", "part", "db", "kt", "java", "xml", "html", "nomedia", "tmp", "log", "pdf", "epub",
    )

    /** Audiobookshelf API path words; every other path segment is masked (ids, share slugs, names). */
    private val PATH_WORDS = setOf(
        "api", "items", "item", "libraries", "library", "play", "file", "files", "cover", "me", "progress", "session",
        "sessions", "sync", "login", "logout", "auth", "status", "ping", "init", "authorize", "callback", "openid", "oauth",
        "search", "series", "authors", "author", "collections", "playlists", "podcasts", "episodes", "episode",
        "listening-sessions", "listening-stats", "bookmarks", "bookmark", "media-progress", "stream", "hls", "public",
        "share", "download", "ebook", "chapters", "users", "tasks", "healthcheck", "socket.io", "feeds", "filterdata",
        "personalized", "stats", "local", "local-all", "close", "batch", "update", "expanded", "token", "refresh",
    )
}
