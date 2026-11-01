package io.wolftown.lauschig.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object Start
@Serializable data object Library
@Serializable data object Discover
@Serializable data object Search
@Serializable data class ItemDetail(val itemId: String)
@Serializable data object Player
@Serializable data object Setup
@Serializable data object Settings
@Serializable data object Cleanup
