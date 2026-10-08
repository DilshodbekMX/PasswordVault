package io.github.dilshodbekmx.passwordvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

// The six colour schemes of the app's own styles (docs/design/looks/README.md, "The colours of the
// mock-ups"): Bank blue, Emerald and Night vault, each light and dark. The user was shown Bank blue
// and Emerald in light and Night vault in dark; the other three keep the hue of each role.
//
// Where the colours come from.
// - Given: the roles the README's table lists are its values, taken from the mock-ups.
// - Derived: every other role has Material 3's tone for that role (the CIE lightness L*: 40, 100, 90
//   and 10 for a colour, its "on" colour, its container and the container's "on" colour in a light
//   scheme, 80, 20, 30 and 90 in a dark one; surfaces from 87 to 100 and from 4 to 24; an outline 50
//   or 60) in the hue and chroma (OKLCH) of the style's given colour of the same family. The error
//   colours are Material 3's own.
// - A scheme the mock-ups do not show mirrors the one they show: the primary colour of the dark
//   scheme is the inverse primary of the light one, a container and its "on" colour change places.
//   Night vault's light primary is the deep gold #6A4800, because the bright gold #F2B84B has a
//   contrast under 2:1 on a light surface; the bright gold is its primary container.
//
// Three places differ from the README's table, each for a reason that the table's own rules give:
// - `surface` is the colour of the screen, the table's "background", in Emerald and Night vault too.
//   Material 3 draws the top bar and every list row on `surface`, and the mock-ups show both on the
//   background colour. The table's "surface (cards, sheets)" colour (white, #161C21) is
//   `surfaceContainerLow`, the role Material 3 gives a sheet and an elevated card, and
//   `surfaceContainerLowest`.
// - Emerald's navigation bar and search field are white, as in the table; the outlines the table
//   names for them (#DCE5E1, #BEC9C5) are `surfaceVariant` and `outlineVariant`. Drawing them is for
//   the screens that own the bar and the field (the looks' second step, build-plan.md §12).
// - Night vault's dark `outline` is #757D86 (tone 52), not the mock-up's #5B636C (tone 42), which
//   reaches 3:1 on the background only: on the colours of a sheet and a dialog (#161C21, #1C232A),
//   where a text field's outline is drawn, it has 2.8:1 and 2.6:1 (ux.md §8 asks for 3:1).
//
// SchemeContrastTest checks every pair of every scheme: 4.5:1 for text, 3:1 for an outline.

/** Bank blue, light: the mock-up "A". */
internal val BankBlueLight = ColorScheme(
    primary = c(0x1D4E89), onPrimary = c(0xFFFFFF), primaryContainer = c(0xD6E3FF), onPrimaryContainer = c(0x0B2F5E),
    secondary = c(0x575E72), onSecondary = c(0xFFFFFF), secondaryContainer = c(0xDAE2F9), onSecondaryContainer = c(0x131C2B),
    tertiary = c(0x9A4500), onTertiary = c(0xFFFFFF), tertiaryContainer = c(0xFFDBC9), onTertiaryContainer = c(0x331200),
    error = c(0xBA1A1A), onError = c(0xFFFFFF), errorContainer = c(0xFFDAD6), onErrorContainer = c(0x410002),
    background = c(0xF8F9FF), onBackground = c(0x191C20), surface = c(0xF8F9FF), onSurface = c(0x191C20),
    surfaceVariant = c(0xDEE3EC), onSurfaceVariant = c(0x43474E), outline = c(0x73777F), outlineVariant = c(0xC3C7CF),
    surfaceContainerLowest = c(0xFFFFFF), surfaceContainerLow = c(0xF1F4FB), surfaceContainer = c(0xECEEF5),
    surfaceContainerHigh = c(0xE6EAF2), surfaceContainerHighest = c(0xDFE2E9),
    surfaceDim = c(0xD7DAE1), surfaceBright = c(0xF8F9FF), surfaceTint = c(0x1D4E89), scrim = c(0x000000),
    inverseSurface = c(0x2E3138), inverseOnSurface = c(0xEEF0F7), inversePrimary = c(0xA9C7FF),
    primaryFixed = c(0xD6E3FF), primaryFixedDim = c(0xA9C7FF), onPrimaryFixed = c(0x001B3E), onPrimaryFixedVariant = c(0x154781),
    secondaryFixed = c(0xDAE2F9), secondaryFixedDim = c(0xBEC6DD), onSecondaryFixed = c(0x131C2B), onSecondaryFixedVariant = c(0x404659),
    tertiaryFixed = c(0xFFDBC9), tertiaryFixedDim = c(0xFFB68E), onTertiaryFixed = c(0x331200), onTertiaryFixedVariant = c(0x763300),
)

/** Bank blue, dark: derived. The blue of the light scheme is the primary container here. */
internal val BankBlueDark = ColorScheme(
    primary = c(0xA9C7FF), onPrimary = c(0x0B2F5E), primaryContainer = c(0x1D4E89), onPrimaryContainer = c(0xD6E3FF),
    secondary = c(0xBEC6DD), onSecondary = c(0x2A3041), secondaryContainer = c(0x404659), onSecondaryContainer = c(0xDAE2F9),
    tertiary = c(0xFFB68E), onTertiary = c(0x532200), tertiaryContainer = c(0x763300), onTertiaryContainer = c(0xFFDBC9),
    error = c(0xFFB4AB), onError = c(0x690005), errorContainer = c(0x93000A), onErrorContainer = c(0xFFDAD6),
    background = c(0x111318), onBackground = c(0xDFE2E9), surface = c(0x111318), onSurface = c(0xDFE2E9),
    surfaceVariant = c(0x43474E), onSurfaceVariant = c(0xC3C7CF), outline = c(0x8D9199), outlineVariant = c(0x43474E),
    surfaceContainerLowest = c(0x0C0E13), surfaceContainerLow = c(0x191C20), surfaceContainer = c(0x1D2025),
    surfaceContainerHigh = c(0x282A2F), surfaceContainerHighest = c(0x32353A),
    surfaceDim = c(0x111318), surfaceBright = c(0x37393F), surfaceTint = c(0xA9C7FF), scrim = c(0x000000),
    inverseSurface = c(0xDFE2E9), inverseOnSurface = c(0x2E3138), inversePrimary = c(0x1D4E89),
    primaryFixed = c(0xD6E3FF), primaryFixedDim = c(0xA9C7FF), onPrimaryFixed = c(0x001B3E), onPrimaryFixedVariant = c(0x154781),
    secondaryFixed = c(0xDAE2F9), secondaryFixedDim = c(0xBEC6DD), onSecondaryFixed = c(0x131C2B), onSecondaryFixedVariant = c(0x404659),
    tertiaryFixed = c(0xFFDBC9), tertiaryFixedDim = c(0xFFB68E), onTertiaryFixed = c(0x331200), onTertiaryFixedVariant = c(0x763300),
)

/** Emerald, light: the mock-up "B". The screen is tinted; what lies on it (sheets, dialogs, the bar) is white. */
internal val EmeraldLight = ColorScheme(
    primary = c(0x006B5E), onPrimary = c(0xFFFFFF), primaryContainer = c(0xCDE8E1), onPrimaryContainer = c(0x00201B),
    secondary = c(0x4B635D), onSecondary = c(0xFFFFFF), secondaryContainer = c(0xCDE8E1), onSecondaryContainer = c(0x00201B),
    tertiary = c(0x8A4B00), onTertiary = c(0xFFFFFF), tertiaryContainer = c(0xFFDCC1), onTertiaryContainer = c(0x2E1500),
    error = c(0xBA1A1A), onError = c(0xFFFFFF), errorContainer = c(0xFFDAD6), onErrorContainer = c(0x410002),
    background = c(0xF4F8F6), onBackground = c(0x161D1B), surface = c(0xF4F8F6), onSurface = c(0x161D1B),
    surfaceVariant = c(0xDCE5E1), onSurfaceVariant = c(0x3F4946), outline = c(0x6F7976), outlineVariant = c(0xBEC9C5),
    surfaceContainerLowest = c(0xFFFFFF), surfaceContainerLow = c(0xFFFFFF), surfaceContainer = c(0xFFFFFF),
    surfaceContainerHigh = c(0xFFFFFF), surfaceContainerHighest = c(0xDDE4E2),
    surfaceDim = c(0xD5DBD9), surfaceBright = c(0xFFFFFF), surfaceTint = c(0x006B5E), scrim = c(0x000000),
    inverseSurface = c(0x12332D), inverseOnSurface = c(0xE6F2EF), inversePrimary = c(0x8FE0CC),
    primaryFixed = c(0x9EF2E2), primaryFixedDim = c(0x82D6C6), onPrimaryFixed = c(0x00201B), onPrimaryFixedVariant = c(0x005046),
    secondaryFixed = c(0xCDE8E1), secondaryFixedDim = c(0xB2CCC5), onSecondaryFixed = c(0x091F1B), onSecondaryFixedVariant = c(0x344B46),
    tertiaryFixed = c(0xFFDCC1), tertiaryFixedDim = c(0xFFB77C), onTertiaryFixed = c(0x2E1500), onTertiaryFixedVariant = c(0x6D3A00),
)

/** Emerald, dark: derived. */
internal val EmeraldDark = ColorScheme(
    primary = c(0x8FE0CC), onPrimary = c(0x003730), primaryContainer = c(0x005046), onPrimaryContainer = c(0xCDE8E1),
    secondary = c(0xB2CCC5), onSecondary = c(0x1E352F), secondaryContainer = c(0x344B46), onSecondaryContainer = c(0xCDE8E1),
    tertiary = c(0xFFB77C), onTertiary = c(0x4C2700), tertiaryContainer = c(0x6D3A00), onTertiaryContainer = c(0xFFDCC1),
    error = c(0xFFB4AB), onError = c(0x690005), errorContainer = c(0x93000A), onErrorContainer = c(0xFFDAD6),
    background = c(0x0F1413), onBackground = c(0xDDE4E2), surface = c(0x0F1413), onSurface = c(0xDDE4E2),
    surfaceVariant = c(0x3F4946), onSurfaceVariant = c(0xBEC9C5), outline = c(0x899390), outlineVariant = c(0x3F4946),
    surfaceContainerLowest = c(0x0A0F0E), surfaceContainerLow = c(0x181D1B), surfaceContainer = c(0x1C211F),
    surfaceContainerHigh = c(0x262B29), surfaceContainerHighest = c(0x303634),
    surfaceDim = c(0x0F1413), surfaceBright = c(0x353A39), surfaceTint = c(0x8FE0CC), scrim = c(0x000000),
    inverseSurface = c(0xDDE4E2), inverseOnSurface = c(0x2C3130), inversePrimary = c(0x006B5E),
    primaryFixed = c(0x9EF2E2), primaryFixedDim = c(0x82D6C6), onPrimaryFixed = c(0x00201B), onPrimaryFixedVariant = c(0x005046),
    secondaryFixed = c(0xCDE8E1), secondaryFixedDim = c(0xB2CCC5), onSecondaryFixed = c(0x091F1B), onSecondaryFixedVariant = c(0x344B46),
    tertiaryFixed = c(0xFFDCC1), tertiaryFixedDim = c(0xFFB77C), onTertiaryFixed = c(0x2E1500), onTertiaryFixedVariant = c(0x6D3A00),
)

/** Night vault, light: derived. Deep gold on a cool white; the bright gold is the primary container. */
internal val NightVaultLight = ColorScheme(
    primary = c(0x6A4800), onPrimary = c(0xFFFFFF), primaryContainer = c(0xF2B84B), onPrimaryContainer = c(0x2A1C00),
    secondary = c(0x6A5D43), onSecondary = c(0xFFFFFF), secondaryContainer = c(0xFFDFA3), onSecondaryContainer = c(0x3A2D11),
    tertiary = c(0x1E5586), onTertiary = c(0xFFFFFF), tertiaryContainer = c(0xCCE5FF), onTertiaryContainer = c(0x001C37),
    error = c(0xBA1A1A), onError = c(0xFFFFFF), errorContainer = c(0xFFDAD6), onErrorContainer = c(0x410002),
    background = c(0xF6FAFC), onBackground = c(0x171C21), surface = c(0xF6FAFC), onSurface = c(0x171C21),
    surfaceVariant = c(0xDAE3EE), onSurfaceVariant = c(0x404750), outline = c(0x707881), outlineVariant = c(0xBEC7D2),
    surfaceContainerLowest = c(0xFFFFFF), surfaceContainerLow = c(0xF1F4F7), surfaceContainer = c(0xEBEEF1),
    surfaceContainerHigh = c(0xE5E8EB), surfaceContainerHighest = c(0xE0E3E5),
    surfaceDim = c(0xD7DADD), surfaceBright = c(0xF6FAFC), surfaceTint = c(0x6A4800), scrim = c(0x000000),
    inverseSurface = c(0x2B3136), inverseOnSurface = c(0xEEF1F4), inversePrimary = c(0xF2B84B),
    primaryFixed = c(0xFFDFA3), primaryFixedDim = c(0xF2B84B), onPrimaryFixed = c(0x2A1C00), onPrimaryFixedVariant = c(0x5E4100),
    secondaryFixed = c(0xF1E1C4), secondaryFixedDim = c(0xD4C5A8), onSecondaryFixed = c(0x251A03), onSecondaryFixedVariant = c(0x52452C),
    tertiaryFixed = c(0xCCE5FF), tertiaryFixedDim = c(0x8FC7FF), onTertiaryFixed = c(0x001C37), onTertiaryFixedVariant = c(0x104979),
)

/** Night vault, dark: the mock-up "C". The screen is the darkest colour; what lies on it is lighter. */
internal val NightVaultDark = ColorScheme(
    primary = c(0xF2B84B), onPrimary = c(0x2A1C00), primaryContainer = c(0x3A2D11), onPrimaryContainer = c(0xFFDFA3),
    secondary = c(0xD4C5A8), onSecondary = c(0x3B2F16), secondaryContainer = c(0x3A2D11), onSecondaryContainer = c(0xFFDFA3),
    tertiary = c(0x8FC7FF), onTertiary = c(0x003259), tertiaryContainer = c(0x104979), onTertiaryContainer = c(0xCCE5FF),
    error = c(0xFFB4AB), onError = c(0x690005), errorContainer = c(0x93000A), onErrorContainer = c(0xFFDAD6),
    background = c(0x0E1216), onBackground = c(0xE3E6EA), surface = c(0x0E1216), onSurface = c(0xE3E6EA),
    surfaceVariant = c(0x333C45), onSurfaceVariant = c(0xA7AFB8), outline = c(0x757D86), outlineVariant = c(0x333C45),
    surfaceContainerLowest = c(0x070C10), surfaceContainerLow = c(0x161C21), surfaceContainer = c(0x161C21),
    surfaceContainerHigh = c(0x1C232A), surfaceContainerHighest = c(0x242B32),
    surfaceDim = c(0x0E1216), surfaceBright = c(0x2B3136), surfaceTint = c(0xF2B84B), scrim = c(0x000000),
    inverseSurface = c(0xE3E6EA), inverseOnSurface = c(0x1A1F24), inversePrimary = c(0x6A4800),
    primaryFixed = c(0xFFDFA3), primaryFixedDim = c(0xF2B84B), onPrimaryFixed = c(0x2A1C00), onPrimaryFixedVariant = c(0x5E4100),
    secondaryFixed = c(0xF1E1C4), secondaryFixedDim = c(0xD4C5A8), onSecondaryFixed = c(0x251A03), onSecondaryFixedVariant = c(0x52452C),
    tertiaryFixed = c(0xCCE5FF), tertiaryFixedDim = c(0x8FC7FF), onTertiaryFixed = c(0x001C37), onTertiaryFixedVariant = c(0x104979),
)

/** The opaque colour 0xRRGGBB. */
internal fun c(rgb: Int): Color = Color(0xFF000000 or rgb.toLong())
