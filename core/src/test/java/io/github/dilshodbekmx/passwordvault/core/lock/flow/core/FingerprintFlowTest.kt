package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintResult
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of F3, the daily unlock with the fingerprint (lock.md §12), through the engine: the
 * prompt that opens by itself (§9.2 rule 7), the launch numbers (rule 8), and what section 14 says
 * about every result (F15a, F15b, F15c).
 */
class FingerprintFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    /** Writes down what the engine asks of the fingerprint key, in order; [cancelWorks] false: `cancel()` reaches nothing. */
    private class Watched(private val inner: FingerprintKey) : FingerprintKey {
        val asked = ArrayList<String>()
        var cancelWorks = true

        override fun availability(): Int = inner.availability().also { asked += "availability" }

        override fun create(alias: String): KeyCreate = inner.create(alias)

        override fun probe(alias: String, nonce: ByteArray): Ks<Unit> = inner.probe(alias, nonce).also { asked += "probe" }

        override suspend fun seal(alias: String, vk: ByteArray, use: PromptUse, launch: Int): FingerprintResult =
            inner.seal(alias, vk, use, launch)

        override suspend fun open(alias: String, nonce: ByteArray, ct: ByteArray, use: PromptUse, launch: Int): FingerprintResult {
            asked += "open $use $launch"
            return inner.open(alias, nonce, ct, use, launch)
        }

        override fun cancel() {
            asked += "cancel"
            if (cancelWorks) inner.cancel()
        }
    }

    private val watched = Watched(f.keys.fingerprint)

    private lateinit var vault: FakeVaultPort.FakeVault
    private lateinit var finger: ShortcutSlot

    /** A vault with a PIN (unless [pin] is false) and fingerprint unlock, started and in front. */
    private fun withFingerprint(pin: Boolean = true, row: Int = 0, total: Int = 0) {
        f.fingerprintKey = watched
        f.rebuild()
        vault = f.seedVault()
        if (pin) f.plantPin(vault, row = row, total = total)
        finger = f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
    }

    private val alias get() = RecordCodec.FINGERPRINT_ALIAS_PREFIX + finger.id

    // ---- F3 ------------------------------------------------------------------------------------------

    @Test
    fun f3_thePromptOpensByItselfOncePerVisit_andAnEnrolledFingerOpensTheVault() {
        withFingerprint(row = 2, total = 3)
        // Step 1: the pad, with the fingerprint key once canAuthenticate has answered (asked once per visit).
        assertTrue(f.pad.fingerprintKey)
        assertEquals(listOf("availability"), watched.asked)

        await { f.engine.promptIfDue() }

        // Step 2 before step 3: the probe, then the prompt, under launch number 1.
        assertEquals(listOf("availability", "probe", "open UNLOCK 1"), watched.asked)
        assertEquals(FakeKeys.PromptCall("open", alias, PromptUse.UNLOCK, 1), f.keys.prompts.single())
        // Step 4: 32 bytes that open the vault (I7).
        assertEquals(LockState.Unlocked(emptyList(), null), f.state)
        assertEquals(LockFixture.VAULT, f.port.openVaultId)
        // Step 5: no count is reset.
        assertEquals(PinRecord.Guard(charged = false, row = 2, total = 3), f.pinRecord().guard)
        assertEquals(0, f.keys.hmacCalls.size)
        assertTrue(f.port.writes.isEmpty())

        // The next visit asks again and launches under the next number.
        f.lock()
        await { f.engine.promptIfDue() }
        assertEquals(listOf("availability", "probe", "open UNLOCK 1", "availability", "probe", "open UNLOCK 2"), watched.asked)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f3_step5_aChargeThatIsHeldIsRefundedWhenTheFingerprintIsUsed() {
        withFingerprint()
        f.type("58")
        assertTrue(f.pinRecord().guard.charged)
        f.keys.holdPrompts = true
        runBlocking {
            val prompt = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.useFingerprint() }
            // §6.3 A: the fingerprint key is tapped, so the entry is given up before the prompt is answered.
            assertFalse(f.pinRecord().guard.charged)
            assertEquals(0, f.pad.digits)
            assertEquals(1, f.engine.core.outstandingLaunch)
            f.keys.answer(FakeKeys.Prompt.Touch)
            prompt.join()
        }
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
        assertEquals(0, f.engine.core.outstandingLaunch)
    }

    @Test
    fun f3_step7_aClosedPromptShowsThePad_andItDoesNotOpenByItselfAgainOnThisVisit() {
        withFingerprint()
        f.keys.scriptPrompt(FakeKeys.Prompt.Close(10)) // Back, or the X
        await { f.engine.promptIfDue() }
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = true, opening = false, aside = null), f.screen)

        await { f.engine.promptIfDue() } // another resume of the same visit
        assertEquals(1, f.keys.prompts.size)

        f.keys.scriptPrompt(FakeKeys.Prompt.Close(13)) // the fingerprint key opens it again; "Use PIN" closes it
        await { f.engine.useFingerprint() }
        assertEquals(2, f.keys.prompts.size)
        assertEquals(2, f.keys.prompts.last().launch)
        assertNull(f.pad.status)
        assertEquals(finger, f.fingerprintRecord().slot) // nothing changes

        f.leaveAndReturn() // the next visit opens it by itself again
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f3_aPromptThatCouldNotBeShown_isTriedAgainAtTheNextResume() {
        withFingerprint()
        f.keys.scriptPrompt(FakeKeys.Prompt.NotShown) // the activity was not resumed (§9.2 rule 3)
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Locked)
        await { f.engine.promptIfDue() }
        assertEquals(2, f.keys.prompts.size)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f3_theSystemsLockout_saysSo_andKeepsTheKeyOnThePad() {
        withFingerprint()
        f.keys.scriptPrompt(FakeKeys.Prompt.Error(7))
        await { f.engine.promptIfDue() }
        assertEquals(FingerprintLine.Locked, f.pad.status)
        assertTrue(f.pad.fingerprintKey)
        f.keys.scriptPrompt(FakeKeys.Prompt.Error(9))
        await { f.engine.useFingerprint() }
        assertEquals(FingerprintLine.Locked, f.pad.status)
        assertEquals(finger, f.fingerprintRecord().slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
    }

    @Test
    fun f3_aPromptThatSucceededAndDidNotOpen_isTemporary_andANewPromptWorks() {
        withFingerprint()
        f.keys.scriptPrompt(FakeKeys.Prompt.SucceededButFailed) // the phone's own PIN was typed on the prompt's lockout screen (§9.3)
        await { f.engine.promptIfDue() }
        assertEquals(FingerprintLine.DidNotOpen, f.pad.status)
        assertTrue(f.pad.fingerprintKey)
        assertTrue(f.state is LockState.Locked) // success is not an unlock (I7)
        assertEquals(finger, f.fingerprintRecord().slot)

        await { f.engine.useFingerprint() }
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f3_fingerprintSwitchedOffForApps_andAnyOtherError() {
        withFingerprint()
        f.keys.scriptPrompt(FakeKeys.Prompt.Error(1))
        await { f.engine.promptIfDue() }
        assertEquals(FingerprintLine.NotForApps, f.pad.status)
        assertTrue(f.pad.fingerprintKey)

        f.keys.scriptPrompt(FakeKeys.Prompt.Error(2)) // any other code: the key is hidden until the next visit
        await { f.engine.useFingerprint() }
        assertEquals(FingerprintLine.CannotCheck, f.pad.status)
        assertFalse(f.pad.fingerprintKey)
        await { f.engine.useFingerprint() } // hidden: nothing opens
        assertEquals(2, f.keys.prompts.size)
        assertEquals(finger, f.fingerprintRecord().slot) // nothing is removed and nothing stored

        f.leaveAndReturn()
        assertTrue(f.pad.fingerprintKey)
        assertNull(f.pad.status)
    }

    // ---- section 14 ----------------------------------------------------------------------------------

    @Test
    fun f15a_theKeyIsInvalidAtTheProbe_theSlotIsRemovedBeforeAnyPrompt_andThePinStillWorks() {
        withFingerprint()
        f.keys.invalidateFingerprintKeys() // a finger was added in the phone's settings
        await { f.engine.promptIfDue() }

        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts) // classified before the user is asked for a finger
        val record = f.fingerprintRecord()
        assertNull(record.slot)
        assertEquals(FingerprintRecord.Off(FingerprintRecord.OffReason.KEY_GONE, f.clock.wallMs()), record.off)
        assertEquals(listOf(alias), f.keys.deleted)
        assertEquals(LockScreen.Pad(digits = 0, status = FingerprintLine.TurnedOff, fingerprintKey = false, opening = false, aside = null), f.screen)
        assertEquals(LockSummary.Fingerprint.Off, f.engine.summary.value.fingerprint)

        f.type(PIN) // pin.rec is untouched
        assertTrue(f.state is LockState.Unlocked)
        // F15a step 3: after the unlock, "Turn fingerprint unlock on again?", once.
        val offer = f.notices.single() as Notice.FingerprintAgain
        await { f.engine.answerNotice(offer.id, NoticeAnswer.LATER) }
        assertNull(f.fingerprintRecord().off) // `off` is only for the one-time explanation
        f.lock()
        f.type(PIN)
        assertEquals(emptyList<Notice>(), f.notices)
    }

    @Test
    fun f15a_withoutAPin_theMasterPasswordScreenSaysTheSame() {
        withFingerprint(pin = false)
        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = null, aside = null), f.screen)
        f.keys.invalidateFingerprintKeys()
        await { f.engine.promptIfDue() }
        assertNull(f.field.reason)
        assertNull(f.fingerprintRecord().slot)
    }

    @Test
    fun f15b_theKeyIsGone_theSlotIsRemoved() {
        withFingerprint()
        f.keys.dropAllKeys() // a reinstall that kept the data and not the keys; the canary can be made again
        await { f.engine.promptIfDue() }
        assertNull(f.fingerprintRecord().slot)
        assertEquals(FingerprintRecord.OffReason.KEY_GONE, f.fingerprintRecord().off!!.reason)
        assertEquals(FingerprintLine.TurnedOff, f.pad.status)
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
    }

    @Test
    fun f15c_theKeystoreDoesNotAnswerAtTheProbe_theKeyIsHiddenForThisVisit_andNothingElseChanges() {
        withFingerprint()
        f.keys.scriptProbe(Ks.Temporary(null))
        await { f.engine.promptIfDue() }
        assertEquals(FingerprintLine.CannotCheck, f.pad.status)
        assertFalse(f.pad.fingerprintKey)
        assertEquals(finger, f.fingerprintRecord().slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)

        f.leaveAndReturn() // the next visit tries again
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun aWrapThatDoesNotMatchItsKey_showsAtItsFirstUse_andIsRemovedThen() {
        withFingerprint()
        val garbage = KeyWrap(vault.newestVkId, ByteArray(12) { 4 }, ByteArray(48) { 5 })
        f.putFingerprint(FingerprintRecord(LockFixture.VAULT, finger.copy(wraps = listOf(garbage)), null))
        f.restart()
        f.startInFront()
        await { f.engine.promptIfDue() }
        assertNull(f.fingerprintRecord().slot)
        assertEquals(FingerprintRecord.OffReason.BAD_WRAP, f.fingerprintRecord().off!!.reason)
        assertEquals(listOf(alias), f.keys.deleted)
        assertEquals(FingerprintLine.TurnedOff, f.pad.status)
        assertTrue(f.state is LockState.Locked)
    }

    @Test
    fun whileThePhoneSaysFingerprintCannotBeUsed_theKeyIsNotProbed_andTheSlotIsLeftAlone() {
        f.keys.availability = 11 // every finger was deleted, or the screen lock removed
        withFingerprint()
        f.keys.invalidateFingerprintKeys()
        assertEquals(LockScreen.Pad(digits = 0, status = FingerprintLine.NoneSaved, fingerprintKey = false, opening = false, aside = null), f.screen)
        await { f.engine.promptIfDue() }
        await { f.engine.useFingerprint() }
        assertFalse("probe" in watched.asked)
        assertEquals(finger, f.fingerprintRecord().slot)
        assertEquals(LockSummary.Fingerprint.Unavailable(11), f.engine.summary.value.fingerprint)

        // The first visit after fingerprint can be used again finds out that the key died (F15a).
        f.keys.availability = 0
        f.leaveAndReturn()
        await { f.engine.promptIfDue() }
        assertNull(f.fingerprintRecord().slot)
        assertEquals(FingerprintLine.TurnedOff, f.pad.status)
    }

    @Test
    fun theOtherAnswersOfCanAuthenticate() {
        f.keys.availability = 1
        withFingerprint()
        assertEquals(FingerprintLine.NotForApps, f.pad.status)
        f.keys.availability = 15
        f.leaveAndReturn()
        assertEquals(FingerprintLine.UpdateNeeded, f.pad.status)
        f.keys.availability = 12
        f.leaveAndReturn()
        assertEquals(FingerprintLine.CannotCheck, f.pad.status)
        f.keys.availability = -1 // unknown (API 28 and 29): the prompt decides
        f.leaveAndReturn()
        assertTrue(f.pad.fingerprintKey)
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(finger, f.fingerprintRecord().slot)
    }

    // ---- the fingerprint-only screen, and practice ------------------------------------------------------

    @Test
    fun onlyFingerprint_theFingerprintOnlyScreen_andNoPinIsOfferedAfterwards() {
        withFingerprint(pin = false)
        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = null, aside = null), f.screen)
        await { f.engine.promptIfDue() }
        assertEquals(LockState.Unlocked(emptyList(), null), f.state) // a fingerprint approves nothing: no proof, no PIN offer
    }

    @Test
    fun onAPracticeScreen_thePromptDoesNotOpenByItself() {
        f.fingerprintKey = watched
        f.rebuild()
        vault = f.seedVault()
        f.plantPin(vault)
        finger = f.plantFingerprint(vault)
        f.plantState(practice = StateRecord.Practice(f.clock.wallMs() - 2 * 86_400_000L, 0, null)) // due since yesterday
        f.startInFront()
        assertEquals(MpReason.PracticeDue, f.field.reason)
        await { f.engine.promptIfDue() }
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertFalse("probe" in watched.asked)
    }

    // ---- launch numbers (§9.2 rule 8), I11 ---------------------------------------------------------------

    @Test
    fun aSecondCallWhileAPromptIsOpenIsIgnored() {
        withFingerprint()
        f.keys.holdPrompts = true
        runBlocking {
            val first = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.promptIfDue() }
            f.engine.useFingerprint()
            f.engine.promptIfDue()
            assertEquals(1, f.keys.prompts.size)
            f.keys.answer(FakeKeys.Prompt.Touch)
            first.join()
        }
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun whenTheVisitEnds_theOpenPromptIsCancelled_andItsResultOpensNothing() {
        withFingerprint()
        f.keys.holdPrompts = true
        runBlocking {
            val prompt = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.promptIfDue() }
            assertEquals(1, f.engine.core.outstandingLaunch)
            f.engine.appStopped() // Home while the prompt is open
            prompt.join()
        }
        assertTrue("cancel" in watched.asked)
        assertEquals(0, f.engine.core.outstandingLaunch)
        assertTrue(f.state is LockState.Locked)
        assertNull(f.port.openVaultId)
    }

    @Test
    fun aCallbackThatArrivesWhenNoLaunchIsOutstanding_isDropped_andTheNextVisitGetsItsOwnPrompt() {
        withFingerprint()
        watched.cancelWorks = false // the system closed the prompt, and its result comes only when the app is in front again
        f.keys.holdPrompts = true
        runBlocking {
            val late = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.promptIfDue() }
            f.engine.appStopped()
            f.engine.appStarted() // the next visit
            assertEquals(0, f.engine.core.outstandingLaunch)

            f.keys.answer(FakeKeys.Prompt.Touch) // the old launch answers now, even with a finger
            late.join()
            assertTrue(f.state is LockState.Locked) // dropped: it opens nothing
            assertNull(f.port.openVaultId)
            assertFalse("open current" in f.port.calls)

            f.keys.holdPrompts = false
            f.engine.promptIfDue() // the new visit's own prompt, under its own number
        }
        assertEquals(listOf(1, 2), f.keys.prompts.map { it.launch })
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun i11_aVisitThatEndsWhileStorageOpensTheVault_theKeyIsDroppedAndTheAppStaysLocked() {
        withFingerprint()
        runBlocking {
            f.port.beforeCall = { call ->
                if (call == "open current") {
                    watched.cancelWorks = false
                    launch(start = CoroutineStart.UNDISPATCHED) { f.engine.appStopped() }
                }
            }
            f.engine.promptIfDue()
        }
        assertTrue(f.state is LockState.Locked)
        assertNull(f.port.openVaultId)
        assertEquals(listOf("vaults", "open current", "lock"), f.port.calls)
    }

    // ---- older copies, and a copy that does not open -------------------------------------------------------

    @Test
    fun f16a_anOlderCopyUnderTheSameKey_theFingerprintOpensIt_withTheOlderCopyNotice() {
        withFingerprint()
        vault.save()
        vault.live.first().state = FakeVaultPort.FileState.DAMAGED
        f.lock() // nothing was open; a new process is simpler
        f.restart()
        f.startInFront()
        await { f.engine.promptIfDue() }
        assertEquals(1L, f.port.openGeneration)
        assertTrue(f.notices.single() is Notice.OlderCopy)
        assertEquals(finger, f.fingerprintRecord().slot)
    }

    @Test
    fun f16a_theCopyThatOpensNeedsAnotherKey_theMasterPasswordScreenNamesIt_andTheShortcutsAreKept() {
        withFingerprint()
        // Since the lock screen was drawn, a newer generation under another key has appeared.
        vault.rekey(bytes("a newer master password"))
        await { f.engine.promptIfDue() }

        assertEquals(MpReason.OlderCopy(vault.live.first().createdMs), f.field.reason)
        assertFalse(f.field.canUsePin)
        assertEquals(finger, f.fingerprintRecord().slot)
        assertEquals(2, f.keys.aliases.size)
        assertNull(f.port.openVaultId)
    }

    @Test
    fun aFingerprintWhoseCopyDoesNotOpen_isStoragesFlow() {
        withFingerprint()
        vault.live.first().state = FakeVaultPort.FileState.DAMAGED // since the lock screen was drawn
        await { f.engine.promptIfDue() }
        assertEquals(LockState.Unreadable(CurrentVault.Why.DAMAGED, emptyList()), f.state)
        assertEquals(finger, f.fingerprintRecord().slot)
    }

    @Test
    fun afterTheLimit_aFingerprintUnlockOffersThePinThatNeedsTheMasterPassword() {
        withFingerprint(row = 4, total = 9)
        f.type(LockFixture.WRONG_PIN)
        assertTrue(f.screen is LockScreen.FingerprintOnly)
        await { f.engine.promptIfDue() } // it has not opened by itself on this visit yet
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.TURNED_OFF, offer.reason)
        assertTrue(offer.needsMasterPassword)
    }
}
