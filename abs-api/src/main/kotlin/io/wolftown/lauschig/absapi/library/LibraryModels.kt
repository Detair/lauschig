package io.wolftown.lauschig.absapi.library

import io.wolftown.lauschig.absapi.LenientLibraryItemListSerializer
import kotlinx.serialization.Serializable

/** `GET /api/libraries` wraps the list: `{ "libraries": [...] }`. */
@Serializable
data class LibrariesResponse(val libraries: List<LibraryDto>)

@Serializable
data class LibraryDto(
    val id: String,
    val name: String,
    /** "book" | "podcast" */
    val mediaType: String,
    val displayOrder: Int = 0,
)

/** `GET /api/libraries/:id/items`; `page` is 0-based, `limit` echoes the request (0 = unlimited). */
@Serializable
data class LibraryItemsPage(
    @Serializable(with = LenientLibraryItemListSerializer::class)
    val results: List<LibraryItemDto>,
    val total: Int,
    val limit: Int,
    val page: Int,
)

/**
 * Library item in minified (list) or expanded (`/api/items/:id?expanded=1`) shape. Minified results lack
 * `media.metadata.series`, `chapters` and `audioFiles` (the defaults cover that); use the expanded item for those.
 */
@Serializable
data class LibraryItemDto(
    val id: String,
    val libraryId: String,
    val mediaType: String,
    val updatedAt: Long,
    val media: BookMediaDto,
)

@Serializable
data class BookMediaDto(
    val metadata: BookMetadataDto,
    val duration: Double? = null,
    val chapters: List<ChapterDto> = emptyList(),
    val audioFiles: List<AudioFileDto> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class BookMetadataDto(
    val title: String,
    val subtitle: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    /** In list results this is the display string, e.g. "Saga #3". */
    val seriesName: String? = null,
    val series: List<SeriesRefDto> = emptyList(),
    val genres: List<String> = emptyList(),
    val description: String? = null,
)

@Serializable
data class SeriesRefDto(
    val id: String,
    val name: String,
    /** String on purpose: sequences such as "1.5" or "0" are free text on the server. */
    val sequence: String? = null,
)

@Serializable
data class ChapterDto(
    val id: Int,
    val start: Double,
    val end: Double,
    val title: String,
)

@Serializable
data class AudioFileDto(
    /** 1-based track index as assigned by the server; defines playback order. */
    val index: Int,
    val ino: String,
    val duration: Double,
    val metadata: FileMetadataDto,
)

@Serializable
data class FileMetadataDto(
    val filename: String,
    /** Includes the leading dot, e.g. ".m4b". */
    val ext: String,
    val size: Long,
)
