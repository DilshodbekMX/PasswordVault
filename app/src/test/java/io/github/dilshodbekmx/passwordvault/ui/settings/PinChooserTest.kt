package io.github.dilshodbekmx.passwordvault.ui.settings

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.ui.setup.PinChooser
import io.github.dilshodbekmx.passwordvault.ui.setup.PinDigits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 4.3, [JVM]: the choice of a new PIN shared by setup and "Change PIN" (lock.md §7.2,
 * §7.3; ux.md §5.6) over the real blocklist: the three messages, the first entry kept for the second,
 * three mismatches back to the first entry, and a new PIN equal to the current one refused.
 */
class PinChooserTest {
    private fun chooser() = PinChooser(PinBlocklist::reason)

    private fun PinChooser.type(pin: String, current: ByteArray? = null): PinChooser.Result {
        var result: PinChooser.Result = PinChooser.Result.Typing
        for (digit in digitsOf(pin)) result = digit(digit, current)
        return result
    }

    @Test
    fun theThreeBlocklistMessages() {
        val cases = listOf(
            Pins.DATE to R.string.pin_refused_date,
            Pins.YEAR to R.string.pin_refused_date,
            Pins.REPEAT to R.string.pin_refused_pattern,
            Pins.PATTERN to R.string.pin_refused_pattern,
            Pins.STEPS to R.string.pin_refused_pattern,
            Pins.SEQUENCE to R.string.pin_refused_sequence,
            Pins.KEYPAD to R.string.pin_refused_sequence,
            Pins.RUN_OF_FOUR to R.string.pin_refused_sequence,
            Pins.PATH to R.string.pin_refused_sequence,
        )
        for ((pin, message) in cases) {
            val chooser = chooser()
            val result = chooser.type(pin)
            assertTrue("$pin is refused", result is PinChooser.Result.Refused)
            assertEquals("the message for $pin", UiText.of(message), PinChooser.refusal((result as PinChooser.Result.Refused).reason))
            assertEquals("$pin: the dots are cleared and the pad stays", 0, chooser.digits.count)
            assertEquals("$pin: still the first entry", PinChooser.Step.CHOOSE, chooser.step)
        }
    }

    @Test
    fun eachReasonOfTheBlocklistHasOneOfTheThreeMessages() {
        val messages = PinBlocklist.Reason.entries.map { PinChooser.refusal(it) }.toSet()
        assertEquals(
            setOf(UiText.of(R.string.pin_refused_date), UiText.of(R.string.pin_refused_pattern), UiText.of(R.string.pin_refused_sequence)),
            messages,
        )
    }

    @Test
    fun anAllowedPinIsTypedAgainAndThenHandedOver() {
        val chooser = chooser()
        assertEquals(PinChooser.Result.Typing, chooser.digit(5))
        assertEquals(1, chooser.digits.count)
        chooser.delete()
        assertEquals(0, chooser.digits.count)
        assertEquals(PinChooser.Result.ToConfirm, chooser.type(Pins.ALLOWED))
        assertEquals(PinChooser.Step.CONFIRM, chooser.step)
        val confirmed = chooser.type(Pins.ALLOWED)
        assertTrue(confirmed is PinChooser.Result.Confirmed)
        assertArrayEquals(Pins.ALLOWED.toByteArray(Charsets.US_ASCII), (confirmed as PinChooser.Result.Confirmed).pin)
        assertEquals(PinChooser.Step.CHOOSE, chooser.step)
    }

    @Test
    fun threeMismatchesReturnToTheFirstEntry() {
        val chooser = chooser()
        assertEquals(PinChooser.Result.ToConfirm, chooser.type(Pins.ALLOWED))
        assertEquals(PinChooser.Result.Mismatch, chooser.type(Pins.ALLOWED_2))
        assertEquals(1, chooser.mismatches)
        assertEquals(PinChooser.Step.CONFIRM, chooser.step)
        assertEquals(PinChooser.Result.Mismatch, chooser.type(Pins.ALLOWED_3))
        assertEquals(PinChooser.Step.CONFIRM, chooser.step)
        assertEquals(PinChooser.Result.StartAgain, chooser.type(Pins.ALLOWED_2))
        assertEquals(PinChooser.Step.CHOOSE, chooser.step)
        assertEquals(0, chooser.mismatches)
        // The first entry is gone: the old PIN typed now is a new first entry, not a match.
        assertEquals(PinChooser.Result.ToConfirm, chooser.type(Pins.ALLOWED_2))
        assertTrue(chooser.type(Pins.ALLOWED_2) is PinChooser.Result.Confirmed)
    }

    @Test
    fun aMatchAfterTwoMismatchesIsAccepted() {
        val chooser = chooser()
        chooser.type(Pins.ALLOWED)
        chooser.type(Pins.ALLOWED_2)
        chooser.type(Pins.ALLOWED_3)
        assertTrue(chooser.type(Pins.ALLOWED) is PinChooser.Result.Confirmed)
    }

    @Test
    fun aNewPinEqualToTheCurrentOneIsRefused() {
        val chooser = chooser()
        val current = Pins.ALLOWED.toByteArray(Charsets.US_ASCII)
        assertEquals(PinChooser.Result.SameAsCurrent, chooser.type(Pins.ALLOWED, current))
        assertEquals(PinChooser.Step.CHOOSE, chooser.step)
        assertEquals(PinChooser.Result.ToConfirm, chooser.type(Pins.ALLOWED_2, current))
    }

    @Test
    fun digitsAreBytesAndTakingThemClearsThem() {
        val digits = PinDigits()
        for (d in digitsOf(Pins.ALLOWED).dropLast(1)) assertFalse(digits.add(d))
        assertTrue(digits.add(3))
        assertTrue(digits.full)
        assertFalse("a seventh digit is not taken", digits.add(1))
        val taken = digits.take()
        assertArrayEquals(Pins.ALLOWED.toByteArray(Charsets.US_ASCII), taken)
        assertEquals(0, digits.count)
    }
}
