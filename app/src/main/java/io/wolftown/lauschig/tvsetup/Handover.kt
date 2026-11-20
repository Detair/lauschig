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

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** [PLAIN_SIZE] bytes; null when the JSON does not fit. */
    fun encode(handover: PhoneHandover): ByteArray? {
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
        // The same rules as the server QR code: http(s), no user, no query, no fragment.
        if (handover.local != null && ServerQr.serverAddress(handover.local) == null) return null
        if (handover.public != null && ServerQr.serverAddress(handover.public) == null) return null
        return handover
    }
}

/** The TV's one-byte answer; the code is what goes over the wire. */
enum class HandoverReply(val code: Int) {
    SIGNED_IN(1),
    NOT_ACCEPTED(2),
    SERVER_UNREACHABLE(3),
    SIGN_IN_REFUSED(4),
    FAILED(5),
    ;

    companion object {
        fun of(code: Int): HandoverReply? = entries.firstOrNull { it.code == code }
    }
}
