package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.DAY_MS
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.PIN
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.digits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calls of [LockManager] that the screens of a new master password use (lock.md F11; build plan
 * 5.5 builds the screens), through [DefaultLockManager] over the real engine with fake ports: "Proof
 * now" in the open app (F11 step 3, T16), the request that uses it up (steps 4 and 5), and the end
 * of a flow that made no request, which drops the proof (`leaveNewMasterPassword`), and "Finish now"
 * in the open app (`finishNewMasterPassword`, steps 9 and 12 to 19; decision 14). Added by the merge
 * of round R3 (build-plan.md §12), which gave the lock manager these calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMasterPasswordTest {

    private suspend fun request(rig: LockRig, password: String = NEW_PASSWORD): RequestResult {
        val result = rig.manager.requestNewMasterPassword(password.toCharArray(), password.toCharArray())
        rig.settle()
        return result
    }

    /**
     * "Proof now" with [pin] typed in the screen's prompt, or with none. A PIN is a counted check
     * (lock.md §6.3), which waits for its charge to age: the call runs on its own while the clock
     * moves on.
     */
    private fun prove(rig: LockRig, pin: String?): ProofResult {
        val proving = rig.test.backgroundScope.asyncOn { rig.manager.proveForNewMasterPassword(pin?.let(::digits)) }
        rig.settle()
        if (pin != null) rig.advance(LockRig.CHARGE_AGE_MS)
        return proving.getCompleted()
    }

    // ---- "Proof now" in the open app (lock.md F11 step 3) ----------------------------------------------

    @Test
    fun inTheOpenApp_thePinTypedNowIsTheProof_andTheRequestUsesItUp() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)

        assertEquals("an app that is already open is not enough: the PIN is asked for", ProofResult.NeedsPin, prove(rig, null))
        assertEquals("without the proof of step 3 nothing is asked for (T16)", RequestResult.NotAllowed, request(rig))
        assertNull(rig.status.pending)

        assertEquals("a wrong PIN is counted as always", ProofResult.PinWrong(4), prove(rig, WRONG_PIN))
        assertEquals(ProofResult.Proven(Shortcut.PIN), prove(rig, PIN))
        assertEquals("asked again without a PIN, the proof that stands is the answer", ProofResult.Proven(Shortcut.PIN), prove(rig, null))

        assertEquals(RequestResult.Requested(DAY_MS), request(rig))
        val pending = assertNotNullAnd(rig.status.pending)
        assertEquals(Shortcut.PIN, pending.by)
        assertEquals("the request used the proof up", RequestResult.NotAllowed, request(rig, "yet another new password"))
        assertEquals(ProofResult.NeedsPin, prove(rig, null))
    }

    @Test
    fun withoutAPin_theFingerprintConfirms_andNeverInPlaceOfAPin() = runTest {
        val rig = LockRig(this)
        val proof = rig.setUp()
        rig.fingerprintOn(proof)
        rig.answerNotices(NoticeAnswer.LATER)

        val proving = backgroundScope.asyncOn { rig.manager.proveForNewMasterPassword(null) }
        rig.settle()
        assertEquals("\"Confirm with your fingerprint\" (lock.md §9.2 rule 5)", PromptUse.CONFIRM_REPLACEMENT, rig.prompt.use)
        rig.touch()
        assertEquals(ProofResult.Proven(Shortcut.FINGERPRINT), proving.getCompleted())
        assertEquals(RequestResult.Requested(DAY_MS), request(rig))
        assertEquals(Shortcut.FINGERPRINT, rig.status.pending?.by)

        // A PIN set now: the fingerprint is no longer accepted in its place.
        val again = rig.unlockedProofForAPin()
        assertEquals(PinSetResult.Set, rig.manager.setPin(digits(PIN), again))
        rig.settle()
        assertEquals(ProofResult.NeedsPin, prove(rig, null))
    }

    @Test
    fun afterICantRememberIt_thePinThatOpensTheAppIsTheProof() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember) // "Use my PIN" on the "Forgot master password?" sheet (lock.md F11 step 1)
        rig.typePin()
        assertTrue(rig.isUnlocked)
        rig.answerNotices(NoticeAnswer.LATER)

        assertEquals("on the lock screen the PIN was an ordinary entry, and the proof of step 3", ProofResult.Proven(Shortcut.PIN), prove(rig, null))
        assertEquals(RequestResult.Requested(DAY_MS), request(rig))
    }

    @Test
    fun neitherAPinNorAFingerprint_theRouteIsNotOffered() = runTest {
        val rig = LockRig(this)
        rig.setUp()
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals(ProofResult.NotOffered, prove(rig, null))
        assertEquals(RequestResult.NotAllowed, request(rig))
    }

    @Test
    fun aLockDropsTheProof() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals(ProofResult.Proven(Shortcut.PIN), prove(rig, PIN))
        rig.lock()
        rig.typePin()
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals("lock.md §5.8: a lock drops it", RequestResult.NotAllowed, request(rig))
    }

    // ---- the proof goes with its flow (lock.md F11 step 3, §5.8; build-plan.md §12 item 13) ------------

    @Test
    fun anHourAfterTheFlowWasLeft_inTheSameSession_noNewMasterPasswordCanBeAskedForWithoutThePinAgain() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals(ProofResult.Proven(Shortcut.PIN), prove(rig, PIN))

        rig.manager.leaveNewMasterPassword() // "Cancel" on the explanation, Back, or the screens closed
        rig.settle()
        rig.advance(HOUR_MS) // the app stays open: no lock, no ON_STOP

        assertEquals(RequestResult.NotAllowed, request(rig))
        assertNull("nothing is pending", rig.status.pending)
        assertEquals("the PIN is asked for again", ProofResult.NeedsPin, prove(rig, null))
        assertEquals(ProofResult.Proven(Shortcut.PIN), prove(rig, PIN))
        assertEquals("typed again, it is the proof of a new flow", RequestResult.Requested(DAY_MS), request(rig))
    }

    @Test
    fun theProofOfTheLockScreenRoute_goesWithItsFlowToo() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)
        rig.send(LockIntent.CantRemember)
        rig.typePin() // the PIN opens the app and is the proof of step 3
        rig.answerNotices(NoticeAnswer.LATER)

        rig.manager.leaveNewMasterPassword() // the screens of F11 closed without a request
        rig.settle()

        assertEquals(RequestResult.NotAllowed, request(rig))
        assertEquals(ProofResult.NeedsPin, prove(rig, null))
    }

    @Test
    fun leavingAfterTheRequestChangesNothing() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals(ProofResult.Proven(Shortcut.PIN), prove(rig, PIN))
        assertEquals(RequestResult.Requested(DAY_MS), request(rig))

        rig.manager.leaveNewMasterPassword()
        rig.settle()

        assertNotNull("the request stands", rig.status.pending)
        assertNotNull(rig.stateRecord().pendingMp)
    }

    // ---- "Finish now" in the open app (lock.md F11 steps 9 and 12 to 19; build-plan.md §12, decision 14) ----

    /**
     * A real request for [NEW_PASSWORD] (F11 steps 3 to 5) whose 24 hours are credited, in a new
     * process opened with the PIN, its notices answered: the banner says that it is ready.
     */
    private suspend fun readyToFinish(rig: LockRig) {
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals(ProofResult.Proven(Shortcut.PIN), prove(rig, PIN))
        assertEquals(RequestResult.Requested(DAY_MS), request(rig))
        val state = rig.stateRecord()
        val p = state.pendingMp!!
        rig.putState(state.copy(pendingMp = StateRecord.PendingMp(p.requestedMs, p.by, DAY_MS, p.seenElapsedMs, p.vkId, p.salt, p.nonce, p.ct)))
        rig.restart()
        rig.toFront()
        rig.typePin()
        assertTrue(rig.isUnlocked)
        rig.answerNotices(NoticeAnswer.LATER)
        assertTrue("\"Your new master password is ready\"", assertNotNullAnd(rig.status.pending).ready)
    }

    /**
     * "Finish now", the new master password typed once on its screen in the open app. A replacement
     * waits until its mark is a second old on disk before storage's step (F11 step 15,
     * `PasswordFlows.SETTLED_MS`): the call runs on its own while the clock moves on.
     */
    private fun finish(rig: LockRig, password: String): FinishResult {
        val finishing = rig.test.backgroundScope.asyncOn { rig.manager.finishNewMasterPassword(password.toCharArray()) }
        rig.settle()
        rig.advance(LockRig.CHARGE_AGE_MS)
        return finishing.getCompleted()
    }

    @Test
    fun finishNow_typedOnceInTheOpenApp_finishesTheReplacement_andNothingLocks() = runTest {
        val rig = LockRig(this)
        readyToFinish(rig)
        val oldVault = rig.vaultId
        rig.vault.matched.clear()

        assertEquals(FinishResult.Finished, finish(rig, NEW_PASSWORD))

        assertEquals("no lock was asked of the session", 0, rig.lockNows)
        assertTrue(rig.isUnlocked)
        assertNotEquals("the session goes on in the new vault (F11 steps 16 and 17)", oldVault, rig.vaultId)
        assertNull("nothing is pending", rig.status.pending)
        assertEquals("step 20: the replacement is told after each unlock", oldVault, rig.status.replaced?.oldVaultId)
        val proof = assertNotNullAnd(rig.unlocked.proof)
        assertEquals("step 19 goes on with the new master password's proof", NEW_PASSWORD, proof.useBytes { String(it, Charsets.UTF_8) })
        val choose = rig.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals("\"Choose a PIN\" follows at once, as after a restore", ChoosePinReason.NONE_HERE, choose.reason)
        assertTrue("without asking for the master password again", !choose.needsMasterPassword)
        assertTrue("D-9b had that proof", rig.vault.matched.any { it.proof === proof && it.bytesLent })
    }

    @Test
    fun finishNow_withTheCurrentMasterPassword_cancelsTheRequest_andItsProofGoesAtOnce() = runTest {
        val rig = LockRig(this)
        readyToFinish(rig)
        val vaultId = rig.vaultId
        rig.vault.matched.clear()

        assertEquals(FinishResult.RequestCancelled, finish(rig, LockRig.PASSWORD))

        assertEquals("nothing was replaced", vaultId, rig.vaultId)
        assertNull("F11 step 9: the request is gone", rig.status.pending)
        assertNull(rig.stateRecord().pendingMp)
        assertTrue("\"You typed your master password, so the new one was cancelled.\"", rig.notices.any { it is Notice.PendingCancelled })
        val matched = rig.vault.matched.single()
        assertTrue("D-9b had the proof while it lent its bytes", matched.bytesLent)
        assertThrows("then it went: it has no next step", IllegalStateException::class.java) { matched.proof.useBytes { } }
        assertEquals(0, rig.lockNows)
    }

    @Test
    fun finishNow_isTooLateWithNothingPending_triesNothingBeforeItsTime_andCountsAnotherPasswordAsWrong() = runTest {
        val rig = LockRig(this)
        rig.setUpWithPin()
        rig.answerNotices(NoticeAnswer.LATER)
        assertEquals("nothing is pending", FinishResult.TooLate, finish(rig, NEW_PASSWORD))
        assertEquals(ProofResult.Proven(Shortcut.PIN), prove(rig, PIN))
        assertEquals(RequestResult.Requested(DAY_MS), request(rig))
        val vaultId = rig.vaultId

        assertEquals("F11 step 12: not tried before its time", FinishResult.NotFinished, finish(rig, NEW_PASSWORD))
        assertEquals(vaultId, rig.vaultId)
        assertNotNull("the request stands", rig.status.pending)

        val other = LockRig(this, seed = 2L)
        readyToFinish(other)
        assertEquals(FinishResult.Wrong, finish(other, "neither the old one nor the new one"))
        assertNotNull("the request stands", other.status.pending)
        assertEquals("and the new one still finishes", FinishResult.Finished, finish(other, NEW_PASSWORD))
    }

    @Test
    fun aReadyNewMasterPasswordTypedOnTheLockScreen_stillFinishesThere() = runTest {
        // Decision 14 changes "Finish now" only: lock.md F11 step 13, "The lock screen's master-password field accepts it as well".
        val rig = LockRig(this)
        readyToFinish(rig)
        val oldVault = rig.vaultId
        rig.lock()
        rig.send(LockIntent.UseMasterPassword)

        val typed = backgroundScope.asyncOn { rig.manager.submitMasterPassword(NEW_PASSWORD.toCharArray(), MpPurpose.UNLOCK) }
        rig.settle()
        rig.advance(LockRig.CHARGE_AGE_MS) // the mark's second on disk, as above

        assertTrue(typed.getCompleted() is MpResult.Right)
        assertTrue(rig.isUnlocked)
        assertNotEquals(oldVault, rig.vaultId)
        assertNull(rig.status.pending)
    }

    /** The proof of the master password typed in a prompt of the open app, for a flow that needs one ("Set a PIN", F6). */
    private suspend fun LockRig.unlockedProofForAPin() = (submit(LockRig.PASSWORD, MpPurpose.SET_PIN) as MpResult.Right).proof

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        const val NEW_PASSWORD = "Samarqand tongi 2026 yangi kalit"
        const val HOUR_MS = 3_600_000L
    }
}
