package io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintOnOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of **F15**, Keystore failures (lock.md §12, §14), each case from the failure to the
 * shortcut working again, through the engine: **a** the fingerprint key is invalidated (a finger
 * added), **b** a key is gone (one alias, or every key of the app as after a reinstall without its
 * keys), **c** the Keystore does not answer, **d** it keeps failing. The lock screen's half is
 * `UnlockFlows` (3.10); what these runs add is the end of each case in F6 and F9: the offer after
 * the unlock, the master password of that flow, and a new key. Each run ends in the state lock.md
 * describes, and nothing is removed for a failure that is not GONE (I6).
 */
class KeystoreFailureFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    private lateinit var vault: FakeVaultPort.FakeVault

    /** A vault with a PIN (when asked) and fingerprint unlock, started and in front. */
    private fun phone(pin: Boolean = true, fingerprint: Boolean = true) {
        vault = f.seedVault()
        if (pin) f.plantPin(vault)
        if (fingerprint) f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
    }

    // ---- a. the fingerprint key is invalid ---------------------------------------------------------------

    @Test
    fun f15a_aFingerAdded_theSlotGoesAtTheProbe_thePinStillWorks_andFingerprintIsTurnedOnAgain() {
        phone()
        val old = f.fingerprintAliasOnDisk()!!
        f.keys.invalidateFingerprintKeys()
        await { f.engine.promptIfDue() }
        // Step 1: the slot is removed (`off = key_gone`), the alias deleted, before any prompt.
        assertNull(f.fingerprintRecord().slot)
        assertEquals(FingerprintRecord.OffReason.KEY_GONE, f.fingerprintRecord().off?.reason)
        assertEquals(listOf(old), f.keys.deleted)
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        // Step 2: the pad says so; the PIN is untouched.
        assertEquals(FingerprintLine.TurnedOff, f.pad.status)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        // Step 3: "Turn fingerprint unlock on again?" leads to F9, which needs the master password.
        val offer = f.notices.single() as Notice.FingerprintAgain
        await { f.engine.answerNotice(offer.id, NoticeAnswer.TURN_ON_FINGERPRINT) }
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(f.proofInTheOpenApp()))
        assertTrue(f.fingerprintOpens(vault))
        assertEquals(LockSummary.Fingerprint.On, f.engine.summary.value.fingerprint)
        f.lock()
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f15a_withoutAPin_theMasterPasswordScreen_thenTheProofOfThatUnlockTurnsItOnAgain() {
        phone(pin = false)
        assertTrue(f.screen is LockScreen.FingerprintOnly)
        f.keys.invalidateFingerprintKeys()
        await { f.engine.promptIfDue() }
        assertEquals(FingerprintLine.TurnedOff, f.field.fingerprintLine)
        val proof = f.unlockWithMasterPassword()
        assertTrue(f.notices.any { it is Notice.FingerprintAgain })
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
        assertTrue(f.fingerprintOpens(vault))
    }

    @Test
    fun f15a_allFingersDeleted_nothingIsProbedOrRemovedUntilAFingerIsSavedAgain() {
        phone()
        val slot = f.fingerprintRecord().slot
        f.keys.invalidateFingerprintKeys()
        f.keys.availability = 11 // no finger saved
        f.leaveAndReturn()
        assertEquals(FingerprintLine.NoneSaved, f.pad.status)
        await { f.engine.promptIfDue() }
        assertEquals(slot, f.fingerprintRecord().slot) // left alone (§13.1)
        f.type(PIN)
        assertEquals(LockSummary.Fingerprint.Unavailable(11), f.engine.summary.value.fingerprint)
        // F9 cannot turn on a fingerprint without a finger: "Add a fingerprint in your phone's settings first."
        f.keys.enrolled = false
        assertEquals(FingerprintOnOutcome.NoneEnrolled, f.fingerprintOn(f.proofInTheOpenApp()))
        assertEquals(slot, f.fingerprintRecord().slot)

        // A finger is saved again: the first visit finds the key dead (F15a), and F9 makes a new one.
        f.keys.enrolled = true
        f.keys.availability = 0
        f.lock()
        await { f.engine.promptIfDue() }
        assertNull(f.fingerprintRecord().slot)
        f.type(PIN)
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(f.proofInTheOpenApp()))
        assertTrue(f.fingerprintOpens(vault))
    }

    // ---- b. a key is gone ---------------------------------------------------------------------------------

    @Test
    fun f15b_thePinKeyIsGone_theSlotGoes_theFallbackSaysSo_andTheMasterPasswordSetsAPinAgain() {
        phone()
        val pinAlias = f.pinAliasOnDisk()!!
        f.keys.device.delete(pinAlias)
        f.type(PIN)
        // Step 1: the slot is removed (`off = key_gone`); nothing was counted.
        val record = f.pinRecord()
        assertNull(record.slot)
        assertEquals(PinRecord.OffReason.KEY_GONE, record.off?.reason)
        assertEquals(PinRecord.Guard(false, 0, 0), record.guard)
        // Step 2: the fingerprint-only screen, with the reason.
        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = PinReason.STOPPED_WORKING, aside = null), f.screen)
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        // Step 3: the offer; after a fingerprint unlock it needs the master password (§8.2).
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.STOPPED_WORKING to true, offer.reason to offer.needsMasterPassword)
        assertTrue(f.setPin(NEW_PIN, f.proofInTheOpenApp()))
        assertTrue(f.pinOpens(vault, NEW_PIN))
        f.lock()
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f15b_theFingerprintKeyIsGone() {
        phone()
        val alias = f.fingerprintAliasOnDisk()!!
        f.keys.device.delete(alias)
        await { f.engine.promptIfDue() }
        assertNull(f.fingerprintRecord().slot)
        assertEquals(FingerprintRecord.OffReason.KEY_GONE, f.fingerprintRecord().off?.reason)
        assertEquals(FingerprintLine.TurnedOff, f.pad.status)
        f.type(PIN)
        assertTrue(f.notices.any { it is Notice.FingerprintAgain })
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(f.proofInTheOpenApp()))
        assertTrue(f.fingerprintOpens(vault))
    }

    @Test
    fun f15b_everyKeyIsGone_dropKeys_theStoppedWorkingTexts_notRestartYourPhone_andBothAreSetUpAgain() {
        phone()
        f.keys.dropAllKeys() // the canary too; it can be made again (§14.2)
        await { f.engine.promptIfDue() }
        assertNull(f.fingerprintRecord().slot)
        f.type(PIN)
        assertNull(f.pinRecord().slot)
        assertEquals(MpReason.Pin(PinReason.STOPPED_WORKING), f.field.reason) // not "can't be checked right now"
        assertEquals(FingerprintLine.TurnedOff, f.field.fingerprintLine)

        val proof = f.unlockWithMasterPassword()
        assertEquals(ChoosePinReason.STOPPED_WORKING, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
        assertTrue(f.notices.any { it is Notice.FingerprintAgain })
        assertTrue(f.setPin(NEW_PIN, proof))
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
        assertTrue(f.pinOpens(vault, NEW_PIN))
        assertTrue(f.fingerprintOpens(vault))
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)
        assertEquals(LockSummary.Fingerprint.On, f.engine.summary.value.fingerprint)
    }

    @Test
    fun f15b_inChangePin_theSlotGoes_andTheMasterPasswordSetsANewPin() {
        phone(fingerprint = false)
        f.type(PIN)
        f.keys.device.delete(f.pinAliasOnDisk()!!)
        assertEquals(PinChangeOutcome.CannotCheck, f.changePin(PIN, NEW_PIN))
        assertEquals(PinRecord.OffReason.KEY_GONE, f.pinRecord().off?.reason)
        assertTrue(f.setPin(NEW_PIN, f.proofInTheOpenApp()))
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }

    // ---- c. the Keystore does not answer -------------------------------------------------------------------

    @Test
    fun f15c_thePin_isRefunded_notOfferedAgainOnThisVisit_nothingStored_andTheNextVisitTriesAgain() {
        phone(fingerprint = false)
        val before = f.pinRecord()
        f.keys.answering = false
        f.type(PIN)
        assertEquals(MpReason.Pin(PinReason.CANNOT_CHECK), f.field.reason)
        assertEquals(before, f.pinRecord()) // refunded: no count, no `off`
        assertEquals(emptyList<String>(), f.keys.deleted)

        // In the flows of the open app it changes nothing either.
        val proof = f.unlockWithMasterPassword()
        assertEquals(PinChangeOutcome.CannotCheck, f.changePin(PIN, NEW_PIN))
        assertFalse(f.setPin(NEW_PIN, proof))
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof))
        assertEquals(before.slot, f.pinRecord().slot)
        assertEquals(emptyList<String>(), f.keys.deleted)

        f.keys.answering = true
        f.lock()
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f15c_theFingerprint_isHiddenForThisVisit_andNothingElseChanges() {
        phone()
        val slot = f.fingerprintRecord().slot
        f.keys.scriptProbe(Ks.Temporary(null))
        await { f.engine.promptIfDue() }
        assertEquals(FingerprintLine.CannotCheck, f.pad.status)
        assertFalse(f.pad.fingerprintKey)
        assertEquals(slot, f.fingerprintRecord().slot)
        f.leaveAndReturn()
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
    }

    // ---- d. it keeps failing ---------------------------------------------------------------------------------

    /** A PIN key whose aliases in [stuck] never answer (TEMPORARY), as on a phone that reports a dead key as a passing error [V-C6]. */
    private class StuckPinKey(private val inner: PinKey, val stuck: MutableSet<String>) : PinKey {
        override fun create(alias: String): KeyCreate = inner.create(alias)

        override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> = if (alias in stuck) Ks.Temporary(null) else inner.hmac(alias, message)
    }

    /** A fingerprint key whose aliases in [stuck] never answer at the probe. */
    private class StuckFingerprintKey(private val inner: FingerprintKey, val stuck: MutableSet<String>) : FingerprintKey by inner {
        override fun probe(alias: String, nonce: ByteArray): Ks<Unit> = if (alias in stuck) Ks.Temporary(null) else inner.probe(alias, nonce)
    }

    @Test
    fun f15d_temporaryFailuresRemoveNothingHoweverOftenTheyCome_andTheShortcutsAreSetUpAgainWithNewKeys() {
        val stuck = HashSet<String>()
        f.pinKey = StuckPinKey(f.keys.pin, stuck)
        f.fingerprintKey = StuckFingerprintKey(f.keys.fingerprint, stuck)
        f.rebuild()
        vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState()
        stuck += listOf(f.pinAliasOnDisk()!!, f.fingerprintAliasOnDisk()!!)
        f.startInFront()
        val pin = f.pinRecord()
        val finger = f.fingerprintRecord()

        repeat(20) { visit ->
            await { f.engine.promptIfDue() }
            assertEquals("visit $visit", FingerprintLine.CannotCheck, f.pad.status)
            f.type(PIN)
            assertTrue("visit $visit", f.state is LockState.Locked)
            assertEquals("visit $visit", MpReason.Pin(PinReason.CANNOT_CHECK), f.field.reason)
            f.leaveAndReturn()
        }
        // No count, no `off`, no deleted key, however often (§14.1: TEMPORARY changes nothing).
        assertEquals(pin, f.pinRecord())
        assertEquals(finger, f.fingerprintRecord())
        assertEquals(emptyList<String>(), f.keys.deleted)

        // The route the TEMPORARY texts name: "Change PIN" › "Use master password instead" makes a new
        // PIN key, and fingerprint unlock off and on makes a new fingerprint key.
        val proof = f.unlockWithMasterPassword()
        assertTrue(f.setPin(NEW_PIN, proof))
        f.fingerprintOff()
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
        assertNotEquals(pin.slot, f.pinRecord().slot)
        assertNotEquals(finger.slot, f.fingerprintRecord().slot)
        f.lock()
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        f.lock()
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        // The old PIN key, which a new slot replaced, goes with the next sweep; the old fingerprint key
        // went at once, when its slot was removed (§5.7).
        f.restart()
        f.startInFront()
        assertFalse(stuck.any { it in f.keys.aliases })
    }
}
