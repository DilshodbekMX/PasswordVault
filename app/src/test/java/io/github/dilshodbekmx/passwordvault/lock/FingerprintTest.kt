package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 4.1, [JVM]: fingerprint through [DefaultLockManager] and the real [PromptLauncher], with
 * fake ports and a fake activity prompt. The prompt that opens by itself (lock.md §9.2 rule 7) and
 * when it must not; the fingerprint key of the pad (`UseFingerprint`); the fingerprint-only screen
 * and the prompt over a lock screen (§13.2); a rotation with the prompt open (§11, check E11); a
 * result that comes after its visit (I11, §9.2 rule 8); turning it on and off (F9) and what the
 * status says (§9.4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FingerprintTest {

    /** A phone with a vault, a PIN and fingerprint unlock, unlocked, with nothing left to answer. */
    private suspend fun withPinAndFingerprint(rig: LockRig): MasterProof {
        val proof = rig.setUpWithPin()
        rig.fingerprintOn(proof)
        rig.answerNotices(NoticeAnswer.LATER)
        return proof
    }

    /** How many prompts for an unlock the activities of [rig] have shown. */
    private fun unlockPrompts(rig: LockRig): Int = rig.window.shows.count { it.use == PromptUse.UNLOCK }

    // ---- the prompt by itself (lock.md §9.2 rule 7) -------------------------------------------------

    @Test
    fun thePromptOpensByItselfOverThePad_andATouchOpensTheApp() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        val pinBefore = rig.pinRecord()

        rig.lock()

        val pad = rig.pad
        assertTrue("the pad shows its fingerprint key", pad.fingerprintKey)
        assertEquals("one prompt, opened by the lock itself", 1, unlockPrompts(rig))
        assertEquals(PromptUse.UNLOCK, rig.prompt.use)
        assertTrue("its negative button is \"Use PIN\": the pad is behind it (lock.md §9.2 rule 5)", rig.prompt.pinUsable)
        assertEquals("fingerprint prompt: the system's prompt over the pad (§13.2)", PromptUse.UNLOCK, rig.launcher.open?.use)
        assertTrue("until a finger opens the vault the app is locked (I7)", rig.state is LockUiState.Locked)

        rig.touch()

        val unlocked = rig.unlocked
        assertNull("a fingerprint approves nothing: no proof (lock.md §8.2)", unlocked.proof)
        assertEquals(emptyList<Notice>(), unlocked.notices)
        assertNull(rig.launcher.open)
        assertEquals("a fingerprint unlock changes no count (lock.md §6.4)", pinBefore.guard, rig.pinRecord().guard)
    }

    @Test
    fun aClosedPromptShowsThePad_itDoesNotOpenAgainByItself_butTheFingerprintKeyOpensIt() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        assertEquals(1, unlockPrompts(rig))

        rig.promptError(13) // the negative button, "Use PIN"
        assertTrue(rig.pad.fingerprintKey)
        assertNull(rig.launcher.open)

        // The focus comes back to the activity when the prompt closes: that alone opens nothing.
        rig.launcher.ready(rig.window)
        rig.settle()
        assertEquals("not again on the same visit after the user closed it", 1, unlockPrompts(rig))

        rig.send(LockIntent.UseFingerprint) // the fingerprint key of the pad
        assertEquals(2, unlockPrompts(rig))
        rig.promptError(10) // Back
        assertTrue(rig.state is LockUiState.Locked)

        rig.send(LockIntent.UseFingerprint)
        rig.touch()
        assertTrue(rig.isUnlocked)
    }

    @Test
    fun everyVisitGetsItsOwnPrompt() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        rig.promptError(10)
        assertEquals(1, unlockPrompts(rig))

        rig.away()
        rig.toFront() // a new visit of the lock screen
        assertEquals(2, unlockPrompts(rig))
        rig.touch()
        assertTrue(rig.isUnlocked)
    }

    @Test
    fun thePromptWaitsUntilTheActivityIsResumedAndHasTheFocus() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.window.focused = false // a dialog of the open app is still closing when the app locks
        rig.lock()
        assertTrue(rig.pad.fingerprintKey)
        assertEquals("nothing opens under a window that has the focus", 0, unlockPrompts(rig))

        rig.window.focused = true
        rig.launcher.ready(rig.window) // the focus is back
        rig.settle()
        assertEquals(1, unlockPrompts(rig))
    }

    @Test
    fun aPromptThatCannotBeShownIsNotCountedAsThisVisitsPrompt() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        rig.promptError(10)
        // The activity is paused: the library would drop a launch without a callback (lock.md §9.2 rule 3).
        rig.window.resumed = false
        rig.send(LockIntent.UseFingerprint)
        assertEquals("nothing was launched", 1, unlockPrompts(rig))
        assertNull(rig.launcher.open)
        assertTrue(rig.pad.fingerprintKey)

        rig.window.resumed = true
        rig.send(LockIntent.UseFingerprint)
        assertEquals(2, unlockPrompts(rig))
    }

    @Test
    fun noPromptOpensByItselfUnderTheForgotSheet() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        rig.promptError(10)
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.OpenForgotSheet) // "Forgot master password?"
        assertEquals(1, unlockPrompts(rig))

        // The app is left and comes back with the sheet still open: the new visit shows the pad under it.
        rig.away()
        rig.toFront()
        assertTrue(rig.pad.fingerprintKey)
        assertEquals("no prompt under the sheet (lock.md §9.2 rule 7: \"no dialog is open\")", 1, unlockPrompts(rig))

        rig.send(LockIntent.CloseSheet) // "Try again"
        assertEquals("with the sheet gone, this visit's prompt opens", 2, unlockPrompts(rig))
    }

    @Test
    fun noPromptOpensByItselfOverTheRestoreScreens_untilTheLockScreenIsUsedAgain() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        rig.promptError(10)
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.OpenForgotSheet)
        rig.send(LockIntent.CloseSheet)
        rig.send(LockIntent.RestoreFromFile) // the screen now opens the restore screens over the lock screen
        assertTrue("the lock itself changes nothing", rig.state is LockUiState.Locked)

        rig.away() // the file picker
        rig.toFront()
        assertTrue("the lock screen under the restore screens is the pad of a new visit", rig.pad.fingerprintKey)
        assertEquals("but no prompt opens over the restore screens", 1, unlockPrompts(rig))

        rig.send(LockIntent.UseFingerprint) // Back from the restore screens, then the fingerprint key
        assertEquals(2, unlockPrompts(rig))
        rig.promptError(10)
        rig.away()
        rig.toFront()
        assertEquals("the lock screen was used again: the next visit's prompt opens by itself", 3, unlockPrompts(rig))
    }

    // ---- the fingerprint-only screen (lock.md §13.1 row 6, §13.2) -----------------------------------

    @Test
    fun withoutAPinTheFingerprintOnlyScreenIsShown_withThePromptOverIt() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUp() // "Not now" at the PIN step
        rig.fingerprintOn(proof)
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()

        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = null, aside = null), rig.screen)
        assertEquals(PromptUse.UNLOCK, rig.prompt.use)
        assertFalse("no pad behind it: the negative button is \"Use master password\"", rig.prompt.pinUsable)

        rig.promptError(13)
        assertTrue(rig.screen is LockScreen.FingerprintOnly)
        rig.send(LockIntent.UseFingerprint) // "Use fingerprint"
        assertEquals(2, unlockPrompts(rig))
        rig.promptError(10)

        rig.send(LockIntent.UseMasterPassword)
        assertFalse(rig.field.canUsePin)
        assertTrue(rig.unlockWith(LockRig.PASSWORD) is MpResult.Right)
    }

    // ---- what a prompt can end with (lock.md §9.3, §14.3) --------------------------------------------

    @Test
    fun aLockoutSaysSoOnThePad_andThePinStillOpens() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        rig.promptError(7)
        assertEquals("\"Fingerprint is locked for now. Use your PIN.\"", FingerprintLine.Locked, rig.pad.status)
        assertTrue("nothing is removed for a passing error (lock.md F15c)", (rig.fingerprintOnDisk() as RecordRead.Ok).record.slot != null)
        rig.typePin()
        assertTrue(rig.isUnlocked)
    }

    @Test
    fun aSuccessWithoutACipherOpensNothing() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        rig.launcher.succeeded(null) // onAuthenticationSucceeded proves nothing by itself (lock.md I7, §9.2 rule 6)
        rig.settle()
        assertTrue(rig.state is LockUiState.Locked)
        assertEquals(FingerprintLine.DidNotOpen, rig.pad.status)
        assertNull(rig.port.openVaultId)
    }

    @Test
    fun aKeyThatTheNewFingerInvalidated_turnsFingerprintOffWithItsLine_andIsOfferedAgainAfterTheUnlock() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.keys.invalidateFingerprintKeys() // a finger was added in the phone's settings (lock.md F15a)
        rig.lock()

        assertEquals("the probe found it before any prompt (F3 step 2)", 0, unlockPrompts(rig))
        val pad = rig.pad
        assertFalse(pad.fingerprintKey)
        assertEquals(FingerprintLine.TurnedOff, pad.status)
        val record = (rig.fingerprintOnDisk() as RecordRead.Ok).record
        assertNull(record.slot)
        assertEquals(FingerprintRecord.OffReason.KEY_GONE, record.off?.reason)
        assertEquals("the PIN is untouched (I6)", PinStatus.On, rig.status.pin)

        rig.typePin()
        assertTrue("\"Turn fingerprint unlock on again?\"", rig.notices.single() is Notice.FingerprintAgain)
    }

    // ---- a rotation (lock.md §11, check E11) ---------------------------------------------------------

    @Test
    fun aRotationWithThePromptOpen_cancelsNothing_andTheResultReachesTheNewActivity() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        val before = rig.window
        val shown = rig.prompt
        val launch = rig.launcher.open!!.launch

        rig.rotate()

        assertEquals("the prompt was not cancelled", 0, before.dismissals)
        assertEquals("the launch is still the one that is outstanding", launch, rig.launcher.open?.launch)
        assertEquals("and no second prompt was launched on the new activity", 0, rig.window.shows.size)
        assertTrue(rig.state is LockUiState.Locked)

        rig.launcher.succeeded(shown.cipher) // the new activity's prompt delivers the result
        rig.settle()
        assertTrue(rig.isUnlocked)
    }

    // ---- a result that comes after its visit (I11; lock.md §9.2 rule 8) --------------------------------

    @Test
    fun whenTheAppIsLeftWithThePromptOpen_itIsCancelled_andALateSuccessOpensNothing() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        val shown = rig.prompt

        rig.away() // ON_STOP ends the visit and its launch

        assertEquals("cancelAuthentication()", 1, rig.window.dismissals)
        assertNull(rig.launcher.open)
        rig.launcher.succeeded(shown.cipher) // a callback with no launch outstanding
        rig.settle()
        assertTrue("dropped: nothing opens", rig.state is LockUiState.Locked)
        assertNull(rig.port.openVaultId)

        rig.toFront()
        assertEquals("the next visit opens its own prompt", 2, unlockPrompts(rig))
        rig.touch()
        assertTrue(rig.isUnlocked)
    }

    @Test
    fun aPromptCancelledAtOnStopThatNeverAnswers_doesNotStopTheNextVisitsPrompt() = runTest {
        val rig = LockRig(this)
        withPinAndFingerprint(rig)
        rig.lock()
        rig.away() // cancelled; its own callback never comes (the activity was stopped)
        rig.toFront()
        // The next launch gives the cancelled prompt's callback a moment to arrive first (rule 8), then goes ahead.
        rig.advance(PromptLauncher.STALE_WAIT_MS)
        assertEquals(2, unlockPrompts(rig))
        rig.touch()
        assertTrue(rig.isUnlocked)
    }

    @Test
    fun aLockWhileThePromptIsOpen_endsItsLaunch() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        // Settings › App lock › Fingerprint unlock: the prompt "Turn on fingerprint unlock" is open when the app locks.
        val turnOn = backgroundScope.asyncOn { rig.manager.fingerprintOn(proof) }
        rig.settle()
        assertEquals(PromptUse.TURN_ON, rig.prompt.use)
        val shown = rig.prompt

        rig.lock(LockReason.NO_TOUCH)

        assertEquals(1, rig.window.dismissals)
        assertEquals("the flow ended with its prompt: nothing was turned on", FingerprintOnResult.Cancelled, turnOn.getCompleted())
        rig.launcher.succeeded(shown.cipher)
        rig.settle()
        assertEquals(FingerprintStatus.Off, rig.status.fingerprint)
        assertEquals("the key that no record names was deleted (lock.md F9 step 9)", 0, rig.keys.aliases.count { it.startsWith("pv_fp_") })
    }

    // ---- on and off (lock.md F9), and the status (§9.4) ------------------------------------------------

    @Test
    fun turningFingerprintOnAndOff_showsInTheStatus() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUpWithPin()
        assertEquals(FingerprintStatus.Off, rig.status.fingerprint)

        rig.fingerprintOn(proof)
        assertEquals(FingerprintStatus.On, rig.status.fingerprint)
        assertEquals(1, rig.keys.aliases.count { it.startsWith("pv_fp_") })

        rig.manager.fingerprintOff() // no proof: making the lock stricter needs nothing (lock.md §8.2)
        rig.settle()
        assertEquals(FingerprintStatus.Off, rig.status.fingerprint)
        val record = (rig.fingerprintOnDisk() as RecordRead.Ok).record
        assertNull(record.slot)
        assertEquals(FingerprintRecord.OffReason.TURNED_OFF, record.off?.reason)
        assertEquals("its key is deleted at once (lock.md §5.7)", 0, rig.keys.aliases.count { it.startsWith("pv_fp_") })
        assertEquals(PinStatus.On, rig.status.pin)
    }

    @Test
    fun turningOn_aClosedPromptAnAbsentFingerAndAPausedActivity_changeNothing() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUpWithPin()

        val closed = backgroundScope.asyncOn { rig.manager.fingerprintOn(proof) }
        rig.settle()
        rig.promptError(13) // "Cancel"
        assertEquals(FingerprintOnResult.Cancelled, closed.getCompleted())

        rig.keys.enrolled = false
        assertEquals(FingerprintOnResult.NoneEnrolled, rig.manager.fingerprintOn(proof))
        rig.keys.enrolled = true

        rig.window.resumed = false
        assertEquals(FingerprintOnResult.NotShown, rig.manager.fingerprintOn(proof))
        rig.window.resumed = true

        rig.settle()
        assertEquals(FingerprintStatus.Off, rig.status.fingerprint)
        assertEquals("no key is left behind", 0, rig.keys.aliases.count { it.startsWith("pv_fp_") })
    }

    @Test
    fun turningOnNeedsAProofThatTheLockMadeAndStillHolds() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUpWithPin()
        rig.away() // ON_STOP drops every proof (lock.md §5.8)
        rig.toFront()
        assertNull(rig.unlocked.proof)
        assertEquals(FingerprintOnResult.Failed, rig.manager.fingerprintOn(proof))
        assertEquals(0, rig.window.shows.size)
    }

    @Test
    fun whatThePhoneSaysAboutFingerprintIsAskedAgainAtEveryUnlockAndEveryReturn() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals("canAuthenticate 0: the switch is enabled", FingerprintStatus.Off, rig.status.fingerprint)

        rig.keys.availability = 11 // every finger was deleted in the phone's settings while the app was away
        rig.away()
        rig.toFront()
        assertEquals("lock.md §9.4: its reason is shown in Settings", FingerprintStatus.Unavailable(11), rig.status.fingerprint)

        rig.keys.availability = 0
        rig.lock()
        rig.typePin()
        assertEquals("asked again at the unlock", FingerprintStatus.Off, rig.status.fingerprint)
    }

    @Test
    fun theProofOfSetupIsTheOneTheStatePublishes() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUp()
        assertSame(proof, rig.unlocked.proof)
    }
}
