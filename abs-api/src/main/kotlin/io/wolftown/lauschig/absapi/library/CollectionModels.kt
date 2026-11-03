package io.wolftown.lauschig.absapi.library

import io.wolftown.lauschig.absapi.LenientListSerializer
import kotlinx.serialization.Serializable

/** Anything with an id (the expanded library items of a collection; only the id is used). */
@Serializable
data class IdOnlyDto(val id: String)

/** `Collection.toOldJSONExpanded`: [books] are expanded library items in collection order. Books only. */
@Serializable
data class CollectionDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val description: String? = null,
    val lastUpdate: Long = 0,
    @Serializable(with = LenientIdListSerializer::class)
    val books: List<IdOnlyDto> = emptyList(),
)

/** `GET /api/libraries/:id/collections`. */
@Serializable
data class CollectionsPage(
    @Serializable(with = LenientCollectionListSerializer::class)
    val results: List<CollectionDto> = emptyList(),
    val total: Int = 0,
)

/** A playlist entry: a book ([episodeId] null) or a podcast episode (then [episode] is the expanded episode). */
@Serializable
data class PlaylistItemDto(
    val libraryItemId: String,
    val episodeId: String? = null,
    val episode: EpisodeDto? = null,
)

/** `Playlist.toOldJSONExpanded`. */
@Serializable
data class PlaylistDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val description: String? = null,
    val lastUpdate: Long = 0,
    @Serializable(with = LenientPlaylistItemListSerializer::class)
    val items: List<PlaylistItemDto> = emptyList(),
)

/** `GET /api/playlists`: the user's own playlists in all accessible libraries. */
@Serializable
data class PlaylistsResponse(
    @Serializable(with = LenientPlaylistListSerializer::class)
    val playlists: List<PlaylistDto> = emptyList(),
)

object LenientIdListSerializer : LenientListSerializer<IdOnlyDto>(IdOnlyDto.serializer())
object LenientCollectionListSerializer : LenientListSerializer<CollectionDto>(CollectionDto.serializer())
object LenientPlaylistItemListSerializer : LenientListSerializer<PlaylistItemDto>(PlaylistItemDto.serializer())
object LenientPlaylistListSerializer : LenientListSerializer<PlaylistDto>(PlaylistDto.serializer())
