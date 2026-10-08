package io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts

import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of **F6 "Change"** (lock.md §12), through the engine: "Enter your current PIN", a
 * counted PIN check in a prompt of the open app (§6.3) that must yield the open vault's key, then
 * the new PIN, which must differ (§7.3). What the prompt can end with (F6 step 2): right, wrong, a
 * limit, a PIN that cannot be checked, a prompt that ended. Each run ends in the state lock.md
 * describes, the counts of §6.4 among it: `row` to 0 and `total` unchanged for a right PIN in a
 * prompt, nothing forgiven there, every wrong PIN reported at the next unlock (§13.4).
 */
class ChangePinFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    /** A vault with a PIN (and a fingerprint when asked), opened with the PIN. */
    private fun openByPin(
        row: Int = 0,
        total: Int = 0,
        fingerprint: Boolean = false,
        fixture: LockFixture = f,
    ): FakeVaultPort.FakeVault {
        val vault = fixture.seedVault()
        fixture.plantPin(vault, row = row, total = total)
        if (fingerprint) fixture.plantFingerprint(vault)
        fixture.plantState()
        fixture.startInFront()
        fixture.type(PIN)
        assertTrue("opened by the PIN: ${fixture.state}", fixture.state is LockState.Unlocked)
        return vault
    }

    @Test
    fun f6_change_theCurrentPinThenTheNewOne() {
        val vault = openByPin(row = 0, total = 3)
        val old = f.pinAliasOnDisk()!!
        val hmacs = f.keys.hmacCalls.size
        val waits = f.clock.waits.size

        assertEquals(PinChangeOutcome.Changed, f.changePin(PIN, NEW_PIN))

        // Step 2: a counted check whose charge was a second old when the HMAC was asked for (I4).
        assertEquals(old, f.keys.hmacCalls[hmacs].alias)
        assertTrue(f.clock.waits.size > waits)
        // Steps 4 to 7: a new slot and key, one wrap for the open vault's key; the old key waits for the sweep.
        val record = f.pinRecord()
        val slot = record.slot!!
        assertNotEquals(old, PinWrap.alias(slot.id))
        assertEquals(listOf(vault.newestVkId), slot.wraps.map { it.vkId })
        assertTrue(f.pinOpens(vault, NEW_PIN))
        assertFalse(f.pinOpens(vault, PIN))
        assertTrue(old in f.keys.aliases)
        assertEquals(emptyList<String>(), f.keys.deleted)
        // §6.4: a new PIN set without the master password: row 0, total unchanged; nothing charged.
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 3), record.guard)
        assertNull(record.off)
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)
        assertTrue(f.state is LockState.Unlocked) // the app stays open

        f.lock()
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        f.restart()
        f.startInFront()
        assertEquals(listOf(old), f.keys.deleted) // the sweep, at the next start (§5.7)
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f6_step2_aWrongCurrentPin_isCounted_neverForgiven_andReportedAtTheNextUnlock() {
        openByPin(row = 0, total = 1)
        assertEquals(PinChangeOutcome.Wrong(left = 4), f.changePin(WRONG_PIN, NEW_PIN))
        assertEquals(PinRecord.Guard(false, 1, 2), f.pinRecord().guard)
        assertEquals(1, f.pinRecord().unseen.count)
        // The same wrong PIN again is not counted (§6.6); another one is.
        assertEquals(PinChangeOutcome.SameWrongAgain, f.changePin(WRONG_PIN, NEW_PIN))
        assertEquals(PinRecord.Guard(false, 1, 2), f.pinRecord().guard)
        assertEquals(PinChangeOutcome.Wrong(left = 3), f.changePin("582915", NEW_PIN))
        assertEquals(PinRecord.Guard(false, 2, 3), f.pinRecord().guard)

        // The right PIN on the lock screen forgives nothing typed in a prompt.
        f.lock()
        f.type(PIN)
        assertEquals(PinRecord.Guard(false, 0, 3), f.pinRecord().guard)
        val notice = f.notices.filterIsInstance<Notice.WrongTries>().single()
        assertEquals(2, notice.wrongPins)
        assertTrue(f.pinOpens(f.port.current!!, PIN)) // the old PIN is still the PIN: nothing changed
    }

    @Test
    fun f6_step2_aLimitInThePrompt_turnsThePinOff_theAppStaysOpen_thenTheMasterPassword() {
        val vault = f.seedVault()
        f.plantPin(vault, row = 4, total = 4)
        f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        await { f.engine.promptIfDue() } // a fingerprint unlock resets no count (§6.4)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(PinRecord.Guard(false, 4, 4), f.pinRecord().guard)
        val old = f.pinAliasOnDisk()!!

        assertEquals(PinChangeOutcome.Limit, f.changePin(WRONG_PIN, NEW_PIN))
        assertTrue("the app stays open (§6.5 step 3)", f.state is LockState.Unlocked)
        val record = f.pinRecord()
        assertNull(record.slot)
        assertEquals(PinRecord.OffReason.LIMIT, record.off?.reason)
        assertFalse(old in f.keys.aliases)
        assertEquals(LockSummary.Pin.Off(PinReason.LIMIT), f.engine.summary.value.pin)
        assertEquals("the fingerprint is untouched (§6.5 step 4)", 1, f.keys.aliases.size)

        // "Type master password": the "Set" branch.
        val proof = f.proofInTheOpenApp()
        assertTrue(f.setPin(NEW_PIN, proof))
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        assertTrue(f.pinOpens(vault, NEW_PIN))
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)
    }

    @Test
    fun f6_step2_aPinThatCannotBeChecked_F15c_isRefunded_andNothingElseChanges() {
        val vault = openByPin(row = 1, total = 2)
        val before = f.pinRecord()
        f.keys.scriptHmac(Ks.Temporary(null))
        assertEquals(PinChangeOutcome.CannotCheck, f.changePin(PIN, NEW_PIN))
        assertEquals(PinRecord.Guard(false, 0, 2), before.guard) // the unlock reset the row
        assertEquals(before, f.pinRecord()) // refunded: no count changed, and no new key
        assertEquals(1, f.keys.aliases.size)
        assertEquals(emptyList<String>(), f.keys.deleted)

        // The charge cannot be written: nothing counted, nothing checked.
        val hmacs = f.keys.hmacCalls.size
        f.files.failNextWrite()
        assertEquals(PinChangeOutcome.CannotCheck, f.changePin(PIN, NEW_PIN))
        assertEquals(hmacs, f.keys.hmacCalls.size)
        assertEquals(before, f.pinRecord())

        // The Keystore answers again: the change works.
        assertEquals(PinChangeOutcome.Changed, f.changePin(PIN, NEW_PIN))
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }

    @Test
    fun f6_step2_aPinKeyThatIsGone_F15b_removesTheSlot_andTheMasterPasswordSetsANewOne() {
        val vault = openByPin()
        val old = f.pinAliasOnDisk()!!
        f.keys.device.delete(old) // the key is gone; the canary answers
        assertEquals(PinChangeOutcome.CannotCheck, f.changePin(PIN, NEW_PIN))
        val record = f.pinRecord()
        assertNull(record.slot)
        assertEquals(PinRecord.OffReason.KEY_GONE, record.off?.reason)
        assertEquals(PinRecord.Guard(false, 0, 0), record.guard) // refunded
        assertEquals(LockSummary.Pin.Off(PinReason.STOPPED_WORKING), f.engine.summary.value.pin)
        assertTrue(f.state is LockState.Unlocked)

        assertTrue(f.setPin(NEW_PIN, f.proofInTheOpenApp()))
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }

    @Test
    fun f6_step3_theNewPinMustDiffer_andIsComparedBeforeAnythingIsCharged() {
        openByPin()
        val before = f.pinRecord()
        val hmacs = f.keys.hmacCalls.size
        val writes = f.files.log.size
        assertEquals(PinChangeOutcome.SameAsCurrent, f.changePin(PIN, PIN))
        assertEquals(hmacs, f.keys.hmacCalls.size)
        assertEquals(writes, f.files.log.size) // no charge was written
        assertEquals(before, f.pinRecord())
    }

    @Test
    fun f6_step3_aNewPinTheBlocklistRefuses_isNotSaved_beforeAnythingIsCharged() {
        openByPin()
        val before = f.pinRecord()
        val hmacs = f.keys.hmacCalls.size
        val writes = f.files.log.size
        for (pin in listOf(BLOCKED_PIN, "111111", "071990", "102030")) {
            assertEquals(pin, PinChangeOutcome.NotSaved, f.changePin(PIN, pin))
        }
        assertEquals(hmacs, f.keys.hmacCalls.size)
        assertEquals(writes, f.files.log.size)
        assertEquals(before, f.pinRecord())
        assertEquals(1, f.keys.aliases.size)
    }

    @Test
    fun pinsThatAreNotSixDigits_areTheCallersMistake_andNothingIsCharged() {
        openByPin()
        val writes = f.files.log.size
        assertThrows(IllegalArgumentException::class.java) { await { f.engine.changePin(digits("58291"), digits(NEW_PIN)) } }
        assertThrows(IllegalArgumentException::class.java) { await { f.engine.changePin(digits(PIN), digits("90512x")) } }
        assertEquals(writes, f.files.log.size)
        assertFalse(f.pinRecord().guard.charged)
    }

    @Test
    fun aRightPinThatDoesNotYieldTheOpenVaultsKey_provesNothing() {
        // The PIN was not kept by a change of the master password (F8 step 3, "Not now"): its slot
        // opens only the key from before. A new PIN under the current key would add a way in (§8.2).
        val vault = f.seedVault()
        val vk1 = vault.newestVkId
        val vk2 = vault.rekey(bytes(LockFixture.PASSWORD))
        f.plantPin(vault, vkIds = listOf(vk1))
        f.plantFingerprint(vault, vkIds = listOf(vk2))
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        f.startInFront()
        assertTrue(f.screen is LockScreen.FingerprintOnly)
        await { f.engine.promptIfDue() }
        assertEquals(vk2, f.port.openVkId)
        assertEquals(listOf(vk1), f.pinRecord().slot!!.wraps.map { it.vkId }) // a generation under vk1 is still kept
        val aliases = f.keys.aliases

        assertEquals(PinChangeOutcome.CannotCheck, f.changePin(PIN, NEW_PIN))
        assertEquals(listOf(vk1), f.pinRecord().slot!!.wraps.map { it.vkId })
        assertEquals(aliases, f.keys.aliases)
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard) // it was the right PIN, and counted so
    }

    @Test
    fun aKeyThatCannotBeMade_NOTNOW_theOldPinStillWorks() {
        val vault = openByPin()
        val old = f.pinRecord().slot
        f.keys.scriptCreate(KeyCreate.NotNow)
        assertEquals(PinChangeOutcome.NotSaved, f.changePin(PIN, NEW_PIN))
        assertEquals(old, f.pinRecord().slot)
        assertEquals(1, f.keys.deleted.size) // the half-made alias
        assertTrue(f.pinOpens(vault, PIN))
    }

    @Test
    fun aRecordWriteThatFails_theOldPinStillWorks_andTheNewKeyIsLeftToTheSweep() {
        val clock = FakeClock(elapsedMs = 3_600_000L)
        val g = LockFixture(clock)
        val oldSlot = arrayOfNulls<String>(1)
        g.recordFiles = FailingWrites(g.files) { _, name, bytes ->
            // Only the write of a pin.rec with another slot than the one planted: the new PIN's (step 7).
            name == RecordCodec.Pin.fileName && oldSlot[0] != null &&
                (RecordCodec.Pin.decode(bytes, VAULT) as? RecordRead.Ok)?.record?.slot?.let { it.id != oldSlot[0] } == true
        }
        g.rebuild()
        val vault = openByPin(fixture = g)
        oldSlot[0] = g.pinRecord().slot!!.id
        assertEquals(PinChangeOutcome.NotSaved, g.changePin(PIN, NEW_PIN))
        assertEquals(oldSlot[0], g.pinRecord().slot!!.id)
        assertEquals(2, g.keys.aliases.size)
        assertEquals(emptyList<String>(), g.keys.deleted)

        g.lock()
        g.type(PIN) // "Your old PIN still works."
        assertTrue(g.state is LockState.Unlocked)
        g.restart()
        g.startInFront()
        assertEquals(1, g.keys.deleted.size) // the new key, which no record names
        assertTrue(g.pinOpens(vault, PIN))
    }

    @Test
    fun i11_aLockWhileTheChargeAges_givesTheChangeUp_andChangesNothing() {
        val clock = FakeClock(elapsedMs = 3_600_000L)
        val g = LockFixture(clock)
        val vault = openByPin(fixture = g)
        val before = g.pinRecord().slot
        clock.autoAdvance = false
        val outcome = runBlocking {
            var result: PinChangeOutcome? = null
            val change = launch(start = CoroutineStart.UNDISPATCHED) { result = g.engine.changePin(digits(PIN), digits(NEW_PIN)) }
            val lock = launch(start = CoroutineStart.UNDISPATCHED) { g.engine.lock(LockCause.NO_TOUCH) } // ends the prompts at once
            clock.advance(1_000)
            change.join()
            lock.join()
            result
        }
        assertEquals(PinChangeOutcome.NotSaved, outcome)
        assertEquals(before, g.pinRecord().slot)
        assertEquals(1, g.keys.aliases.size)
        assertFalse(g.pinRecord().guard.charged)
        assertTrue(g.state is LockState.Locked)
        clock.autoAdvance = true
        g.type(PIN)
        assertTrue(g.state is LockState.Unlocked)
        assertTrue(g.pinOpens(vault, PIN))
    }

    /**
     * The check of the current PIN has ended right, and the app stops being visible while the second
     * step runs (at its [nth] use of the open vault's key: 1, whether the check yielded it; 2, the
     * wrap of the new PIN): the prompt ends at once, without the mutex, and nothing is written.
     */
    private fun onStopAtTheKey(nth: Int) {
        val g = LockFixture()
        val vault = openByPin(fixture = g)
        val before = g.pinRecord()
        val outcome = runBlocking {
            var uses = 0
            val stops = ArrayList<Job>()
            g.port.beforeCall = { call ->
                if (call == "withVaultKey" && ++uses == nth) stops += launch(start = CoroutineStart.UNDISPATCHED) { g.engine.appStopped() }
            }
            val result = g.engine.changePin(digits(PIN), digits(NEW_PIN))
            stops.forEach { it.join() }
            result
        }
        g.port.beforeCall = null
        assertEquals("ON_STOP at use $nth", PinChangeOutcome.NotSaved, outcome)
        assertEquals(before.slot, g.pinRecord().slot)
        assertEquals("no key is left that no record names", listOf(PinWrap.alias(before.slot!!.id)), g.keys.aliases)
        await { g.engine.appStarted() }
        g.lock()
        g.type(PIN) // the old PIN opens the app
        assertTrue(g.state is LockState.Unlocked)
        assertTrue(g.pinOpens(vault, PIN))
    }

    @Test
    fun i11_anOnStopBetweenTheCheckAndTheNewPin_changesNothing() {
        onStopAtTheKey(1)
        onStopAtTheKey(2)
    }

    @Test
    fun changePinWhileLocked_isNotChecked() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        f.startInFront()
        assertEquals(PinChangeOutcome.CannotCheck, f.changePin(PIN, NEW_PIN))
        assertEquals(0, f.keys.hmacCalls.size)
        assertFalse(f.pinRecord().guard.charged)
    }

    @Test
    fun withoutAPin_thereIsNothingToChange_andTheMasterPasswordSetsOne() {
        val vault = f.seedVault()
        f.plantState()
        f.startInFront()
        val proof = f.unlockWithMasterPassword()
        assertEquals(PinChangeOutcome.CannotCheck, f.changePin(PIN, NEW_PIN))
        assertEquals(RecordRead.Missing, f.pinOnDisk())
        assertTrue(f.setPin(NEW_PIN, proof))
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }

    @Test
    fun theCallersArraysAreNotChanged() {
        openByPin()
        val current = digits(PIN)
        val new = digits(NEW_PIN)
        assertEquals(PinChangeOutcome.Changed, await { f.engine.changePin(current, new) })
        assertArrayEquals(digits(PIN), current)
        assertArrayEquals(digits(NEW_PIN), new)
    }

    @Test
    fun aChangeAfterTheRowWasRaisedByWrongTriesInPrompts_resetsTheRowOnly() {
        openByPin(row = 0, total = 0)
        assertEquals(PinChangeOutcome.Wrong(4), f.changePin(WRONG_PIN, NEW_PIN))
        assertEquals(PinChangeOutcome.Wrong(3), f.changePin("582915", NEW_PIN))
        assertEquals(PinChangeOutcome.Changed, f.changePin(PIN, NEW_PIN))
        assertEquals(PinRecord.Guard(false, 0, 2), f.pinRecord().guard) // never forgiven in a prompt (§6.3)
        assertEquals(2, f.pinRecord().unseen.count) // reported at the next unlock
        assertEquals(5, PinGuard.left(f.pinRecord().guard)) // the row is back to 0: five tries before the PIN turns off
        f.lock()
        f.type(NEW_PIN)
        assertEquals(2, f.notices.filterIsInstance<Notice.WrongTries>().single().wrongPins)
    }

    @Test
    fun aChangeWhoseFirstWriteFailsAtTheDirectorySync_isOnDiskAfterARestart() {
        val vault = openByPin()
        val old = f.pinAliasOnDisk()!!
        // The charge and the right PIN's record write first; then the new PIN's write fails at its
        // directory sync, with its rename already on disk (§5.9).
        var writes = 0
        f.files.onBoundary = { _, at ->
            if (at.endsWith("pin.rec: after the directory sync")) {
                writes++
                if (writes == 2) f.files.failNextWrite(FakeRecordFiles.WriteStep.DIR_SYNC)
            }
        }
        // F6 step 8: the record read back is the new one, so nobody can promise that the old PIN still works.
        assertEquals(PinChangeOutcome.MayNotBeSaved, f.changePin(PIN, NEW_PIN))
        f.files.onBoundary = null
        val onDisk = f.pinAliasOnDisk()!!
        assertNotEquals(old, onDisk)
        assertTrue(onDisk in f.keys.aliases)
        f.restart()
        f.startInFront()
        assertEquals(listOf(old), f.keys.deleted)
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }
}
