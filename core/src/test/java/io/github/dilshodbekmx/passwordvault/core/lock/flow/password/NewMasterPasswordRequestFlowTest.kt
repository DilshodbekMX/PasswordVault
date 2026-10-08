package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PendingWrap
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ProofOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.flow.RequestOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.newerRecord
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.unlockWithMasterPassword
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
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
 * Scripted runs of **F10 step 4**, "I can't remember it", and of **F11**, asking and waiting (lock.md
 * §12; build plan 4.4), through the engine; with them the first half of test **T16**: a new master
 * password cannot be asked for with a fingerprint while a PIN slot for the current key exists, nor
 * while the PIN cannot be checked; it is cancelled by the button and by a correct master password;
 * and its 24 hours are credited by §6.9, which no kill, reboot or date can shorten.
 *
 * The proof of step 3 ("Proof now. An app that is already open is not enough.") is the PIN typed
 * now: on the lock screen after "I can't remember it", where it is an ordinary entry of the visit
 * and also opens the app, or in a prompt of the open app (`LockEngine.proveForNewMasterPassword`).
 */
class NewMasterPasswordRequestFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private lateinit var vault: FakeVaultPort.FakeVault

    /** In the 14-day rhythm, [daysAgo] days after the last correct master password: due after 14, required after 21. */
    private fun practice(daysAgo: Long) = StateRecord.Practice(f.clock.wallMs() - daysAgo * DAY, 3, null)

    /** A vault with a PIN and fingerprint unlock (each when asked), started and in front; practice is past its grace unless said otherwise. */
    private fun phone(pin: Boolean = true, fingerprint: Boolean = true, practice: StateRecord.Practice? = practice(22)) {
        vault = f.seedVault()
        if (pin) f.plantPin(vault)
        if (fingerprint) f.plantFingerprint(vault)
        if (practice != null) f.plantState(practice = practice)
        f.startInFront()
    }

    private val pending: StateRecord.PendingMp? get() = f.stateRecord().pendingMp

    /** Whether [password] opens the pending wrap on disk and gives the vault's key (lock.md §4.3, F11 step 14). */
    private fun pendingOpensWith(password: String): Boolean {
        val p = pending ?: return false
        val kek = await { f.kdf.kek(MasterPasswordBytes.of(password.toCharArray()), p.salt) }
        val vk = PendingWrap.open(f.aead, kek, p.nonce, p.ct, VAULT, p.vkId) ?: return false
        return vk.contentEquals(vault.vk(p.vkId))
    }

    // ---------------------------------------------------------------------------------------------
    // F10 step 4, and F11 from the lock screen

    @Test
    fun f10_step4_iCantRememberIt_thenThePin_opensTheApp_andIsTheProofOfF11_untilANewOneIsChosenTheBannerStands() {
        phone()
        val lastOk = f.stateRecord().practice.lastOkMs
        assertEquals(MpReason.PracticeDue, f.field.reason)
        assertEquals("past its grace: the master password is required", Practice(notNow = null), f.field.practice)

        f.cantRemember() // "I can't remember it"

        // F11 step 3 on the lock screen: the pad, an ordinary entry of this visit.
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = true, opening = false, aside = null), f.screen)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertNull("a PIN gives no master-password proof", (f.state as LockState.Unlocked).proof)
        // F10 step 4: inside the open app practice is still overdue, and the standing banner says so (§13.4 item 6).
        assertEquals(lastOk, f.engine.summary.value.practiceOverdueSinceMs)
        assertEquals("a PIN is not practice", lastOk, f.stateRecord().practice.lastOkMs)

        // Step 3: that entry is the proof; nothing is asked again.
        val checks = f.keys.hmacCalls.size
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove())
        assertEquals(checks, f.keys.hmacCalls.size)

        // Steps 4 and 5: the new master password, typed twice; the request is written.
        val asked = f.request(NEW_PASSWORD)
        assertEquals(RequestOutcome.Requested(EntryPolicy.PENDING_WAIT_MS), asked)
        val p = pending!!
        assertEquals(f.clock.wallMs(), p.requestedMs)
        assertEquals(Shortcut.PIN, p.by)
        assertEquals(0L, p.creditedMs)
        assertEquals(f.clock.elapsedMs(), p.seenElapsedMs)
        assertEquals(vault.newestVkId, p.vkId)
        assertEquals(16, p.salt.size)
        assertEquals(12, p.nonce.size)
        assertEquals(48, p.ct.size)
        assertTrue("the wrap opens with the password that was chosen, and gives the vault's key", pendingOpensWith(NEW_PASSWORD))
        assertFalse(pendingOpensWith(PASSWORD))
        assertEquals(
            LockSummary.Pending(p.requestedMs, Shortcut.PIN, creditedMs = 0, remainingMs = 24 * HOUR, ready = false),
            f.engine.summary.value.pending,
        )
        // A new one is chosen: the banner goes, and practice is not asked while it is pending (step 10).
        assertNull(f.engine.summary.value.practiceOverdueSinceMs)
        assertEquals("the lock asked storage for no write (I1)", emptyList<String>(), f.port.writingCalls)
        f.lock()
        assertTrue(f.screen is LockScreen.Pad)
        // The proof is used up: a second request needs it again.
        f.type(PIN)
        assertEquals(RequestOutcome.NotAllowed, f.request(THIRD_PASSWORD))
        assertTrue(pendingOpensWith(NEW_PASSWORD))
    }

    @Test
    fun f10_step4_aUserWhoLeavesF11WithoutChoosing_isInTheOpenApp_andPracticeIsStillOverdue() {
        phone()
        f.cantRemember()
        f.type(PIN)
        val since = f.engine.summary.value.practiceOverdueSinceMs
        assertNotNull(since)
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove())
        // They leave the flow ("Cancel" on the explanation): nothing is pending, and the banner stays.
        assertNull(pending)
        f.passTime(HOUR)
        assertEquals(since, f.engine.summary.value.practiceOverdueSinceMs)
        // Skipping was for that visit only: the next lock screen asks for the master password again.
        f.lock()
        assertEquals(Practice(notNow = null), f.field.practice)
        f.cantRemember()
        f.type(PIN)
        assertEquals(since, f.engine.summary.value.practiceOverdueSinceMs)
        // It goes when a master password has been typed.
        assertTrue(f.check(PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(f.engine.summary.value.practiceOverdueSinceMs)
    }

    @Test
    fun f11_step1_theFirstOptionOfTheForgotSheet_onAnyMasterPasswordScreen_goesTheSameWay() {
        phone(practice = practice(1)) // no practice due: the pad
        await { f.engine.useMasterPassword() } // "Forgot PIN? Use master password": the sheet is on this screen
        assertNull(f.field.practice)
        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it"))

        f.cantRemember() // "Use my PIN"

        assertEquals(LockScreen.Pad(0, null, fingerprintKey = true, opening = false, aside = null), f.screen)
        f.type(PIN)
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove())
        assertTrue(f.request() is RequestOutcome.Requested)
    }

    @Test
    fun f11_iCantRememberIt_changesNothing_whereTheRouteIsNotOffered() {
        // Neither a PIN nor a fingerprint: the master-password screen stays.
        phone(pin = false, fingerprint = false)
        val before = f.screen
        f.cantRemember()
        assertEquals(before, f.screen)
        assertFalse(f.engine.core.visit!!.practiceSkipped)
        // On the lock screen of a vault put aside.
        f.port.seedAside(OTHER_VAULT, bytes("the other one"))
        f.plantState(OTHER_VAULT)
        await { f.engine.showAside(OTHER_VAULT) }
        val aside = f.screen
        f.cantRemember()
        assertEquals(aside, f.screen)
        assertFalse(f.engine.core.visit!!.practiceSkipped)
        // In the open app.
        await { f.engine.goBack() }
        f.unlockWithMasterPassword()
        val open = f.state
        f.cantRemember()
        assertEquals(open, f.state)
    }

    @Test
    fun f11_theHookOfNotNowWithoutAStateRecord_andTheHookOfICantRememberIt_bothRunAfterTheUnlock() {
        phone(fingerprint = false, practice = null) // state.rec MISSING: due now and still skippable (§5.3)
        assertEquals(Practice(notNow = Shortcut.PIN), f.field.practice)
        await { f.engine.notNow() } // snoozes in the record that this visit's unlock makes
        await { f.engine.useMasterPassword() }
        f.cantRemember()

        f.type(PIN)

        val now = f.clock.wallMs()
        assertEquals("the snooze of \"Not now\"", StateRecord.Practice(now - DAY, 0, now + 20 * HOUR), f.stateRecord().practice)
        assertEquals("and the proof of \"I can't remember it\"", ProofOutcome.Proven(Shortcut.PIN), f.prove())
    }

    // ---------------------------------------------------------------------------------------------
    // T16: never with a fingerprint while a PIN slot for the current key exists

    @Test
    fun t16_aFingerprintThatOpensTheAppWhileAPinExists_provesNothing_thePinMustBeTypedNow() {
        phone()
        f.cantRemember()
        await { f.engine.promptIfDue() } // the prompt opens by itself on the pad, and a finger opens the app
        assertTrue(f.state is LockState.Unlocked)

        assertEquals(RequestOutcome.NotAllowed, f.request())
        assertNull(pending)
        val prompts = f.keys.prompts.size
        assertEquals("the PIN, whenever there is one: a fingerprint is not accepted in its place", ProofOutcome.NeedsPin, f.prove())
        assertEquals("no fingerprint prompt was shown for it", prompts, f.keys.prompts.size)
        assertEquals(RequestOutcome.NotAllowed, f.request())

        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN)) // a counted check in a prompt (§6.3)
        assertTrue(f.request() is RequestOutcome.Requested)
        assertEquals(Shortcut.PIN, pending!!.by)
    }

    @Test
    fun t16_anAppThatIsAlreadyOpenIsNotEnough() {
        phone(practice = practice(1))
        f.type(PIN) // an ordinary unlock, not after "I can't remember it"
        assertEquals(RequestOutcome.NotAllowed, f.request())
        assertEquals(ProofOutcome.NeedsPin, f.prove())
        assertNull(pending)
        // Nor is the master password's proof: who knows it changes it with F8.
        f.lock()
        f.unlockWithMasterPassword()
        assertEquals(RequestOutcome.NotAllowed, f.request())
        assertNull(pending)
    }

    @Test
    fun t16_inThePrompt_aWrongPinIsCountedAsAlways_theSameOneAgainIsNot_andTheRightOneProves() {
        phone(practice = practice(1))
        await { f.engine.promptIfDue() } // opened with the fingerprint
        val checks = f.keys.hmacCalls.size

        assertEquals(ProofOutcome.PinWrong(left = 4), f.prove(WRONG_PIN))
        assertEquals(PinRecord.Guard(charged = false, row = 1, total = 1), f.pinRecord().guard)
        assertEquals(ProofOutcome.SamePinAgain, f.prove(WRONG_PIN))
        assertEquals(checks + 1, f.keys.hmacCalls.size)
        assertEquals(RequestOutcome.NotAllowed, f.request())

        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        assertEquals("a right PIN in a prompt forgives nothing (§6.4)", PinRecord.Guard(false, 0, 1), f.pinRecord().guard)
        assertTrue(f.request() is RequestOutcome.Requested)
        // The wrong PIN is reported at the next unlock (§13.4 item 4).
        f.lock()
        f.type(PIN)
        assertEquals(1, f.notices.filterIsInstance<Notice.WrongTries>().single().wrongPins)
    }

    // ---------------------------------------------------------------------------------------------
    // T16: nor while the PIN cannot be checked

    @Test
    fun t16_whileThePinRecordCannotBeRead_theFlowStops_andNeverFallsBackToTheFingerprint() {
        vault = f.seedVault()
        f.plantPin(vault)
        f.plantFingerprint(vault)
        f.plantState(practice = practice(1))
        f.files.fail(FakeRecordFiles.Op.READ, times = -1) { it.endsWith("/pin.rec") } // UNREADABLE (§5.3)
        f.startInFront()
        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = PinReason.CANNOT_CHECK, aside = null), f.screen)
        await { f.engine.useMasterPassword() }
        f.cantRemember() // "Use my fingerprint" is what the sheet could offer; the engine decides what it proves
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        val prompts = f.keys.prompts.size

        assertEquals(RequestOutcome.NotAllowed, f.request())
        assertEquals("\"Your PIN can't be checked right now. Try again later.\"", ProofOutcome.CannotCheck, f.prove())
        assertEquals(ProofOutcome.CannotCheck, f.prove(PIN))
        assertEquals(prompts, f.keys.prompts.size)
        assertEquals(0, f.keys.hmacCalls.size)
        assertEquals(RequestOutcome.NotAllowed, f.request())
        assertNull(pending)
    }

    @Test
    fun t16_aPinTheKeystoreCannotCheckRightNow_stopsTheFlow_andTheFingerprintIsNotAskedInItsPlace() {
        phone(practice = practice(1))
        await { f.engine.promptIfDue() }
        val prompts = f.keys.prompts.size
        f.keys.scriptHmac(Ks.Temporary(null))

        assertEquals(ProofOutcome.CannotCheck, f.prove(PIN))

        assertEquals("the try was refunded", PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        assertNotNull(f.pinRecord().slot)
        assertEquals(ProofOutcome.NeedsPin, f.prove()) // still the PIN: never the fingerprint
        assertEquals(prompts, f.keys.prompts.size)
        assertEquals(RequestOutcome.NotAllowed, f.request())
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN)) // later it can be checked again
    }

    @Test
    fun t16_whenTheChargeCannotBeWritten_thePinCannotBeChecked_andFillingTheStorageIsNoWayRoundIt() {
        phone(practice = practice(1))
        await { f.engine.promptIfDue() }
        val prompts = f.keys.prompts.size
        val checks = f.keys.hmacCalls.size
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1) { it.endsWith("/pin.rec") } // the storage is full

        assertEquals(ProofOutcome.CannotCheck, f.prove(PIN))
        assertEquals(ProofOutcome.NeedsPin, f.prove())

        assertEquals(checks, f.keys.hmacCalls.size)
        assertEquals(prompts, f.keys.prompts.size)
        assertEquals(RequestOutcome.NotAllowed, f.request())
    }

    @Test
    fun t16_onTheLockScreen_aPinThatCannotBeCheckedHidesThePad_andTheFingerprintThatThenOpensTheAppProvesNothing() {
        phone()
        f.cantRemember()
        f.keys.scriptHmac(Ks.Temporary(null))
        f.type(PIN) // F15c: the PIN is not offered again on this visit
        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = PinReason.CANNOT_CHECK, aside = null), f.screen)
        await { f.engine.useFingerprint() }
        assertTrue(f.state is LockState.Unlocked)

        assertEquals(RequestOutcome.NotAllowed, f.request())
        assertEquals(ProofOutcome.NeedsPin, f.prove())
        assertNull(pending)
    }

    // ---------------------------------------------------------------------------------------------
    // A fingerprint, only when there is no PIN

    @Test
    fun f11_withoutAPin_theFingerprintThatOpensTheAppAfterICantRememberIt_isTheProof() {
        phone(pin = false)
        assertEquals(Practice(notNow = null), f.field.practice)

        f.cantRemember()

        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = null, aside = null), f.screen)
        await { f.engine.promptIfDue() }
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(ProofOutcome.Proven(Shortcut.FINGERPRINT), f.prove())
        assertEquals("nothing was asked again", 1, f.keys.prompts.size)
        assertTrue(f.request() is RequestOutcome.Requested)
        assertEquals(Shortcut.FINGERPRINT, pending!!.by)
        assertEquals(Shortcut.FINGERPRINT, f.engine.summary.value.pending!!.by)
    }

    @Test
    fun f11_withoutAPin_inTheOpenApp_thePromptConfirmWithYourFingerprint_isTheProof_onlyWhenItGivesTheVaultsKey() {
        phone(pin = false, practice = practice(1))
        await { f.engine.promptIfDue() }
        val alias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + f.fingerprintRecord().slot!!.id

        assertEquals(ProofOutcome.Proven(Shortcut.FINGERPRINT), f.prove())

        assertEquals(FakeKeys.PromptCall("open", alias, PromptUse.CONFIRM_REPLACEMENT, 2), f.keys.prompts.last())
        assertEquals(0, f.engine.core.outstandingLaunch)
        assertEquals("it is held: nothing is asked again", ProofOutcome.Proven(Shortcut.FINGERPRINT), f.prove())
        assertEquals(2, f.keys.prompts.size)
        assertTrue(f.request() is RequestOutcome.Requested)
        assertEquals(Shortcut.FINGERPRINT, pending!!.by)
    }

    @Test
    fun f11_theStatesOfThePinInWhichAFingerprintIsAccepted() {
        // MISSING is the test above. DAMAGED, NEWER, turned off at a limit, key gone, and a slot that the
        // owner's own change left without a wrap for the current key (F8 step 3, "Not now").
        val states: Map<String, (LockFixture, FakeVaultPort.FakeVault) -> Unit> = mapOf(
            "damaged" to { g, v ->
                g.plantPin(v)
                g.files.damage(g.dirOf(VAULT), RecordCodec.Pin.fileName) { it.also { b -> b[b.size - 1] = (b[b.size - 1] + 1).toByte() } }
            },
            "newer" to { g, v ->
                g.plantPin(v)
                g.files.damage(g.dirOf(VAULT), RecordCodec.Pin.fileName) { newerRecord(it) }
            },
            "turned off at a limit" to { g, _ ->
                g.putPin(PinRecord(VAULT, null, PinRecord.Guard(false, 5, 5), PinRecord.Off(PinRecord.OffReason.LIMIT, g.clock.wallMs()), PinRecord.Unseen(5, g.clock.wallMs(), false)))
            },
            "key gone" to { g, _ ->
                g.putPin(PinRecord(VAULT, null, PinRecord.Guard(false, 0, 0), PinRecord.Off(PinRecord.OffReason.KEY_GONE, g.clock.wallMs()), PinRecord.Unseen(0, null, false)))
            },
            "no wrap for the current key" to { g, v ->
                val old = v.newestVkId
                v.rekey(bytes("the words after a change"))
                g.plantPin(v, vkIds = listOf(old))
            },
        )
        for ((name, plant) in states) {
            val g = LockFixture()
            val v = g.seedVault()
            plant(g, v)
            g.plantFingerprint(v)
            g.plantState(practice = StateRecord.Practice(g.clock.wallMs(), 3, null), maxGeneration = StateRecord.MaxGeneration(v.live.first().number, g.clock.wallMs()))
            g.startInFront()
            assertTrue("$name: ${g.screen}", g.screen is LockScreen.FingerprintOnly)
            await { g.engine.promptIfDue() }
            assertTrue("$name: ${g.state}", g.state is LockState.Unlocked)

            assertEquals(name, ProofOutcome.Proven(Shortcut.FINGERPRINT), g.prove())
            assertEquals(name, PromptUse.CONFIRM_REPLACEMENT, g.keys.prompts.last().use)
            assertEquals("$name: a PIN typed where none can be the proof", ProofOutcome.Proven(Shortcut.FINGERPRINT), g.prove(PIN))
            assertTrue(name, g.request() is RequestOutcome.Requested)
            assertEquals(name, Shortcut.FINGERPRINT, g.stateRecord().pendingMp!!.by)
            assertEquals("$name: no PIN was checked", 0, g.keys.hmacCalls.size)
        }
    }

    @Test
    fun f11_aLimitInTheProofPrompt_turnsThePinOff_andTheNextStepIsTheFingerprint() {
        phone(practice = practice(1))
        await { f.engine.promptIfDue() }
        val alias = RecordCodec.PIN_ALIAS_PREFIX + f.pinRecord().slot!!.id
        // "Forgot your PIN as well? After 5 wrong tries the PIN is turned off, and you can then continue with your fingerprint."
        for ((i, wrong) in listOf("000001", "000002", "000003", "000004").withIndex()) {
            assertEquals(ProofOutcome.PinWrong(left = 4 - i), f.prove(wrong))
        }

        assertEquals(ProofOutcome.PinGone, f.prove("000005"))

        assertNull(f.pinRecord().slot)
        assertEquals(PinRecord.OffReason.LIMIT, f.pinRecord().off?.reason)
        assertTrue(alias in f.keys.deleted)
        assertTrue("the app stays open (§6.5 step 3)", f.state is LockState.Unlocked)
        assertEquals(RequestOutcome.NotAllowed, f.request())
        assertEquals("a PIN typed now cannot be the proof any more", ProofOutcome.PinGone, f.prove(PIN))
        assertEquals(ProofOutcome.Proven(Shortcut.FINGERPRINT), f.prove())
        assertTrue(f.request() is RequestOutcome.Requested)
        assertEquals(Shortcut.FINGERPRINT, pending!!.by)
        // The owner is told about the wrong tries at the next unlock (§13.4 item 4).
        assertEquals(5, f.pinRecord().unseen.count)
    }

    @Test
    fun f11_onTheLockScreen_aLimitAfterICantRememberIt_leavesTheFingerprint_whichIsThenTheProof() {
        phone()
        f.cantRemember()
        for (wrong in listOf("000001", "000002", "000003", "000004", "000005")) f.type(wrong)
        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = PinReason.LIMIT, aside = null), f.screen)
        await { f.engine.useFingerprint() }
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(ProofOutcome.Proven(Shortcut.FINGERPRINT), f.prove())
        assertEquals("the entry that opened the app was the proof", 1, f.keys.prompts.size)
        assertTrue(f.request() is RequestOutcome.Requested)
    }

    @Test
    fun f11_theFingerprintPrompt_closed_failed_orNotShown_provesNothing_andRemovesNothing() {
        val cases: List<Pair<FakeKeys.Prompt, ProofOutcome>> = listOf(
            FakeKeys.Prompt.Close(13) to ProofOutcome.Cancelled, // "Cancel"
            FakeKeys.Prompt.Close(10) to ProofOutcome.Cancelled,
            FakeKeys.Prompt.Error(7) to ProofOutcome.FingerprintFailed(FingerprintLine.Locked),
            FakeKeys.Prompt.SucceededButFailed to ProofOutcome.FingerprintFailed(FingerprintLine.DidNotOpen),
            FakeKeys.Prompt.NotShown to ProofOutcome.NotShown,
        )
        phone(pin = false, practice = practice(1))
        await { f.engine.promptIfDue() }
        val slot = f.fingerprintRecord().slot
        for ((prompt, expected) in cases) {
            f.keys.scriptPrompt(prompt)
            assertEquals("$prompt", expected, f.prove())
            assertEquals("$prompt", RequestOutcome.NotAllowed, f.request())
            assertEquals("$prompt", slot, f.fingerprintRecord().slot)
            assertEquals("$prompt: no launch is left outstanding", 0, f.engine.core.outstandingLaunch)
        }
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertEquals(ProofOutcome.Proven(Shortcut.FINGERPRINT), f.prove())
    }

    @Test
    fun f11_aFingerprintKeyThatIsGone_removesTheSlot_andTheRouteIsNotOffered() {
        phone(pin = false, practice = practice(1))
        await { f.engine.promptIfDue() }
        val alias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + f.fingerprintRecord().slot!!.id
        f.keys.invalidateFingerprintKeys() // a finger was added (F15a)

        assertEquals(ProofOutcome.NotOffered, f.prove())

        assertEquals(1, f.keys.prompts.size) // the probe found it: no prompt
        assertNull(f.fingerprintRecord().slot)
        assertEquals(FingerprintRecord.OffReason.KEY_GONE, f.fingerprintRecord().off?.reason)
        assertEquals(listOf(alias), f.keys.deleted)
        assertEquals(LockSummary.Fingerprint.Off, f.engine.summary.value.fingerprint)
        assertEquals(ProofOutcome.NotOffered, f.prove())
        assertEquals(RequestOutcome.NotAllowed, f.request())
    }

    @Test
    fun f11_whileThePhoneSaysFingerprintCannotBeUsed_itIsNotOffered_andTheKeyIsNotTouched() {
        phone(pin = false, practice = practice(1))
        await { f.engine.promptIfDue() }
        val slot = f.fingerprintRecord().slot
        f.keys.availability = 11
        assertEquals(ProofOutcome.NotOffered, f.prove())
        assertEquals(1, f.keys.prompts.size)
        assertEquals(slot, f.fingerprintRecord().slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
        // A probe that cannot be made right now is TEMPORARY: nothing is removed.
        f.keys.availability = 0
        f.keys.scriptProbe(Ks.Temporary(null))
        assertEquals(ProofOutcome.FingerprintFailed(FingerprintLine.CannotCheck), f.prove())
        assertEquals(slot, f.fingerprintRecord().slot)
    }

    @Test
    fun f11_aPinSetWhileTheFingerprintPromptIsOpen_meansThePinIsTheProof() {
        phone(pin = false, practice = practice(1))
        val master = f.unlockWithMasterPassword() // the owner is at the phone, and sets a PIN meanwhile
        f.answerAll()
        f.keys.holdPrompts = true
        val outcome = runBlocking {
            var result: ProofOutcome? = null
            val prove = launch(start = CoroutineStart.UNDISPATCHED) { result = f.engine.proveForNewMasterPassword(null) }
            while (f.keys.openPrompt == null) yield()
            assertEquals(PromptUse.CONFIRM_REPLACEMENT, f.keys.openPrompt!!.use)
            f.engine.setPin(LockFixture.digits(PIN), master)
            f.keys.answer(FakeKeys.Prompt.Touch)
            prove.join()
            result
        }
        assertEquals(ProofOutcome.NeedsPin, outcome)
        assertEquals(RequestOutcome.NotAllowed, f.request())
    }

    @Test
    fun f11_neitherAPinNorAFingerprint_theRouteIsNotOffered() {
        phone(pin = false, fingerprint = false, practice = practice(1))
        f.unlockWithMasterPassword()
        assertEquals(ProofOutcome.NotOffered, f.prove())
        assertEquals(ProofOutcome.PinGone, f.prove(PIN))
        assertEquals(RequestOutcome.NotAllowed, f.request())
        f.lock()
        assertEquals("in a locked app", ProofOutcome.NotOffered, f.prove())
    }

    // ---------------------------------------------------------------------------------------------
    // The proof is in memory only, for this flow (§5.8)

    @Test
    fun f11_theProofIsDroppedAtALock_atOnStop_andByTheRequestItWasMadeFor() {
        phone(practice = practice(1))
        f.type(PIN)
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        f.leaveAndReturn() // ON_STOP
        assertEquals(RequestOutcome.NotAllowed, f.request())

        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        f.lock()
        f.type(PIN)
        assertEquals(RequestOutcome.NotAllowed, f.request())

        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        assertTrue(f.request() is RequestOutcome.Requested)
        assertEquals(RequestOutcome.NotAllowed, f.request(THIRD_PASSWORD))
        assertTrue(pendingOpensWith(NEW_PASSWORD))
    }

    @Test
    fun f11_aProofFromTheLockScreen_goesWithALockToo_andASecondVisitWithoutICantRememberItGivesNone() {
        phone()
        f.cantRemember()
        f.type(PIN)
        f.lock()
        // Practice is asked again; the user takes the sheet's first option once more, but opens with the master password.
        f.cantRemember()
        await { f.engine.useMasterPassword() }
        f.unlockWithMasterPassword()
        assertEquals("nothing is forgotten: no proof", RequestOutcome.NotAllowed, f.request())
        assertEquals(ProofOutcome.NeedsPin, f.prove())
    }

    // ---------------------------------------------------------------------------------------------
    // Steps 4 and 5: the request

    private fun proven() {
        phone(practice = practice(1))
        f.type(PIN)
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
    }

    @Test
    fun f11_step4_theTwoTypingsMustBeTheSame_andHoldNoUnassignedCodePoint_beforeAnythingIsDerived_andTheProofStands() {
        proven()
        val derivations = f.kdf.derivations
        assertEquals(RequestOutcome.Mismatch, f.request(NEW_PASSWORD, again = "$NEW_PASSWORD!"))
        assertEquals(RequestOutcome.Unassigned(0xFFFF), f.request("new \uFFFF words"))
        assertEquals(derivations, f.kdf.derivations)
        assertNull(pending)
        assertTrue(f.request() is RequestOutcome.Requested)
    }

    @Test
    fun f11_step5_notEnoughMemory_orARecordThatCannotBeWritten_nothingIsPending_andTheProofStands() {
        proven()
        f.kdf.unavailable = true
        assertEquals(RequestOutcome.NoMemory, f.request())
        assertNull(pending)
        f.kdf.unavailable = false

        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1) { it.endsWith("/state.rec") }
        assertEquals(RequestOutcome.NotSaved, f.request())
        assertNull("nothing is pending on disk", pending)
        assertNull("nor in memory", f.engine.summary.value.pending)

        assertTrue("the same proof: the flow goes on", f.request() is RequestOutcome.Requested)
        assertTrue(pendingOpensWith(NEW_PASSWORD))
    }

    @Test
    fun f11_step5_withoutAStateRecordThatCanHoldIt_nothingIsPending() {
        vault = f.seedVault()
        f.plantPin(vault)
        f.plantState(practice = practice(1))
        f.files.fail(FakeRecordFiles.Op.READ, times = -1) { it.endsWith("/state.rec") } // UNREADABLE
        f.startInFront()
        f.type(PIN)
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        assertEquals(RequestOutcome.NotSaved, f.request())
        assertNull(f.engine.summary.value.pending)
    }

    @Test
    fun f11_step5_anEarlierRequestIsReplaced_andItsWaitStartsAgain() {
        proven()
        assertTrue(f.request(NEW_PASSWORD) is RequestOutcome.Requested)
        val first = pending!!
        f.passTime(10 * HOUR)
        assertEquals(10 * HOUR, f.engine.summary.value.pending!!.creditedMs)
        f.lock()
        f.type(PIN)
        assertEquals(1, f.notices.filterIsInstance<Notice.Pending>().size)
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))

        assertEquals(RequestOutcome.Requested(24 * HOUR), f.request(THIRD_PASSWORD))

        val second = pending!!
        assertEquals(0L, second.creditedMs)
        assertEquals(f.clock.wallMs(), second.requestedMs)
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertTrue(pendingOpensWith(THIRD_PASSWORD))
        assertFalse(pendingOpensWith(NEW_PASSWORD))
        assertEquals(24 * HOUR, f.engine.summary.value.pending!!.remainingMs)
        assertEquals("the notice of the earlier request is gone", 0, f.notices.count { it is Notice.Pending || it is Notice.PendingReady })
    }

    @Test
    fun f11_step5_aLockOrAnOnStopWhileTheRequestIsMade_dropsTheProof_andNothingIsWritten() {
        for (lock in listOf(true, false)) {
            val g = LockFixture()
            val v = g.seedVault()
            g.plantPin(v)
            g.plantState()
            g.startInFront()
            g.type(PIN)
            assertEquals(ProofOutcome.Proven(Shortcut.PIN), g.prove(PIN))
            var meanwhile: Job? = null
            val outcome = runBlocking {
                g.port.beforeCall = { call ->
                    // While the request works on the vault's key, the app locks, or stops being visible.
                    if (call == "withVaultKey" && meanwhile == null) {
                        meanwhile = launch(start = CoroutineStart.UNDISPATCHED) { if (lock) g.engine.lock(LockCause.NO_TOUCH) else g.engine.appStopped() }
                    }
                }
                val result = g.engine.requestNewMasterPassword(NEW_PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray())
                meanwhile!!.join()
                result
            }
            g.port.beforeCall = null
            assertEquals("lock = $lock", RequestOutcome.Cancelled, outcome)
            assertNull("lock = $lock", g.stateRecord().pendingMp)
            assertEquals("lock = $lock", lock, g.state is LockState.Locked)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Waiting (steps 7 to 12)

    @Test
    fun t16_itIsCancelledByTheButton_withoutAnyProof() {
        proven()
        assertTrue(f.request() is RequestOutcome.Requested)
        f.lock()
        await { f.engine.promptIfDue() } // whoever opens the app in that time sees the banner
        f.type(PIN)
        val banner = f.notices.filterIsInstance<Notice.Pending>().single()
        assertEquals(Notice.Pending(banner.id, pending!!.requestedMs, Shortcut.PIN, f.engine.summary.value.pending!!.remainingMs), banner)

        await { f.engine.answerNotice(banner.id, NoticeAnswer.CANCEL_IT) } // "Cancel it"

        assertNull(pending)
        assertNull(f.engine.summary.value.pending)
        // And from Settings: `cancelPending`, in an app that a fingerprint opened.
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        assertTrue(f.request() is RequestOutcome.Requested)
        await { f.engine.cancelPending() }
        assertNull(pending)
        assertEquals(0, f.notices.count { it is Notice.Pending || it is Notice.PendingReady })
        await { f.engine.cancelPending() } // nothing is pending: nothing
        assertNull(pending)
    }

    @Test
    fun t16_itIsCancelledByACorrectMasterPassword_typedAnywhere() {
        // In a prompt of the open app.
        proven()
        assertTrue(f.request() is RequestOutcome.Requested)
        assertTrue(f.check(PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(pending)
        assertEquals(1, f.notices.filterIsInstance<Notice.PendingCancelled>().size)
        // On the lock screen.
        f.answerAll()
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        assertTrue(f.request() is RequestOutcome.Requested)
        f.lock()
        await { f.engine.useMasterPassword() }
        assertEquals("not ready: the field says when", 24 * HOUR, f.field.pendingReadyInMs)
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertNull(pending)
        assertTrue(f.notices.first() is Notice.PendingCancelled)
        // Where "Finish now" asks for the new one.
        f.answerAll()
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        assertTrue(f.request() is RequestOutcome.Requested)
        f.passTime(25 * HOUR)
        val outcome = f.finish(PASSWORD)
        assertTrue("$outcome", outcome is MasterPasswordOutcome.Right)
        assertEquals("the proof is the current master password's, for this vault", VAULT, (outcome as MasterPasswordOutcome.Right).proof.vaultId)
        assertNull(pending)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(1, f.notices.filterIsInstance<Notice.PendingCancelled>().size)
    }

    @Test
    fun t16_itCannotFinishBefore24HoursAreCredited_underKillsRebootsAndDates() {
        proven()
        assertTrue(f.request() is RequestOutcome.Requested)
        fun credited() = f.engine.summary.value.pending!!.creditedMs

        /**
         * Not ready: the new password is not tried before its time (step 12), neither by the lock
         * screen's field, where it is a wrong master password like any other, nor by "Finish now".
         */
        fun cannotFinish(at: String, typeIt: Boolean = true) {
            assertFalse(at, f.engine.summary.value.pending!!.ready)
            if (f.state is LockState.Locked) {
                if (f.screen !is LockScreen.MasterPassword) await { f.engine.useMasterPassword() }
                assertEquals(at, f.engine.summary.value.pending!!.remainingMs, f.field.pendingReadyInMs)
                if (typeIt) assertEquals(at, MasterPasswordOutcome.Wrong, f.unlockWith(NEW_PASSWORD))
                assertTrue(at, f.state is LockState.Locked)
            } else {
                assertEquals(at, MasterPasswordOutcome.NotFinished, f.finish(NEW_PASSWORD))
            }
            assertEquals(at, emptyList<String>(), f.port.writingCalls)
            assertEquals(at, VAULT, f.port.current!!.vaultId)
            assertTrue(at, pendingOpensWith(NEW_PASSWORD))
        }

        f.passTime(10 * HOUR)
        assertEquals(10 * HOUR, credited())
        cannotFinish("after 10 hours")

        // The process is killed; 5 hours later the app is opened again. On one boot the credit is exact.
        f.restart()
        f.clock.advance(5 * HOUR)
        f.startInFront()
        assertEquals(15 * HOUR, credited())
        cannotFinish("after a kill")

        // The date is set a month ahead: nothing.
        f.clock.jumpWall(30 * DAY)
        await { f.engine.refresh() }
        assertEquals(15 * HOUR, credited())
        cannotFinish("after the date was set forward", typeIt = false)

        // The phone is switched off for 8 hours (nothing is credited) and has been on again for 2 hours.
        f.files.reboot()
        f.clock.reboot(offMs = 8 * HOUR, uptimeMs = 2 * HOUR)
        f.restart()
        f.startInFront()
        assertEquals(17 * HOUR, credited())
        cannotFinish("after a reboot")

        // A reboot without the app being looked at until the uptime is past the last value seen: only the difference is credited.
        f.clock.advance(HOUR) // seen at 3 hours of uptime: 18 hours
        await { f.engine.refresh() }
        assertEquals(18 * HOUR, credited())
        f.files.reboot()
        f.clock.reboot(offMs = 0, uptimeMs = 5 * HOUR)
        f.restart()
        f.startInFront()
        assertEquals("5 hours of new uptime, of which 2 are credited", 20 * HOUR, credited())
        cannotFinish("after a second reboot")

        // A second short of the 24 hours.
        f.clock.advance(4 * HOUR - 1_000 - (credited() - 20 * HOUR))
        await { f.engine.refresh() }
        assertEquals(24 * HOUR - 1_000, credited())
        cannotFinish("a second before")

        f.passTime(1_000)
        assertTrue(f.engine.summary.value.pending!!.ready)
        assertEquals(0L, f.engine.summary.value.pending!!.remainingMs)
        assertNull("ready: the field no longer says when", f.field.pendingReadyInMs)
    }

    @Test
    fun f11_step11_beforeTheLastPinTry_theWarningSaysWhatIsAtStake() {
        phone(practice = practice(1))
        f.type(PIN)
        assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN))
        assertTrue(f.request() is RequestOutcome.Requested)
        f.lock()
        for (wrong in listOf("000001", "000002", "000003")) f.type(wrong)
        assertEquals(PadStatus.WrongPin(2), f.pad.status)
        f.type("000004")
        assertEquals(PadStatus.LastTryWhilePending, f.pad.status)
    }
}
