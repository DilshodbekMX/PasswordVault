package io.github.dilshodbekmx.passwordvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The colour maths of the theme's JVM tests: WCAG 2.2's contrast ratio (ux.md §8 "Contrast") and a
// colour's hue. The colours are opaque sRGB, read as the 8-bit values they were written with.

/** "#1D4E89". */
fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** WCAG's relative luminance, 0 (black) to 1 (white). */
fun Color.luminance(): Double {
    val argb = toArgb()
    require(argb ushr 24 == 0xFF) { "not opaque: ${hex()}" }
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(argb shr 16 and 0xFF) + 0.7152 * linear(argb shr 8 and 0xFF) + 0.0722 * linear(argb and 0xFF)
}

/** WCAG's contrast ratio of two colours, 1 to 21. */
fun contrast(a: Color, b: Color): Double {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** A colour's chroma and hue in OKLCH (Björn Ottosson's OKLab): how strong the colour is, and its hue in degrees. */
class Hue(val chroma: Double, val degrees: Double)

fun Color.hue(): Hue {
    val argb = toArgb()
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val r = linear(argb shr 16 and 0xFF)
    val g = linear(argb shr 8 and 0xFF)
    val b = linear(argb and 0xFF)
    val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    return Hue(hypot(a, bb), (Math.toDegrees(atan2(bb, a)) + 360.0) % 360.0)
}

/** The distance between two hues, 0 to 180 degrees. */
fun hueDistance(a: Double, b: Double): Double = abs((a - b + 540.0) % 360.0 - 180.0)

/** Every colour role of a Material 3 colour scheme, by its name. */
fun ColorScheme.roles(): Map<String, Color> = linkedMapOf(
    "primary" to primary, "onPrimary" to onPrimary, "primaryContainer" to primaryContainer, "onPrimaryContainer" to onPrimaryContainer,
    "inversePrimary" to inversePrimary,
    "secondary" to secondary, "onSecondary" to onSecondary, "secondaryContainer" to secondaryContainer, "onSecondaryContainer" to onSecondaryContainer,
    "tertiary" to tertiary, "onTertiary" to onTertiary, "tertiaryContainer" to tertiaryContainer, "onTertiaryContainer" to onTertiaryContainer,
    "background" to background, "onBackground" to onBackground, "surface" to surface, "onSurface" to onSurface,
    "surfaceVariant" to surfaceVariant, "onSurfaceVariant" to onSurfaceVariant, "surfaceTint" to surfaceTint,
    "inverseSurface" to inverseSurface, "inverseOnSurface" to inverseOnSurface,
    "error" to error, "onError" to onError, "errorContainer" to errorContainer, "onErrorContainer" to onErrorContainer,
    "outline" to outline, "outlineVariant" to outlineVariant, "scrim" to scrim,
    "surfaceBright" to surfaceBright, "surfaceDim" to surfaceDim,
    "surfaceContainer" to surfaceContainer, "surfaceContainerHigh" to surfaceContainerHigh, "surfaceContainerHighest" to surfaceContainerHighest,
    "surfaceContainerLow" to surfaceContainerLow, "surfaceContainerLowest" to surfaceContainerLowest,
    "primaryFixed" to primaryFixed, "primaryFixedDim" to primaryFixedDim, "onPrimaryFixed" to onPrimaryFixed, "onPrimaryFixedVariant" to onPrimaryFixedVariant,
    "secondaryFixed" to secondaryFixed, "secondaryFixedDim" to secondaryFixedDim, "onSecondaryFixed" to onSecondaryFixed,
    "onSecondaryFixedVariant" to onSecondaryFixedVariant,
    "tertiaryFixed" to tertiaryFixed, "tertiaryFixedDim" to tertiaryFixedDim, "onTertiaryFixed" to onTertiaryFixed,
    "onTertiaryFixedVariant" to onTertiaryFixedVariant,
)

/** One of the app's six own schemes, with its name for a failure message. */
class NamedLook(val name: String, val style: StyleChoice, val dark: Boolean, val look: Look) {
    val colors: ColorScheme get() = look.colors
    override fun toString(): String = name
}

/** The app's own styles: "Phone's colours" comes from the phone. */
val OWN_STYLES = listOf(StyleChoice.BANK_BLUE, StyleChoice.EMERALD, StyleChoice.NIGHT_VAULT)

/** The six schemes: every own style in light and in dark. */
val OWN_LOOKS: List<NamedLook> = OWN_STYLES.flatMap { style ->
    listOf(false, true).map { dark -> NamedLook("${style.name} ${if (dark) "dark" else "light"}", style, dark, Looks.of(style, dark)) }
}
