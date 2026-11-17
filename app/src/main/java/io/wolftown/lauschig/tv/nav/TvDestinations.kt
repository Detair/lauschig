package io.wolftown.lauschig.tv.nav

import io.wolftown.lauschig.ui.nav.ProfilePicker
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.Welcome
import kotlinx.serialization.Serializable

// The TV's own pages. Pages that bind a view model to their route (Setup, ItemDetail, Browse) keep the phone's route
// classes, since those view models read them from their SavedStateHandle.

/** The start page with the rows. */
@Serializable data object TvHome

/** All titles as a grid. */
@Serializable data object TvLibrary

/** Search with the TV's keyboard. */
@Serializable data object TvSearch

/** The few settings that make sense on a TV. */
@Serializable data object TvSettings

/** The full-screen player. */
@Serializable data object TvPlayer

/** "Who is listening?": the saved profiles. */
@Serializable data object TvProfiles

/**
 * The TV's first page for the phone's ([io.wolftown.lauschig.AppPages.start]). The sign-in stays as it is; the welcome pages
 * are skipped, as they are laid out for a touchscreen; any start tab of the phone becomes the TV's start page, as the TV has
 * no tabs to choose from.
 */
fun tvStart(start: Any): Any = when (start) {
    is Setup -> start
    Welcome -> Setup()
    ProfilePicker -> TvProfiles
    else -> TvHome
}
