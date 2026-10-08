package io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintOnOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of **F9**, fingerprint unlock on and off (lock.md §12), through the engine: the
 * master password of the flow (§8.2), the canary and a new key (step 4), the prompt "Turn on
 * fingerprint unlock" under a launch number of its own (§9.2), the one wrap written (step 8), and
 * every error or cancel, after which the earlier state is kept and the new key is gone (step 9).
 * "Off" needs no proof and deletes the key at once (§5.7).
 */
class FingerprintOnOffFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    /** A vault with a PIN, opened with the master password: its proof. */
    private fun openWithMasterPassword(fingerprint: Boolean = false): Pair<FakeVaultPort.FakeVault, MasterProof> {
        val vault = f.seedVault()
        f.plantPin(vault)
        if (fingerprint) f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        return vault to f.unlockWithMasterPassword()
    }

    /** No fingerprint key that no record names is left in the Keystore. */
    private fun assertNoStrayFingerprintKey(vaultId: String = VAULT) {
        val named = setOfNotNull(f.fingerprintAliasOnDisk(vaultId))
        assertEquals(named, f.keys.aliases.filter { it.startsWith(RecordCodec.FINGERPRINT_ALIAS_PREFIX) }.toSet())
    }

    @Test
    fun f9_on_theFingerprintWrapsTheOpenVaultsKey_andOpensTheAppFromTheNextVisit() {
        val (vault, proof) = openWithMasterPassword()
        assertEquals(LockSummary.Fingerprint.Off, f.engine.summary.value.fingerprint)

        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))

        val record = f.fingerprintRecord()
        val slot = record.slot!!
        assertNull(record.off)
        assertEquals(f.clock.wallMs(), slot.createdMs)
        assertEquals(listOf(vault.newestVkId), slot.wraps.map { it.vkId })
        assertEquals(12, slot.wraps.single().nonce.size) // cipher.iv
        assertEquals(48, slot.wraps.single().ct.size)
        assertTrue(f.fingerprintOpens(vault))
        val alias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + slot.id
        // Step 6: the prompt "Turn on fingerprint unlock", under the launch number it was given.
        assertEquals(listOf(FakeKeys.PromptCall("seal", alias, PromptUse.TURN_ON, 1)), f.keys.prompts)
        assertEquals(0, f.engine.core.outstandingLaunch)
        assertEquals(LockSummary.Fingerprint.On, f.engine.summary.value.fingerprint)
        assertTrue(f.state is LockState.Unlocked)
        assertTrue("the proof of the flow is kept for its next step", f.engine.core.holds(proof))
        assertEquals(emptyList<String>(), f.port.writes)

        f.lock()
        assertTrue(f.pad.fingerprintKey)
        await { f.engine.promptIfDue() }
        assertEquals(LockState.Unlocked(emptyList(), null), f.state)
        assertEquals(PromptUse.UNLOCK, f.keys.prompts.last().use)
        assertEquals(2, f.keys.prompts.last().launch) // the next launch has the next number (§9.2 rule 8)
    }

    @Test
    fun f9_on_aSlotThatWasThereIsReplaced_andItsKeyIsLeftToTheSweep() {
        val (vault, proof) = openWithMasterPassword(fingerprint = true)
        val old = f.fingerprintAliasOnDisk()!!
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
        val new = f.fingerprintAliasOnDisk()!!
        assertNotEquals(old, new)
        assertTrue(old in f.keys.aliases)
        assertEquals(emptyList<String>(), f.keys.deleted)
        f.restart()
        f.startInFront()
        assertEquals(listOf(old), f.keys.deleted)
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.fingerprintOpens(vault))
    }

    @Test
    fun f9_step4_noFingerEnrolled_addAFingerprintFirst() {
        val (_, proof) = openWithMasterPassword()
        f.keys.enrolled = false
        assertEquals(FingerprintOnOutcome.NoneEnrolled, f.fingerprintOn(proof))
        assertEquals(RecordRead.Missing, f.fingerprintOnDisk())
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertNoStrayFingerprintKey()
        f.keys.enrolled = true
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
    }

    @Test
    fun f9_step4_aKeyThatCannotBeMadeNow_orThatWouldBeInSoftware() {
        val (_, proof) = openWithMasterPassword()
        f.keys.scriptCreate(KeyCreate.NotNow)
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof))
        f.keys.scriptCreate(KeyCreate.NoSecureHardware)
        assertEquals(FingerprintOnOutcome.NoSecureHardware, f.fingerprintOn(proof))
        assertEquals(RecordRead.Missing, f.fingerprintOnDisk())
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertNoStrayFingerprintKey()

        f.keys.answering = false // F15c: the Keystore does not answer
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof))
        f.keys.answering = true
        assertEquals(RecordRead.Missing, f.fingerprintOnDisk())
    }

    @Test
    fun f18_aPhoneWithoutSecureHardware_hasNoFingerprintUnlock() {
        f.keys.softwareOnly = true
        val vault = f.seedVault()
        f.plantState()
        f.startInFront()
        val proof = f.unlockWithMasterPassword()
        assertEquals(FingerprintOnOutcome.NoSecureHardware, f.fingerprintOn(proof))
        assertEquals(emptyList<String>(), f.keys.aliases)
        assertEquals(LockSummary.Fingerprint.NoSecureHardware, f.engine.summary.value.fingerprint)
        assertEquals(vault.vaultId, f.port.openVaultId)
    }

    @Test
    fun f9_step9_everyWayThePromptCanEnd_keepsTheEarlierState_andDeletesTheNewKey() {
        val (_, proof) = openWithMasterPassword()
        val cases = listOf(
            FakeKeys.Prompt.Close(10) to FingerprintOnOutcome.Cancelled, // Back or the X: stay on the screen
            FakeKeys.Prompt.Close(13) to FingerprintOnOutcome.Cancelled, // "Cancel"
            FakeKeys.Prompt.Close(5) to FingerprintOnOutcome.Cancelled,
            FakeKeys.Prompt.Error(7) to FingerprintOnOutcome.Failed, // locked out
            FakeKeys.Prompt.Error(1) to FingerprintOnOutcome.Failed, // not allowed for apps
            FakeKeys.Prompt.SucceededButFailed to FingerprintOnOutcome.Failed, // §9.3: success is not a wrap
            FakeKeys.Prompt.NotShown to FingerprintOnOutcome.NotShown, // §9.2 rule 3
        )
        for ((prompt, expected) in cases) {
            f.keys.scriptPrompt(prompt)
            assertEquals(prompt.toString(), expected, f.fingerprintOn(proof))
            assertEquals(prompt.toString(), RecordRead.Missing, f.fingerprintOnDisk())
            assertNoStrayFingerprintKey()
            assertEquals(0, f.engine.core.outstandingLaunch)
        }
        assertEquals(cases.size, f.keys.deleted.size)
        assertEquals((1..cases.size).toList(), f.keys.prompts.map { it.launch }) // every prompt had its own number
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
    }

    @Test
    fun aKeyThatDiesWhileThePromptIsOpen_isGone_andDeleted() {
        val (_, proof) = openWithMasterPassword()
        f.keys.holdPrompts = true
        val outcome = runBlocking {
            var result: FingerprintOnOutcome? = null
            val on = launch(start = CoroutineStart.UNDISPATCHED) { result = f.engine.fingerprintOn(proof) }
            while (f.keys.openPrompt == null) yield()
            f.keys.invalidateFingerprintKeys() // a finger was added meanwhile
            f.keys.answer(FakeKeys.Prompt.Touch)
            on.join()
            result
        }
        assertEquals(FingerprintOnOutcome.Failed, outcome)
        assertEquals(RecordRead.Missing, f.fingerprintOnDisk())
        assertNoStrayFingerprintKey()
    }

    @Test
    fun aLockWhileThePromptIsOpen_cancelsIt_andItsResultChangesNothing() {
        val (vault, proof) = openWithMasterPassword(fingerprint = true)
        val before = f.fingerprintRecord()
        f.keys.holdPrompts = true
        val outcome = runBlocking {
            var result: FingerprintOnOutcome? = null
            val on = launch(start = CoroutineStart.UNDISPATCHED) { result = f.engine.fingerprintOn(proof) }
            assertEquals(PromptUse.TURN_ON, f.keys.openPrompt!!.use)
            // The launch ends and the prompt is cancelled (§9.2 rule 8, §10.4 step 2).
            val lock = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock() }
            on.join()
            lock.join()
            result
        }
        assertEquals(FingerprintOnOutcome.Cancelled, outcome)
        assertEquals(1, f.keys.cancels)
        assertEquals(before, f.fingerprintRecord())
        assertNoStrayFingerprintKey()
        assertTrue(f.state is LockState.Locked)
        f.keys.holdPrompts = false
        await { f.engine.promptIfDue() } // the earlier fingerprint still opens the app
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.fingerprintOpens(vault))
    }

    @Test
    fun anOnStopWhileThePromptIsOpen_endsItsLaunch_andATouchThatComesLaterChangesNothing() {
        val (_, proof) = openWithMasterPassword()
        f.keys.holdPrompts = true
        val outcome = runBlocking {
            var result: FingerprintOnOutcome? = null
            val on = launch(start = CoroutineStart.UNDISPATCHED) { result = f.engine.fingerprintOn(proof) }
            // The app stops being visible: the launch ends at once. The fake's prompt is answered by the
            // cancel; a finger that a real prompt delivered late would be dropped the same way.
            val stop = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.appStopped() }
            on.join()
            stop.join()
            result
        }
        assertEquals(FingerprintOnOutcome.Cancelled, outcome)
        assertEquals(RecordRead.Missing, f.fingerprintOnDisk())
        assertNoStrayFingerprintKey()
        assertTrue("the app was not locked by it", f.state is LockState.Unlocked)
        assertFalse("ON_STOP dropped the proof (§5.8)", f.engine.core.holds(proof))
    }

    @Test
    fun aCallerCancelledWhileThePromptIsOpen_endsTheLaunch_andTheNewKeyGoes() {
        val (_, proof) = openWithMasterPassword()
        f.keys.holdPrompts = true
        runBlocking {
            val on = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.fingerprintOn(proof) }
            assertEquals(1, f.engine.core.outstandingLaunch)
            on.cancel()
            on.join()
        }
        assertEquals(0, f.engine.core.outstandingLaunch)
        assertEquals(1, f.keys.cancels)
        assertEquals(RecordRead.Missing, f.fingerprintOnDisk())
        assertNoStrayFingerprintKey()
    }

    @Test
    fun aSecondCallWhileThePromptIsOpen_showsNoSecondPrompt() {
        val (_, proof) = openWithMasterPassword()
        f.keys.holdPrompts = true
        runBlocking {
            var first: FingerprintOnOutcome? = null
            val on = launch(start = CoroutineStart.UNDISPATCHED) { first = f.engine.fingerprintOn(proof) }
            assertEquals(FingerprintOnOutcome.NotShown, f.engine.fingerprintOn(proof)) // §9.2 rule 4
            assertEquals(1, f.keys.prompts.size)
            f.keys.answer(FakeKeys.Prompt.Touch)
            on.join()
            assertEquals(FingerprintOnOutcome.On, first)
        }
        assertNoStrayFingerprintKey()
    }

    @Test
    fun f9_step2_needsTheMasterPasswordOfTheFlow_andAFingerprintOrPinUnlockIsNotEnough() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        f.startInFront()
        f.type(PIN)
        assertNull((f.state as LockState.Unlocked).proof)
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(MasterProof.forFakes(bytes(PASSWORD), VAULT)))
        val proof = f.proofInTheOpenApp()
        f.leaveAndReturn() // ON_STOP drops it
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof))
        f.lock()
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof)) // and the locked app turns nothing on
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertEquals(emptyList<String>(), f.keys.aliases.filter { it.startsWith(RecordCodec.FINGERPRINT_ALIAS_PREFIX) })
    }

    @Test
    fun f9_step8_aRecordWriteThatFails_keepsTheEarlierState_andLeavesTheNewKeyToTheSweep() {
        val (vault, proof) = openWithMasterPassword(fingerprint = true)
        val before = f.fingerprintRecord()
        f.files.failNextWrite(FakeRecordFiles.WriteStep.RENAME)
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof))
        assertEquals(before, f.fingerprintRecord())
        assertEquals(2, f.keys.aliases.count { it.startsWith(RecordCodec.FINGERPRINT_ALIAS_PREFIX) })
        assertEquals(emptyList<String>(), f.keys.deleted)
        f.lock()
        await { f.engine.promptIfDue() } // the earlier fingerprint still works
        assertTrue(f.state is LockState.Unlocked)
        f.restart()
        f.startInFront()
        assertEquals(1, f.keys.deleted.size) // the sweep: the new key, which no record names
        assertNoStrayFingerprintKey()
        assertTrue(f.fingerprintOpens(vault))
    }

    @Test
    fun f9_step8_aWriteThatFailsAtTheDirectorySync_hasReplacedTheRecord_andItsKeyIsStillThere() {
        val (vault, proof) = openWithMasterPassword(fingerprint = true)
        val old = f.fingerprintAliasOnDisk()!!
        f.files.failNextWrite(FakeRecordFiles.WriteStep.DIR_SYNC) // §5.9: "not written", although the rename is on disk
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof))
        val onDisk = f.fingerprintAliasOnDisk()!!
        assertNotEquals(old, onDisk)
        assertTrue("a record names the new key: it was not deleted", onDisk in f.keys.aliases)

        f.restart()
        f.startInFront()
        assertEquals(listOf(old), f.keys.deleted) // the key the new slot replaced, which no record names now
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.fingerprintOpens(vault))
    }

    @Test
    fun f9_aFingerprintRecordOfANewerApp_isNeverReplaced() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState()
        val newer = newerRecord(f.files.bytes(f.dirOf(VAULT), RecordCodec.Fingerprint.fileName)!!)
        f.files.put(f.dirOf(VAULT), RecordCodec.Fingerprint.fileName, newer)
        f.startInFront()
        val proof = f.unlockWithMasterPassword()
        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof))
        assertArrayEquals(newer, f.files.bytes(f.dirOf(VAULT), RecordCodec.Fingerprint.fileName))
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
    }

    // ---- Off -------------------------------------------------------------------------------------------

    @Test
    fun f9_off_needsNoProof_writesTheRecord_andDeletesTheKeyAtOnce() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        await { f.engine.promptIfDue() } // opened with the fingerprint: no proof at all
        val alias = f.fingerprintAliasOnDisk()!!
        val pin = f.pinRecord()

        f.fingerprintOff()

        assertEquals(FingerprintRecord(VAULT, slot = null, off = FingerprintRecord.Off(FingerprintRecord.OffReason.TURNED_OFF, f.clock.wallMs())), f.fingerprintRecord())
        assertEquals(listOf(alias), f.keys.deleted)
        assertFalse(alias in f.keys.aliases)
        assertEquals(pin, f.pinRecord()) // the PIN is untouched (I6)
        assertEquals(LockSummary.Fingerprint.Off, f.engine.summary.value.fingerprint)
        assertTrue(f.state is LockState.Unlocked)

        f.lock()
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
        val prompts = f.keys.prompts.size
        await { f.engine.promptIfDue() }
        assertEquals(prompts, f.keys.prompts.size)
        f.type(PIN)
        assertEquals("no 'turn on again' for a fingerprint the owner turned off", emptyList<Any>(), f.notices)
        assertTrue(f.pinOpens(vault, PIN))
    }

    @Test
    fun f9_off_aWriteThatFails_stillDeletesTheKey_soNoFingerOpensTheApp() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        f.type(PIN)
        val alias = f.fingerprintAliasOnDisk()!!
        f.files.failNextWrite()
        f.fingerprintOff()
        assertEquals(alias, f.fingerprintAliasOnDisk()) // the record on disk still names the slot …
        assertFalse(alias in f.keys.aliases) // … whose key is gone

        f.restart()
        f.startInFront()
        await { f.engine.promptIfDue() } // the key is GONE at the probe: the slot is removed, no prompt
        assertTrue(f.state is LockState.Locked)
        assertNull(f.fingerprintRecord().slot)
        assertEquals(0, f.keys.prompts.size)
    }

    @Test
    fun f9_off_whileLocked_orWithoutASlot_doesNothing() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        f.fingerprintOff() // locked: nothing
        assertTrue(f.fingerprintRecord().slot != null)
        f.type(PIN)
        f.fingerprintOff()
        val off = f.fingerprintRecord()
        f.fingerprintOff() // again: nothing more
        assertEquals(off, f.fingerprintRecord())
        assertEquals(1, f.keys.deleted.size)
    }

    @Test
    fun offThenOn_F15d_makesANewKey() {
        val (vault, proof) = openWithMasterPassword(fingerprint = true)
        val first = f.fingerprintAliasOnDisk()!!
        f.fingerprintOff()
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
        val second = f.fingerprintAliasOnDisk()!!
        assertNotEquals(first, second)
        assertEquals(listOf(first), f.keys.deleted)
        assertTrue(f.fingerprintOpens(vault))
        assertNoStrayFingerprintKey()
    }
}
