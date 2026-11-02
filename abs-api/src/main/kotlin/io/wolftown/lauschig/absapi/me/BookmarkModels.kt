package io.wolftown.lauschig.absapi.me

import io.wolftown.lauschig.absapi.LenientListSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * `AudioBookmarkObject` (server `models/User.js`). Identity on the server is [libraryItemId] + [time] (seconds);
 * there is no episode id. `createdAt` is epoch ms.
 */
@Serializable
data class BookmarkDto(
    val libraryItemId: String,
    val time: Double,
    val title: String,
    val createdAt: Long = 0,
)

/** `GET /api/me/bookmarks`; [bookmarks] is null when the field is absent (never read as "all deleted"). */
@Serializable
data class BookmarksResponse(
    @Serializable(with = LenientBookmarkListSerializer::class)
    val bookmarks: List<BookmarkDto>? = null,
)

/** Body of create (`POST`) and update (`PATCH`) `/api/me/item/:id/bookmark`; [title] must not be empty. */
@Serializable
data class BookmarkBody(val time: Double, val title: String)

object LenientBookmarkListSerializer : LenientListSerializer<BookmarkDto>(BookmarkDto.serializer())

/**
 * The `:time` path segment of `DELETE /api/me/item/:id/bookmark/:time`. The server compares it with `!==` after
 * `Number()`, so it must be the exact number in plain decimal notation (`123`, `123.5`, never `1.0E7`).
 */
fun bookmarkTimePath(time: Double): String = BigDecimal.valueOf(time).stripTrailingZeros().toPlainString()
