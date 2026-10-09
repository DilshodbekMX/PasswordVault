package io.github.dilshodbekmx.passwordvault.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast of the app's six own colour schemes (build plan §12, 4.3b; ux.md §8 "Contrast";
 * docs/design/looks/README.md): every text pair at least 4.5:1, every icon and every outline that
 * carries meaning at least 3:1, by WCAG 2.2's contrast ratio. "Phone's colours" is not here: its
 * scheme comes from the phone (Material 3 makes its pairs from the wallpaper).
 *
 * A pair is a colour and what it is drawn on. The lists below name every pair that Material 3's
 * components and the app's own screens draw, so a role that is changed later is checked wherever it
 * is used.
 */
class SchemeContrastTest {

    @Test
    fun everyTextPairOfEverySchemeHasAContrastOfAtLeast4point5() {
        val low = OWN_LOOKS.flatMap { scheme -> TEXT_PAIRS.mapNotNull { (on, under) -> scheme.below(on, under, TEXT) } }
        assertTrue("text pairs under $TEXT:1:\n" + low.joinToString("\n"), low.isEmpty())
        // Every scheme was looked at, with every role of every pair.
        assertEquals(6, OWN_LOOKS.size)
        assertEquals(6, OWN_LOOKS.map { it.colors }.toSet().size)
        assertEquals(25 + SURFACES.size * ON_A_SURFACE.size, TEXT_PAIRS.size)
        assertEquals(48, OWN_LOOKS.first().colors.roles().size)
    }

    @Test
    fun everyIconOfEverySchemeHasAContrastOfAtLeast3() {
        // An icon is drawn in the colours text is drawn in, on the same surfaces.
        val low = OWN_LOOKS.flatMap { scheme -> TEXT_PAIRS.mapNotNull { (on, under) -> scheme.below(on, under, GRAPHIC) } }
        assertTrue("icon pairs under $GRAPHIC:1:\n" + low.joinToString("\n"), low.isEmpty())
    }

    @Test
    fun everyOutlineThatCarriesMeaningHasAContrastOfAtLeast3() {
        val low = OWN_LOOKS.flatMap { scheme -> OUTLINE_PAIRS.mapNotNull { (on, under) -> scheme.below(on, under, GRAPHIC) } }
        assertTrue("outline pairs under $GRAPHIC:1:\n" + low.joinToString("\n"), low.isEmpty())
    }

    /**
     * The PIN pad as each style draws it (`PinPad.kt`, `PadScreen.kt`): the digits and the two icons
     * on the screen, and the dots on the screen or on Bank blue's lock panel.
     */
    @Test
    fun thePinPadCanBeReadInEveryStyle() {
        for (scheme in OWN_LOOKS) {
            val c = scheme.colors
            val look = scheme.look
            val digitOn = if (look.pinKeys == PinKeyLook.FILLED) c.surfaceContainerHigh else c.background
            assertAtLeast(TEXT, c.onSurface, digitOn, "$scheme: a digit on its key")
            if (look.fingerprintKeyTonal) {
                assertAtLeast(GRAPHIC, c.onSecondaryContainer, c.secondaryContainer, "$scheme: the fingerprint icon on its tonal key")
            } else {
                assertAtLeast(GRAPHIC, c.primary, c.background, "$scheme: the fingerprint icon")
            }
            assertAtLeast(GRAPHIC, c.onSurfaceVariant, c.background, "$scheme: the delete icon")
            val panel = look.lockPanel
            if (panel == null) {
                assertAtLeast(GRAPHIC, c.primary, c.background, "$scheme: a filled dot")
                assertAtLeast(GRAPHIC, c.outline, c.background, "$scheme: an empty dot's ring")
            } else {
                assertAtLeast(GRAPHIC, panel.content, panel.colour, "$scheme: a filled dot on the panel")
                assertAtLeast(GRAPHIC, panel.ring, panel.colour, "$scheme: an empty dot's ring on the panel")
            }
        }
    }

    @Test
    fun aLightSchemeIsLightAndADarkOneDark() {
        for (scheme in OWN_LOOKS) {
            val c = scheme.colors
            for (surface in SURFACES) {
                val luminance = c.roles().getValue(surface).luminance()
                if (scheme.dark) assertTrue("$scheme $surface", luminance < 0.05) else assertTrue("$scheme $surface", luminance > 0.6)
            }
            assertEquals("$scheme: the screen and the rows on it have one colour", c.background, c.surface)
            assertEquals("$scheme: scrim", Color.Black, c.scrim)
        }
    }

    /**
     * The roles the mock-ups show are the values of the README's table "The colours of the
     * mock-ups", but for the three places that `Schemes.kt` explains: the table's "surface (cards,
     * sheets)" is `surfaceContainerLow`, Emerald's two outlines are `surfaceVariant` and
     * `outlineVariant`, and Night vault's outline is lighter than the mock-up's.
     */
    @Test
    fun theSchemesTheMockUpsShowHaveTheirColours() {
        val bankBlue = mapOf(
            "primary" to 0x1D4E89, "onPrimary" to 0xFFFFFF, "primaryContainer" to 0xD6E3FF, "onPrimaryContainer" to 0x0B2F5E,
            "secondaryContainer" to 0xDAE2F9, "onSecondaryContainer" to 0x131C2B,
            "background" to 0xF8F9FF, "surface" to 0xF8F9FF, "surfaceContainer" to 0xECEEF5, "surfaceContainerHigh" to 0xE6EAF2,
            "onSurface" to 0x191C20, "onSurfaceVariant" to 0x43474E, "outline" to 0x73777F, "outlineVariant" to 0xC3C7CF,
            "tertiary" to 0x9A4500, "inverseSurface" to 0x2E3138, "inverseOnSurface" to 0xEEF0F7, "inversePrimary" to 0xA9C7FF,
        )
        val emerald = mapOf(
            "primary" to 0x006B5E, "onPrimary" to 0xFFFFFF, "primaryContainer" to 0xCDE8E1, "onPrimaryContainer" to 0x00201B,
            "secondaryContainer" to 0xCDE8E1, "onSecondaryContainer" to 0x00201B,
            "background" to 0xF4F8F6, "surfaceContainerLow" to 0xFFFFFF, "surfaceContainer" to 0xFFFFFF, "surfaceContainerHigh" to 0xFFFFFF,
            "surfaceVariant" to 0xDCE5E1,
            "onSurface" to 0x161D1B, "onSurfaceVariant" to 0x3F4946, "outline" to 0x6F7976, "outlineVariant" to 0xBEC9C5,
            "tertiary" to 0x8A4B00, "inverseSurface" to 0x12332D, "inverseOnSurface" to 0xE6F2EF, "inversePrimary" to 0x8FE0CC,
        )
        val nightVault = mapOf(
            "primary" to 0xF2B84B, "onPrimary" to 0x2A1C00, "primaryContainer" to 0x3A2D11, "onPrimaryContainer" to 0xFFDFA3,
            "secondaryContainer" to 0x3A2D11, "onSecondaryContainer" to 0xFFDFA3,
            "background" to 0x0E1216, "surfaceContainerLow" to 0x161C21, "surfaceContainer" to 0x161C21, "surfaceContainerHigh" to 0x1C232A,
            "onSurface" to 0xE3E6EA, "onSurfaceVariant" to 0xA7AFB8, "outlineVariant" to 0x333C45,
            "tertiary" to 0x8FC7FF, "inverseSurface" to 0xE3E6EA, "inverseOnSurface" to 0x1A1F24, "inversePrimary" to 0x6A4800,
        )
        val shown = listOf(
            Looks.of(StyleChoice.BANK_BLUE, dark = false) to bankBlue,
            Looks.of(StyleChoice.EMERALD, dark = false) to emerald,
            Looks.of(StyleChoice.NIGHT_VAULT, dark = true) to nightVault,
        )
        for ((look, table) in shown) {
            val roles = look.colors.roles()
            for ((role, rgb) in table) assertEquals(role, "#%06X".format(rgb), roles.getValue(role).hex())
        }
        // Night vault's light primary is the deep gold the README names, and the bright gold its container.
        val light = Looks.of(StyleChoice.NIGHT_VAULT, dark = false).colors
        assertEquals("#6A4800", light.primary.hex())
        assertEquals("#F2B84B", light.primaryContainer.hex())
        assertTrue(contrast(c(0xF2B84B), light.surface) < 2.0)
        // The mock-up's outline of Night vault would not do on a sheet or in a dialog.
        val dark = Looks.of(StyleChoice.NIGHT_VAULT, dark = true).colors
        assertTrue(contrast(c(0x5B636C), dark.surfaceContainerLow) < GRAPHIC)
        assertTrue(contrast(c(0x5B636C), dark.surfaceContainerHigh) < GRAPHIC)
    }

    /** The three schemes the mock-ups do not show keep the hue of each role of the one they show. */
    @Test
    fun theDerivedSchemesKeepTheHueOfEachRole() {
        for (style in OWN_STYLES) {
            val light = Looks.of(style, dark = false).colors.roles()
            val dark = Looks.of(style, dark = true).colors.roles()
            for (role in HUED_ROLES) {
                val a = light.getValue(role).hue()
                val b = dark.getValue(role).hue()
                if (a.chroma < GREY || b.chroma < GREY) continue // a grey has no hue to keep
                val apart = hueDistance(a.degrees, b.degrees)
                assertTrue("$style $role: ${light.getValue(role).hex()} and ${dark.getValue(role).hex()} are $apart degrees apart", apart <= SAME_HUE)
            }
            // The accent itself is a colour in both, never a grey.
            for (role in listOf("primary", "tertiary")) {
                assertTrue("$style $role light", light.getValue(role).hue().chroma > 0.05)
                assertTrue("$style $role dark", dark.getValue(role).hue().chroma > 0.05)
            }
        }
        // And the three styles differ: blue, green and gold.
        val primaries = OWN_STYLES.map { Looks.of(it, dark = false).colors.primary.hue().degrees }
        for (i in primaries.indices) {
            for (j in i + 1 until primaries.size) assertTrue(hueDistance(primaries[i], primaries[j]) > 60.0)
        }
    }

    private fun NamedLook.below(on: String, under: String, least: Double): String? {
        val roles = colors.roles()
        val top = roles.getValue(on)
        val bottom = roles.getValue(under)
        val ratio = contrast(top, bottom)
        return if (ratio < least) "$name: $on ${top.hex()} on $under ${bottom.hex()} is %.2f:1".format(ratio) else null
    }

    private fun assertAtLeast(least: Double, on: Color, under: Color, what: String) {
        val ratio = contrast(on, under)
        assertTrue("$what: ${on.hex()} on ${under.hex()} is %.2f:1, under $least:1".format(ratio), ratio >= least)
    }

    private companion object {
        /** WCAG 2.2 AA for text. */
        const val TEXT = 4.5

        /** WCAG 2.2 AA for icons, outlines and other graphics that carry meaning. */
        const val GRAPHIC = 3.0

        /** Under this chroma a colour is a grey. */
        const val GREY = 0.02

        /** Two colours this many degrees apart at most have one hue; the mock-ups' own tones of one colour are up to 11 apart. */
        const val SAME_HUE = 20.0

        /** The screen and everything text can lie on (a sheet, a menu, the bars, the search field, a dialog, a filled card). */
        val SURFACES = listOf(
            "background", "surface", "surfaceDim", "surfaceBright", "surfaceContainerLowest", "surfaceContainerLow",
            "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
        )

        /**
         * The colours of text and icons on a surface: the text itself, the quieter text and the
         * icons, and the four accents (a text button and a heading, the navigation bar's chosen
         * label, the symbols of a shown password, an error).
         */
        val ON_A_SURFACE = listOf("onSurface", "onSurfaceVariant", "primary", "secondary", "tertiary", "error")

        val TEXT_PAIRS: List<Pair<String, String>> = listOf(
            // A colour and the colour made to lie on it.
            "onPrimary" to "primary", "onPrimaryContainer" to "primaryContainer",
            "onSecondary" to "secondary", "onSecondaryContainer" to "secondaryContainer",
            "onTertiary" to "tertiary", "onTertiaryContainer" to "tertiaryContainer",
            "onError" to "error", "onErrorContainer" to "errorContainer",
            "onBackground" to "background",
            "onSurface" to "surfaceVariant", "onSurfaceVariant" to "surfaceVariant",
            // A snackbar, a tooltip and the mock-ups' copy row: text and the action's label.
            "inverseOnSurface" to "inverseSurface", "inversePrimary" to "inverseSurface",
            // A marked cell of "Show in big letters" and of the words to write down: a letter, a digit, a symbol.
            "onSurface" to "secondaryContainer", "primary" to "secondaryContainer", "tertiary" to "secondaryContainer",
            // The roles that are the same in light and in dark.
            "onPrimaryFixed" to "primaryFixed", "onPrimaryFixed" to "primaryFixedDim", "onPrimaryFixedVariant" to "primaryFixed",
            "onSecondaryFixed" to "secondaryFixed", "onSecondaryFixed" to "secondaryFixedDim", "onSecondaryFixedVariant" to "secondaryFixed",
            "onTertiaryFixed" to "tertiaryFixed", "onTertiaryFixed" to "tertiaryFixedDim", "onTertiaryFixedVariant" to "tertiaryFixed",
        ) + SURFACES.flatMap { surface -> ON_A_SURFACE.map { it to surface } }

        /**
         * The outlines that say what a thing is or how it is set: a text field's (also in a sheet
         * and in a dialog), an empty dot of the PIN row, a switch that is off (its ring, and its
         * thumb on the track of `surfaceContainerHighest`), a segmented button's. A text field with
         * the focus or with an error has the primary or the error colour, which are text pairs
         * above. `outlineVariant` is a divider's colour and carries none (a digit key's ring).
         */
        val OUTLINE_PAIRS: List<Pair<String, String>> = SURFACES.map { "outline" to it }

        /** The roles that have a colour of their own, which a style's two schemes share. */
        val HUED_ROLES = listOf(
            "primary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
            "secondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "tertiaryContainer", "onTertiaryContainer",
            "error", "errorContainer",
        )
    }
}
