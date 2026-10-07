package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The prompts of the open app (lock.md §6.3 "in a prompt inside the open app", §6.8, §5.8): the
 * master password typed for a backup, an export, a change or a setting, the PIN typed for "Change
 * PIN" and the like, and the proofs a right master password gives.
 */
class PromptChecksTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    /** A vault with a PIN, open by the PIN (so the unlocked state holds no proof). */
    private fun openByPin(row: Int = 0, total: Int = 0, fixture: LockFixture = f): FakeVaultPort.FakeVault {
        val vault = fixture.seedVault()
        fixture.plantPin(vault, row = row, total = total)
        fixture.plantState()
        fixture.startInFront()
        fixture.type(PIN)
        check(fixture.state is LockState.Unlocked)
        return vault
    }

    private fun pinInPrompt(pin: String, fixture: LockFixture = f, useKek: ((ByteArray) -> Unit)? = null): PinOutcome {
        val typed = digits(pin)
        return await { fixture.engine.core.unlock.pinInPrompt(typed, useKek) }
    }

    // ---- the master password in the open app -------------------------------------------------------------

    @Test
    fun aRightMasterPasswordInTheOpenApp_givesAProof_returnsTheCounts_andCountsAsPractice() {
        val lastOk = f.clock.wallMs() - 21 * 3_600_000L
        val vault = f.seedVault()
        f.plantPin(vault, row = 2, total = 4)
        f.plantState(practice = StateRecord.Practice(lastOk, 1, null))
        f.startInFront()
        f.type(PIN)
        assertEquals(PinRecord.Guard(false, 0, 4), f.pinRecord().guard)

        val outcome = f.check(PASSWORD)
        val proof = (outcome as MasterPasswordOutcome.Right).proof
        assertEquals(VAULT, proof.vaultId)
        assertTrue(proof.useBytes { it.contentEquals(bytes(PASSWORD)) })
        assertTrue(f.engine.core.holds(proof))
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard) // a correct master password, anywhere in the app (§6.4)
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 2, null), f.stateRecord().practice) // 20 hours passed: the step goes up
        assertEquals(listOf("checkPassword"), f.port.calls.takeLast(1))
        assertTrue(f.port.writes.isEmpty())
        assertTrue(f.state is LockState.Unlocked) // the screen stays as it is
    }

    @Test
    fun wrongMasterPasswordsInTheOpenApp_areCounted_reported_andWaitLikeAnyOther() {
        openByPin()
        repeat(5) { assertEquals(MasterPasswordOutcome.Wrong, f.check("wrong $it")) }
        assertEquals(5, f.stateRecord().mpGuard.failures)
        assertEquals(5, f.stateRecord().mpGuard.unseen)
        val wait = f.check(PASSWORD) as MasterPasswordOutcome.Wait
        assertEquals(f.stateRecord().mpGuard.lastFailElapsedMs!! + 30_000, wait.untilElapsedMs)
        f.clock.advance(30_000)
        assertTrue(f.check(PASSWORD) is MasterPasswordOutcome.Right)
        // In a prompt nothing is forgiven: they are reported at the next unlock.
        f.lock()
        f.type(PIN)
        assertEquals(5, (f.notices.single() as Notice.WrongTries).wrongMasterPasswords)
    }

    @Test
    fun notEnoughMemoryInAPrompt_isNotCounted() {
        openByPin()
        f.port.noMemory = true
        assertEquals(MasterPasswordOutcome.NoMemory, f.check(PASSWORD))
        assertEquals(0, f.stateRecord().mpGuard.failures)
    }

    @Test
    fun aMasterPasswordCheckedWhileTheAppIsLocked_isNotChecked() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        f.startInFront()
        assertEquals(MasterPasswordOutcome.TooLate, f.check(PASSWORD))
        assertFalse("checkPassword" in f.port.calls)
    }

    @Test
    fun i11_aLockWhileTheCheckRuns_theResultProvesNothing() {
        openByPin()
        val outcome = runBlocking {
            f.port.beforeCall = { call -> if (call == "checkPassword") launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock() } }
            f.engine.checkMasterPassword(PASSWORD.toCharArray())
        }
        assertEquals(MasterPasswordOutcome.TooLate, outcome)
        assertTrue(f.state is LockState.Locked)
        assertEquals(0, f.stateRecord().mpGuard.failures) // it was right, and counted as right
    }

    // ---- proofs (§5.8) ------------------------------------------------------------------------------------

    @Test
    fun aProofIsDroppedOnLock_andWhenTheAppStopsBeingVisible() {
        f.seedVault()
        f.plantState()
        f.startInFront()
        val atUnlock = (f.unlockWith(PASSWORD) as MasterPasswordOutcome.Right).proof
        val inApp = (f.check(PASSWORD) as MasterPasswordOutcome.Right).proof
        assertTrue(f.engine.core.holds(atUnlock) && f.engine.core.holds(inApp))

        await { f.engine.appStopped() }
        assertDropped(atUnlock)
        assertDropped(inApp)
        assertFalse(f.engine.core.holds(atUnlock))
        assertEquals(LockState.Unlocked((f.state as LockState.Unlocked).notices, null), f.state) // the app is still open, without its proof

        await { f.engine.appStarted() }
        val again = (f.check(PASSWORD) as MasterPasswordOutcome.Right).proof
        f.lock()
        assertDropped(again)
    }

    @Test
    fun aProofTheEngineDidNotMake_isNotOneOfItsOwn() {
        openByPin()
        assertFalse(f.engine.core.holds(MasterProof.forFakes(bytes(PASSWORD), VAULT)))
    }

    private fun assertDropped(proof: MasterProof) {
        try {
            proof.useBytes { it.size }
            fail("the proof should have been dropped")
        } catch (e: IllegalStateException) {
            // dropped
        }
    }

    // ---- the PIN in a prompt (§6.3) ------------------------------------------------------------------------

    @Test
    fun aRightPinInAPrompt_yieldsTheOpenVaultsKey_afterACharge_thatIsASecondOld() {
        val vault = openByPin()
        val hmacsBefore = f.keys.hmacCalls.size
        val startedAt = f.clock.elapsedMs()
        var kek: ByteArray? = null
        val outcome = pinInPrompt(PIN) { kek = it }

        val keys = (outcome as PinOutcome.Right).keys
        assertTrue(keys.getValue(vault.newestVkId).contentEquals(vault.vk(vault.newestVkId)))
        assertEquals(32, kek!!.size) // KEK_pin for F8 step 5
        assertEquals(hmacsBefore + 1, f.keys.hmacCalls.size)
        assertTrue(f.keys.hmacCalls.last().atMs >= startedAt + 1_000) // I4 holds in a prompt too
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard) // written: charged = false
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun aWrongPinInAPrompt_isCounted_neverForgiven_andReportedAtTheNextUnlock() {
        openByPin()
        assertEquals(PinOutcome.Wrong(4), pinInPrompt(WRONG_PIN))
        assertEquals(PinRecord.Guard(false, 1, 1), f.pinRecord().guard)
        assertEquals(1, f.pinRecord().unseen.count)
        assertEquals(PinOutcome.SameAsBefore, pinInPrompt(WRONG_PIN)) // the same one again costs nothing (§6.6)
        assertEquals(PinRecord.Guard(false, 1, 1), f.pinRecord().guard)

        assertTrue(pinInPrompt(PIN) is PinOutcome.Right) // a right PIN in a prompt forgives nothing
        assertEquals(PinRecord.Guard(false, 0, 1), f.pinRecord().guard)
        assertEquals(1, f.pinRecord().unseen.count)

        f.lock()
        f.type(PIN) // nor does the next right PIN on the lock screen
        assertEquals(PinRecord.Guard(false, 0, 1), f.pinRecord().guard)
        assertEquals(1, (f.notices.single() as Notice.WrongTries).wrongPins)
    }

    @Test
    fun f5_aLimitReachedInAPrompt_turnsThePinOff_andTheAppStaysOpen() {
        val vault = f.seedVault()
        val slot = f.plantPin(vault, row = 4, total = 4)
        f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        await { f.engine.promptIfDue() } // open by the fingerprint, which resets no count
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(PinRecord.Guard(false, 4, 4), f.pinRecord().guard)

        assertEquals(PinOutcome.Limit, pinInPrompt(WRONG_PIN))
        assertNull(f.pinRecord().slot)
        assertEquals(PinRecord.OffReason.LIMIT, f.pinRecord().off!!.reason)
        assertTrue(PinWrap.alias(slot.id) in f.keys.deleted)
        assertTrue(f.state is LockState.Unlocked) // the prompt closes with its message, and the app stays open (§6.5 step 3)
        assertEquals(LockSummary.Pin.Off(PinReason.LIMIT), f.engine.summary.value.pin)
        assertEquals(LockSummary.Fingerprint.On, f.engine.summary.value.fingerprint) // nothing else changes
        assertEquals(PinOutcome.NotCharged, pinInPrompt(PIN)) // no PIN can be typed now
        assertTrue(f.port.writes.isEmpty())
    }

    @Test
    fun aPinThatCannotBeCheckedInAPrompt_isRefunded() {
        openByPin(row = 1, total = 1)
        f.keys.scriptHmac(Ks.Temporary(null))
        assertEquals(PinOutcome.CannotCheck, pinInPrompt(PIN))
        assertEquals(PinRecord.Guard(false, 0, 1), f.pinRecord().guard) // as the unlock left it: nothing was counted

        val hmacs = f.keys.hmacCalls.size
        f.files.failNextWrite() // the charge cannot be written: nothing is counted and nothing is checked
        assertEquals(PinOutcome.CannotCheck, pinInPrompt(PIN))
        assertEquals(hmacs, f.keys.hmacCalls.size)
        assertTrue(pinInPrompt(PIN) is PinOutcome.Right)
    }

    @Test
    fun aLockWhileThePromptWaits_givesTheEntryUp() {
        val clock = FakeClock(elapsedMs = 3_600_000L)
        val g = LockFixture(clock)
        openByPin(fixture = g)
        clock.autoAdvance = false
        val hmacs = g.keys.hmacCalls.size
        val outcome = runBlocking {
            var result: PinOutcome? = null
            val prompt = launch(start = CoroutineStart.UNDISPATCHED) { result = g.engine.core.unlock.pinInPrompt(digits(PIN)) }
            val lock = launch(start = CoroutineStart.UNDISPATCHED) { g.engine.lock() } // ends the prompt at once; waits for the mutex
            clock.advance(1_000)
            prompt.join()
            lock.join()
            result
        }
        // The check ran for a right PIN whose prompt had ended: counted as right, nothing proven (I11).
        assertEquals(PinOutcome.TooLate, outcome)
        assertEquals(hmacs + 1, g.keys.hmacCalls.size)
        assertFalse(g.pinRecord().guard.charged)
        assertTrue(g.state is LockState.Locked)
    }

    @Test
    fun aPinInAPromptWhileTheAppIsLocked_isNotCharged() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        f.startInFront()
        assertEquals(PinOutcome.NotCharged, pinInPrompt(PIN))
        assertFalse(f.pinRecord().guard.charged)
        assertEquals(0, f.keys.hmacCalls.size)
    }
}
