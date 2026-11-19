package io.wolftown.lauschig.tv.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import io.wolftown.lauschig.db.entities.LibraryKind.AUDIOBOOK
import io.wolftown.lauschig.db.entities.LibraryKind.HOERSPIEL
import io.wolftown.lauschig.db.entities.LibraryKind.PODCAST
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TV spec E6: the library and the search of the TV leave podcasts out, through the view models' kind filter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvKindsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun allChosen_becomesAllButPodcasts() {
        assertEquals(setOf(AUDIOBOOK, HOERSPIEL), kindsWithoutPodcasts(setOf(AUDIOBOOK, HOERSPIEL, PODCAST), emptySet()))
    }

    @Test fun aChoiceWithPodcasts_losesThem() {
        assertEquals(setOf(HOERSPIEL), kindsWithoutPodcasts(setOf(AUDIOBOOK, HOERSPIEL, PODCAST), setOf(HOERSPIEL, PODCAST)))
    }

    @Test fun nothingToDo_withoutPodcastLibraries_orWhenTheyAreOutAlready() {
        assertNull(kindsWithoutPodcasts(setOf(AUDIOBOOK, HOERSPIEL), emptySet()))
        assertNull(kindsWithoutPodcasts(setOf(AUDIOBOOK, PODCAST), setOf(AUDIOBOOK)))
        assertNull("libraries not known yet", kindsWithoutPodcasts(emptySet(), emptySet()))
    }

    /** Only podcasts: an empty choice would mean "all" again, so nothing changes. */
    @Test fun onlyPodcasts_isLeftAlone() {
        assertNull(kindsWithoutPodcasts(setOf(PODCAST), emptySet()))
        assertNull(kindsWithoutPodcasts(setOf(AUDIOBOOK, PODCAST), setOf(PODCAST)))
    }

    @Test fun thePage_setsTheFilterOnce_whenTheLibrariesArrive() {
        var available by mutableStateOf(emptySet<io.wolftown.lauschig.db.entities.LibraryKind>())
        var selected by mutableStateOf(emptySet<io.wolftown.lauschig.db.entities.LibraryKind>())
        val calls = mutableListOf<Set<io.wolftown.lauschig.db.entities.LibraryKind>>()
        rule.setContent { HidePodcasts(available, selected) { calls += it; selected = it } }
        rule.waitForIdle()
        assertEquals(emptyList<Any>(), calls)
        available = setOf(AUDIOBOOK, PODCAST)
        rule.waitForIdle()
        assertEquals(listOf(setOf(AUDIOBOOK)), calls)
    }
}
