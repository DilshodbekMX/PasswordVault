package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.DAY_MS
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.PIN
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.lock.LockRig.Companion.digits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calls of [LockManager] that the screens of a new master password use (lock.md F11; build plan
 * 5.5 builds the screens), through [DefaultLockManager] over the real engine with fake ports: "Proof
 * now" in the open app (F11 step 3, T16), the request that uses it up (steps 4 and 5), and the end
 * of a flow that made no request, which drops the proof (`leaveNewMasterPassword`). Added by the
 * merge of round R3 (build-plan.md §12), which gave the lock manager these calls.
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
