package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ProofOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ReplacedLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.RequestOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.NEW_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.setPin
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MasterPasswordGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of **F11 finishing**, steps 13 to 20 (lock.md §12; build plan 4.4), and the rest of
 * test **T16**: finishing writes `replaced` before storage is called; the replacement makes a new
 * vault N from the open entries and puts the old vault aside, whole and marked; from then on the old
 * vault offers nothing but its own master password, and its slots go at a later start; a second
 * replacement leaves two marked vaults, each with its own record; the new master password neither
 * opens nor deletes a marked vault, and the old one opens it and, after the question, makes it
 * current, with no slots and no mark; the notice is shown until 7 days are credited, whatever the
 * wall clock says. And each way finishing can fail or be cut short.
 */
class ReplacementFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private lateinit var vault: FakeVaultPort.FakeVault
    private val entries = Vault(
        entries = listOf(
            Entry(id = "00000000-0000-4000-8000-0000000000d1", title = "Mail", username = "aziz", password = "m1", createdAt = 1L, updatedAt = 2L),
        ),
    )

    /** The vault with a PIN and fingerprint unlock and two accounts, opened with the PIN. */
    private fun phone(pin: Boolean = true, fingerprint: Boolean = true) {
        vault = f.seedVault()
        vault.content = entries
        if (pin) f.plantPin(vault)
        if (fingerprint) f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        if (pin) f.type(PIN) else await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
    }

    /** A new master password asked for with the PIN (the fingerprint without one), and 24 hours credited: ready. */
    private fun ready(pin: String? = PIN) {
        assertTrue(f.prove(pin) is ProofOutcome.Proven)
        assertTrue(f.request(NEW_PASSWORD) is RequestOutcome.Requested)
        f.passTime(24 * HOUR)
        assertTrue(f.engine.summary.value.pending!!.ready)
    }

    private val mark: StateRecord.Replaced? get() = f.stateRecord(VAULT).replaced

    /** Vault N as the replacement made it, and the old vault as it left it. */
    private fun assertReplaced(n: String, by: Shortcut, pinSlot: Any?, fingerprintSlot: Any?) {
        assertTrue(Uuid.isValid(n))
        assertNotEquals(VAULT, n)
        // Step 16: vault N from the open entries; the old vault put aside, whole; no other write.
        val current = f.port.current!!
        assertEquals(n, current.vaultId)
        assertEquals(entries, current.content)
        assertTrue(current.password(current.newestVkId).contentEquals(bytes(NEW_PASSWORD)))
        assertEquals(listOf(1L), current.live.map { it.number })
        assertEquals(listOf(VAULT), f.port.asideVaults.map { it.vaultId })
        assertEquals("the old vault's files, whole", listOf(1L), vault.live.map { it.number })
        assertTrue(vault.password(vault.newestVkId).contentEquals(bytes(PASSWORD)))
        assertEquals(listOf("replaceVault $n"), f.port.writingCalls)
        // Step 15: the old vault's mark; pending_mp stays. Step 18: nothing else in its directory changes now.
        val marked = mark!!
        assertEquals(n, marked.newVaultId)
        assertEquals(by, marked.by)
        assertNotNull(f.stateRecord(VAULT).pendingMp)
        assertEquals(pinSlot, (f.pinOnDisk(VAULT) as? RecordRead.Ok)?.record?.slot)
        assertEquals(fingerprintSlot, (f.fingerprintOnDisk(VAULT) as? RecordRead.Ok)?.record?.slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertEquals("the try that opened the pending wrap is not counted", 0, f.stateRecord(VAULT).mpGuard.failures)
        // Step 17: lock/N/ with its state.rec: practice step 0, last_ok now, counters zero.
        val state = f.stateRecord(n)
        assertEquals(n, state.vaultId)
        assertEquals(StateRecord.Practice(f.clock.wallMs(), 0, null), state.practice)
        assertEquals(StateRecord.MpGuard(0, null, 0), state.mpGuard)
        assertNull(state.mpChange)
        assertNull(state.pendingMp)
        assertNull(state.replaced)
        assertEquals(1L, state.maxGeneration.n)
        // Step 19: vault N's ways in, as a restored vault gets them; the summary.
        assertEquals(LockSummary.Replaced(marked.atMs, by, VAULT), f.engine.summary.value.replaced)
        assertNull(f.engine.summary.value.pending)
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin)
        assertEquals(LockSummary.Fingerprint.Off, f.engine.summary.value.fingerprint)
    }

    // ---------------------------------------------------------------------------------------------
    // Finishing

    @Test
    fun f11_finishNow_inTheOpenApp_makesVaultN_putsTheOldVaultAsideMarked_andTheSessionGoesOnInN() {
        phone()
        val pinSlot = f.pinRecord().slot
        val fingerprintSlot = f.fingerprintRecord().slot
        ready()
        f.lock()
        f.type(PIN)
        val notice = f.notices.filterIsInstance<Notice.PendingReady>().single() // "Your new master password is ready. Type it to finish."
        await { f.engine.answerNotice(notice.id, NoticeAnswer.FINISH_NOW) }
        // T16: the mark is on disk, a second old, before storage is called.
        val order = ArrayList<Pair<String, Long>>()
        f.files.onBoundary = { _, at ->
            if (at.endsWith("$VAULT/state.rec: after the rename") && mark != null && order.none { it.first == "mark" }) order += "mark" to f.clock.elapsedMs()
        }
        f.port.onBoundary = { _, at -> if (at == "replaceVault: before") order += "storage" to f.clock.elapsedMs() }

        val outcome = f.finish(NEW_PASSWORD)

        assertTrue("$outcome", outcome is MasterPasswordOutcome.Right)
        val n = f.port.current!!.vaultId
        assertEquals(listOf("mark", "storage"), order.map { it.first })
        assertTrue("the mark settles for a second", order[1].second - order[0].second >= 1_000)
        assertReplaced(n, Shortcut.PIN, pinSlot, fingerprintSlot)
        // The session goes on in N, with the proof of the new master password for "Choose a PIN" (F1 step 6).
        val proof = (outcome as MasterPasswordOutcome.Right).proof
        assertEquals(n, proof.vaultId)
        assertTrue(proof.useBytes { it.contentEquals(bytes(NEW_PASSWORD)) })
        assertTrue(f.engine.core.holds(proof))
        assertEquals(proof, (f.state as LockState.Unlocked).proof)
        assertEquals(n, f.port.openVaultId)
        val offer = f.notices.single() as Notice.ChoosePin
        assertEquals(ChoosePinReason.NONE_HERE, offer.reason)
        assertFalse(offer.needsMasterPassword)
        assertTrue(f.setPin(NEW_PIN, proof))
        f.lock()
        f.type(NEW_PIN)
        assertEquals(n, f.port.openVaultId)
    }

    @Test
    fun f11_step13_onTheLockScreen_theNewMasterPasswordFinishes_andNoPinOrFingerprintIsAskedFor() {
        phone()
        val pinSlot = f.pinRecord().slot
        val fingerprintSlot = f.fingerprintRecord().slot
        ready()
        f.lock()
        assertTrue(f.screen is LockScreen.Pad)
        await { f.engine.useMasterPassword() }
        assertNull("ready: no \"starts working in about N hours\"", f.field.pendingReadyInMs)
        val checks = f.keys.hmacCalls.size
        val prompts = f.keys.prompts.size

        val outcome = f.unlockWith(NEW_PASSWORD)

        assertTrue("$outcome", outcome is MasterPasswordOutcome.Right)
        assertEquals(checks, f.keys.hmacCalls.size)
        assertEquals(prompts, f.keys.prompts.size)
        assertTrue(f.state is LockState.Unlocked)
        val n = f.port.current!!.vaultId
        assertReplaced(n, Shortcut.PIN, pinSlot, fingerprintSlot)
        assertEquals(n, (outcome as MasterPasswordOutcome.Right).proof.vaultId)
        assertEquals(ChoosePinReason.NONE_HERE, (f.notices.single() as Notice.ChoosePin).reason)
        assertNull(f.engine.core.visit)
    }

    @Test
    fun f11_pinAndFingerprintLostDuringTheWait_theNewMasterPasswordStillOpensTheApp() {
        phone()
        ready()
        f.passTime(0)
        // The wait is over, and meanwhile a finger was added and the PIN was forgotten.
        f.lock()
        f.keys.invalidateFingerprintKeys()
        await { f.engine.useFingerprint() } // F15a: the slot goes
        for (wrong in listOf("000001", "000002", "000003", "000004")) f.type(wrong)
        assertEquals(PadStatus.LastTry, f.pad.status) // ready now: not "your new master password is not working yet"
        f.type("000005")
        assertEquals(MpReason.Pin(io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason.LIMIT), f.field.reason)

        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)

        assertNotEquals(VAULT, f.port.openVaultId)
        assertNotNull(mark)
    }

    @Test
    fun f11_finishedByFingerprint_whereThereIsNoPin_saysSo() {
        phone(pin = false)
        val fingerprintSlot = f.fingerprintRecord().slot
        ready(pin = null)
        val n = f.finish(NEW_PASSWORD).let { f.port.current!!.vaultId }
        assertReplaced(n, Shortcut.FINGERPRINT, null, fingerprintSlot)
        f.lock()
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(Shortcut.FINGERPRINT, f.notices.filterIsInstance<Notice.Replaced>().single().by)
    }

    // ---------------------------------------------------------------------------------------------
    // Step 18: the old vault, put aside and marked

    @Test
    fun t16_theOldVaultOffersNothingButItsOwnMasterPassword_andTheNewOneNeitherOpensNorDeletesIt() {
        phone()
        val pinAlias = PinWrap.alias(f.pinRecord().slot!!.id)
        val fingerprintAlias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + f.fingerprintRecord().slot!!.id
        val n = f.replaceMasterPassword()
        val at = mark!!.atMs
        f.answerAll()
        f.lock()
        // Vault N's lock screen: no PIN yet.
        assertNull(f.field.reason)
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        val notice = f.notices.filterIsInstance<Notice.Replaced>().single() // step 20
        assertEquals(Notice.Replaced(notice.id, at, Shortcut.PIN, VAULT), notice)

        await { f.engine.answerNotice(notice.id, NoticeAnswer.IT_WASNT_ME) }

        // The old vault's own lock screen (F16b step 2): its master-password field only.
        assertTrue(f.state is LockState.Locked)
        assertNull("the open vault was locked first", f.port.openVaultId)
        assertEquals(MpReason.FromBefore(at), f.field.reason)
        assertEquals(AsideLine.FromBefore(at), f.field.aside)
        assertFalse(f.field.canUsePin)
        assertNull(f.field.fingerprintLine)
        assertNull(f.field.pendingReadyInMs)
        val checks = f.keys.hmacCalls.size
        val prompts = f.keys.prompts.size
        val derivations = f.kdf.derivations
        f.type(PIN)
        await { f.engine.promptIfDue() }
        await { f.engine.useFingerprint() }
        assertEquals("no PIN is checked", checks, f.keys.hmacCalls.size)
        assertEquals("no fingerprint is asked for", prompts, f.keys.prompts.size)
        // The new master password, and the pending password, do not open it.
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith(NEW_PASSWORD))
        assertEquals("its pending wrap is not tried", derivations, f.kdf.derivations)
        assertEquals(MpError.Wrong(offerForgot = false, replaced = null), f.field.error)
        assertTrue(f.state is LockState.Locked)
        assertEquals(1, f.stateRecord(VAULT).mpGuard.failures) // its own guard (§6.8)
        await { f.engine.goBack() }

        // A later start finds vault N on the phone: the old vault's slots, keys and pending password go (§5.11 step 3).
        f.restart()
        f.startInFront()
        val pin = f.pinRecord(VAULT)
        assertNull(pin.slot)
        assertEquals(PinRecord.OffReason.REPLACED, pin.off?.reason)
        val finger = f.fingerprintRecord(VAULT)
        assertNull(finger.slot)
        assertEquals(FingerprintRecord.OffReason.REPLACED, finger.off?.reason)
        assertNull(f.stateRecord(VAULT).pendingMp)
        assertNotNull("the mark stays", mark)
        assertEquals(setOf(pinAlias, fingerprintAlias), f.keys.deleted.toSet())

        // Neither Settings › Old passwords "Open" nor "Delete" takes the new master password.
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(AsideOutcome.Wrong, await { f.engine.mergeAside(VAULT, NEW_PASSWORD.toCharArray()) })
        assertEquals(AsideOutcome.Wrong, await { f.engine.deleteAside(VAULT, NEW_PASSWORD.toCharArray()) })
        assertNotNull(f.port.vault(VAULT))
        assertEquals(listOf("replaceVault $n"), f.port.writingCalls)
    }

    @Test
    fun t16_theOldMasterPasswordOpensIt_andAfterTheQuestionMakesItCurrent_withNoSlotsAndNoMark() {
        phone()
        val n = f.replaceMasterPassword()
        val at = mark!!.atMs
        // Vault N gets a PIN of its own, which must survive what follows.
        assertTrue(f.setPin(NEW_PIN, (f.state as LockState.Unlocked).proof!!))
        val nPin = f.pinRecord(n).slot!!
        f.answerAll()
        await { f.engine.itWasntMe(VAULT) } // "It wasn't me", from Settings or the notice

        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.TooLate) // it opened in place; the question first
        assertEquals(LockScreen.OpenOlderSet(at, AsideLine.FromBefore(at)), f.screen)
        assertEquals(VAULT, f.port.openAsideId)
        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET) // "Open my passwords from 7 Oct"

        assertTrue(f.state is LockState.Unlocked)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(listOf(n), f.port.asideVaults.map { it.vaultId })
        // Step 5: no slots and no mark.
        assertNull(f.pinRecord(VAULT).slot)
        assertEquals(PinRecord.OffReason.REPLACED, f.pinRecord(VAULT).off?.reason)
        assertNull(f.fingerprintRecord(VAULT).slot)
        val state = f.stateRecord(VAULT)
        assertNull(state.replaced)
        assertNull(state.pendingMp)
        // Step 6: "Choose a new PIN. Someone else may know the old one.", with the proof of the master password just typed.
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.SOMEONE_MAY_KNOW, offer.reason)
        assertFalse(offer.needsMasterPassword)
        val proof = (f.state as LockState.Unlocked).proof!!
        assertEquals(VAULT, proof.vaultId)
        assertTrue(proof.useBytes { it.contentEquals(bytes(PASSWORD)) })
        // Step 4: the lock touches nothing of the vault that is now put aside; its own PIN opens it again.
        assertEquals(nPin, f.pinRecord(n).slot)
        assertTrue(PinWrap.alias(nPin.id) in f.keys.aliases)
        assertEquals(listOf("replaceVault $n", "swapIn $VAULT"), f.port.writingCalls)
        assertEquals(LockSummary.Pin.NotSet, f.engine.summary.value.pin)
        assertNull(f.engine.summary.value.replaced)
        f.answerAll()
        f.lock()
        await { f.engine.showAside(n) }
        f.type(NEW_PIN)
        assertEquals(n, f.port.current!!.vaultId)
        assertEquals(n, f.port.openVaultId)
    }

    @Test
    fun f11_step20_theNoticeIsShownAfterEveryUnlockOfN_untilSevenDaysAreCredited_whateverTheWallClockSays() {
        phone()
        f.replaceMasterPassword()
        val seen = mark!!.seenElapsedMs
        fun unlockAndCount(): Int {
            f.lock()
            assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
            return f.notices.filterIsInstance<Notice.Replaced>().size
        }
        assertEquals(1, unlockAndCount())
        f.clock.jumpWall(30 * DAY)
        assertEquals("setting the date forward does not make it go away (I8)", 1, unlockAndCount())
        f.clock.jumpWall(-60 * DAY)
        assertEquals(1, unlockAndCount())
        f.clock.advance(seen + 7 * DAY - 1 - f.clock.elapsedMs())
        assertEquals(1, unlockAndCount())
        f.clock.advance(1)
        assertEquals(0, unlockAndCount())
        assertNull(f.engine.summary.value.replaced)
    }

    @Test
    fun f11_aSecondReplacementLeavesTwoMarkedVaults_eachWithItsOwnRecord() {
        phone()
        val n1 = f.replaceMasterPassword(NEW_PASSWORD)
        assertTrue(f.setPin(NEW_PIN, (f.state as LockState.Unlocked).proof!!))
        f.answerAll()

        val n2 = f.replaceMasterPassword(THIRD_PASSWORD, pin = NEW_PIN)

        assertEquals(n2, f.port.current!!.vaultId)
        assertEquals(listOf(n1, VAULT), f.port.asideVaults.map { it.vaultId })
        assertEquals(n1, f.stateRecord(VAULT).replaced!!.newVaultId)
        assertEquals(n2, f.stateRecord(n1).replaced!!.newVaultId)
        assertNull(f.stateRecord(n2).replaced)
        assertEquals(listOf("replaceVault $n1", "replaceVault $n2"), f.port.writingCalls)
        val second = f.stateRecord(n1).replaced!!
        assertEquals(LockSummary.Replaced(second.atMs, Shortcut.PIN, n1), f.engine.summary.value.replaced)
        // A wrong master password on N2's screen names the latest replacement; the button opens the list.
        f.answerAll()
        f.lock()
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it"))
        assertEquals(ReplacedLine(second.atMs, Shortcut.PIN, null), (f.field.error as MpError.Wrong).replaced)
        // A later start removes the slots of both.
        f.restart()
        f.startInFront()
        assertNull(f.pinRecord(VAULT).slot)
        assertNull(f.pinRecord(n1).slot)
        assertEquals(PinRecord.OffReason.REPLACED, f.pinRecord(n1).off?.reason)
        assertNotNull(f.stateRecord(VAULT).replaced)
        assertNotNull(f.stateRecord(n1).replaced)
        // Each old vault opens with its own master password, on its own lock screen.
        await { f.engine.showAside(n1) }
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith(PASSWORD))
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(NEW_PASSWORD))
        assertTrue(f.screen is LockScreen.OpenOlderSet)
        f.answerOlder(OlderPasswordAnswer.KEEP_NEWER_SET)
        await { f.engine.goBack() }
        await { f.engine.showAside(VAULT) }
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(PASSWORD))
        assertTrue(f.screen is LockScreen.OpenOlderSet)
    }

    // ---------------------------------------------------------------------------------------------
    // What stops it

    @Test
    fun f11_finishNow_beforeItIsReady_orWithNothingPending_triesNothing_andAWrongPasswordIsCounted() {
        phone()
        assertEquals(MasterPasswordOutcome.TooLate, f.finish(NEW_PASSWORD)) // nothing pending
        assertTrue(f.prove(PIN) is ProofOutcome.Proven)
        assertTrue(f.request(NEW_PASSWORD) is RequestOutcome.Requested)
        val derivations = f.kdf.derivations
        assertEquals(MasterPasswordOutcome.NotFinished, f.finish(NEW_PASSWORD))
        assertEquals("not tried before its time", derivations, f.kdf.derivations)
        assertEquals(0, f.stateRecord().mpGuard.failures)
        f.passTime(24 * HOUR)
        assertEquals(MasterPasswordOutcome.Wrong, f.finish("not the new one"))
        assertEquals(1, f.stateRecord().mpGuard.failures)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertTrue(f.finish(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        // The try that opened the pending wrap is step 14, not §6.8 step 5: it neither counts nor resets the old vault's count.
        assertEquals(1, f.stateRecord(VAULT).mpGuard.failures)
    }

    @Test
    fun f11_afterFiveWrongTries_finishNowWaitsLikeAnyField_andThenFinishes() {
        phone()
        ready()
        repeat(MasterPasswordGuard.FREE_TRIES) { assertEquals(MasterPasswordOutcome.Wrong, f.finish("wrong $it")) }
        val waited = f.finish(NEW_PASSWORD)
        assertTrue("$waited", waited is MasterPasswordOutcome.Wait)
        assertEquals(VAULT, f.port.current!!.vaultId)
        f.passTime(MasterPasswordGuard.WAIT_MS)
        assertTrue(f.finish(NEW_PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f11_step15_whenTheMarkCannotBeWritten_couldntFinishNow_nothingChanged_andThePendingPasswordStaysReady() {
        phone()
        ready()
        // The second write of state.rec from now: the first counts the try (§6.8), the second is the mark.
        var writes = 0
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1) { it.endsWith("$VAULT/state.rec") && ++writes == 2 }

        assertEquals(MasterPasswordOutcome.NotFinished, f.finish(NEW_PASSWORD))

        f.files.clearFaults()
        assertNull(mark)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(VAULT, f.port.openVaultId)
        assertTrue(f.engine.summary.value.pending!!.ready)
        assertEquals(0, f.stateRecord().mpGuard.failures)
        // The same on the lock screen: the vault opened with the pending key is dropped again.
        f.lock()
        await { f.engine.useMasterPassword() }
        writes = 0
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1) { it.endsWith("$VAULT/state.rec") && ++writes == 2 }
        assertEquals(MasterPasswordOutcome.NotFinished, f.unlockWith(NEW_PASSWORD))
        f.files.clearFaults()
        assertTrue(f.state is LockState.Locked)
        assertNull(f.port.openVaultId)
        assertNull(f.field.error)
        assertNull(mark)
        // PIN and fingerprint work as before; the next try finishes.
        await { f.engine.usePin() }
        f.type(PIN)
        assertEquals(VAULT, f.port.openVaultId)
        assertTrue(f.finish(NEW_PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f11_step16_storageFails_theMarkIsUndone_andThePendingPasswordStaysReady() {
        for (fault in listOf(FakeVaultPort.WriteFault.NOTHING_CHANGED, FakeVaultPort.WriteFault.QUARANTINED)) {
            val g = LockFixture()
            val v = g.seedVault()
            g.plantPin(v)
            g.plantFingerprint(v)
            g.plantState()
            g.startInFront()
            g.type(PIN)
            assertTrue(g.prove(PIN) is ProofOutcome.Proven)
            assertTrue(g.request(NEW_PASSWORD) is RequestOutcome.Requested)
            g.passTime(24 * HOUR)
            g.port.failWrites(fault)

            assertEquals("$fault", MasterPasswordOutcome.NotFinished, g.finish(NEW_PASSWORD))

            assertNull("$fault: replaced = null", g.stateRecord().replaced)
            assertNotNull("$fault", g.stateRecord().pendingMp)
            assertEquals("$fault", VAULT, g.port.current!!.vaultId)
            assertEquals("$fault", emptyList<String>(), g.port.asideVaults.map { it.vaultId })
            assertEquals("$fault: the session goes on", VAULT, g.port.openVaultId)
            g.lock()
            g.type(PIN)
            assertEquals("$fault: the PIN works as before", VAULT, g.port.openVaultId)
            g.lock()
            await { g.engine.promptIfDue() }
            assertTrue("$fault: and the fingerprint", g.state is LockState.Unlocked)
            assertTrue("$fault", g.finish(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        }
    }

    @Test
    fun f11_notEnoughMemory_beforeTheMark_orInStorage_nothingChanged() {
        phone()
        ready()
        f.kdf.unavailable = true
        assertEquals(MasterPasswordOutcome.NoMemory, f.finish(NEW_PASSWORD))
        assertNull(mark)
        assertEquals(0, f.stateRecord().mpGuard.failures)
        f.kdf.unavailable = false
        f.port.noMemory = true
        assertEquals(MasterPasswordOutcome.NoMemory, f.finish(NEW_PASSWORD))
        assertNull("the mark is undone", mark)
        assertEquals("storage wrote nothing", emptyList<String>(), f.port.writes)
        assertEquals(VAULT, f.port.current!!.vaultId)
        f.port.noMemory = false
        // On the lock screen the field says so, and the next try finishes.
        f.lock()
        await { f.engine.useMasterPassword() }
        f.kdf.unavailable = true
        assertEquals(MasterPasswordOutcome.NoMemory, f.unlockWith(NEW_PASSWORD))
        assertEquals(MpError.NoMemory, f.field.error)
        assertNull(f.port.openVaultId)
        f.kdf.unavailable = false
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f11_step16_savingMayNotHaveFinished_theLockScreenOfWhatIsThereNow_andTheMarkStays() {
        for (onTheLockScreen in listOf(false, true)) {
            val g = LockFixture()
            val v = g.seedVault()
            g.plantPin(v)
            g.plantState()
            g.startInFront()
            g.type(PIN)
            assertTrue(g.prove(PIN) is ProofOutcome.Proven)
            assertTrue(g.request(NEW_PASSWORD) is RequestOutcome.Requested)
            g.passTime(24 * HOUR)
            g.port.failWrites(FakeVaultPort.WriteFault.UNCLEAR)
            val outcome = if (onTheLockScreen) {
                g.lock()
                await { g.engine.useMasterPassword() }
                g.unlockWith(NEW_PASSWORD)
            } else {
                g.finish(NEW_PASSWORD)
            }
            val where = "on the lock screen: $onTheLockScreen"
            assertEquals(where, MasterPasswordOutcome.TooLate, outcome)
            // N is in place and perhaps not durable; nothing is open; the mark stays (start-up settles it).
            val n = g.port.current!!.vaultId
            assertNotEquals(where, VAULT, n)
            assertNull(where, g.port.openVaultId)
            assertNotNull(where, g.stateRecord(VAULT).replaced)
            assertTrue(where, g.state is LockState.Locked)
            // "After that step, before step 17": N has no lock directory, "Welcome back" asks for the new master password.
            assertEquals(where, RecordRead.Missing, g.stateOnDisk(n))
            assertEquals(where, MpReason.WelcomeBack, g.field.reason)
            assertTrue(where, g.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
            assertEquals(where, ChoosePinReason.NONE_HERE, g.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
            assertTrue(where, g.stateOnDisk(n) is RecordRead.Ok)
            assertFalse(where, hasUsableShortcut(g, VAULT))
        }
    }

    @Test
    fun f11_aLockBeforeStep15_leavesEverythingAsItWas_andTheNewPasswordFinishesAtTheNextTry() {
        phone()
        ready()
        val outcome = runBlocking {
            var locking: Job? = null
            // The try is counted (its write is the first of state.rec from now); the app locks right then.
            f.files.onBoundary = { _, at ->
                if (locking == null && at.endsWith("$VAULT/state.rec: after the directory sync")) {
                    locking = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock(LockCause.NO_TOUCH) }
                }
            }
            val result = f.engine.finishPending(NEW_PASSWORD.toCharArray())
            f.files.onBoundary = null
            locking!!.join()
            result
        }
        assertEquals(MasterPasswordOutcome.TooLate, outcome)
        assertNull(mark)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertTrue(f.state is LockState.Locked)
        assertEquals("the try is given back", 0, f.stateRecord().mpGuard.failures)
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f11_onTheLockScreen_theVisitEndsBeforeStep15_theKeyIsDropped_andNothingChanged() {
        phone()
        ready()
        f.lock()
        await { f.engine.useMasterPassword() }
        val outcome = runBlocking {
            var off: Job? = null
            f.port.beforeCall = { call ->
                // Storage opens the vault with the pending key (step 14): the screen goes off.
                if (call == "open current" && off == null) off = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock(LockCause.SCREEN_OFF) }
            }
            val result = f.engine.unlockWithMasterPassword(NEW_PASSWORD.toCharArray())
            f.port.beforeCall = null
            off!!.join()
            result
        }
        assertEquals(MasterPasswordOutcome.TooLate, outcome)
        assertNull(f.port.openVaultId)
        assertNull(mark)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertTrue(f.state is LockState.Locked)
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f11_steps15To17RunToTheirEnd_aLockMeanwhile_locksVaultN_andNoProofIsHandedOut() {
        phone()
        ready()
        f.clock.autoAdvance = false
        val outcome = runBlocking {
            var result: MasterPasswordOutcome? = null
            val finish = launch(start = CoroutineStart.UNDISPATCHED) { result = f.engine.finishPending(NEW_PASSWORD.toCharArray()) }
            assertNotNull("the mark is written, and settles", mark)
            val locking = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.lock(LockCause.NO_TOUCH) }
            f.clock.advance(1_000)
            finish.join()
            locking.join()
            result
        }
        f.clock.autoAdvance = true
        assertEquals(MasterPasswordOutcome.TooLate, outcome)
        val n = f.port.current!!.vaultId
        assertNotEquals(VAULT, n)
        assertTrue(f.stateOnDisk(n) is RecordRead.Ok)
        assertEquals(n, mark!!.newVaultId)
        assertTrue(f.state is LockState.Locked)
        assertNull(f.port.openVaultId)
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f11_theNewPasswordIsTriedOnlyWhenNoPasswordSlotOpens_theCurrentOneStillOpensTheVault_andCancelsIt() {
        phone()
        ready()
        f.lock()
        await { f.engine.useMasterPassword() }
        val derivations = f.kdf.derivations
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals("the pending wrap was not tried", derivations, f.kdf.derivations)
        assertEquals(VAULT, f.port.openVaultId)
        assertNull(f.stateRecord().pendingMp)
        assertTrue(f.notices.first() is Notice.PendingCancelled)
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts.filter { it.use != io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse.UNLOCK })
    }
}
