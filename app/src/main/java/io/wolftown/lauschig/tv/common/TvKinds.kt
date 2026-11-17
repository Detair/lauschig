package io.wolftown.lauschig.tv.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import io.wolftown.lauschig.db.entities.LibraryKind

/**
 * The kinds a page of the TV should filter to so that podcasts stay out (TV spec E6: no podcasts in the first TV version),
 * or null when nothing has to change: there are no podcast libraries, podcasts are already left out, or leaving them out
 * would leave nothing (an empty choice means "all" and would bring them back).
 */
internal fun kindsWithoutPodcasts(available: Set<LibraryKind>, selected: Set<LibraryKind>): Set<LibraryKind>? {
    if (LibraryKind.PODCAST !in available) return null
    if (selected.isNotEmpty() && LibraryKind.PODCAST !in selected) return null
    return (selected.ifEmpty { available } - LibraryKind.PODCAST).takeIf { it.isNotEmpty() }
}

/**
 * Sets the page's kind filter without podcasts once the libraries are known. This writes the filter the phone's page of the
 * same view model keeps too; on a television only the TV interface exists, so nothing else reads it there.
 */
@Composable
internal fun HidePodcasts(available: Set<LibraryKind>, selected: Set<LibraryKind>, setKinds: (Set<LibraryKind>) -> Unit) {
    val set = rememberUpdatedState(setKinds)
    LaunchedEffect(available, selected) { kindsWithoutPodcasts(available, selected)?.let { set.value(it) } }
}
