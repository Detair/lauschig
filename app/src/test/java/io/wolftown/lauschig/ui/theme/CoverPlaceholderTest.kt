package io.wolftown.lauschig.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.wolftown.lauschig.ui.LauschigDarkColors
import io.wolftown.lauschig.ui.LauschigLightColors
import io.wolftown.lauschig.ui.withPureBlack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPlaceholderTest {
    private val titles = listOf(
        "Die drei ??? und der Super-Papagei", "Bibi Blocksberg – Hexen gibt es doch", "Der Anfang", "Die Mitte", "Das Ende",
        "Mehrteiler", "Momo", "Krabat", "Tintenherz", "Die unendliche Geschichte", "Pippi Langstrumpf", "Räuber Hotzenplotz",
    )

    @Test fun sameTitle_sameTone_everyTime() {
        for (title in titles) {
            val first = placeholderToneFor(title, LauschigDarkColors)
            repeat(3) { assertEquals(first, placeholderToneFor(title, LauschigDarkColors)) }
            // Same slot in another scheme: the pick depends on the title only.
            assertEquals(placeholderTones(LauschigLightColors)[placeholderSlot(title)], placeholderToneFor(title, LauschigLightColors))
            assertEquals(placeholderTones(LauschigDarkColors)[placeholderSlot(title)], first)
        }
    }

    @Test fun titles_spreadOverSeveralTones() {
        val used = titles.map { placeholderSlot(it) }.toSet()
        assertTrue("only $used", used.size >= 3)
    }

    @Test fun tones_comeFromTheScheme_andTheInitialsStayReadable() {
        // Some wallpapers give a light scheme mid-tone containers with white text on them (seen on the test emulator).
        val midToneContainers = lightColorScheme(
            primaryContainer = Color(0xFF5D6A9A), onPrimaryContainer = Color.White,
            secondaryContainer = Color(0xFF6E7389), onSecondaryContainer = Color.White,
            tertiaryContainer = Color(0xFF7D6A86), onTertiaryContainer = Color.White,
            surfaceContainerHighest = Color(0xFFE2E2E9),
        )
        val schemes = listOf(lightColorScheme(), darkColorScheme(), LauschigLightColors, LauschigDarkColors, LauschigDarkColors.withPureBlack(), midToneContainers)
        for (scheme in schemes) {
            val tones = placeholderTones(scheme)
            assertEquals(PLACEHOLDER_TONES, tones.size)
            assertTrue(tones.any { it.start == scheme.primaryContainer })
            tones.forEachIndexed { i, t ->
                // titleLarge initials: at least AA for large text on both ends of the gradient.
                assertTrue("tone $i of $scheme", contrastRatio(t.content, t.start) >= 3.0)
                assertTrue("tone $i of $scheme", contrastRatio(t.content, t.end) >= 3.0)
            }
        }
    }

    @Test fun initials_takeTheFirstLetterOfTwoWords() {
        assertEquals("DA", initialsOf("Der Anfang"))
        assertEquals("M", initialsOf("Mehrteiler"))
        assertEquals("DD", initialsOf("Die drei ??? und der Super-Papagei"))
        assertEquals("BB", initialsOf("Bibi Blocksberg – Hexen gibt es doch"))
        assertEquals("", initialsOf("   "))
        assertEquals("ÄS", initialsOf("äpfel: süß"))
        // Punctuation is no initial.
        assertEquals("KU", initialsOf("??? Kids und der Fall"))
    }

    @Test fun blankTitle_stillGetsATone() {
        assertTrue(placeholderToneFor("", LauschigDarkColors) in placeholderTones(LauschigDarkColors))
    }
}
