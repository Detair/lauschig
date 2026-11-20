package io.wolftown.lauschig.tvsetup

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * What the TV shows while it waits for a phone (docs/superpowers/plans/2026-11-20-tv-phone-setup.md, "Das Protokoll"):
 *
 * `lauschig://tv?v=1&a=<IPv4>&p=<port>&k=<X25519 public key, base64url without padding>`
 *
 * [address] is whatever the TV's port is bound to; whether it may be sent to is decided by [TvSetupCodes.read] and the
 * sender, not here, so the listener can be tested on the loopback.
 */
class TvSetupCode(val address: String, val port: Int, publicKey: ByteArray) {
    private val key: ByteArray = publicKey.copyOf()

    init {
        require(key.size == KEY_SIZE) { "an X25519 public key has $KEY_SIZE bytes" }
    }

    /** A copy: the code must stay what the TV showed, whatever a caller does with the array. */
    val publicKey: ByteArray get() = key.copyOf()

    private val keyText: String = Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    /** The code's line, as the QR code carries it. */
    val text: String = "lauschig://tv?v=1&a=$address&p=$port&k=$keyText"

    /**
     * Four digits both screens show. Someone on the same network who swaps the code for their own gets another number,
     * so a person comparing the two screens notices.
     */
    val number: String = run {
        val digest = MessageDigest.getInstance("SHA-256").digest("lauschig-tv-number-v1\n$address\n$port\n$keyText".toByteArray(Charsets.UTF_8))
        val first = ByteBuffer.wrap(digest, 0, 4).int.toLong() and 0xFFFF_FFFFL
        (first % 10_000).toString().padStart(4, '0')
    }

    /** HPKE's info: a packet sealed for one address and port does not open on another. */
    fun contextInfo(): ByteArray = "lauschig-tv-setup-v1\n$address\n$port".toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is TvSetupCode && other.address == address && other.port == port && other.key.contentEquals(key)

    override fun hashCode(): Int = (address.hashCode() * 31 + port) * 31 + key.contentHashCode()

    // The key and the address stay out of logs and crash reports.
    override fun toString(): String = "TvSetupCode(…)"

    internal companion object {
        const val KEY_SIZE = 32
    }
}

enum class TvSetupCodeError {
    /** Another text, also the server code. */
    NOT_A_TV_CODE,

    /** `v` is missing or is not 1. */
    UNKNOWN_VERSION,

    /** An address, port or key that cannot be, or a part given twice or not at all. */
    BAD_CODE,

    /** A valid IPv4 address outside 10/8, 172.16/12 and 192.168/16. */
    NOT_HOME_NETWORK,

    /** Longer than [TvSetupCodes.MAX_LENGTH]. */
    TOO_LONG,
}

sealed interface TvSetupCodeResult {
    data class Code(val code: TvSetupCode) : TvSetupCodeResult
    data class Rejected(val reason: TvSetupCodeError) : TvSetupCodeResult
}

object TvSetupCodes {
    /** A real code needs about 110 characters; anything far longer is refused unread. */
    const val MAX_LENGTH = 256

    private const val HEAD = "lauschig://tv"
    private val KEYS = setOf("v", "a", "p", "k")
    private val KEY_CHARS = Regex("[A-Za-z0-9_-]{43}")
    private val PORT = Regex("[1-9][0-9]{0,4}")
    private val OCTET = Regex("0|[1-9][0-9]{0,2}")

    /** What a scanned [text] holds. Blanks around it do not count; unknown parts are dropped and kept nowhere. */
    fun read(text: String): TvSetupCodeResult {
        if (text.length > MAX_LENGTH) return rejected(TvSetupCodeError.TOO_LONG)
        val body = text.trim().substringBefore('#')
        val mark = body.indexOf('?')
        val head = if (mark < 0) body else body.substring(0, mark)
        if (mark < 0 || !(head.equals(HEAD, ignoreCase = true) || head.equals("$HEAD/", ignoreCase = true))) {
            return rejected(TvSetupCodeError.NOT_A_TV_CODE)
        }
        val known = HashMap<String, String>()
        for (part in body.substring(mark + 1).split('&')) {
            val key = part.substringBefore('=')
            if (key !in KEYS) continue
            // A part twice: which one counts would be a guess, and a guess could send the token elsewhere.
            if (known.put(key, part.substringAfter('=', "")) != null) return rejected(TvSetupCodeError.BAD_CODE)
        }
        if (known["v"] != "1") return rejected(TvSetupCodeError.UNKNOWN_VERSION)
        val address = known["a"] ?: return rejected(TvSetupCodeError.BAD_CODE)
        val bytes = ipv4(address) ?: return rejected(TvSetupCodeError.BAD_CODE)
        if (!isPrivate(bytes)) return rejected(TvSetupCodeError.NOT_HOME_NETWORK)
        val port = known["p"]?.takeIf { PORT.matches(it) }?.toInt()?.takeIf { it in 1024..65535 } ?: return rejected(TvSetupCodeError.BAD_CODE)
        val key = known["k"]?.let(::key) ?: return rejected(TvSetupCodeError.BAD_CODE)
        return TvSetupCodeResult.Code(TvSetupCode(address, port, key))
    }

    /** A literal IPv4 address of 10/8, 172.16/12 or 192.168/16; never resolves a name. */
    fun homeAddress(address: String): Inet4Address? {
        val bytes = ipv4(address)?.takeIf(::isPrivate) ?: return null
        // getByAddress builds the address from the bytes; no lookup happens.
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    /** Four decimal numbers 0–255 without leading zeros: the one spelling, so phone and TV hash the same text. */
    private fun ipv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (!OCTET.matches(part)) return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun isPrivate(b: ByteArray): Boolean {
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
    }

    /** Exactly 32 bytes in the one canonical spelling; any other spelling of the same bytes would change the number. */
    private fun key(text: String): ByteArray? {
        if (!KEY_CHARS.matches(text)) return null
        val bytes = try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size != TvSetupCode.KEY_SIZE) return null
        return bytes.takeIf { Base64.getUrlEncoder().withoutPadding().encodeToString(it) == text }
    }

    private fun rejected(reason: TvSetupCodeError) = TvSetupCodeResult.Rejected(reason)
}
