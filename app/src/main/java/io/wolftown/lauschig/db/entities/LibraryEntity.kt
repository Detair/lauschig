package io.wolftown.lauschig.db.entities

import androidx.room.Entity

enum class LibraryKind { AUDIOBOOK, HOERSPIEL, PODCAST }

/** Default mapping: podcast media type, else Hörspiel if the name says so, else audiobook. */
fun defaultLibraryKind(mediaType: String, name: String): LibraryKind = when {
    mediaType.equals("podcast", ignoreCase = true) -> LibraryKind.PODCAST
    name.contains("hörspiel", ignoreCase = true) || name.contains("hoerspiel", ignoreCase = true) -> LibraryKind.HOERSPIEL
    else -> LibraryKind.AUDIOBOOK
}

@Entity(tableName = "libraries", primaryKeys = ["serverId", "id"])
data class LibraryEntity(
    val serverId: String,
    val id: String,
    val name: String,
    val mediaType: String,
    val displayOrder: Int,
    val kind: LibraryKind = defaultLibraryKind(mediaType, name),
    /** User choice from settings; wins over [kind]. */
    val kindOverride: LibraryKind? = null,
) {
    val effectiveKind: LibraryKind get() = kindOverride ?: kind
}
