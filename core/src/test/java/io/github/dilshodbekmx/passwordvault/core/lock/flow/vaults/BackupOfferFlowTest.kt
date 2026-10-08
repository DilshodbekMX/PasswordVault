package io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinSetOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of lock.md **F12**, backup and export (build plan 3.14): the master password for a
 * backup or an export is checked against the vault's own slot under its guard, counts as practice,
 * uses neither the PIN nor the fingerprint and changes no lock record but the practice dates and the
 * counters; "Make a backup now?" is offered on the entry just made, and only then (step 2).
 */
class BackupOfferFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    private fun backupNow(): List<Notice.BackupNow> = f.notices.filterIsInstance<Notice.BackupNow>()

    private fun vaultWithPin(practice: StateRecord.Practice = StateRecord.Practice(f.clock.wallMs(), 3, null)) {
        val vault = f.seedVault()
        f.plantPin(vault, row = 1, total = 2)
        f.plantFingerprint(vault)
        f.plantState(practice = practice)
    }

    @Test
    fun step2_afterAMasterPasswordUnlock_theOfferUsesThatEntry_onceAndLast() {
        vaultWithPin()
        f.startInFront()
        await { f.engine.useMasterPassword() }
        val proof = (f.unlockWith(PASSWORD) as MasterPasswordOutcome.Right).proof
        val before = f.notices
        assertEquals(listOf(ChoosePinReason.FORGOTTEN), before.filterIsInstance<Notice.ChoosePin>().map { it.reason })
        val records = listOf("pin.rec", "fingerprint.rec", "state.rec").associateWith { f.files.bytes(f.dirOf(VAULT), it)!!.toList() }

        await { f.engine.offerBackup() }

        val notices = f.notices
        assertTrue(notices.last() is Notice.BackupNow) // §13.4 item 7, after the others
        assertEquals(before, notices.dropLast(1))
        assertSame(proof, (f.state as LockState.Unlocked).proof) // the backup it starts uses that entry
        assertTrue(f.engine.core.holds(proof))
        await { f.engine.offerBackup() } // once
        assertEquals(1, backupNow().size)
        for ((name, bytes) in records) assertEquals(name, bytes, f.files.bytes(f.dirOf(VAULT), name)!!.toList()) // nothing written
        assertEquals(emptyList<String>(), f.port.writes)

        await { f.engine.answerNotice(backupNow().single().id, NoticeAnswer.LATER) }
        assertEquals(emptyList<Notice.BackupNow>(), backupNow())
    }

    @Test
    fun step2_anOfferThatWasAnswered_isNotMadeAgainOnTheSameEntry_butANewEntryGetsItsOwn() {
        vaultWithPin()
        f.startInFront()
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        f.answerNotices()
        await { f.engine.offerBackup() }
        await { f.engine.answerNotice(backupNow().single().id, NoticeAnswer.LATER) }

        // "The typed bytes are kept only until the offer is answered" (F12 step 2): that entry is not offered again.
        await { f.engine.offerBackup() }
        assertEquals(emptyList<Notice.BackupNow>(), backupNow())

        // The next master password typed is a new entry, with an offer of its own.
        f.lock()
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        f.answerNotices()
        await { f.engine.offerBackup() }
        assertEquals(1, backupNow().size)
    }

    @Test
    fun step2_theTypedBytesAreKeptOnlyUntilTheOfferIsAnswered() {
        vaultWithPin()
        f.startInFront()
        await { f.engine.useMasterPassword() }
        val proof = (f.unlockWith(PASSWORD) as MasterPasswordOutcome.Right).proof
        // The notices before it take nothing from the entry: "Choose a new PIN" › "Later" among them.
        f.answerNotices()
        assertSame(proof, (f.state as LockState.Unlocked).proof)
        await { f.engine.offerBackup() }
        assertTrue(proof.useBytes { it.contentEquals(bytes(PASSWORD)) })

        await { f.engine.answerNotice(backupNow().single().id, NoticeAnswer.LATER) }

        // §5.8: the flow of that entry has ended. Its proof is off the state, not held, and zeroed.
        assertEquals(LockState.Unlocked(emptyList(), null), f.state)
        assertFalse(f.engine.core.holds(proof))
        try {
            proof.useBytes { it.size }
            throw AssertionError("the proof still lends its bytes")
        } catch (e: IllegalStateException) {
            // dropped
        }
        // What needs the master password asks for it again from here on (§8.2): that entry sets no PIN.
        val pinBefore = f.pinRecord()
        assertEquals(PinSetOutcome.NotSet, await { f.engine.setPin(digits("368014"), proof) })
        assertEquals(pinBefore, f.pinRecord())
        // A master password typed now is a new entry, with a proof of its own.
        val again = (f.check(PASSWORD) as MasterPasswordOutcome.Right).proof
        assertTrue(f.engine.core.holds(again))
    }

    @Test
    fun step2_backUpNow_keepsTheEntryForTheBackupItStarts_untilTheAppStopsBeingVisible() {
        vaultWithPin()
        f.startInFront()
        await { f.engine.useMasterPassword() }
        val proof = (f.unlockWith(PASSWORD) as MasterPasswordOutcome.Right).proof
        f.answerNotices()
        await { f.engine.offerBackup() }

        await { f.engine.answerNotice(backupNow().single().id, NoticeAnswer.BACK_UP_NOW) }

        // The backup that this answer starts is sealed from that entry (architecture.md §4.12).
        assertEquals(LockState.Unlocked(emptyList(), proof), f.state)
        assertTrue(f.engine.core.holds(proof))
        assertTrue(proof.useBytes { it.contentEquals(bytes(PASSWORD)) })
        // The file picker makes the app stop being visible, and that drops it as always (§5.8, §10.3).
        await { f.engine.appStopped() }
        assertNull((f.state as LockState.Unlocked).proof)
        assertFalse(f.engine.core.holds(proof))
    }

    @Test
    fun step2_afterSetupOrARestore_theOfferUsesThatEntryToo() {
        f.startInFront()
        assertTrue(f.restoreBackup() is CreateOutcome.Created)
        await { f.engine.offerBackup() }
        assertEquals(listOf("ChoosePin", "BackupNow"), f.notices.map { it::class.simpleName })
    }

    @Test
    fun step2_noOffer_withoutAnEntryJustMade() {
        vaultWithPin()
        f.startInFront()
        await { f.engine.offerBackup() } // locked
        assertTrue(f.state is LockState.Locked)

        f.type(PIN) // a PIN unlock: no master password was typed
        await { f.engine.offerBackup() }
        assertEquals(emptyList<Notice.BackupNow>(), backupNow())

        f.lock()
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        f.leaveAndReturn() // the app stopped being visible: the proof is dropped (§5.8)
        assertNull((f.state as LockState.Unlocked).proof)
        await { f.engine.offerBackup() }
        assertEquals(emptyList<Notice.BackupNow>(), backupNow())
    }

    @Test
    fun step2_noOffer_whileANewMasterPasswordIsPending() {
        vaultWithPin()
        f.startInFront()
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        // A request in this session (F11 step 5, build plan 4.4) leaves a pending password on the record.
        val pending = StateRecord.PendingMp(
            f.clock.wallMs(), Shortcut.PIN, 0L, f.clock.elapsedMs(), f.port.current!!.newestVkId,
            ByteArray(16) { 1 }, ByteArray(12) { 2 }, ByteArray(48) { 3 },
        )
        val core = f.engine.core
        assertTrue(await { core.locked { core.lockOf(VAULT).master.update(bestEffort = false) { it.copy(pendingMp = pending) } } })

        await { f.engine.offerBackup() } // F11 step 10: "You can make a backup when your new master password has started working."
        assertEquals(emptyList<Notice.BackupNow>(), backupNow())

        // And the master password a backup asks for cannot be checked while one is pending: a right one
        // cancels it first (F11 step 9).
        assertTrue(f.check(PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(f.stateRecord().pendingMp)
        assertTrue(f.notices.last() is Notice.PendingCancelled)
    }

    @Test
    fun step2to5_theMasterPasswordOfABackupOrExport_countsAsPractice_andChangesNoOtherRecord() {
        // Practice step 0, last typed 21 hours ago: not due yet, so the PIN opens the app.
        vaultWithPin(StateRecord.Practice(f.clock.wallMs() - 21 * PracticeSchedule.HOUR_MS, 0, null))
        f.startInFront()
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        val pinBefore = f.pinRecord()
        val fingerprintBefore = f.files.bytes(f.dirOf(VAULT), "fingerprint.rec")!!.toList()
        val stateBefore = f.stateRecord()
        val hmacs = f.keys.hmacCalls.size

        assertEquals(MasterPasswordOutcome.Wrong, f.check("not the master password"))
        assertEquals(StateRecord.MpGuard(1, f.clock.elapsedMs(), 1), f.stateRecord().mpGuard)
        val right = f.check(PASSWORD) as MasterPasswordOutcome.Right

        assertEquals(VAULT, right.proof.vaultId)
        assertTrue(right.proof.useBytes { it.contentEquals(bytes(PASSWORD)) })
        // Practice: last_ok now, and one step up after 20 hours (§8.3).
        val state = f.stateRecord()
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 1, null), state.practice)
        assertEquals(StateRecord.MpGuard(0, null, 1), state.mpGuard) // a slip typed in a prompt is never forgiven (§13.4 item 4)
        assertEquals(stateBefore.copy(practice = state.practice, mpGuard = state.mpGuard), state)
        // The PIN's counters return to zero (§6.4); nothing else of pin.rec or fingerprint.rec changes.
        assertEquals(pinBefore.copy(guard = PinRecord.Guard(false, 0, 0)), f.pinRecord())
        assertEquals(fingerprintBefore, f.files.bytes(f.dirOf(VAULT), "fingerprint.rec")!!.toList())
        // PIN and fingerprint are never used here, and nothing of the vault is written.
        assertEquals(hmacs, f.keys.hmacCalls.size)
        assertEquals(emptyList<String>(), f.keys.prompts.map { it.alias })
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(emptyList<String>(), f.port.writes)
    }

    @Test
    fun step2_afterAVaultPutAsideWasOpenedWithItsMasterPassword_theOfferUsesThatEntry() {
        vaultWithPin()
        f.port.seedAside(OTHER_VAULT, bytes(Secrets.OTHER))
        f.plantState(OTHER_VAULT)
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        val proof = (f.unlockWith(Secrets.OTHER) as MasterPasswordOutcome.Right).proof
        assertEquals(OTHER_VAULT, proof.vaultId)

        await { f.engine.offerBackup() }

        assertEquals(1, backupNow().size)
        assertSame(proof, (f.state as LockState.Unlocked).proof)
    }
}
