package io.github.dilshodbekmx.passwordvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One of the eight tones of an account's badge in the list (ux.md §5.11): the colour of the badge
 * and of the letter on it, which have a contrast of at least 4.5:1.
 */
@Immutable
class AvatarTone(val background: Color, val letter: Color)

// The eight tones of each scheme (docs/design/looks/README.md, "The eight avatar tones"), in the
// README's order; a title's hash picks one (`EntryRow.toneOf`). Bank blue light, Emerald light and
// Night vault dark are the mock-ups' own. The other three are derived from them as the schemes are
// (Schemes.kt): the same hue, the badge and the letter at the tones of the scheme the mock-ups show
// (a badge of tone 90 with a letter of tone 20 in a light scheme, 21 and 85 in a dark one).
// LooksTest checks the contrast of every pair.

internal val BankBlueLightTones = avatarTones(
    0xD6E3FF to 0x0B2F5E, // blue
    0xFFD9DF to 0x5C1129, // rose
    0xE6DEFF to 0x2A1A5E, // violet
    0xD4EBC9 to 0x173A08, // green
    0xFFDDB3 to 0x4A2800, // amber
    0xC9ECE6 to 0x003A33, // teal
    0xDDE3EA to 0x25313D, // slate
    0xFFDBCF to 0x5A1B06, // coral
)

internal val BankBlueDarkTones = avatarTones(
    0x1F3350 to 0xC2D5FE, // blue
    0x51252F to 0xFEC6CF, // rose
    0x322D56 to 0xDACEFE, // violet
    0x23381C to 0xBCDEAB, // green
    0x432E1A to 0xFECC8B, // amber
    0x1C3733 to 0xA9DFD6, // teal
    0x2C333B to 0xCCD5E0, // slate
    0x4E281D to 0xFDC9B7, // coral
)

internal val EmeraldLightTones = avatarTones(
    0xD4E4F7 to 0x0E2F4F, // blue
    0xF9DDE2 to 0x5A1A29, // rose
    0xE3E0F9 to 0x2B2559, // violet
    0xD2EED4 to 0x123A1A, // green
    0xF7E3BF to 0x462C00, // amber
    0xC4EDE5 to 0x00382F, // teal
    0xDCE4E8 to 0x243038, // slate
    0xF9DDD2 to 0x55200C, // coral
)

internal val EmeraldDarkTones = avatarTones(
    0x213448 to 0xBFD7F3, // blue
    0x4E272E to 0xF3CAD2, // rose
    0x312F4F to 0xD5D0F5, // violet
    0x213824 to 0xB5DFB8, // green
    0x40301A to 0xEED19A, // amber
    0x1D3731 to 0xA2E0D5, // teal
    0x2C3439 to 0xCAD6DC, // slate
    0x4B2A1F to 0xF5CCBB, // coral
)

internal val NightVaultLightTones = avatarTones(
    0xD7E3F7 to 0x0B305E, // blue
    0xFED9E1 to 0x571B2F, // rose
    0xE4DFFF to 0x34236A, // violet
    0xD4E9C8 to 0x123805, // green
    0xF7E0B7 to 0x412D00, // amber
    0xC6EAE3 to 0x003732, // teal
    0xDCE3EA to 0x223241, // slate
    0xFFDBCE to 0x55200F, // coral
)

internal val NightVaultDarkTones = avatarTones(
    0x1F3450 to 0xC3D7F7, // blue
    0x46222C to 0xF7BECA, // rose
    0x2F2852 to 0xD3C8FF, // violet
    0x1F3519 to 0xBFE0AC, // green
    0x40300F to 0xF6D08D, // amber
    0x143C37 to 0xA9E5DA, // teal
    0x263039 to 0xC5D0DB, // slate
    0x45251B to 0xF7BBA6, // coral
)

/** How many tones a scheme has: `EntryRow.BADGE_TONES`. */
const val AVATAR_TONES = 8

/** The tones of one scheme from its pairs of 0xRRGGBB: a badge's colour to its letter's. */
private fun avatarTones(vararg pairs: Pair<Int, Int>): List<AvatarTone> {
    require(pairs.size == AVATAR_TONES)
    return pairs.map { (background, letter) -> AvatarTone(c(background), c(letter)) }
}
