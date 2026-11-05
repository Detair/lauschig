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
/** Why the sign-in form is open. */
enum class SetupMode {
    /** The first sign-in, or a re-auth of the active account. */
    SIGN_IN,

    /** One more account: the active one is set aside and stays signed in. */
    ADD_PROFILE,
}

/** The sign-in form. Its [mode] is in the route, so it survives a rotation, a process death and the way through the SSO browser. */
@Serializable data class Setup(val mode: SetupMode = SetupMode.SIGN_IN)
@Serializable data object Settings
/** Tab order, visibility and start screen (Einstellungen → Darstellung → Navigation anpassen). */
@Serializable data object NavigationSettings
@Serializable data object Cleanup
@Serializable data object Downloads
@Serializable data object ConnectionStatus
@Serializable data object ServerAdmin
/** Listening statistics (from Start and Settings). */
@Serializable data object Stats
/** Achievements („Erfolge“), from the statistics and from the notice on Start. */
@Serializable data object AchievementsPage
/** The year in review („Jahresrückblick“) of [year], a story over the whole window (from Stats and Start). */
@Serializable data class YearReviewPage(val year: Int)
