package io.wolftown.lauschig.ui.review

import io.wolftown.lauschig.stats.SlideKind
import io.wolftown.lauschig.stats.SlideKind.ACHIEVED
import io.wolftown.lauschig.stats.SlideKind.CLOSING
import io.wolftown.lauschig.stats.SlideKind.DAYS
import io.wolftown.lauschig.stats.SlideKind.OPENING
import io.wolftown.lauschig.stats.SlideKind.PERSONALITY
import io.wolftown.lauschig.stats.SlideKind.RHYTHM
import io.wolftown.lauschig.stats.SlideKind.TOP_PEOPLE
import org.junit.Assert.assertEquals
import org.junit.Test

/** A story's place mapped onto rebuilt slides: the same kind, else the slide that follows in the fixed order. */
class StoryPlaceTest {
    private val before = listOf(OPENING, DAYS, RHYTHM, TOP_PEOPLE, PERSONALITY, CLOSING)

    @Test fun theSameKind_whereItMoved_andThePlayedEntrancesFollowTheirKinds() {
        val after = listOf(OPENING, DAYS, TOP_PEOPLE, ACHIEVED, PERSONALITY, CLOSING) // rhythm gone, achievements new
        assertEquals(StoryPlace(4, setOf(0, 2, 4)), relocate(before, 4, setOf(0, 3, 4), after))
    }

    @Test fun aKindThatIsGone_goesToTheSlideThatNowFollows_orTheLastOne() {
        val noRhythm = listOf(OPENING, DAYS, TOP_PEOPLE, CLOSING)
        assertEquals(2, relocate(before, 2, emptySet(), noRhythm).index) // rhythm → the author
        val noClosing = listOf<SlideKind>(OPENING, DAYS)
        assertEquals(1, relocate(before, 5, emptySet(), noClosing).index)
    }

    @Test fun anUnknownPlace_staysInRange_andNoSlidesNoPlace() {
        assertEquals(StoryPlace(1, emptySet()), relocate(emptyList(), 7, setOf(1, 2), listOf(OPENING, CLOSING)))
        assertEquals(StoryPlace(0, emptySet()), relocate(before, 3, setOf(3), emptyList()))
    }
}
