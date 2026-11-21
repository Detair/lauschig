package io.wolftown.lauschig.serverqr

import io.wolftown.lauschig.connection.ServerAddresses
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What a scanned text turned out to be ([ServerQr.read]). */
sealed interface ServerQrResult {
    /** The two addresses as the setup form writes them; an address the code does not have is empty. At least one is not. */
    data class Addresses(val local: String, val public: String) : ServerQrResult

    /** Nothing of the text is used. */
    data class Rejected(val reason: ServerQrError) : ServerQrResult
}

enum class ServerQrError {
    /** Another QR code (a web address, a Wi-Fi, ...) or one that says something twice. */
    NOT_A_SERVER_CODE,

    /** A format of another version of the app. */
    UNKNOWN_VERSION,

    /** A server code without any address. */
    NO_ADDRESS,

    /** An address that is not a plain `http://` or `https://` address. */
    BAD_ADDRESS,

    /** An address with a user name or password in it (`https://user:pass@host`). */
    CREDENTIALS,

    /** Longer than [ServerQr.MAX_LENGTH]. */
    TOO_LONG,
}

/** What "Server-Adressen als QR-Code" shows: the code's [text] and the [addresses] in it as plain text, local first. */
data class ServerQrCode(val text: String, val addresses: List<String>)

/**
 * The text of the server QR code (docs/server-qr-code.md):
 *
 * `lauschig://server?v=1&local=<address>&public=<address>`
 *
 * It carries where a server is, and nothing about who signs in there: no user name, password, token, profile or Wi-Fi
 * name. The writer puts nothing else in, the reader takes nothing else out. Only the app's own scanner hands a text to
 * [read]; no link from outside does (the manifest has no filter for it), so a web page cannot fill in the setup form.
 */
object ServerQr {
    /** Longer texts are refused unread. Two addresses need far less. */
    const val MAX_LENGTH = 1024

    private const val HEAD = "lauschig://server"
    private const val VERSION = "1"
    private val KEYS = setOf("v", "local", "public")

    /** The code's text for [addresses], or null without any address. Whatever else the account has stays out. */
    fun text(addresses: ServerAddresses): String? {
        val local = addresses.local?.let(::plain)
        val public = addresses.public?.let(::plain)
        if (local == null && public == null) return null
        return buildString {
            append(HEAD).append("?v=").append(VERSION)
            local?.let { append("&local=").append(encode(it)) }
            public?.let { append("&public=").append(encode(it)) }
        }
    }

    /** The code for the page that shows it, or null without any address. */
    fun code(addresses: ServerAddresses): ServerQrCode? =
        text(addresses)?.let { ServerQrCode(it, listOfNotNull(addresses.local?.let(::plain), addresses.public?.let(::plain))) }

    /**
     * What a scanned [text] holds. Everything but `v`, `local` and `public` is dropped here and kept nowhere. Blanks
     * around the text do not count (a tool may end it with a line break); inside an address they refuse it.
     */
    fun read(text: String): ServerQrResult {
        if (text.length > MAX_LENGTH) return ServerQrResult.Rejected(ServerQrError.TOO_LONG)
        val body = text.trim().substringBefore('#')
        val mark = body.indexOf('?')
        val head = if (mark < 0) body else body.substring(0, mark)
        if (mark < 0 || !(head.equals(HEAD, ignoreCase = true) || head.equals("$HEAD/", ignoreCase = true))) {
            return ServerQrResult.Rejected(ServerQrError.NOT_A_SERVER_CODE)
        }
        val known = HashMap<String, String>()
        for (part in body.substring(mark + 1).split('&')) {
            val key = part.substringBefore('=')
            if (key !in KEYS) continue
            // The same key twice: which one counts would be a guess.
            if (known.put(key, part.substringAfter('=', "")) != null) return ServerQrResult.Rejected(ServerQrError.NOT_A_SERVER_CODE)
        }
        if (known["v"] != VERSION) return ServerQrResult.Rejected(ServerQrError.UNKNOWN_VERSION)
        val local = address(known["local"])
        val public = address(known["public"])
        // One address that cannot be taken refuses the whole code: half a code is not what its maker meant.
        listOf(local, public).firstNotNullOfOrNull { it as? ServerQrError }?.let { return ServerQrResult.Rejected(it) }
        if (local == null && public == null) return ServerQrResult.Rejected(ServerQrError.NO_ADDRESS)
        return ServerQrResult.Addresses((local as String?).orEmpty(), (public as String?).orEmpty())
    }

    /** Null: none. A [String]: the address as the form writes it. A [ServerQrError]: not an address to take. */
    private fun address(raw: String?): Any? {
        if (raw.isNullOrEmpty()) return null
        val decoded = decode(raw) ?: return ServerQrError.BAD_ADDRESS
        return check(decoded)
    }

    /**
     * The same rules for an address that comes as plain text, not percent-encoded (the TV setup packet,
     * [io.wolftown.lauschig.tvsetup.HandoverFormat]): the address as the form writes it, or null when the code would
     * refuse it. One rule set for both ways in, so a phone cannot hand the TV what the scanner would not take.
     */
    fun serverAddress(value: String): String? = check(value) as? String

    /** A [String]: the address as the form writes it. A [ServerQrError]: not an address to take. */
    private fun check(decoded: String): Any {
        if (decoded.any { it.isWhitespace() || it.isISOControl() }) return ServerQrError.BAD_ADDRESS
        val scheme = decoded.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return ServerQrError.BAD_ADDRESS
        val url = decoded.toHttpUrlOrNull() ?: return ServerQrError.BAD_ADDRESS
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return ServerQrError.CREDENTIALS
        if (url.query != null || url.fragment != null) return ServerQrError.BAD_ADDRESS
        return url.toString().trimEnd('/')
    }

    /**
     * Scheme, host, port and path, as the setup form shows an account's address; never a user, a query or a fragment.
     * Also what the phone hands a TV (`ui/tvsetup`), so both ways out write an account's address the same way.
     */
    internal fun plain(url: HttpUrl): String =
        url.newBuilder().username("").password("").query(null).fragment(null).build().toString().trimEnd('/')

    /** Percent-encodes everything but letters, digits and `-._~`. */
    private fun encode(value: String): String = buildString {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (b >= 0 && (c.isLetterOrDigit() || c in "-._~")) append(c)
            else append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
    }

    /** Undoes [encode]; a `+` stays a plus. Null for a broken `%` sequence or bytes that are no UTF-8 text. */
    private fun decode(value: String): String? {
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] == '%') {
                if (i + 2 >= value.length) return null
                val hi = hex(value[i + 1])
                val lo = hex(value[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes.write((hi shl 4) or lo)
                i += 3
            } else {
                val end = value.indexOf('%', i).let { if (it < 0) value.length else it }
                bytes.write(value.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private const val HEX = "0123456789ABCDEF"
}
