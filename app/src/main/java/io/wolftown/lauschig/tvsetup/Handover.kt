package io.wolftown.lauschig.tvsetup

import io.wolftown.lauschig.serverqr.ServerQr
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What the phone hands the TV: where the server is and a refresh token of a session made for the TV. */
@Serializable
class PhoneHandover(
    val v: Int = 1,
    val local: String? = null,
    val public: String? = null,
    val refresh: String,
) {
    override fun equals(other: Any?): Boolean =
        other is PhoneHandover && other.v == v && other.local == local && other.public == public && other.refresh == refresh

    override fun hashCode(): Int = listOf(v, local, public, refresh).hashCode()

    // The token and the addresses stay out of logs and crash reports.
    override fun toString(): String = "PhoneHandover(v=$v)"
}

/** The plaintext block: two bytes length (big-endian), that many bytes of JSON, zeros to [PLAIN_SIZE]. */
object HandoverFormat {
    /** Every block has this size, so the packet's length says nothing about the token or the addresses. */
    const val PLAIN_SIZE = 2048

    /** `LTV1` + 32 bytes encapsulated key + [PLAIN_SIZE] + 16 bytes tag. */
    const val PACKET_SIZE = 2100

    private const val VERSION = 1

    /**
     * The longest server address a packet may carry, after normalisation. The TV shows each address in its question
     * before it uses the packet; a very long one would push the warning and the buttons off the screen. A real server
     * address is far shorter.
     */
    const val MAX_ADDRESS_LENGTH = 200

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** [PLAIN_SIZE] bytes; null when the JSON does not fit, or an address is longer than the TV would take. */
    fun encode(handover: PhoneHandover): ByteArray? {
        // The phone never sends what the TV would drop: measured the way decode measures it.
        if (listOfNotNull(handover.local, handover.public).any { (ServerQr.serverAddress(it) ?: it).length > MAX_ADDRESS_LENGTH }) return null
        val bytes = json.encodeToString(PhoneHandover.serializer(), handover).toByteArray(Charsets.UTF_8)
        if (bytes.size > PLAIN_SIZE - 2) return null
        return ByteArray(PLAIN_SIZE).also {
            it[0] = (bytes.size shr 8).toByte()
            it[1] = bytes.size.toByte()
            bytes.copyInto(it, 2)
        }
    }

    /** Null for anything that is not a valid block: a reader that guesses would hand the TV what the phone never meant. */
    fun decode(plain: ByteArray): PhoneHandover? {
        if (plain.size != PLAIN_SIZE) return null
        val length = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        if (length == 0 || length > PLAIN_SIZE - 2) return null
        // Padding that is not zero means the block was not written by this format.
        for (i in 2 + length until PLAIN_SIZE) if (plain[i] != 0.toByte()) return null
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain, 2, length)).toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        if (nestedTooDeep(text)) return null
        val handover = try {
            val element = json.parseToJsonElement(text).jsonObject
            // `v` must be there and be the number 1; the class default would otherwise let a missing one pass.
            val v = element["v"] as? JsonPrimitive ?: return null
            if (v.isString || v.content != VERSION.toString()) return null
            json.decodeFromJsonElement(PhoneHandover.serializer(), element)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (handover.local == null && handover.public == null) return null
        if (handover.refresh.isEmpty()) return null
        // The same rules as the server QR code: http(s), no user, no query, no fragment. The TV goes on with the
        // checked form of each address, not the text that came in.
        val local = handover.local?.let { ServerQr.serverAddress(it)?.takeIf { a -> a.length <= MAX_ADDRESS_LENGTH } ?: return null }
        val public = handover.public?.let { ServerQr.serverAddress(it)?.takeIf { a -> a.length <= MAX_ADDRESS_LENGTH } ?: return null }
        return PhoneHandover(handover.v, local, public, handover.refresh)
    }

    /**
     * The parser recurses per bracket level, so a decrypted block of a thousand `[` would end in a StackOverflowError,
     * which no `catch (Exception)` stops. A valid block has depth 1; anything beyond [MAX_DEPTH] is refused unparsed.
     * Brackets inside string literals do not count, so a token or address containing them still passes.
     */
    private fun nestedTooDeep(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> if (++depth > MAX_DEPTH) return true
                ']', '}' -> depth--
            }
        }
        return false
    }

    private const val MAX_DEPTH = 4
}

/** The TV's one-byte answer; the code is what goes over the wire. */
enum class HandoverReply(val code: Int) {
    SIGNED_IN(1),
    NOT_ACCEPTED(2),
    SERVER_UNREACHABLE(3),
    SIGN_IN_REFUSED(4),
    FAILED(5),

    /** The person at the TV said no, or did not answer in time (spec E17): nothing went to the server. */
    DECLINED(6),
    ;

    companion object {
        fun of(code: Int): HandoverReply? = entries.firstOrNull { it.code == code }
    }
}
