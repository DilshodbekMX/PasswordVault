package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ProofOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.flow.RequestOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.fingerprintAliasOnDisk
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.fingerprintOpens
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.pinAliasOnDisk
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.pinOpens
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.proofInTheOpenApp
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.unlockWithMasterPassword
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of **F8**, change the master password (lock.md §12; build plan 4.4), through the
 * engine: the whole change with the PIN and the fingerprint kept; the order of decision 20 (the
 * mark, the PIN's wrap, a second on disk, the vault, step 8, the fingerprint); every branch of step
 * 3 (wrong, the same wrong PIN again, a limit, "Not now", a PIN that cannot be checked, a key that
 * is gone), of step 6 (nothing in place, quarantined, "Saving may not have finished", not enough
 * memory) and of step 7 ("Not now", closed, TEMPORARY, GONE, fingerprint not usable now); what ends
 * the flow before step 6 (a lock, `ON_STOP`, a proof that is not this engine's); and step 10 in a
 * later process. Each run ends in the state lock.md describes.
 */
class ChangeMasterPasswordFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private lateinit var vault: FakeVaultPort.FakeVault

    /** The key the vault is under before the change. */
    private lateinit var vkId1: String

    /** The key of the newest live generation: the new key, once a change has reached the disk. */
    private val newestVkId: String get() = vault.newestVkId

    /**
     * A vault with two generations, a PIN and fingerprint unlock (each when asked). The app is opened
     * with the PIN (the master password when there is none), and the current master password is typed
     * in the flow (F8 step 1): its proof.
     */
    private fun phone(pin: Boolean = true, fingerprint: Boolean = true): MasterProof {
        vault = f.seedVault(generations = 2)
        vkId1 = vault.newestVkId
        if (pin) f.plantPin(vault)
        if (fingerprint) f.plantFingerprint(vault)
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        f.startInFront()
        if (pin) f.type(PIN) else f.unlockWithMasterPassword()
        f.answerAll()
        return f.proofInTheOpenApp()
    }

    private val proofNow: MasterProof? get() = (f.state as LockState.Unlocked).proof

    /** The change as a call that suspends, for a test that does something while it waits. */
    private suspend fun change(proof: MasterProof, pin: String?): ChangeOutcome =
        f.engine.changeMasterPassword(proof, NEW_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray(), pin?.let(LockFixture::digits))

    private fun assertDropped(proof: MasterProof) {
        assertFalse(f.engine.core.holds(proof))
        assertThrows(IllegalStateException::class.java) { proof.useBytes { } }
    }

    // ---------------------------------------------------------------------------------------------
    // The whole change

    @Test
    fun f8_theWholeChange_keepsThePinAndTheFingerprint_andEndsAsStep8Says() {
        val proof = phone()
        val pinAlias = f.pinAliasOnDisk()
        val fingerprintAlias = f.fingerprintAliasOnDisk()!!

        val outcome = f.changeMasterPassword(proof, NEW_PASSWORD, pin = PIN)

        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = true), outcome)
        // Step 6: one more generation, numbered above every file, under a new key with the new password's slot.
        val vkId2 = newestVkId
        assertNotEquals(vkId1, vkId2)
        assertEquals(listOf(3L, 2L, 1L), vault.live.map { it.number })
        assertEquals(listOf(vkId2, vkId1, vkId1), vault.live.map { it.vkId })
        assertTrue(vault.password(vkId2).contentEquals(bytes(NEW_PASSWORD)))
        assertEquals("the lock asked storage for this one write (I1)", listOf("changePassword $vkId2"), f.port.writingCalls)
        assertEquals(listOf("vault-3.tmp staged", "vault-3.tmp is .pvault"), f.port.writes)
        assertEquals(vkId2, f.port.openVkId)
        assertEquals(3L, f.port.openGeneration)
        // Step 5: the PIN's slot holds the wrap for the key before and the wrap for the new key, under the same key.
        assertEquals(listOf(vkId1, vkId2), f.pinWraps())
        assertEquals(pinAlias, f.pinAliasOnDisk())
        assertTrue(f.pinOpens(vault, PIN, vkId1))
        assertTrue(f.pinOpens(vault, PIN, vkId2))
        // Step 7: the fingerprint's slot likewise, sealed in the prompt "Keep fingerprint unlock" on the existing key.
        assertEquals(listOf(vkId1, vkId2), f.fingerprintWraps())
        assertTrue(f.fingerprintOpens(vault, vkId1))
        assertTrue(f.fingerprintOpens(vault, vkId2))
        assertEquals(listOf(FakeKeys.PromptCall("seal", fingerprintAlias, PromptUse.KEEP_AFTER_CHANGE, 1)), f.keys.prompts)
        assertEquals("no key was made or deleted", emptyList<String>(), f.keys.deleted)
        assertEquals(setOf(pinAlias, fingerprintAlias), f.keys.aliases.toSet())
        // Step 8: practice step 0 and last_ok now, counters zero, no mark; pin.rec: guard zero.
        val state = f.stateRecord()
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 0, null), state.practice)
        assertEquals(StateRecord.MpGuard(0, null, 0), state.mpGuard)
        assertNull(state.mpChange)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
        // The summary, for Settings › App lock.
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)
        assertEquals(LockSummary.Fingerprint.On, f.engine.summary.value.fingerprint)
        // The proof of the old master password is gone; the state holds the proof of the new one (steps 3 and 9).
        assertDropped(proof)
        val newProof = proofNow!!
        assertTrue(f.engine.core.holds(newProof))
        assertEquals(VAULT, newProof.vaultId)
        assertTrue(newProof.useBytes { it.contentEquals(bytes(NEW_PASSWORD)) })
        assertEquals(emptyList<Notice>(), f.notices)
    }

    @Test
    fun f8_afterTheChange_theNewMasterPasswordThePinAndTheFingerprintOpenTheNewestCopy_andTheOldOneIsAnOlderMasterPassword() {
        val proof = phone()
        assertEquals(ChangeOutcome.Changed(true, true), f.changeMasterPassword(proof, pin = PIN))
        f.lock()

        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(3L, f.port.openGeneration)
        assertNull("a PIN unlock gives no proof", proofNow)
        f.lock()
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(3L, f.port.openGeneration)
        f.lock()

        await { f.engine.useMasterPassword() }
        // The change was proven (mp_change is null): the old master password has stopped working; only "OK".
        val changedAt = vault.generation(3).createdMs
        assertEquals(MasterPasswordOutcome.OlderPassword(changedAt, unfinishedChange = false), f.unlockWith(PASSWORD))
        assertEquals(LockScreen.OlderPassword(changedAt, unfinishedChange = false), f.screen)
        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE) // not offered on that screen: nothing
        assertEquals(LockScreen.OlderPassword(changedAt, unfinishedChange = false), f.screen)
        assertEquals(emptyList<String>(), f.port.writingCalls.filter { it.startsWith("putNewerAside") })
        f.answerOlder(OlderPasswordAnswer.OK)
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(3L, f.port.openGeneration)
        // Nothing is retired in the process that made the change (step 10: "at a later app start").
        assertEquals(3, vault.live.size)
        assertEquals(listOf(vkId1, newestVkId), f.pinWraps())
    }

    @Test
    fun f8_step10_inALaterProcess_theNewMasterPasswordTyped_retiresTheOldCopies_andTheWrapsForTheOldKeyGo() {
        val proof = phone()
        assertEquals(ChangeOutcome.Changed(true, true), f.changeMasterPassword(proof, pin = PIN))
        val vkId2 = newestVkId
        f.restart()
        f.startInFront()

        f.type(PIN) // a shortcut retires nothing
        assertEquals(3, vault.live.size)
        assertEquals(listOf(vkId1, vkId2), f.pinWraps())
        assertEquals(listOf(vkId1, vkId2), f.fingerprintWraps())
        f.lock()
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)

        assertEquals(listOf(3L), vault.live.map { it.number })
        assertEquals(listOf(vkId2), f.pinWraps())
        assertEquals(listOf(vkId2), f.fingerprintWraps())
        assertTrue(f.pinOpens(vault, PIN, vkId2))
        assertTrue(f.fingerprintOpens(vault, vkId2))
        assertEquals("the lock asked for no other write", listOf("changePassword $vkId2"), f.port.writingCalls)
    }

    // ---------------------------------------------------------------------------------------------
    // Decision 20: the order of steps 5 to 8

    /** Every record write (at its rename) and every storage boundary of `changePassword`, with the time. */
    private fun recordOrder(): MutableList<Pair<String, Long>> {
        val order = ArrayList<Pair<String, Long>>()
        f.files.onBoundary = { _, at ->
            if (at.endsWith(": after the rename")) {
                val what = when (at.substringAfterLast('/').substringBefore(':')) {
                    RecordCodec.State.fileName -> "state.rec: mp_change " + (if (f.stateRecord().mpChange != null) "set" else "null")
                    RecordCodec.Pin.fileName -> "pin.rec: " + f.pinWraps().size + " wrap(s)" + (if (f.pinRecord().guard.charged) ", charged" else "")
                    else -> "fingerprint.rec: " + f.fingerprintWraps().size + " wrap(s)"
                }
                order += what to f.clock.elapsedMs()
            }
        }
        f.port.onBoundary = { _, at -> if (at.startsWith("changePassword")) order += "storage: $at" to f.clock.elapsedMs() }
        return order
    }

    @Test
    fun f8_decision20_theMark_thenThePinsWrap_aSecondOnDisk_thenTheVault_thenStep8_thenTheFingerprint() {
        val proof = phone()
        val order = recordOrder()

        assertEquals(ChangeOutcome.Changed(true, true), f.changeMasterPassword(proof, pin = PIN))

        assertEquals(
            listOf(
                "pin.rec: 1 wrap(s), charged", // step 3: a counted check, its charge first (§6.3)
                "pin.rec: 1 wrap(s)", // the right PIN's record
                "state.rec: mp_change set", // step 5: the mark is on disk before the vault is written …
                "pin.rec: 2 wrap(s)", // … and so is the PIN's wrap for the new key
                "storage: changePassword: before",
                "storage: changePassword: staged",
                "storage: changePassword: proven",
                "storage: changePassword: published",
                "state.rec: mp_change null", // step 8, at once
                "fingerprint.rec: 2 wrap(s)", // step 7
            ),
            order.map { it.first },
        )
        val wrapOnDisk = order.first { it.first == "pin.rec: 2 wrap(s)" }.second
        val vaultWritten = order.first { it.first == "storage: changePassword: before" }.second
        assertTrue("the mark and the wrap are a second old before storage is asked", vaultWritten - wrapOnDisk >= 1_000)
        assertEquals(f.stateRecord().mpChange, null)
    }

    @Test
    fun f8_step8IsOnDiskBeforeTheFingerprintPromptOpens_andNoMutexIsHeldWhileItIsOpen() {
        val proof = phone()
        f.keys.holdPrompts = true
        val outcome = runBlocking {
            var result: ChangeOutcome? = null
            val change = launch(start = CoroutineStart.UNDISPATCHED) { result = change(proof, PIN) }
            while (f.keys.openPrompt == null) yield()
            assertEquals(PromptUse.KEEP_AFTER_CHANGE, f.keys.openPrompt!!.use)
            // The change is proven and written while the prompt waits for a finger, for as long as the user likes.
            assertNull(f.stateRecord().mpChange)
            assertEquals(0, f.stateRecord().practice.step)
            assertEquals(listOf(vkId1, newestVkId), f.pinWraps())
            assertEquals(listOf(vkId1), f.fingerprintWraps())
            assertNotNull(proofNow)
            f.engine.refresh() // it would wait for ever if the change held the mutex (architecture.md §5.2)
            assertEquals(LockSummary.Fingerprint.TurnOnAgain, f.engine.summary.value.fingerprint)
            f.keys.answer(FakeKeys.Prompt.Touch)
            change.join()
            result
        }
        assertEquals(ChangeOutcome.Changed(true, true), outcome)
        assertEquals(listOf(vkId1, newestVkId), f.fingerprintWraps())
        assertEquals(LockSummary.Fingerprint.On, f.engine.summary.value.fingerprint)
    }

    // ---------------------------------------------------------------------------------------------
    // Step 3: the PIN

    @Test
    fun f8_step3_aWrongPinIsCounted_andNothingHasChanged_theSameOneAgainIsNotCountedAgain_thenTheRightOneKeepsIt() {
        val proof = phone()
        val checks = f.keys.hmacCalls.size

        assertEquals(ChangeOutcome.PinWrong(left = 4), f.changeMasterPassword(proof, pin = WRONG_PIN))

        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), f.pinRecord().guard)
        assertEquals(1, f.pinRecord().unseen.count)
        assertEquals(checks + 1, f.keys.hmacCalls.size)
        assertFalse("no key was asked for", f.port.calls.contains("newKey"))
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertNull(f.stateRecord().mpChange)
        assertEquals(listOf(vkId1), f.pinWraps())
        assertTrue("the proof still stands: the flow goes on", f.engine.core.holds(proof))

        // §6.6: the same wrong PIN again is refunded, not checked and not counted; the prompt says the same.
        assertEquals(ChangeOutcome.PinWrong(left = 4), f.changeMasterPassword(proof, pin = WRONG_PIN))
        assertEquals(checks + 1, f.keys.hmacCalls.size)
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), f.pinRecord().guard)

        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = true), f.changeMasterPassword(proof, pin = PIN))
        assertEquals("pin.rec: guard zero (step 8)", PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        // A wrong PIN typed in a prompt is reported at the next unlock (§13.4 item 4): nothing forgave it.
        assertEquals(1, f.pinRecord().unseen.count)
        f.lock()
        f.type(PIN)
        assertEquals(1, f.notices.filterIsInstance<Notice.WrongTries>().single().wrongPins)
    }

    @Test
    fun f8_step3_aLimitTurnsThePinOff_andTheChangeGoesOnWithoutIt() {
        val proof = phone(fingerprint = false)
        val alias = f.pinAliasOnDisk()!!
        for ((i, wrong) in listOf("000001", "000002", "000003", "000004").withIndex()) {
            assertEquals(ChangeOutcome.PinWrong(left = 4 - i), f.changeMasterPassword(proof, pin = wrong))
        }

        val outcome = f.changeMasterPassword(proof, pin = "000005") // the fifth wrong PIN in a row (§6.5)

        assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), outcome)
        val pin = f.pinRecord()
        assertNull(pin.slot)
        assertEquals(PinRecord.OffReason.LIMIT, pin.off?.reason)
        assertEquals(listOf(alias), f.keys.deleted)
        assertEquals(LockSummary.Pin.Off(PinReason.LIMIT), f.engine.summary.value.pin)
        assertTrue(vault.password(newestVkId).contentEquals(bytes(NEW_PASSWORD)))
        assertNull(f.stateRecord().mpChange)
        assertTrue("the app stays open (§6.5 step 3)", f.state is LockState.Unlocked)
    }

    @Test
    fun f8_step3_notNow_thePinIsNotKept_thenTheNewMasterPasswordOnce_andChooseAPin() {
        val proof = phone(fingerprint = false)

        val outcome = f.changeMasterPassword(proof, pin = null) // "Not now"

        assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), outcome)
        assertEquals("only the unlock's check; none in the change", 1, f.keys.hmacCalls.size)
        assertEquals("the slot keeps its wrap for the key before, and gains none", listOf(vkId1), f.pinWraps())
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin) // "Set a PIN"
        assertNotNull("the proof for \"Choose a PIN\" after step 6", proofNow)

        // The crash table, "After step 6, PIN step skipped": the new master password once, then "Choose a PIN".
        f.lock()
        assertEquals(MpReason.MasterChanged, f.field.reason)
        assertFalse(f.field.canUsePin)
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.MASTER_CHANGED, offer.reason)
        assertFalse(offer.needsMasterPassword)
    }

    @Test
    fun f8_step3_aPinThatCannotBeCheckedRightNow_goesOnAsAfterNotNow() {
        // TEMPORARY from the Keystore (F15c).
        val proof = phone(fingerprint = false)
        f.keys.scriptHmac(Ks.Temporary(null))
        assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))
        assertEquals(listOf(vkId1), f.pinWraps())
        assertEquals("the try was refunded", PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        assertNotNull(f.pinRecord().slot)
        assertTrue(vault.password(newestVkId).contentEquals(bytes(NEW_PASSWORD)))
    }

    @Test
    fun f8_step3_whenTheChargeCannotBeWritten_thePinIsNotChecked_andTheChangeGoesOnWithoutIt() {
        val proof = phone(fingerprint = false)
        val checks = f.keys.hmacCalls.size
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1) { it.endsWith("/pin.rec") } // §6.3 C.3: storage full at the charge
        assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))
        assertEquals("no PIN is checked without a charge on disk (I4)", checks, f.keys.hmacCalls.size)
        assertEquals(listOf(vkId1), f.pinWraps())
    }

    @Test
    fun f8_step3_aPinKeyThatIsGone_removesTheSlot_andTheChangeGoesOn() {
        val proof = phone(fingerprint = false)
        val alias = f.pinAliasOnDisk()!!
        f.keys.scriptHmac(Ks.Gone("not found"))
        assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))
        assertNull(f.pinRecord().slot)
        assertEquals(PinRecord.OffReason.KEY_GONE, f.pinRecord().off?.reason)
        assertEquals(listOf(alias), f.keys.deleted)
    }

    @Test
    fun f8_step3_withoutAPinForTheCurrentKey_nothingIsChecked() {
        val proof = phone(pin = false, fingerprint = false)
        assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))
        assertEquals(0, f.keys.hmacCalls.size)
        assertEquals(RecordRead.Missing, f.pinOnDisk())
    }

    @Test
    fun f8_step5_aPinRecordThatCannotBeWritten_isNotAKeptPin_inThisProcessAsAfterTheNextStart() {
        val proof = phone(fingerprint = false)
        var failed = false
        // The write of step 5 that adds the wrap for the new key fails; the charge and the right PIN's record are written.
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1) { path ->
            val second = path.endsWith("/pin.rec") && f.port.calls.contains("newKey") && !failed
            if (second) failed = true
            second
        }

        assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))

        assertTrue(failed)
        assertEquals(listOf(vkId1), f.pinWraps())
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin)
        f.files.clearFaults()
        f.restart()
        f.startInFront()
        assertEquals(MpReason.MasterChanged, f.field.reason)
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
    }

    // ---------------------------------------------------------------------------------------------
    // Step 7: the fingerprint

    @Test
    fun f8_step7_notNow_aClosedPrompt_orATemporaryError_leavesTheSlotWithItsOldWrap() {
        val prompts = listOf(
            FakeKeys.Prompt.Close(13), // "Not now"
            FakeKeys.Prompt.Close(10), // Back
            FakeKeys.Prompt.Error(7), // locked out for now
            FakeKeys.Prompt.SucceededButFailed,
            FakeKeys.Prompt.NotShown,
        )
        for (prompt in prompts) {
            val g = LockFixture()
            val v = g.seedVault()
            val old = v.newestVkId
            g.plantPin(v)
            val slot = g.plantFingerprint(v)
            g.plantState()
            g.startInFront()
            g.type(PIN)
            val proof = g.proofInTheOpenApp()
            g.keys.scriptPrompt(prompt)

            assertEquals("$prompt", ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), g.changeMasterPassword(proof, pin = PIN))

            assertEquals("$prompt", slot, g.fingerprintRecord().slot) // as it was: its one wrap, for the key before
            assertEquals("$prompt", listOf(old), g.fingerprintWraps())
            assertEquals("$prompt", emptyList<String>(), g.keys.deleted)
            assertEquals("$prompt: \"Fingerprint unlock: turn on again\"", LockSummary.Fingerprint.TurnOnAgain, g.engine.summary.value.fingerprint)
            assertEquals("$prompt: no launch is left outstanding", 0, g.engine.core.outstandingLaunch)
            // The fingerprint key is hidden until fingerprint is turned on again; the PIN opens the newest copy.
            g.lock()
            assertEquals("$prompt", LockScreen.Pad(0, null, fingerprintKey = false, opening = false, aside = null), g.screen)
            g.type(PIN)
            assertEquals("$prompt", 2L, g.port.openGeneration)
        }
    }

    @Test
    fun f8_step7_aKeyThatWasInvalidated_orIsMissing_removesTheSlot_asInF15() {
        for (missing in listOf(false, true)) {
            val g = LockFixture()
            val v = g.seedVault()
            g.plantPin(v)
            val slot = g.plantFingerprint(v)
            val alias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + slot.id
            g.plantState()
            g.startInFront()
            g.type(PIN)
            val proof = g.proofInTheOpenApp()
            if (missing) g.keys.device.delete(alias) else g.keys.invalidateFingerprintKeys()
            val deletedBefore = g.keys.deleted.size

            assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), g.changeMasterPassword(proof, pin = PIN))

            val record = g.fingerprintRecord()
            assertNull(record.slot)
            assertEquals(FingerprintRecord.OffReason.KEY_GONE, record.off?.reason)
            assertEquals(listOf(alias), g.keys.deleted.drop(deletedBefore))
            assertEquals(LockSummary.Fingerprint.Off, g.engine.summary.value.fingerprint)
            // F15a step 3: the next master-password unlock offers to turn it on again.
            g.lock()
            await { g.engine.useMasterPassword() }
            assertTrue(g.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
            assertEquals(1, g.notices.filterIsInstance<Notice.FingerprintAgain>().size)
        }
    }

    @Test
    fun f8_step7_whileThePhoneSaysFingerprintCannotBeUsed_theKeyIsNotTouched_andTheSlotIsLeftAlone() {
        val proof = phone()
        val slot = f.fingerprintRecord().slot
        f.keys.availability = 11 // no finger is saved on the phone now

        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))

        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertEquals(slot, f.fingerprintRecord().slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertEquals(LockSummary.Fingerprint.Unavailable(11), f.engine.summary.value.fingerprint)
    }

    @Test
    fun f8_step7_aFingerprintRecordThatCannotBeWritten_isNotAKeptFingerprint() {
        val proof = phone()
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1) { it.endsWith("/fingerprint.rec") }

        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))

        assertEquals(listOf(vkId1), f.fingerprintWraps())
        assertEquals(LockSummary.Fingerprint.TurnOnAgain, f.engine.summary.value.fingerprint)
        assertEquals(emptyList<String>(), f.keys.deleted)
    }

    // ---------------------------------------------------------------------------------------------
    // Step 6: storage

    @Test
    fun f8_step6_nothingInPlace_orQuarantined_nothingHasChanged_andEverythingStillWorks() {
        for (fault in listOf(FakeVaultPort.WriteFault.NOTHING_CHANGED, FakeVaultPort.WriteFault.QUARANTINED)) {
            val g = LockFixture()
            val v = g.seedVault(generations = 2)
            val old = v.newestVkId
            g.plantPin(v)
            g.plantFingerprint(v)
            g.plantState(maxGeneration = StateRecord.MaxGeneration(2, g.clock.wallMs()))
            g.startInFront()
            g.type(PIN)
            val proof = g.proofInTheOpenApp()
            val practice = g.stateRecord().practice
            g.port.failWrites(fault)

            assertEquals("$fault", ChangeOutcome.NothingChanged, g.changeMasterPassword(proof, pin = PIN))

            assertNull("$fault: mp_change = null", g.stateRecord().mpChange)
            assertEquals("$fault: practice is as it was", practice, g.stateRecord().practice)
            assertEquals("$fault", listOf(2L, 1L), v.live.map { it.number })
            assertEquals("$fault", old, g.port.openVkId)
            assertEquals("$fault: no prompt for a change that did not happen", emptyList<FakeKeys.PromptCall>(), g.keys.prompts)
            assertTrue("$fault: the proof of the current master password still stands", g.engine.core.holds(proof))
            assertEquals("$fault", LockSummary.Pin.On, g.engine.summary.value.pin)
            if (fault == FakeVaultPort.WriteFault.QUARANTINED) {
                // Storage moved the file that did not prove to a quarantine name, and never deletes it.
                assertEquals(listOf(FakeVaultPort.Suffix.LIVE, FakeVaultPort.Suffix.LIVE, FakeVaultPort.Suffix.BAD), v.generations.map { it.suffix })
            }
            // "Your old master password, PIN and fingerprint still work."
            g.lock()
            g.type(PIN)
            assertEquals("$fault", 2L, g.port.openGeneration)
            assertEquals("$fault: the unused wrap of the try is dropped at that unlock (§5.4)", listOf(old), g.pinWraps())
            g.lock()
            await { g.engine.promptIfDue() }
            assertTrue("$fault", g.state is LockState.Unlocked)
            g.lock()
            await { g.engine.useMasterPassword() }
            assertTrue("$fault", g.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
            assertEquals("$fault", MasterPasswordOutcome.Wrong, g.check(NEW_PASSWORD))
        }
    }

    @Test
    fun f8_step6_failed_andStartedAgain_step5DropsTheUnusedWrapOfTheFirstTry_andASlotNeverHoldsThree() {
        val proof = phone()
        var mostWraps = 0
        f.files.onBoundary = { _, at -> if (at.endsWith("pin.rec: after the rename")) mostWraps = maxOf(mostWraps, f.pinWraps().size) }
        f.port.failWrites(FakeVaultPort.WriteFault.NOTHING_CHANGED)
        assertEquals(ChangeOutcome.NothingChanged, f.changeMasterPassword(proof, pin = PIN))
        val unused = f.pinWraps().single { it != vkId1 }

        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = true), f.changeMasterPassword(proof, pin = PIN))

        val vkId2 = newestVkId
        assertNotEquals(unused, vkId2)
        assertEquals(listOf(vkId1, vkId2), f.pinWraps())
        assertEquals(listOf(vkId1, vkId2), f.fingerprintWraps())
        assertEquals(2, mostWraps)
        assertEquals(listOf(3L, 2L, 1L), vault.live.map { it.number })
        assertNull(f.stateRecord().mpChange)
    }

    @Test
    fun f8_step6_notEnoughMemory_nothingHasChanged() {
        val proof = phone()
        f.port.noMemory = true

        assertEquals(ChangeOutcome.NoMemory, f.changeMasterPassword(proof, pin = PIN))

        assertNull(f.stateRecord().mpChange)
        assertEquals(listOf(2L, 1L), vault.live.map { it.number })
        assertTrue(f.engine.core.holds(proof))
        f.port.noMemory = false
        assertEquals(ChangeOutcome.Changed(true, true), f.changeMasterPassword(proof, pin = PIN))
    }

    @Test
    fun f8_step6_savingMayNotHaveFinished_theMarkStays_noProofStands_andTheOldPasswordReachesTheCopyFromBefore() {
        val proof = phone()
        f.port.failWrites(FakeVaultPort.WriteFault.UNCLEAR)

        assertEquals(ChangeOutcome.Unclear, f.changeMasterPassword(proof, pin = PIN))

        val vkId2 = newestVkId
        assertEquals("mp_change stays set", StateRecord.MpChange(vkId2, f.stateRecord().mpChange!!.atMs), f.stateRecord().mpChange)
        assertDropped(proof)
        assertNull("which master password the vault has is not known: no proof", proofNow)
        assertTrue("the session goes on under the key the vault port holds", f.state is LockState.Unlocked)
        assertEquals(vkId2, f.port.openVkId)
        assertEquals(vkId2, f.engine.core.open!!.vkId)
        assertEquals("the open vault is the new copy, as storage lists it", 3L, f.engine.core.open!!.generation)
        assertEquals("no fingerprint prompt: the change is not proven", emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertEquals(listOf(vkId1, vkId2), f.pinWraps())
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)

        // "Next time, try your new master password first, then your old one." The old one: F16b, unfinished.
        f.restart()
        f.startInFront()
        await { f.engine.useMasterPassword() }
        val older = f.unlockWith(PASSWORD)
        assertEquals(MasterPasswordOutcome.OlderPassword(vault.generation(3).createdMs, unfinishedChange = true), older)
        f.answerOlder(OlderPasswordAnswer.TRY_AGAIN)
        // The new one opens the copy and clears the mark.
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(f.stateRecord().mpChange)
    }

    // ---------------------------------------------------------------------------------------------
    // What is refused, and what ends the flow before step 6

    @Test
    fun f8_theTwoTypingsMustBeTheSame_andHoldNoUnassignedCodePoint_beforeAnythingIsDerivedOrChecked() {
        val proof = phone()
        val calls = f.port.calls.size
        val checks = f.keys.hmacCalls.size

        assertEquals(ChangeOutcome.Mismatch, f.changeMasterPassword(proof, NEW_PASSWORD, pin = PIN, again = "$NEW_PASSWORD!"))
        assertEquals(ChangeOutcome.Unassigned(0xFFFF), f.changeMasterPassword(proof, "new \uFFFF words", pin = PIN))

        assertEquals(calls, f.port.calls.size)
        assertEquals(checks, f.keys.hmacCalls.size)
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        assertTrue(f.engine.core.holds(proof))
    }

    @Test
    fun f8_step1_onlyAProofThisEngineHoldsForTheOpenVault_startsTheChange() {
        val proof = phone()
        val forged = MasterProof.forFakes(bytes(PASSWORD), VAULT)
        assertEquals(ChangeOutcome.NothingChanged, f.changeMasterPassword(forged, pin = PIN))
        f.leaveAndReturn() // ON_STOP drops every proof (§5.8)
        assertEquals(ChangeOutcome.NothingChanged, f.changeMasterPassword(proof, pin = PIN))
        f.lock()
        assertEquals("in a locked app", ChangeOutcome.NothingChanged, f.changeMasterPassword(proof, pin = PIN))

        assertEquals("no PIN was checked for a flow that cannot start", 1, f.keys.hmacCalls.size)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertFalse(f.port.calls.contains("newKey"))
        assertNull(f.stateRecord().mpChange)
    }

    @Test
    fun f8_aLockWhileTheMarkSettles_discardsTheFlow_andNothingOfTheVaultWasChanged() {
        val proof = phone(fingerprint = false)
        f.clock.autoAdvance = false
        val outcome = runBlocking {
            var result: ChangeOutcome? = null
            val change = launch(start = CoroutineStart.UNDISPATCHED) { result = change(proof, null) }
            // Step 5 is written and the flow waits for it to settle.
            assertNotNull(f.stateRecord().mpChange)
            val lock = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock(LockCause.NO_TOUCH) }
            f.clock.advance(1_000)
            change.join()
            lock.join()
            result
        }
        assertEquals(ChangeOutcome.NothingChanged, outcome)
        assertNull("the mark is undone", f.stateRecord().mpChange)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(listOf(2L, 1L), vault.live.map { it.number })
        assertTrue(f.state is LockState.Locked)
        f.clock.autoAdvance = true
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f8_anOnStopWhileThePinIsChecked_discardsTheFlow_theRightPinIsCountedAsRight() {
        val proof = phone(fingerprint = false)
        f.clock.autoAdvance = false
        val outcome = runBlocking {
            var result: ChangeOutcome? = null
            val change = launch(start = CoroutineStart.UNDISPATCHED) { result = change(proof, PIN) }
            assertTrue("the charge of step 3 is on disk and ages", f.pinRecord().guard.charged)
            val stop = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.appStopped() }
            f.clock.advance(1_000)
            change.join()
            stop.join()
            result
        }
        assertEquals(ChangeOutcome.NothingChanged, outcome)
        assertFalse(f.port.calls.contains("newKey"))
        assertNull(f.stateRecord().mpChange)
        assertEquals(listOf(vkId1), f.pinWraps())
        assertFalse(f.pinRecord().guard.charged)
        assertTrue("ON_STOP does not lock", f.state is LockState.Unlocked)
    }

    @Test
    fun f8_fromStep6OnTheChangeRunsToItsEnd_aLockMeanwhileLocksTheVaultUnderItsNewKey_andNoProofIsHandedOut() {
        val proof = phone(fingerprint = false)
        var locking: Job? = null
        val outcome = runBlocking {
            f.port.beforeCall = { call ->
                // The lock comes while storage writes the new generation: it waits for the mutex.
                if (call == "changePassword") locking = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock(LockCause.NO_TOUCH) }
            }
            val result = change(proof, PIN)
            locking!!.join()
            result
        }
        f.port.beforeCall = null
        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), outcome)
        assertTrue(f.state is LockState.Locked)
        assertNull(f.port.openVaultId)
        assertNull(f.stateRecord().mpChange)
        assertEquals(0, f.stateRecord().practice.step)
        assertTrue(vault.password(newestVkId).contentEquals(bytes(NEW_PASSWORD)))
        assertDropped(proof)
        f.type(PIN)
        assertEquals(3L, f.port.openGeneration)
        assertNull(proofNow)
    }

    // ---------------------------------------------------------------------------------------------
    // More than one change

    @Test
    fun f8_changedTwiceInOneProcess_step5OfTheSecondChangeDropsTheWrapForTheFirstKey() {
        val proof = phone()
        assertEquals(ChangeOutcome.Changed(true, true), f.changeMasterPassword(proof, NEW_PASSWORD, pin = PIN))
        val vkId2 = newestVkId
        val second = f.proofInTheOpenApp(NEW_PASSWORD) // step 1 of the second change

        assertEquals(ChangeOutcome.Changed(true, true), f.changeMasterPassword(second, THIRD_PASSWORD, pin = PIN))

        val vkId3 = newestVkId
        assertEquals(listOf(vkId3, vkId2, vkId1, vkId1), vault.live.map { it.vkId })
        assertEquals(listOf(vkId2, vkId3), f.pinWraps())
        assertEquals(listOf(vkId2, vkId3), f.fingerprintWraps())
        assertTrue(f.pinOpens(vault, PIN, vkId3))
        assertTrue(f.fingerprintOpens(vault, vkId3))
        assertNull(f.stateRecord().mpChange)
        f.lock()
        f.type(PIN)
        assertEquals(4L, f.port.openGeneration)
        f.lock()
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(THIRD_PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f8_withoutAStateRecordThatCanHoldTheMark_theChangeDoesNotStart() {
        val proof = phone(fingerprint = false)
        // state.rec turns unreadable for the engine: a new process finds it so (§5.3 UNREADABLE).
        f.restart()
        f.files.fail(FakeRecordFiles.Op.READ, times = -1) { it.endsWith("/state.rec") }
        f.startInFront()
        f.type(PIN)
        val now = f.proofInTheOpenApp()
        assertNotEquals(proof, now)

        assertEquals(ChangeOutcome.NothingChanged, f.changeMasterPassword(now, pin = PIN))

        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertFalse(f.port.calls.contains("newKey"))
        assertEquals("no PIN is checked for a change that cannot be marked", 2, f.keys.hmacCalls.size)
        assertTrue(f.engine.core.holds(now))
    }

    @Test
    fun f8_whenTheMarkCannotBeWritten_nothingIsChanged() {
        val proof = phone(fingerprint = false)
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1) { it.endsWith("/state.rec") }

        assertEquals(ChangeOutcome.NothingChanged, f.changeMasterPassword(proof, pin = null))

        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertNull(f.stateRecord().mpChange)
        assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), f.changeMasterPassword(proof, pin = null))
    }

    @Test
    fun f8_step8_wrongMasterPasswordsCountedBefore_areZeroed_andAPendingNewMasterPasswordGoes() {
        // A proof from the unlock, then a request made with the PIN (F11), then wrong passwords in a prompt:
        // the change, made with that older proof, proves the new master password, which brings what every
        // correct master password brings (§6.8 step 5; F11 step 9).
        vault = f.seedVault()
        vkId1 = vault.newestVkId
        f.plantPin(vault)
        f.plantState()
        f.startInFront()
        val proof = f.unlockWithMasterPassword()
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        assertTrue(f.request("some other words") is RequestOutcome.Requested)
        assertNotNull(f.stateRecord().pendingMp)
        assertEquals(MasterPasswordOutcome.Wrong, f.check("not it"))
        assertEquals(MasterPasswordOutcome.Wrong, f.check("not it either"))
        assertEquals(2, f.stateRecord().mpGuard.failures)

        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))

        val state = f.stateRecord()
        assertEquals(0, state.mpGuard.failures)
        assertNull(state.pendingMp)
        assertNull(f.engine.summary.value.pending)
        assertEquals(1, f.notices.filterIsInstance<Notice.PendingCancelled>().size)
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 0, null), state.practice)
    }
}
