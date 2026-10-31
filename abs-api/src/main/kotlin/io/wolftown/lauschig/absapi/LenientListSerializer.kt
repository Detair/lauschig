package io.wolftown.lauschig.absapi

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

/**
 * Decodes a JSON array element by element and drops rows that cannot be decoded, so one odd row
 * from the server never fails the surrounding response (login, refresh, item pages).
 */
open class LenientListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = (decoder as? JsonDecoder) ?: return delegate.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            try {
                json.json.decodeFromJsonElement(element, el)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

object LenientMediaProgressListSerializer :
    LenientListSerializer<MediaProgressDto>(MediaProgressDto.serializer())

object LenientLibraryItemListSerializer :
    LenientListSerializer<LibraryItemDto>(LibraryItemDto.serializer())
