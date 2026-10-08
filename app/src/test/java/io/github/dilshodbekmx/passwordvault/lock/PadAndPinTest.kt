package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 4.1, [JVM]: the pad through [DefaultLockManager] with fake ports. The pad's states of
 * lock.md §13.2 (idle, entering, "Opening…"), the intents of its keys (`Digit`, `DeleteDigit`,
 * `ClearDigits`, `UseMasterPassword`, `UsePin`), a right and a wrong PIN, the limit (the JVM side of
 * check E1), a rotation that keeps the digits and the charge (lock.md §11), and a PIN whose result
 * comes after its visit has ended (I11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PadAndPinTest {

    /** A phone with a vault and a PIN, locked, in front: the pad of a fresh visit. */
    private suspend fun lockedOnThePad(rig: LockRig) {
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), rig.screen)
        checksBefore = rig.keys.hmacCalls.size // setting the PIN asked the Keystore twice (lock.md F6 steps 5 and 6)
        rig.vault.matched.clear() // setup's own master password was compared (D-9b)
    }

    private var checksBefore = 0

    /** How many times the PIN key was asked since the pad was shown: once for each PIN that was checked. */
    private val LockRig.checks: Int get() = keys.hmacCalls.size - checksBefore

    // ---- the keys ----------------------------------------------------------------------------------

    @Test
    fun theFirstDigitTakesTheCharge_andTheDotsFollowTheKeys() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        assertFalse(rig.pinRecord().guard.charged)

        rig.send(LockIntent.Digit(5))
        assertEquals("pad, entering: one dot", 1, rig.pad.digits)
        assertTrue("the try is on disk before anything is checked (lock.md §6.3 C)", rig.pinRecord().guard.charged)
        rig.press("82")
        assertEquals(3, rig.pad.digits)
        assertFalse(rig.pad.opening)
        assertEquals("no digit reached the Keystore", 0, rig.checks)
    }

    @Test
    fun deletingBackToNoneGivesTheEntryUp_andRefundsTheCharge() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.press("58")
        rig.send(LockIntent.DeleteDigit)
        assertEquals(1, rig.pad.digits)
        assertTrue("one digit is still typed: the charge is held", rig.pinRecord().guard.charged)
        rig.send(LockIntent.DeleteDigit)
        assertEquals(0, rig.pad.digits)
        val record = rig.pinRecord()
        assertFalse("lock.md §6.3 A: a refund", record.guard.charged)
        assertEquals("a refund never touches a count", 0 to 0, record.guard.row to record.guard.total)
        rig.send(LockIntent.DeleteDigit) // nothing left to delete
        assertEquals(0, rig.pad.digits)
    }

    @Test
    fun clearDigitsEmptiesThePad_andRefundsTheCharge() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.press("5829")
        rig.send(LockIntent.ClearDigits)
        assertEquals(0, rig.pad.digits)
        assertFalse(rig.pinRecord().guard.charged)
    }

    @Test
    fun aDigitThatIsNoKeyOfThePadIsIgnored() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.send(LockIntent.Digit(10))
        rig.send(LockIntent.Digit(-1))
        assertEquals(0, rig.pad.digits)
        assertFalse(rig.pinRecord().guard.charged)
        assertEquals(emptyList<Throwable>(), rig.crashes)
    }

    @Test
    fun useMasterPasswordGivesTheEntryUp_andUsePinLeadsBack() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.press("58")
        rig.send(LockIntent.UseMasterPassword) // "Forgot PIN? Use master password"
        val field = rig.field
        assertNull("the user chose this screen: no reason (lock.md §13.3)", field.reason)
        assertTrue(field.canUsePin)
        assertFalse("lock.md F7 step 1: a held charge is refunded", rig.pinRecord().guard.charged)

        rig.send(LockIntent.UsePin)
        assertEquals(0, rig.pad.digits)
    }

    // ---- the sixth digit ---------------------------------------------------------------------------

    @Test
    fun theSixthDigitShowsOpening_andTheRightPinOpensTheApp() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.press(LockRig.PIN)
        assertTrue("\"Opening…\" until the charge is a second old (lock.md §6.3 E)", rig.pad.opening)
        assertEquals(6, rig.pad.digits)
        assertEquals("I4: no PIN is checked without a charge that is a second old", 0, rig.checks)
        rig.send(LockIntent.Digit(1)) // the pad ignores input until the result is shown
        assertEquals(6, rig.pad.digits)

        rig.advance(LockRig.CHARGE_AGE_MS)
        val unlocked = rig.unlocked
        assertNull("a PIN unlock leaves no proof (lock.md §5.8)", unlocked.proof)
        assertEquals(emptyList<Notice>(), unlocked.notices)
        assertEquals(rig.vaultId, rig.port.openVaultId)
        assertEquals(1, rig.checks)
        val record = rig.pinRecord()
        assertFalse(record.guard.charged)
        assertEquals(0, record.guard.row)
        assertEquals("from the sixth digit to the unlocked state, for the debug status (check E1)", LockRig.CHARGE_AGE_MS, rig.manager.lastPinOpenMs)
        assertEquals("a PIN is no master password: nothing to compare (D-9b)", 0, rig.vault.matched.size)
    }

    @Test
    fun aWrongPinIsCountedAndSaysHowManyTriesAreLeft_andTheSameOneAgainCostsNothing() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.typePin(LockRig.WRONG_PIN)
        assertEquals(LockScreen.Pad(0, PadStatus.WrongPin(left = 4), fingerprintKey = false, opening = false, aside = null), rig.screen)
        var record = rig.pinRecord()
        assertEquals(1 to 1, record.guard.row to record.guard.total)
        assertEquals(1, record.unseen.count)
        assertNull(rig.port.openVaultId)

        rig.typePin(LockRig.WRONG_PIN)
        assertEquals("lock.md §6.6", PadStatus.SameAgain, rig.pad.status)
        record = rig.pinRecord()
        assertEquals("not counted again", 1 to 1, record.guard.row to record.guard.total)
        assertEquals("and not checked again", 1, rig.checks)

        rig.typePin(LockRig.PIN)
        assertTrue(rig.isUnlocked)
        assertEquals("the right PIN put the slip of this visit right: nothing to report (lock.md §6.3 E.6)", emptyList<Notice>(), rig.notices)
        assertEquals(0, rig.pinRecord().guard.total)
    }

    @Test
    fun afterFiveWrongPinsTheSlotAndItsKeyAreGone_andTheMasterPasswordStillOpens() = runTest {
        // The JVM side of check E1 (lock.md §15.3), through the manager.
        val rig = LockRig(this)
        lockedOnThePad(rig)
        assertEquals(PinStatus.On, rig.status.pin)
        assertEquals(1, rig.keys.aliases.count { it.startsWith("pv_pin_") })

        for (wrong in listOf("582914", "582915", "582916", "582917")) rig.typePin(wrong)
        assertEquals(PadStatus.LastTry, rig.pad.status)
        rig.typePin("582918")

        val field = rig.field
        assertEquals("no fingerprint: the master-password screen says why (lock.md §13.3)", MpReason.Pin(PinReason.LIMIT), field.reason)
        assertFalse(field.canUsePin)
        val record = rig.pinRecord()
        assertNull("the slot is gone", record.slot)
        assertEquals(PinRecord.OffReason.LIMIT, record.off?.reason)
        assertEquals("the alias is gone (lock.md §6.5 step 2)", 0, rig.keys.aliases.count { it.startsWith("pv_pin_") })
        assertEquals(PinStatus.Off(PinReason.LIMIT), rig.status.pin)
        assertEquals("no lock event wrote the vault (I1)", 1, rig.port.writingCalls.size) // setup's createVault alone

        val result = rig.unlockWith(LockRig.PASSWORD)
        assertTrue(result is MpResult.Right)
        val notices = rig.notices
        val tries = notices.filterIsInstance<Notice.WrongTries>().single()
        assertEquals(5, tries.wrongPins)
        val offer = notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.TURNED_OFF, offer.reason)
        assertFalse("the unlock just made is the proof (lock.md F5 step 4)", offer.needsMasterPassword)
        assertNotNull(rig.unlocked.proof)
    }

    @Test
    fun aFreshPadWarnsWhenTwoTriesAreLeft() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        for (wrong in listOf("582914", "582915", "582916")) rig.typePin(wrong)
        assertEquals(PadStatus.WrongPin(left = 2), rig.pad.status)
        rig.away()
        rig.toFront()
        assertEquals("a new visit: \"2 tries left before the PIN turns off.\"", PadStatus.TriesLeft(2), rig.pad.status)
    }

    // ---- a rotation (lock.md §11) -------------------------------------------------------------------

    @Test
    fun aRotationKeepsTheDigitsAndTheCharge() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.press("582")
        val writes = rig.files.log.size
        val visitScreen = rig.screen

        rig.rotate() // the activity is destroyed and made again; ProcessLifecycleOwner reports nothing

        assertEquals("the dots are redrawn as they were", visitScreen, rig.screen)
        assertEquals(3, rig.pad.digits)
        assertEquals("no record was written: nothing was refunded and nothing charged again", writes, rig.files.log.size)
        assertTrue("the same charge is used", rig.pinRecord().guard.charged)

        rig.press("913")
        rig.advance(LockRig.CHARGE_AGE_MS)
        assertTrue("the entry begun before the rotation opens the app", rig.isUnlocked)
        assertEquals("one try, checked once", 1, rig.checks)
        assertEquals(0, rig.pinRecord().guard.row)
    }

    // ---- a result that comes after its visit (I11) ---------------------------------------------------

    @Test
    fun aRightPinWhoseCheckEndsAfterTheAppWasLeft_countsAsRight_andOpensNothing() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.typePin(LockRig.WRONG_PIN)
        rig.press(LockRig.PIN)
        assertTrue(rig.pad.opening)

        rig.away() // the screen is switched off, or the app is left, while the charge ages
        rig.advance(LockRig.CHARGE_AGE_MS)

        assertTrue("the app stays locked", rig.state is LockUiState.Locked)
        assertNull("the vault is not open", rig.port.openVaultId)
        val record = rig.pinRecord()
        assertFalse(record.guard.charged)
        assertEquals("the counts are as for any right PIN (lock.md §6.3 E.6)", 0, record.guard.row)

        rig.toFront()
        assertEquals("the next visit starts afresh", 0, rig.pad.digits)
        assertFalse(rig.pad.opening)
        rig.typePin(LockRig.PIN)
        assertTrue(rig.isUnlocked)
    }

    @Test
    fun anEntryLeftUnfinishedWhenTheAppIsLeft_isGivenUp() = runTest {
        val rig = LockRig(this)
        lockedOnThePad(rig)
        rig.press("582")
        assertTrue(rig.pinRecord().guard.charged)
        rig.away()
        assertFalse("the visit ended before the sixth digit: a refund (lock.md §6.3 A)", rig.pinRecord().guard.charged)
        rig.toFront()
        assertEquals(0, rig.pad.digits)
    }
}
