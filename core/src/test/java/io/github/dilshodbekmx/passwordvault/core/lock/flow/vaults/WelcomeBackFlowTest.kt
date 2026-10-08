package io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of lock.md **F14**, a vault without lock records, "Welcome back" (build plan 3.14):
 * the master-password screen with that title; wrong tries counted and waited for in memory, nothing
 * written before the unlock (§5.3); after a correct master password the directory and `state.rec`,
 * then F1 step 5 with the proof just made; no vault data written (I1).
 */
class WelcomeBackFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    @Test
    fun f14_welcomeBack_thenStateRecAndChooseAPin_withTheProofJustMade() {
        f.seedVault(generations = 2)
        f.startInFront()
        val field = f.field
        assertEquals(MpReason.WelcomeBack, field.reason) // step 1
        assertFalse(field.canUsePin)
        assertNull(field.practice)
        assertFalse(f.files.hasDir(f.dirOf(VAULT)))

        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it"))
        assertFalse(f.files.hasDir(f.dirOf(VAULT))) // counted in memory only (§5.3: a new record after the next successful unlock)
        val right = f.unlockWith(PASSWORD) as MasterPasswordOutcome.Right

        // Step 2: the directory and state.rec (§5.3: step 0, last_ok now), then F1 step 5 with this proof.
        assertEquals(listOf("state.rec"), f.files.names(f.dirOf(VAULT)))
        val record = f.stateRecord()
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 0, null), record.practice)
        assertEquals(StateRecord.MpGuard(0, null, 0), record.mpGuard) // its own slip on this visit was forgiven
        assertEquals(StateRecord.MaxGeneration(2, f.clock.wallMs()), record.maxGeneration)
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.NONE_HERE, offer.reason)
        assertFalse(offer.needsMasterPassword)
        assertEquals(VAULT, right.proof.vaultId)
        assertTrue(f.engine.core.holds(right.proof))
        assertTrue(f.keys.canary) // F1 step 5: this phone can hold a key
        assertEquals(emptyList<String>(), f.port.writes) // I1
        assertEquals(emptyList<String>(), f.port.writingCalls)

        // From now on the vault has its lock directory: no "Welcome back" any more.
        f.answerNotices()
        f.lock()
        assertNull(f.field.reason)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f14_wrongTriesWaitFromMemory_andTheNoticeReportsThoseNotForgiven() {
        f.seedVault()
        f.startInFront()
        repeat(5) { assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it $it")) }
        val wait = f.unlockWith(PASSWORD) as MasterPasswordOutcome.Wait // §6.8: the wait runs from memory
        assertEquals(f.clock.elapsedMs() + 30_000, wait.untilElapsedMs)
        assertFalse(f.files.hasDir(f.dirOf(VAULT)))

        f.clock.advance(30_000)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)

        // Two slips of this visit, within its last minute, were forgiven; three are reported (§13.4 item 4).
        assertEquals(3, f.notices.filterIsInstance<Notice.WrongTries>().single().wrongMasterPasswords)
        assertEquals(StateRecord.MpGuard(0, null, 3), f.stateRecord().mpGuard)
    }

    @Test
    fun f14_aVaultMadeCurrentFromAVaultPutAside_thatHasNoLockDirectory_isWelcomedTheSameWay() {
        // A vault N made current with no lock directory (F11 crash table, after storage's last step).
        f.port.seed(OTHER_VAULT, bytes(Secrets.OTHER))
        f.startInFront()
        assertEquals(MpReason.WelcomeBack, f.field.reason)
        assertTrue(f.unlockWith(Secrets.OTHER) is MasterPasswordOutcome.Right)
        assertTrue(f.files.hasDir(f.dirOf(OTHER_VAULT)))
        assertEquals(ChoosePinReason.NONE_HERE, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
    }

    @Test
    fun f14_withoutRecords_neitherShortcutIsOffered_andCanAuthenticateIsNotAsked() {
        f.seedVault()
        f.keys.canary = true
        f.startInFront()
        assertTrue(f.screen is LockScreen.MasterPassword)
        assertEquals(0, f.keys.availabilityCalls) // no fingerprint slot: canAuthenticate is not even asked
        assertTrue(f.state is LockState.Locked)
    }
}
