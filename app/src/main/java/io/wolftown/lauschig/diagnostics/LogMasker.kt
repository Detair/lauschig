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
            .map { v ->
                // A domain takes its subdomains with it: "abs.example.io" is one name, not "abs." plus a private part.
                val sub = if (DOMAIN_LIKE.matches(v)) """(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)*""" else ""
                Regex(sub + Regex.escape(v), RegexOption.IGNORE_CASE)
            }
    }

    /** Only the user's own values ([setLiterals]): run again over kept entries once those are known (early entries). */
    fun maskLiterals(text: String): String {
        var s = text
        for (r in literals) s = r.replace(s, LITERAL)
        return s
    }

    fun mask(text: String): String {
        var s = text
        s = HEADER.replace(s) { m -> "${m.groupValues[1]}: $REDACTED" }
        // A certificate's subject (OkHttp's "not verified" message): names, organisation and town of its owner.
        if (DN_GATE.containsMatchIn(s)) s = DN_FIELD.replace(s) { m -> "${m.groupValues[1]}=$REDACTED" }
        s = URL.replace(s) { m ->
            // A sentence's full stop or comma after a URL is not part of it.
            val trail = m.value.takeLastWhile { it in ".,;:!?" }
            maskUrl(m.value.dropLast(trail.length)) + trail
        }
        s = USER_PATH.replace(s) { m -> maskUserPath(m.groupValues[1], m.groupValues[2]) }
        s = DOCUMENT_ID.replace(s) { m -> maskUserPath(m.groupValues[1] + ":", "/" + m.groupValues[2]).replaceFirst(":/", ":") }
        s = AUTH_SCHEME.replace(s) { m -> "${m.groupValues[1]} $REDACTED" }
        s = JWT.replace(s, TOKEN)
        s = QUOTED_SECRET.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[1]}${m.groupValues[3]}${m.groupValues[4]}$REDACTED${m.groupValues[4]}" }
        s = KEY_VALUE.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}$REDACTED" }
        s = USER_INFO.replace(s, "$REDACTED@$HOST")
        // Before the own values: "lukas@example.io" is one address, not "lukas@" plus a private domain.
        s = EMAIL.replace(s, "<email>")
        s = maskLiterals(s)
        s = QUOTED_HOST.replace(s) { m -> "${m.groupValues[1]} \"$HOST\"" }
        s = UNVERIFIED_HOST.replace(s) { m -> "${m.groupValues[1]} $HOST" }
        s = IPV6.replace(s) { m -> if (looksLikeIpv6(m.groupValues[1])) IP else m.value }
        s = IPV4.replace(s, IP)
        s = WILDCARD_DOMAIN.replace(s) { m -> if (isCodeOrFile(m.groupValues[2])) m.value else m.groupValues[1] + HOST }
        s = DOMAIN.replace(s) { m -> if (isCodeOrFile(m.value)) m.value else HOST }
        s = LONG_SECRET.replace(s, TOKEN)
        return s
    }

    /**
     * A path in shared storage or on a card ([root] = `/storage/emulated/0`, `/storage/1A2B-3C4D`, …): folder and file
     * names are the user's (titles, children's names) and go; the extension stays. The app's own directory there
     * (`Android/data/<this app>`) is kept: download paths are useful and hold only ids.
     */
    private fun maskUserPath(root: String, rest: String): String {
        if (rest.startsWith(OWN_DIR)) {
            // Only the app's own path stays; what follows on the line (another path) is looked at again.
            val own = rest.indexOfFirst { it.isWhitespace() || it == ',' }.let { if (it < 0) rest.length else it }
            val tail = rest.substring(own)
            return root + rest.substring(0, own) + USER_PATH.replace(tail) { m -> maskUserPath(m.groupValues[1], m.groupValues[2]) }
        }
        // The path ends at ": " (Java's "path: open failed"), " (", " at ", or right after a known file extension.
        var end = listOf(": ", " (", " at ", ", ").mapNotNull { d -> rest.indexOf(d).takeIf { it >= 0 } }.minOrNull() ?: rest.length
        val ext = EXTENSION_IN_PATH.findAll(rest.substring(0, end)).lastOrNull()
        if (ext != null) end = ext.range.last + 1
        val extension = ext?.groupValues?.get(1)?.let { ".$it" }.orEmpty()
        return "$root/$ELLIPSIS$extension" + rest.substring(end)
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

    /** Any key that names a secret, also inside a longer name ("newPassword", "authToken", "authOpenIDClientSecret"). */
    private const val SECRET_KEY =
        "[A-Za-z0-9_.-]*(?:pass(?:word|wd|phrase)?|pwd|secret|token|auth(?!or(?!iz))|cookie|session|api[_-]?key|apikey|" +
            "credential|verifier|sid|username|user_?name|e-?mail)[A-Za-z0-9_.-]*"
    /** `"key": "value"` and `'key': 'value'` (JSON, Python-like dumps): the value goes, whatever it holds. */
    private val QUOTED_SECRET = Regex("""(?i)(["'])($SECRET_KEY)\1(\s*:\s*)(["'])(?:(?!\4)[^\\]|\\.)*\4""")
    /** `key=value` / `key: value`: the value goes up to the next delimiter, several words included. */
    private val KEY_VALUE = Regex("""(?i)(?<![\w.-])($SECRET_KEY)(["']?\s*[=:]\s*)(?!["']?<)("[^"]*"|'[^']*'|[^\s"',;&)}\]][^"',;&)}\]\r\n]*)""")
    /** The fields of a certificate's distinguished name; the value goes up to the next comma or the end of the line. */
    private val DN_FIELD = Regex("""(?i)(?<![\w.-])(CN|O|OU|L|ST|C|STREET|E|EMAILADDRESS|UID|DC|SERIALNUMBER|\d+(?:\.\d+)+)=(?!<)(?:\\.|[^,\\\n])*""")
    /** A distinguished name is near: a CN, O or OU attribute (a DN without CN too). */
    private val DN_GATE = Regex("""(?i)(?<![\w.-])(?:CN|O|OU)=""")
    private val DOMAIN_LIKE = Regex("""[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")

    /** `user:pass@host` without a scheme. */
    private val USER_INFO = Regex("""(?<![\w/@<])[^\s:@/"'<>]+:[^\s@/"'<>]+@[A-Za-z0-9.\[\]:-]+""")
    /** `host "nas"` (UnknownHostException) and `Hostname nas not verified` (SSL): single-word hosts as well. */
    private val QUOTED_HOST = Regex("""(?i)\b(host(?:name)?)\s*"[^"]*"""")
    private val UNVERIFIED_HOST = Regex("""(?i)\b(Hostname)\s+(?!<)\S+(?=\s+not verified)""")

    private val URL = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]{1,15}://[^\s"'<>\])}]*""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?![\d.])""")
    /** Also IPv4-mapped (`::ffff:192.168.1.5`): the dotted tail is part of it. */
    private val IPV6 = Regex("""(?<![\w:.])\[?((?:[0-9A-Fa-f]{0,4}:){2,7}(?:\d{1,3}(?:\.\d{1,3}){3}|[0-9A-Fa-f]{0,4}))\]?(?::\d{1,5})?(?![\w:])""")
    /** `*.example.io` (certificate names) and `.example.io` (cookie domains): the plain rule never starts after a dot. */
    private val WILDCARD_DOMAIN = Regex("""(?<![\w.-])(\*\.|\.)((?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z][A-Za-z0-9-]*[A-Za-z0-9])(?![\w-])""")
    private val DOMAIN = Regex("""(?<![\w.@/<-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z][A-Za-z0-9-]*[A-Za-z0-9](?::\d{1,5})?(?![\w-])""")
    /** Anything left that looks like a key: 40+ letters and digits (both) without a break. Ids (UUIDs: 36) stay. */
    private val LONG_SECRET = Regex("""(?<![\w-])(?=[A-Za-z_-]*\d)(?=[\d_-]*[A-Za-z])[A-Za-z0-9_-]{40,}(?![\w-])""")

    /** Shared storage and cards; the rest of the path up to the end of the line is looked at ([maskUserPath]). */
    private val USER_PATH = Regex("""(/storage/emulated/\d+|/storage/self/primary|/storage/[A-Za-z0-9-]+|/sdcard|/mnt/media_rw/[A-Za-z0-9-]+)(/[^\n"'<>]*)""")
    /** A document id of the storage provider: `primary:Hörbücher/…`, `1A2B-3C4D:…`. */
    private val DOCUMENT_ID = Regex("""\b(primary|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}):(?!/)([^\s"'<>][^\n"'<>]*)""")
    private const val OWN_DIR = "/Android/data/io.wolftown.lauschig"
    private val EXTENSION_IN_PATH = Regex("""\.(mp3|m4a|m4b|mp4|aac|ogg|oga|opus|flac|wav|wma|jpg|jpeg|png|webp|gif|txt|json|part|pdf|epub|nfo|cue|opf)\b""", RegexOption.IGNORE_CASE)

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
