package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.proofInTheOpenApp
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MasterPasswordGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of **F16b**, older master passwords (lock.md §12; build plan 4.4), through the
 * engine, in both of its branches:
 *
 * - **After an ordinary change (F8)**, until the old copies are retired: "This is an older master
 *   password", with only "OK" once the change was proven, and with "Try again" and "Open my
 *   passwords from before the change" while it is unfinished; that answer puts the newer copies
 *   aside (repeatable after an interruption), sets `max_generation`, and opens the copy from before
 *   with the password typed a moment ago.
 * - **After a replacement (F11)**: the old vault's own lock screen, the question "Open the older set
 *   instead?", "Cancel", the swap and the old vault's records, and what a visit that ends in between
 *   leaves behind (I11).
 */
class OlderCopiesFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private lateinit var vault: FakeVaultPort.FakeVault
    private lateinit var vkId1: String
    private lateinit var vkId2: String

    /**
     * The phone as F8 leaves it when the process died after its step 6 and before step 8: the copy
     * under the new key (generation 3, [NEW_PASSWORD]) is on disk and unproven, `mp_change` names it,
     * the PIN holds both wraps, the fingerprint only the one from before. [marked] false: the mark was
     * cleared, as step 8 clears it once the change is proven.
     */
    private fun afterAChange(marked: Boolean = true) {
        vault = f.seedVault(generations = 2)
        vkId1 = vault.newestVkId
        vkId2 = vault.rekey(bytes(NEW_PASSWORD))
        f.plantPin(vault, vkIds = listOf(vkId1, vkId2))
        f.plantFingerprint(vault, vkIds = listOf(vkId1))
        f.plantState(
            mpChange = if (marked) StateRecord.MpChange(vkId2, f.clock.wallMs()) else null,
            maxGeneration = StateRecord.MaxGeneration(3, f.clock.wallMs()),
        )
        f.startInFront()
        await { f.engine.useMasterPassword() }
    }

    private val changedAt: Long get() = vault.generation(3).createdMs

    // ---------------------------------------------------------------------------------------------
    // After an ordinary change

    @Test
    fun f16b_theChangeWasProven_theOldPasswordGetsOnlyOK() {
        afterAChange(marked = false)
        assertEquals(MasterPasswordOutcome.OlderPassword(changedAt, unfinishedChange = false), f.unlockWith(PASSWORD))
        assertEquals(LockScreen.OlderPassword(changedAt, unfinishedChange = false), f.screen)
        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE) // not on this screen
        f.answerOlder(OlderPasswordAnswer.TRY_AGAIN) // nor this one: "OK" is the only button
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertNull(f.port.openVaultId)

        f.answerOlder(OlderPasswordAnswer.OK)

        assertTrue(f.screen is LockScreen.MasterPassword)
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(3L, f.port.openGeneration)
    }

    @Test
    fun f16b_theChangeDidNotFinish_tryAgain_andTheNewPasswordOpensTheNewCopy() {
        afterAChange()
        assertEquals(MasterPasswordOutcome.OlderPassword(changedAt, unfinishedChange = true), f.unlockWith(PASSWORD))
        assertEquals(LockScreen.OlderPassword(changedAt, unfinishedChange = true), f.screen)
        assertEquals("nothing opened, and the try stays counted", 1, f.stateRecord().mpGuard.failures)

        f.answerOlder(OlderPasswordAnswer.TRY_AGAIN)

        assertTrue(f.screen is LockScreen.MasterPassword)
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(3L, f.port.openGeneration)
        assertNull(f.stateRecord().mpChange)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f16b_openMyPasswordsFromBeforeTheChange_putsTheNewerCopyAside_andOpensTheCopyFromBefore() {
        afterAChange()
        val pinSlot = f.pinRecord().slot!!.id
        val fingerprintSlot = f.fingerprintRecord().slot
        assertTrue((f.unlockWith(PASSWORD) as MasterPasswordOutcome.OlderPassword).unfinishedChange)

        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE) // after its question was answered with "Open"

        // Step 2: storage puts the generation above the copy that opened aside, never deleting it.
        assertEquals(listOf("putNewerAside 2"), f.port.writingCalls)
        assertEquals(listOf(2L, 1L), vault.live.map { it.number })
        assertEquals(FakeVaultPort.Suffix.ASIDE, vault.generation(3).suffix)
        assertEquals(3, vault.generations.size)
        // Step 3: the vault opens with the password typed a moment ago, as a correct master password.
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(2L, f.port.openGeneration)
        val state = f.stateRecord()
        assertNull(state.mpChange)
        assertEquals(StateRecord.MaxGeneration(2, f.clock.wallMs()), state.maxGeneration)
        assertEquals(StateRecord.MpGuard(0, null, 0), state.mpGuard)
        assertEquals(f.clock.wallMs(), state.practice.lastOkMs)
        assertEquals("F16c: the copy the user chose is not \"older than last time\"", 0, f.notices.filterIsInstance<Notice.OlderThanLastTime>().size)
        val proof = (f.state as LockState.Unlocked).proof!!
        assertTrue(proof.useBytes { it.contentEquals(bytes(PASSWORD)) })
        // No shortcut is removed; the wraps for the abandoned key go by the rule of §5.4.
        assertEquals(pinSlot, f.pinRecord().slot!!.id)
        assertEquals(listOf(vkId1), f.pinWraps())
        assertEquals(fingerprintSlot, f.fingerprintRecord().slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
        f.answerAll()
        f.lock()
        f.type(PIN)
        assertEquals(2L, f.port.openGeneration)
        f.lock()
        await { f.engine.promptIfDue() }
        assertEquals(2L, f.port.openGeneration)
        f.lock()
        await { f.engine.useMasterPassword() }
        assertEquals("the new password opens nothing now", MasterPasswordOutcome.Wrong, f.unlockWith(NEW_PASSWORD))
    }

    @Test
    fun f16b_whenStorageCannotPutTheNewerCopyAside_theScreenStays_andTheStepCanBeRepeated() {
        afterAChange()
        f.unlockWith(PASSWORD)
        f.port.failWrites(FakeVaultPort.WriteFault.REFUSED)

        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)

        assertEquals(LockScreen.OlderPassword(changedAt, unfinishedChange = true), f.screen)
        assertEquals(listOf(3L, 2L, 1L), vault.live.map { it.number })
        assertNotNull(f.stateRecord().mpChange)
        assertEquals(3L, f.stateRecord().maxGeneration.n)

        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)

        assertTrue(f.state is LockState.Unlocked)
        assertEquals(2L, f.port.openGeneration)
    }

    @Test
    fun f16b_aKillAfterStep2_theOldMasterPasswordSimplyOpensTheVaultAtTheNextStart() {
        afterAChange()
        f.unlockWith(PASSWORD)
        f.port.stopAt(3) // putNewerAside: before, generation 3 put aside, after

        val stopped = try {
            f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)
            false
        } catch (e: FakeVaultPort.Stopped) {
            true
        }

        assertTrue(stopped)
        f.restart()
        f.startInFront()
        assertTrue("the PIN's wrap for the key from before fits the newest copy now", f.screen is LockScreen.Pad)
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(2L, f.port.openGeneration)
        assertNull(f.stateRecord().mpChange)
        assertEquals(listOf(vkId1), f.pinWraps())
        // Step 3 had not stored the copy that was chosen, so this one unlock says it is older than the last one opened.
        assertEquals(1, f.notices.filterIsInstance<Notice.OlderThanLastTime>().size)
    }

    @Test
    fun f16b_whenTheFieldMustWait_theCopyFromBeforeIsTheNewestAlready_andTheOldPasswordOpensItAfterTheWait() {
        afterAChange()
        repeat(MasterPasswordGuard.FREE_TRIES - 1) { assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("wrong $it")) }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.OlderPassword) // the fifth try, counted

        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)

        assertTrue(f.state is LockState.Locked)
        assertEquals(listOf(2L, 1L), vault.live.map { it.number })
        val field = f.field
        assertNotNull("the field waits (§6.8)", field.waitUntilElapsedMs)
        f.passTime(MasterPasswordGuard.WAIT_MS)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(2L, f.port.openGeneration)
        assertNull(f.stateRecord().mpChange)
        assertEquals(2L, f.stateRecord().maxGeneration.n)
    }

    @Test
    fun f16b_aMarkWhoseChangeNeverReachedTheDisk_doesNotLetAPasswordFromAnEarlierChangeGoBack() {
        // An earlier change, proven long ago (the copies under the first password are still kept), and
        // a later change that died before its file was in place: its mark names a key no copy has.
        vault = f.seedVault(password = "the very first words", generations = 1)
        vault.rekey(bytes(PASSWORD))
        f.plantPin(vault)
        f.plantState(
            mpChange = StateRecord.MpChange("00000000-0000-4000-8000-0000000000ee", f.clock.wallMs()),
            maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()),
        )
        f.startInFront()
        await { f.engine.useMasterPassword() }

        val outcome = f.unlockWith("the very first words")

        assertEquals(MasterPasswordOutcome.OlderPassword(vault.generation(2).createdMs, unfinishedChange = false), outcome)
        assertFalse((f.screen as LockScreen.OlderPassword).unfinishedChange)
        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(listOf(2L, 1L), vault.live.map { it.number })
    }

    @Test
    fun f16b_aPasswordFromBeforeAnEarlierProvenChange_isOnlyAnOlderMasterPassword_whileALaterChangeIsUnfinished() {
        // Change 1 (PASSWORD to NEW_PASSWORD) is proven. Change 2 (NEW_PASSWORD to THIRD_PASSWORD), in the
        // same process, ends with "Saving may not have finished": its mark stays, and its copy is the
        // newest. The copies under PASSWORD are still kept, because no master password was typed at a
        // later start in between (F8 step 10, and the F8 table: "The password is changed twice before
        // the app is restarted").
        vault = f.seedVault(generations = 2)
        f.plantPin(vault)
        f.plantState()
        f.startInFront()
        f.type(PIN)
        assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), f.changeMasterPassword(f.proofInTheOpenApp(), NEW_PASSWORD, pin = PIN))
        f.port.failWrites(FakeVaultPort.WriteFault.UNCLEAR)
        assertEquals(ChangeOutcome.Unclear, f.changeMasterPassword(f.proofInTheOpenApp(NEW_PASSWORD), THIRD_PASSWORD, pin = PIN))
        f.answerAll()
        f.lock()
        val newest = vault.live.first()
        assertEquals("the second change is unfinished, and its copy is the newest", newest.vkId, f.stateRecord().mpChange?.toVkId)
        assertEquals(3, vault.live.map { it.vkId }.toSet().size)
        await { f.engine.useMasterPassword() }

        // The master password the owner changed away from with change 1, which was proven. "An old master
        // password has to stop working when the user changes it" (F16b; decision 20): only "OK".
        val outcome = f.unlockWith(PASSWORD)

        assertTrue("$outcome", outcome is MasterPasswordOutcome.OlderPassword)
        assertFalse("a password from before a proven change is offered the copy from before", (outcome as MasterPasswordOutcome.OlderPassword).unfinishedChange)
        assertFalse((f.screen as LockScreen.OlderPassword).unfinishedChange)
        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE) // not on this screen: nothing
        assertTrue("${f.port.writingCalls}", f.port.writingCalls.none { it.startsWith("putNewerAside") })
        assertEquals("nothing was put aside", newest.number, vault.live.first().number)
        assertNull("nothing opened", f.port.openVaultId)
        assertTrue(f.state is LockState.Locked)
        // The vault's master password as it stands opens the newest copy.
        f.answerOlder(OlderPasswordAnswer.OK)
        assertTrue(f.unlockWith(THIRD_PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(newest.number, f.port.openGeneration)
    }

    @Test
    fun f16b_onTheLockScreenOfAVaultPutAside_nothingIsPutAside() {
        vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        val other = f.port.seedAside(OTHER_VAULT, bytes("its words before"), putAsideAtMs = f.clock.wallMs() - DAY)
        val otherNew = other.rekey(bytes("its words after"))
        f.plantState(OTHER_VAULT, mpChange = StateRecord.MpChange(otherNew, f.clock.wallMs()), maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }

        val outcome = f.unlockWith("its words before")

        assertEquals(MasterPasswordOutcome.OlderPassword(other.generation(2).createdMs, unfinishedChange = false), outcome)
        assertEquals(LockScreen.OlderPassword(other.generation(2).createdMs, unfinishedChange = false, aside = AsideLine.LockedOn(f.clock.wallMs() - DAY)), f.screen)
        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        f.answerOlder(OlderPasswordAnswer.OK)
        assertTrue(f.unlockWith("its words after") is MasterPasswordOutcome.Right)
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId) // F17 step 4: it opened, so it is current now
    }

    @Test
    fun f16b_theScreenGoesWithTheVisit_andAnAnswerAfterItChangesNothing() {
        afterAChange()
        f.unlockWith(PASSWORD)
        f.leaveAndReturn()
        assertTrue("a new visit: the field", f.screen is LockScreen.MasterPassword || f.screen is LockScreen.Pad)
        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertNull(f.port.openVaultId)
        // A screen-off lock ends the visit too (decision 2).
        await { f.engine.useMasterPassword() }
        f.unlockWith(PASSWORD)
        f.lock(LockCause.SCREEN_OFF)
        assertFalse(f.screen is LockScreen.OlderPassword)
        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    // ---------------------------------------------------------------------------------------------
    // After a replacement

    private lateinit var n: String
    private var replacedAt = 0L

    /** The vault with a PIN and fingerprint unlock, replaced by vault N; then "It wasn't me": the old vault's own lock screen. */
    private fun afterAReplacement() {
        vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        f.type(PIN)
        n = f.replaceMasterPassword()
        replacedAt = f.stateRecord(VAULT).replaced!!.atMs
        f.answerAll()
        await { f.engine.itWasntMe(VAULT) }
        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason)
    }

    private val question: LockScreen get() = LockScreen.OpenOlderSet(replacedAt, AsideLine.FromBefore(replacedAt))

    @Test
    fun f16b_afterAReplacement_itsMasterPasswordOpensItInPlace_andTheQuestionComesFirst_cancelChangesNothing() {
        afterAReplacement()
        val pinSlot = f.pinRecord(VAULT).slot

        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(PASSWORD))

        assertEquals(question, f.screen)
        assertEquals("opened in place", VAULT, f.port.openAsideId)
        assertEquals(n, f.port.current!!.vaultId)
        // Nothing can be typed while the question is shown.
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(PASSWORD))
        f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)
        f.answerOlder(OlderPasswordAnswer.OK)
        assertEquals(question, f.screen)

        f.answerOlder(OlderPasswordAnswer.KEEP_NEWER_SET) // "Cancel"

        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason)
        assertNull("the vault that opened in place is dropped again", f.port.openAsideId)
        assertEquals(listOf("replaceVault $n"), f.port.writingCalls)
        assertEquals(n, f.port.current!!.vaultId)
        assertNotNull(f.stateRecord(VAULT).replaced)
        assertEquals(pinSlot, f.pinRecord(VAULT).slot)
        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET) // no question any more: nothing
        assertEquals(n, f.port.current!!.vaultId)
        await { f.engine.goBack() }
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(n, f.port.openVaultId)
    }

    @Test
    fun f16b_afterAReplacement_openMyPasswordsFrom7Oct_swapsTheVaults_andTheOldVaultHasNoSlotsAndNoMark() {
        afterAReplacement()
        val pinAlias = PinWrap.alias(f.pinRecord(VAULT).slot!!.id)
        val fingerprintAlias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + f.fingerprintRecord(VAULT).slot!!.id
        f.unlockWith(PASSWORD)

        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET)

        assertTrue(f.state is LockState.Unlocked)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(listOf(n), f.port.asideVaults.map { it.vaultId })
        assertEquals(listOf("replaceVault $n", "swapIn $VAULT"), f.port.writingCalls)
        assertEquals(PinRecord.OffReason.REPLACED, f.pinRecord(VAULT).off?.reason)
        assertNull(f.pinRecord(VAULT).slot)
        assertEquals(FingerprintRecord.OffReason.REPLACED, f.fingerprintRecord(VAULT).off?.reason)
        assertNull(f.fingerprintRecord(VAULT).slot)
        assertEquals(setOf(pinAlias, fingerprintAlias), f.keys.deleted.toSet())
        assertNull(f.stateRecord(VAULT).replaced)
        assertNull(f.stateRecord(VAULT).pendingMp)
        assertEquals(ChoosePinReason.SOMEONE_MAY_KNOW, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
        // A later start finds nothing to settle, and N stays as it is.
        f.restart()
        f.startInFront()
        assertNull(f.stateRecord(VAULT).replaced)
        assertNull(f.stateRecord(n).replaced)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f16b_afterAReplacement_aVisitThatEndsBeforeTheAnswer_dropsTheVaultOpenedInPlace() {
        afterAReplacement()
        f.unlockWith(PASSWORD)
        assertEquals(VAULT, f.port.openAsideId)

        await { f.engine.appStopped() }

        assertNull("I11: dropped with the visit", f.port.openAsideId)
        await { f.engine.appStarted() }
        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason)
        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET)
        assertEquals(n, f.port.current!!.vaultId)

        // And at a screen-off lock (decision 2).
        f.unlockWith(PASSWORD)
        assertEquals(question, f.screen)
        f.lock(LockCause.SCREEN_OFF)
        assertNull(f.port.openAsideId)
        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason)
        assertEquals(listOf("replaceVault $n"), f.port.writingCalls)
    }

    @Test
    fun f16b_afterAReplacement_whenTheSwapFails_nothingChanged_andThatVaultsOwnFieldSaysSo() {
        afterAReplacement()
        f.unlockWith(PASSWORD)
        f.port.failWrites(FakeVaultPort.WriteFault.REFUSED)

        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET)

        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason)
        // "Couldn't open your passwords from 7 Oct. Nothing was changed." (§13.3; build-plan §12,
        // decision 17), with the date the question named; before it the field came back without a word.
        assertEquals(MpError.OlderSetNotOpened(replacedAt), f.field.error)
        assertEquals(AsideLine.FromBefore(replacedAt), f.field.aside)
        assertNull(f.port.openAsideId)
        assertEquals(n, f.port.current!!.vaultId)
        assertNotNull(f.stateRecord(VAULT).replaced)
        assertNotNull(f.pinRecord(VAULT).slot)
        // Its master password typed again asks the question again, and the line is gone.
        f.unlockWith(PASSWORD)
        assertEquals(question, f.screen)
        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET)
        assertEquals(VAULT, f.port.current!!.vaultId)
    }

    @Test
    fun f16b_afterAReplacement_theLineOfASwapThatFailed_goesWithTheVisit_andWithAWrongPassword() {
        afterAReplacement()
        f.unlockWith(PASSWORD)
        f.port.failWrites(FakeVaultPort.WriteFault.REFUSED)
        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET)
        assertEquals(MpError.OlderSetNotOpened(replacedAt), f.field.error)

        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not the words of then"))
        assertTrue(f.field.error is MpError.Wrong)

        f.unlockWith(PASSWORD)
        f.port.failWrites(FakeVaultPort.WriteFault.REFUSED)
        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET)
        assertEquals(MpError.OlderSetNotOpened(replacedAt), f.field.error)
        f.leaveAndReturn()
        assertEquals(MpReason.FromBefore(replacedAt), f.field.reason)
        assertNull("a new visit starts without it", f.field.error)
    }

    @Test
    fun f16b_afterAReplacement_theVisitEndsWhileTheVaultsChangePlaces_theSwapStands_andNothingOpens() {
        afterAReplacement()
        f.unlockWith(PASSWORD)
        runBlocking {
            var stop: Job? = null
            f.port.beforeCall = { call -> if (call == "swapIn" && stop == null) stop = launch(start = CoroutineStart.UNDISPATCHED) { f.engine.appStopped() } }
            f.engine.olderPasswordAnswer(OlderPasswordAnswer.OPEN_OLDER_SET)
            f.port.beforeCall = null
            stop!!.join()
        }
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertNull(f.port.openVaultId)
        assertTrue(f.state is LockState.Locked)
        assertNull(f.stateRecord(VAULT).replaced)
        assertNull(f.pinRecord(VAULT).slot)
        await { f.engine.appStarted() }
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(ChoosePinReason.SOMEONE_MAY_KNOW, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
    }

    @Test
    fun f16b_afterAReplacement_step5WritesThatFail_areMadeGoodByTheNextStart() {
        afterAReplacement()
        f.unlockWith(PASSWORD)
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1) { it.contains("/$VAULT/") }

        f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET)

        assertTrue(f.state is LockState.Unlocked)
        assertEquals(VAULT, f.port.current!!.vaultId)
        f.files.clearFaults()
        assertNotNull("on disk the mark is still there", f.stateRecord(VAULT).replaced)
        f.restart()
        f.startInFront() // §5.11 step 3: current, and vault N put aside: the owner went back
        assertNull(f.stateRecord(VAULT).replaced)
        assertNull(f.pinRecord(VAULT).slot)
        assertEquals(PinRecord.OffReason.REPLACED, f.pinRecord(VAULT).off?.reason)
        assertNull(f.fingerprintRecord(VAULT).slot)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
    }

    @Test
    fun f16b_afterAReplacement_aShortcutRecordThatCannotBeReadAtStep5_keepsTheMark_soTheNextStartRemovesTheShortcut() {
        for (record in listOf("pin.rec", "fingerprint.rec")) {
            val g = LockFixture()
            val old = g.seedVault()
            g.plantPin(old)
            g.plantFingerprint(old)
            g.plantState()
            g.startInFront()
            g.type(PIN)
            val newVault = g.replaceMasterPassword()
            g.answerAll()
            g.lock()
            // A later process in which this record of the old vault cannot be read (§5.3 UNREADABLE: an
            // I/O error that may pass). Start-up cannot remove that slot now; it tries again at every start
            // while the mark is there (§5.11 step 3).
            g.files.reboot()
            g.restart()
            g.files.fail(FakeRecordFiles.Op.READ, times = -1) { it.endsWith("/$VAULT/$record") }
            g.startInFront()
            await { g.engine.showAside(VAULT) } // "Open my passwords from before …" (F16b step 1)
            assertEquals(record, MasterPasswordOutcome.TooLate, g.unlockWith(PASSWORD))
            assertTrue(record, g.screen is LockScreen.OpenOlderSet)

            g.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET) // steps 4 to 6

            assertTrue(record, g.state is LockState.Unlocked)
            assertEquals(record, VAULT, g.port.current!!.vaultId)
            assertEquals(record, listOf(newVault), g.port.asideVaults.map { it.vaultId })
            // The error passes, and the phone starts again.
            g.files.clearFaults()
            g.lock()
            g.files.reboot()
            g.restart()
            g.startInFront()
            // The PIN and the fingerprint of the old vault were what whoever replaced the master password
            // knew or had (F11 step 3): step 5 removes both, and "a write that fails is made good by
            // start-up" (§5.11 step 3, the owner went back). Neither may open the owner's passwords now.
            assertNull("$record: the old PIN slot", g.pinRecord(VAULT).slot)
            assertNull("$record: the old fingerprint slot", g.fingerprintRecord(VAULT).slot)
            assertNull(record, g.stateRecord(VAULT).replaced)
            assertTrue("$record: ${g.screen}", g.screen is LockScreen.MasterPassword)
            assertTrue(record, g.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        }
    }

    @Test
    fun f16b_itWasntMe_withMoreThanOneReplacement_isTheSheetsList_soTheOpenAppIsLocked() {
        afterAReplacement()
        await { f.engine.goBack() }
        assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)

        await { f.engine.itWasntMe(null) }

        assertTrue(f.state is LockState.Locked)
        assertNull(f.port.openVaultId)
        val before = f.state
        await { f.engine.itWasntMe(null) } // on the lock screen it changes nothing
        assertEquals(before, f.state)
    }
}
