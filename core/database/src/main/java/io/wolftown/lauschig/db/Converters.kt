package io.wolftown.lauschig.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        runCatching { Json.decodeFromString(ListSerializer(String.serializer()), value) }.getOrDefault(emptyList())
}
