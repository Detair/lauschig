package io.wolftown.lauschig.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** A cover stand-in: a gentle gradient from [start] to [end] with the initials in [content]. */
@Immutable
data class PlaceholderTone(val start: Color, val end: Color, val content: Color)

/**
 * The few harmonious tones a placeholder can take, all from [scheme]'s containers (so dynamic colour, the fallback
 * palette and pure black each get their own): a gradient from one container a little way towards a neighbouring one.
 * The initials take whichever of the container's on-colour, onSurface and surface reads best on both ends – some
 * wallpapers give a light scheme mid-tone containers.
 */
fun placeholderTones(scheme: ColorScheme): List<PlaceholderTone> = with(scheme) {
    listOf(
        tone(primaryContainer, tertiaryContainer, onPrimaryContainer),
        tone(secondaryContainer, primaryContainer, onSecondaryContainer),
        tone(tertiaryContainer, secondaryContainer, onTertiaryContainer),
        tone(lerp(secondaryContainer, tertiaryContainer, 0.5f), secondaryContainer, onSecondaryContainer),
        tone(lerp(primaryContainer, secondaryContainer, 0.5f), tertiaryContainer, onSecondaryContainer),
    )
}

private fun ColorScheme.tone(start: Color, towards: Color, on: Color): PlaceholderTone {
    val end = lerp(start, towards, 0.45f)
    val content = listOf(on, onSurface, surface).maxBy { minOf(contrastRatio(it, start), contrastRatio(it, end)) }
    return PlaceholderTone(start, end, content)
}

/** How many tones [placeholderTones] offers. */
const val PLACEHOLDER_TONES = 5

/** [title]'s slot in [placeholderTones]: the same for the same title, in every scheme and on every start. */
fun placeholderSlot(title: String): Int = Math.floorMod(title.trim().hashCode(), PLACEHOLDER_TONES)

/** The tone for [title] (see [placeholderSlot]). */
fun placeholderToneFor(title: String, scheme: ColorScheme): PlaceholderTone = placeholderTones(scheme)[placeholderSlot(title)]

/** First letters of the first two words that start with a letter or digit ("Der Anfang" → "DA"). */
fun initialsOf(title: String): String =
    title.split(' ', '-', ':', '–', '/').filter { it.isNotBlank() && it.first().isLetterOrDigit() }.take(2)
        .joinToString("") { it.first().uppercase() }
