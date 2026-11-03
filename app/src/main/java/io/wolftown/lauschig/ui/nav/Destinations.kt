package io.wolftown.lauschig.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object Start
@Serializable data object Library
@Serializable data object Discover
@Serializable data object Search
@Serializable data class ItemDetail(val itemId: String)

/** What an entity page lists: everything of one author, narrator, series, genre or tag. */
enum class BrowseKind { AUTHOR, NARRATOR, SERIES, GENRE, TAG }

@Serializable data class Browse(val kind: BrowseKind, val name: String)
/** A cached ABS collection or playlist (by id). */
@Serializable data class Collection(val id: String)
@Serializable data object Player
@Serializable data object Setup
@Serializable data object Settings
@Serializable data object Cleanup
@Serializable data object Downloads
