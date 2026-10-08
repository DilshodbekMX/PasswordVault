package io.github.dilshodbekmx.passwordvault.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.ui.list.EntryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a style is besides its colours (build plan §12, 4.3b; docs/design/looks/README.md "What
 * differs between styles besides colour" and "The eight avatar tones"): the details of [Looks], and
 * the tones of an account's badge with the contrast of each letter.
 */
class LooksTest {

    @Test
    fun theDetailsOfEachStyleAreTheReadmesTable() {
        for (dark in listOf(false, true)) {
            Looks.of(StyleChoice.BANK_BLUE, dark).let {
                assertEquals(PinKeyLook.FILLED, it.pinKeys)
                assertEquals(false, it.fingerprintKeyTonal)
                assertEquals(CircleShape, it.avatarShape)
                assertEquals(true, it.lockPanel)
                assertEquals(CardsLook.NONE, it.cards)
                assertEquals(AddButtonLook.TONAL, it.addButton)
            }
            Looks.of(StyleChoice.EMERALD, dark).let {
                assertEquals(PinKeyLook.PLAIN, it.pinKeys)
                assertEquals(true, it.fingerprintKeyTonal)
                assertEquals(RoundedCornerShape(percent = 30), it.avatarShape) // 12 dp of 40 dp
                assertEquals(false, it.lockPanel)
                assertEquals(CardsLook.ROW_GROUPS, it.cards)
                assertEquals(AddButtonLook.FILLED_ROUND, it.addButton)
            }
            Looks.of(StyleChoice.NIGHT_VAULT, dark).let {
                assertEquals(PinKeyLook.OUTLINED, it.pinKeys)
                assertEquals(false, it.fingerprintKeyTonal)
                assertEquals(CircleShape, it.avatarShape)
                assertEquals(false, it.lockPanel)
                assertEquals(CardsLook.PASSWORD_BLOCK, it.cards)
                assertEquals(AddButtonLook.FILLED, it.addButton)
            }
        }
    }

    /**
     * The colour table's two outlines: only Emerald in light has a white bar and a white search field
     * on a screen that is nearly white, so only it hands out their outlines.
     */
    @Test
    fun onlyEmeraldInLightOutlinesTheBarAndTheSearchField() {
        val emerald = Looks.of(StyleChoice.EMERALD, dark = false)
        assertEquals("#DCE5E1", emerald.barOutline?.hex())
        assertEquals("#BEC9C5", emerald.searchOutline?.hex())
        assertEquals("#FFFFFF", emerald.colors.surfaceContainer.hex())
        assertEquals("#FFFFFF", emerald.colors.surfaceContainerHigh.hex())
        assertTrue("the bar alone is hard to tell from the screen", contrast(emerald.colors.surfaceContainer, emerald.colors.background) < 1.1)
        for (scheme in OWN_LOOKS.filter { it.look !== emerald }) {
            assertEquals("$scheme", null, scheme.look.barOutline)
            assertEquals("$scheme", null, scheme.look.searchOutline)
        }
    }

    @Test
    fun everyStyleHasALightAndADarkSchemeOfItsOwn() {
        assertSame(BankBlueLight, Looks.of(StyleChoice.BANK_BLUE, dark = false).colors)
        assertSame(BankBlueDark, Looks.of(StyleChoice.BANK_BLUE, dark = true).colors)
        assertSame(EmeraldLight, Looks.of(StyleChoice.EMERALD, dark = false).colors)
        assertSame(EmeraldDark, Looks.of(StyleChoice.EMERALD, dark = true).colors)
        assertSame(NightVaultLight, Looks.of(StyleChoice.NIGHT_VAULT, dark = false).colors)
        assertSame(NightVaultDark, Looks.of(StyleChoice.NIGHT_VAULT, dark = true).colors)
        // The same look every time: nothing is made again when a screen is drawn again.
        for (style in StyleChoice.entries) {
            for (dark in listOf(false, true)) assertSame(Looks.of(style, dark), Looks.of(style, dark))
        }
    }

    @Test
    fun bankBlueStandsInForPhonesColoursWhereThePhoneHasNone() {
        for (dark in listOf(false, true)) {
            assertSame(Looks.of(StyleChoice.BANK_BLUE, dark), Looks.of(StyleChoice.PHONE_COLOURS, dark))
        }
        assertEquals(StyleChoice.BANK_BLUE, StyleChoice.DEFAULT)
    }

    @Test
    fun everyAvatarToneHasAContrastOfAtLeast4point5() {
        val low = mutableListOf<String>()
        for (scheme in OWN_LOOKS) {
            val tones = scheme.look.avatarTones
            assertEquals("$scheme", AVATAR_TONES, tones.size)
            tones.forEachIndexed { i, tone ->
                val ratio = contrast(tone.letter, tone.background)
                if (ratio < 4.5) low += "$scheme ${TONE_NAMES[i]}: ${tone.letter.hex()} on ${tone.background.hex()} is %.2f:1".format(ratio)
            }
        }
        assertTrue("avatar tones under 4.5:1:\n" + low.joinToString("\n"), low.isEmpty())
        // The list's rows pick a tone by this number.
        assertEquals(EntryRow.BADGE_TONES, AVATAR_TONES)
        assertEquals(AVATAR_TONES, TONE_NAMES.size)
    }

    @Test
    fun aBadgeIsLightInALightSchemeAndDarkInADarkOne() {
        for (scheme in OWN_LOOKS) {
            for (tone in scheme.look.avatarTones) {
                val badge = tone.background.luminance()
                val letter = tone.letter.luminance()
                if (scheme.dark) {
                    assertTrue("$scheme ${tone.background.hex()}", badge < 0.08 && letter > 0.5)
                } else {
                    assertTrue("$scheme ${tone.background.hex()}", badge > 0.6 && letter < 0.08)
                }
            }
        }
    }

    @Test
    fun theEightTonesOfASchemeDiffer() {
        for (scheme in OWN_LOOKS) {
            val tones = scheme.look.avatarTones
            assertEquals("$scheme", AVATAR_TONES, tones.map { it.background }.toSet().size)
            assertEquals("$scheme", AVATAR_TONES, tones.map { it.letter }.toSet().size)
        }
    }

    /** The tones of the three schemes the mock-ups show are the README's table, in its order. */
    @Test
    fun theTonesTheMockUpsShowAreTheReadmes() {
        val bankBlueLight = listOf(
            0xD6E3FF to 0x0B2F5E, 0xFFD9DF to 0x5C1129, 0xE6DEFF to 0x2A1A5E, 0xD4EBC9 to 0x173A08,
            0xFFDDB3 to 0x4A2800, 0xC9ECE6 to 0x003A33, 0xDDE3EA to 0x25313D, 0xFFDBCF to 0x5A1B06,
        )
        val emeraldLight = listOf(
            0xD4E4F7 to 0x0E2F4F, 0xF9DDE2 to 0x5A1A29, 0xE3E0F9 to 0x2B2559, 0xD2EED4 to 0x123A1A,
            0xF7E3BF to 0x462C00, 0xC4EDE5 to 0x00382F, 0xDCE4E8 to 0x243038, 0xF9DDD2 to 0x55200C,
        )
        val nightVaultDark = listOf(
            0x1F3450 to 0xC3D7F7, 0x46222C to 0xF7BECA, 0x2F2852 to 0xD3C8FF, 0x1F3519 to 0xBFE0AC,
            0x40300F to 0xF6D08D, 0x143C37 to 0xA9E5DA, 0x263039 to 0xC5D0DB, 0x45251B to 0xF7BBA6,
        )
        val shown = listOf(
            Looks.of(StyleChoice.BANK_BLUE, dark = false) to bankBlueLight,
            Looks.of(StyleChoice.EMERALD, dark = false) to emeraldLight,
            Looks.of(StyleChoice.NIGHT_VAULT, dark = true) to nightVaultDark,
        )
        for ((look, table) in shown) {
            assertEquals(
                table.map { (badge, letter) -> "#%06X".format(badge) to "#%06X".format(letter) },
                look.avatarTones.map { it.background.hex() to it.letter.hex() },
            )
        }
    }

    /** A derived tone has the hue of the tone it is derived from: blue stays blue in the other mode. */
    @Test
    fun aToneKeepsItsHueInTheOtherMode() {
        for (style in OWN_STYLES) {
            val light = Looks.of(style, dark = false).avatarTones
            val dark = Looks.of(style, dark = true).avatarTones
            for (i in 0 until AVATAR_TONES) {
                val a = light[i].background.hue()
                val b = dark[i].letter.hue()
                if (a.chroma < 0.015 || b.chroma < 0.015) continue // slate is nearly grey
                val apart = hueDistance(a.degrees, b.degrees)
                assertTrue("$style ${TONE_NAMES[i]}: $apart degrees apart", apart <= 20.0)
            }
            assertNotEquals(light, dark)
        }
    }

    private companion object {
        /** The tones in the README's order. */
        val TONE_NAMES = listOf("blue", "rose", "violet", "green", "amber", "teal", "slate", "coral")
    }
}
