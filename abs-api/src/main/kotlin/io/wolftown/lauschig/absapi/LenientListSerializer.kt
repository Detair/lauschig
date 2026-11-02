package io.wolftown.lauschig.absapi

import io.wolftown.lauschig.absapi.library.EpisodeDto
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import io.wolftown.lauschig.absapi.me.MediaProgressDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

/**
 * Decodes a JSON array element by element and drops rows that cannot be decoded (JSON null yields an empty list, a non-array value throws), so one odd row
 * from the server never fails the surrounding response (login, refresh, item pages).
 */
open class LenientListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = (decoder as? JsonDecoder) ?: return delegate.deserialize(decoder)
        val root = json.decodeJsonElement()
        if (root is JsonNull) return emptyList()
        // Any other non-array shape is a malformed payload, not an empty list: callers may delete local data on "empty".
        val array = root as? JsonArray ?: throw SerializationException("Expected a JSON array but got ${root::class.simpleName}")
        return array.mapNotNull { el ->
            try {
                json.json.decodeFromJsonElement(element, el)
            } catch (_: SerializationException) {
                null
            }
        }
    }
}

object LenientMediaProgressListSerializer :
    LenientListSerializer<MediaProgressDto>(MediaProgressDto.serializer())

object LenientLibraryItemListSerializer :
    LenientListSerializer<LibraryItemDto>(LibraryItemDto.serializer())

object LenientEpisodeListSerializer :
    LenientListSerializer<EpisodeDto>(EpisodeDto.serializer())
