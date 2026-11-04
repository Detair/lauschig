package io.wolftown.lauschig.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/*
 * The cover tint: Lauschig's one signature. A colour taken from a title's cover (see [rememberCoverSwatch]) is blended
 * into the theme's surface for the player, the mini player, the detail header and the "continue" card. Only surfaces
 * are tinted, never text: text keeps onSurface / onSurfaceVariant, and the blend backs off until both still read at
 * WCAG AA ([MIN_TEXT_CONTRAST]) on the tinted colour.
 */

/** WCAG AA for body text. */
const val MIN_TEXT_CONTRAST = 4.5

/** How long a change of tint (another title, the cover arriving) cross-fades. */
const val TINT_FADE_MS = 300

/** True for a dark scheme (judged by its surface, so dynamic and pure-black schemes count too). */
val ColorScheme.isDark: Boolean get() = surface.luminance() < 0.5f

/** The "Reines Schwarz" variant of the dark scheme. */
val ColorScheme.isPureBlack: Boolean get() = surface == Color.Black

/** WCAG contrast ratio of two opaque colours, 1..21. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** How much of the swatch goes into a surface: noticeable in light, a little more in dark, least in pure black. */
fun tintFraction(scheme: ColorScheme): Float = when {
    scheme.isPureBlack -> 0.20f
    scheme.isDark -> 0.30f
    else -> 0.24f
}

/**
 * [base] (a surface role of [scheme]) with [swatch] blended in by [tintFraction]. The fraction shrinks in small steps
 * until onSurface and onSurfaceVariant keep [MIN_TEXT_CONTRAST] on the result (or, when the base itself is below that,
 * the base's own contrast), so a loud or very light cover never costs readability. Pure function for tests.
 */
fun coverTintOver(base: Color, swatch: Color, scheme: ColorScheme): Color {
    val calm = calmSwatch(swatch)
    val texts = listOf(scheme.onSurface, scheme.onSurfaceVariant)
    val wanted = texts.map { minOf(MIN_TEXT_CONTRAST, contrastRatio(it, base)) }
    var fraction = tintFraction(scheme)
    while (fraction > 0f) {
        val tinted = lerp(base, calm, fraction)
        if (texts.indices.all { contrastRatio(texts[it], tinted) >= wanted[it] }) return tinted
        fraction -= 0.02f
    }
    return base
}

/**
 * Tames a cover colour before it is blended: saturation at most 0.5 and lightness between 0.3 and 0.6, hue kept. A
 * neon cover then tints like a muted one, and the strength of the tint is about the same for every title.
 */
fun calmSwatch(color: Color): Color {
    val (h, s, l) = color.toHsl()
    return Color.hsl(h, s.coerceAtMost(0.5f), l.coerceIn(0.3f, 0.6f))
}

/** Hue (0..360), saturation and lightness (0..1) of an sRGB colour. */
internal fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Triple((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
}

/**
 * [base] tinted with the cover of [model] (see [coverTintOver]); [base] itself without a cover, offline without a local
 * cover, or before the first swatch of this slot is known. On a change of cover the previous tint stays until the new
 * swatch is known (see [rememberCoverSwatch]).
 */
@Composable
fun coverTintTarget(model: Any?, coverVersion: Long, base: Color): Color {
    val swatch = rememberCoverSwatch(model, coverVersion)
    val scheme = MaterialTheme.colorScheme
    return if (swatch == null) base else remember(swatch, base, scheme) { coverTintOver(base, swatch, scheme) }
}

/**
 * [coverTintTarget], cross-fading in [TINT_FADE_MS]. A [State], so a caller that only draws with it (a gradient) reads it
 * in the draw phase and skips recomposing during the fade.
 */
@Composable
fun animateCoverTint(model: Any?, coverVersion: Long, base: Color): State<Color> =
    animateColorAsState(coverTintTarget(model, coverVersion, base), tween(TINT_FADE_MS), label = "coverTint")

/**
 * The colour for a control (a filled button, a progress line, a slider) on [background]: the scheme's primary when it
 * keeps WCAG's 3:1 for non-text against it, else onSurface.
 */
fun ColorScheme.controlColorOn(background: Color): Color =
    if (contrastRatio(primary, background) >= MIN_CONTROL_CONTRAST) primary else onSurface

/** WCAG 2.x non-text contrast (controls, icons that carry meaning). */
const val MIN_CONTROL_CONTRAST = 3.0
