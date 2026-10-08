package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintOnOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinSetOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.NEW_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.changePin
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.fingerprintAliasOnDisk
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.fingerprintOn
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.fingerprintOpens
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.pinAliasOnDisk
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.pinOpens
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.setPinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.unlockWithMasterPassword
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
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
 * The owner's decision of 2026-10-08 on lock.md §18 item 19 (build plan §12, decision 4): **after
 * a new PIN or fingerprint slot's record write fails at the directory sync, the lock reads the
 * record back and goes on with what is on disk**, and the outcome lets the screen promise nothing
 * it cannot know (`PinSetOutcome.MayNotBeSaved`, `PinChangeOutcome.MayNotBeSaved`,
 * `FingerprintOnOutcome.MayNotBeOn`).
 *
 * Before it the lock kept the old record in memory and answered "not saved": in that process the
 * old PIN went on working, and its next write of `pin.rec` put the old record back; if the process
 * ended first, the next start read the new record, and the PIN the screen had called "still working"
 * counted as wrong.
 */
class RecordWriteFailureTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private val pinFile = "pin.rec"

    /** A vault with a PIN (and a fingerprint when asked), started and in front. */
    private fun withPin(fingerprint: Boolean = false, total: Int = 0): FakeVaultPort.FakeVault {
        val vault = f.seedVault()
        f.plantPin(vault, total = total)
        if (fingerprint) f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        return vault
    }

    private fun failTheNextPinWriteAt(step: FakeRecordFiles.WriteStep) =
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1, step = step) { it.endsWith("/$pinFile") }

    // ---- F6 "Set" ------------------------------------------------------------------------------------

    @Test
    fun f6_aNewPinWhoseWriteFailsAtTheDirectorySync_isTheOneThatWorksFromNowOn() {
        val vault = withPin()
        val proof = f.unlockWithMasterPassword()
        val old = f.pinAliasOnDisk()!!
        failTheNextPinWriteAt(FakeRecordFiles.WriteStep.DIR_SYNC) // §5.9: "not written", although the rename is on disk

        assertEquals(PinSetOutcome.MayNotBeSaved, f.setPinOutcome(NEW_PIN, proof))

        // The record read back is the new one: the lock goes on with it.
        val new = f.pinAliasOnDisk()!!
        assertNotEquals(old, new)
        assertEquals(new, RecordCodec.PIN_ALIAS_PREFIX + f.engine.core.lockOf(VAULT).pin.record!!.slot!!.id)
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)
        // Neither key is deleted: after a power cut either record can be the one on disk.
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertTrue(old in f.keys.aliases && new in f.keys.aliases)

        // In this same process: "try the new PIN first". It opens the app; the old one is a wrong PIN now.
        f.lock()
        f.type(PIN)
        assertEquals(PadStatus.WrongPin(4), f.pad.status)
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }

    @Test
    fun f6_afterSuchAWrite_theNextStartFindsTheNewRecord_andSweepsTheOldKey() {
        val vault = withPin()
        val proof = f.unlockWithMasterPassword()
        val old = f.pinAliasOnDisk()!!
        failTheNextPinWriteAt(FakeRecordFiles.WriteStep.DIR_SYNC)
        assertEquals(PinSetOutcome.MayNotBeSaved, f.setPinOutcome(NEW_PIN, proof))

        f.restart() // the process ends; what was handed over stays
        f.startInFront()

        assertEquals(listOf(old), f.keys.deleted)
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }

    @Test
    fun f6_afterSuchAWrite_aPowerCutBringsTheOldRecordBack_andItsKeyIsStillThere() {
        val vault = withPin()
        val proof = f.unlockWithMasterPassword()
        val old = f.pinAliasOnDisk()!!
        f.clock.advance(5_000) // everything before is on the flash
        failTheNextPinWriteAt(FakeRecordFiles.WriteStep.DIR_SYNC)
        assertEquals(PinSetOutcome.MayNotBeSaved, f.setPinOutcome(NEW_PIN, proof))
        val new = f.pinAliasOnDisk()!!

        f.files.powerCut(1_000) // the rename was younger than a second: it is lost
        f.restart()
        f.startInFront()

        assertEquals("the old record is back, and names a key that was never deleted", old, f.pinAliasOnDisk())
        assertEquals(listOf(new), f.keys.deleted) // the sweep: the new key, which no record names
        f.type(PIN) // "If it is not accepted, use the old PIN"
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.pinOpens(vault, PIN))
    }

    @Test
    fun f6_theNextWriteThatSucceeds_makesTheNewRecordDurable() {
        withPin()
        val proof = f.unlockWithMasterPassword()
        failTheNextPinWriteAt(FakeRecordFiles.WriteStep.DIR_SYNC)
        assertEquals(PinSetOutcome.MayNotBeSaved, f.setPinOutcome(NEW_PIN, proof))
        val new = f.pinAliasOnDisk()!!

        f.lock()
        f.type(NEW_PIN) // its charge and its record are whole writes of the record the lock works from
        f.clock.advance(1_000)
        f.files.powerCut(1_000)
        f.restart()
        f.startInFront()

        assertEquals(new, f.pinAliasOnDisk())
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f6_aWriteThatFailsBeforeItsRename_leavesTheOldRecord_andSaysThatNothingWasSaved() {
        for (step in listOf(FakeRecordFiles.WriteStep.TEMP_WRITE, FakeRecordFiles.WriteStep.FILE_SYNC, FakeRecordFiles.WriteStep.RENAME)) {
            val g = LockFixture()
            val vault = g.seedVault()
            g.plantPin(vault)
            g.plantState()
            g.startInFront()
            val proof = g.unlockWithMasterPassword()
            val before = g.pinRecord()
            g.files.fail(FakeRecordFiles.Op.REPLACE, times = 1, step = step) { it.endsWith("/$pinFile") }

            assertEquals("$step", PinSetOutcome.NotSet, g.setPinOutcome(NEW_PIN, proof))

            assertEquals("$step", before, g.pinRecord())
            assertEquals("$step", before, g.engine.core.lockOf(VAULT).pin.record)
            assertEquals("$step: the new key is left to the sweep", emptyList<String>(), g.keys.deleted)
            g.lock()
            g.type(PIN) // "Your old PIN still works."
            assertTrue("$step", g.state is LockState.Unlocked)
        }
    }

    @Test
    fun f6_aRecordThatCannotBeReadBack_promisesNothing_andTheLockKeepsTheRecordItHad() {
        withPin()
        val proof = f.unlockWithMasterPassword()
        val before = f.pinRecord()
        failTheNextPinWriteAt(FakeRecordFiles.WriteStep.DIR_SYNC)
        f.files.fail(FakeRecordFiles.Op.READ, times = 1) { it.endsWith("/$pinFile") } // the read-back fails too

        assertEquals(PinSetOutcome.MayNotBeSaved, f.setPinOutcome(NEW_PIN, proof))

        assertEquals(before, f.engine.core.lockOf(VAULT).pin.record)
        assertEquals(emptyList<String>(), f.keys.deleted)
        f.lock()
        f.type(PIN) // this process works from the old record, and its next write puts it back
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(before.slot, f.pinRecord().slot)
    }

    @Test
    fun f6_theFirstPinOfAVault_whoseWriteFailsAtTheDirectorySync_worksInThisProcess() {
        f.startInFront()
        assertTrue(await { f.engine.createVault(PASSWORD.toCharArray(), PASSWORD.toCharArray()) } is CreateOutcome.Created)
        val proof = (f.state as LockState.Unlocked).proof!!
        val vault = f.port.current!!
        assertEquals(RecordRead.Missing, f.pinOnDisk(vault.vaultId))
        failTheNextPinWriteAt(FakeRecordFiles.WriteStep.DIR_SYNC)

        assertEquals(PinSetOutcome.MayNotBeSaved, f.setPinOutcome(NEW_PIN, proof))

        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)
        f.lock()
        assertTrue(f.screen is LockScreen.Pad)
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    // ---- F6 "Change" ---------------------------------------------------------------------------------

    @Test
    fun f6_change_aNewPinWhoseWriteFailsAtTheDirectorySync_isTheOneThatWorksFromNowOn() {
        val vault = withPin(total = 3)
        f.type(PIN)
        val old = f.pinAliasOnDisk()!!
        // The charge and the right PIN's record are written first; the new PIN's write is the third.
        var writes = 0
        f.files.onBoundary = { _, at ->
            if (at.endsWith("$pinFile: after the directory sync") && ++writes == 2) failTheNextPinWriteAt(FakeRecordFiles.WriteStep.DIR_SYNC)
        }

        assertEquals(PinChangeOutcome.MayNotBeSaved, f.changePin(PIN, NEW_PIN))
        f.files.onBoundary = null

        val record = f.engine.core.lockOf(VAULT).pin.record!!
        assertNotEquals(old, RecordCodec.PIN_ALIAS_PREFIX + record.slot!!.id)
        assertEquals("total is unchanged: no master password was typed (§6.4)", PinRecord.Guard(false, 0, 3), record.guard)
        assertEquals(emptyList<String>(), f.keys.deleted)
        f.lock()
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }

    // ---- F9 "On" -------------------------------------------------------------------------------------

    @Test
    fun f9_aNewFingerprintSlotWhoseWriteFailsAtTheDirectorySync_isTheOneThatWorksFromNowOn() {
        val vault = withPin(fingerprint = true)
        val proof = f.unlockWithMasterPassword()
        val old = f.fingerprintAliasOnDisk()!!
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1, step = FakeRecordFiles.WriteStep.DIR_SYNC) { it.endsWith("/fingerprint.rec") }

        assertEquals(FingerprintOnOutcome.MayNotBeOn, f.fingerprintOn(proof))

        val new = f.fingerprintAliasOnDisk()!!
        assertNotEquals(old, new)
        assertEquals(new, RecordCodec.FINGERPRINT_ALIAS_PREFIX + f.engine.core.lockOf(VAULT).fingerprint.slot!!.id)
        assertEquals(LockSummary.Fingerprint.On, f.engine.summary.value.fingerprint)
        assertEquals(emptyList<String>(), f.keys.deleted)

        // In this same process the prompt is asked of the new key, and its wrap opens the vault.
        f.lock()
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(new, f.keys.prompts.last().alias)
        assertTrue(f.fingerprintOpens(vault))
    }

    @Test
    fun f9_afterSuchAWrite_aPowerCutBringsTheEarlierRecordBack_andItsKeyIsStillThere() {
        val vault = withPin(fingerprint = true)
        val proof = f.unlockWithMasterPassword()
        val old = f.fingerprintAliasOnDisk()!!
        f.clock.advance(5_000)
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1, step = FakeRecordFiles.WriteStep.DIR_SYNC) { it.endsWith("/fingerprint.rec") }
        assertEquals(FingerprintOnOutcome.MayNotBeOn, f.fingerprintOn(proof))
        val new = f.fingerprintAliasOnDisk()!!

        f.files.powerCut(1_000)
        f.restart()
        f.startInFront()

        assertEquals(old, f.fingerprintAliasOnDisk())
        assertEquals(listOf(new), f.keys.deleted)
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.fingerprintOpens(vault))
    }

    @Test
    fun f9_aWriteThatFailsBeforeItsRename_keepsTheEarlierSlot_andSaysThatItFailed() {
        withPin(fingerprint = true)
        val proof = f.unlockWithMasterPassword()
        val before = f.fingerprintRecord()
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1, step = FakeRecordFiles.WriteStep.RENAME) { it.endsWith("/fingerprint.rec") }

        assertEquals(FingerprintOnOutcome.Failed, f.fingerprintOn(proof))

        assertEquals(before, f.fingerprintRecord())
        assertEquals(before.slot, f.engine.core.lockOf(VAULT).fingerprint.slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
    }

    @Test
    fun f9_aRecordThatCannotBeReadBack_promisesNothing_andTheLockKeepsTheSlotItHad() {
        withPin(fingerprint = true)
        val proof = f.unlockWithMasterPassword()
        val before = f.fingerprintRecord()
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1, step = FakeRecordFiles.WriteStep.DIR_SYNC) { it.endsWith("/fingerprint.rec") }
        f.files.fail(FakeRecordFiles.Op.READ, times = 1) { it.endsWith("/fingerprint.rec") }

        assertEquals(FingerprintOnOutcome.MayNotBeOn, f.fingerprintOn(proof))

        assertEquals(before.slot, f.engine.core.lockOf(VAULT).fingerprint.slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertFalse(f.fingerprintAliasOnDisk() == RecordCodec.FINGERPRINT_ALIAS_PREFIX + before.slot!!.id)
        assertNull(f.fingerprintRecord().off)
    }
}
