package io.wolftown.lauschig.whatsnew

import androidx.annotation.ArrayRes
import io.wolftown.lauschig.R

/** One version's "Was ist neu": short, user-facing lines as a string array (German in values-de, English fallback). */
data class Release(val versionCode: Int, val versionName: String, @ArrayRes val notes: Int)

/** Every release with notes, newest first. A new version adds its entry on top (a test checks the current one has notes). */
val WHATS_NEW: List<Release> = listOf(
    Release(2, "0.2.0", R.array.whatsnew_0_2_0),
)

/** The last version before "Was ist neu" existed: an update without a stored version came from it. */
const val VERSION_BEFORE_WHATS_NEW = 1

/**
 * The releases to show after a start: none on a first install ([lastSeen] null and the package never [updated]);
 * after an update every release newer than [lastSeen] up to [current], newest first; none when up to date or
 * downgraded. Pure, for tests.
 */
fun releasesToShow(lastSeen: Int?, current: Int, updated: Boolean, releases: List<Release>): List<Release> {
    val since = lastSeen ?: if (updated) VERSION_BEFORE_WHATS_NEW else return emptyList()
    return releases.filter { it.versionCode in (since + 1)..current }.sortedByDescending { it.versionCode }
}
