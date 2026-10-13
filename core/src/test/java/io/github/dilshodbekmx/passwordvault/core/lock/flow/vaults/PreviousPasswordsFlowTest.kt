package io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of lock.md **F17**, start with an empty app and previous passwords, steps 1, 2, 4,
 * 6 and 7 (build plan 3.14; step 3 is in `RestoreFlowTest`, step 5 in `OldPasswordsFlowTest`):
 * "Start with an empty app" only when no shortcut is usable, with the lock doing nothing to the
 * vault's records and keys; a vault put aside shows its own lock screen with its own records, five
 * tries, fingerprint and master-password guard; the vaults change places only once it has opened;
 * Back changes nothing; vaults put aside are never removed automatically.
 */
class PreviousPasswordsFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private val f = LockFixture()

    private val lockedOn = 1_790_500_000_000L

    /** The current vault with its PIN; [OTHER_VAULT] put aside at [lockedOn] with its own PIN, and its fingerprint when [otherFingerprint]. */
    private class Phone(val pin: ShortcutSlot, val otherPin: ShortcutSlot?, val otherFingerprint: ShortcutSlot?)

    private fun twoVaults(otherPin: Boolean = true, otherFingerprint: Boolean = false, otherState: StateRecord? = null): Phone {
        val vault = f.seedVault()
        val pin = f.plantPin(vault)
        f.plantState()
        val old = f.port.seedAside(OTHER_VAULT, bytes(Secrets.OTHER), putAsideAtMs = lockedOn)
        val oldPin = if (otherPin) f.plantPin(old, pin = Secrets.OTHER_PIN) else null
        val oldFinger = if (otherFingerprint) f.plantFingerprint(old) else null
        if (otherState != null) f.putState(otherState) else f.plantState(OTHER_VAULT)
        return Phone(pin, oldPin, oldFinger)
    }

    // ---- step 2: start with an empty app ----------------------------------------------------------

    @Test
    fun f17step2_startEmpty_isRefusedWhileAShortcutIsUsable() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        f.startInFront()
        await { f.engine.useMasterPassword() } // the sheet is on this screen; the PIN would open the vault now
        assertFalse(await { f.engine.startEmpty() })
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertTrue(f.screen is LockScreen.MasterPassword)

        // Only a fingerprint that is usable: the same.
        f.restart()
        f.putPin(f.pinRecord().copy(slot = null, off = PinRecord.Off(PinRecord.OffReason.LIMIT, f.clock.wallMs())))
        f.plantFingerprint(vault)
        f.startInFront()
        assertTrue(f.screen is LockScreen.FingerprintOnly)
        await { f.engine.useMasterPassword() }
        assertFalse(await { f.engine.startEmpty() })
        assertEquals(emptyList<String>(), f.port.writingCalls)

        // In the open app, and on Welcome, there is nothing to put aside from here.
        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertFalse(await { f.engine.startEmpty() })
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f17step2_startEmpty_putsTheVaultAside_theLockDoesNothingElse_andWelcomeFollows() {
        f.seedVault(generations = 2)
        f.plantState()
        f.startInFront()
        assertTrue(f.screen is LockScreen.MasterPassword) // no PIN and no fingerprint: no shortcut is usable now
        val record = f.files.bytes(f.dirOf(VAULT), "state.rec")!!.toList()
        val generations = filesOf(f, VAULT)

        assertTrue(await { f.engine.startEmpty() })

        assertEquals(listOf("putAside"), f.port.writingCalls)
        assertEquals(listOf(VAULT), (f.state as LockState.NoVault).aside.map { it.vaultId })
        assertEquals(generations, filesOf(f, VAULT))
        // lock/<vault_id>/ stays where it is, as it was.
        assertEquals(listOf("state.rec"), f.files.names(f.dirOf(VAULT)))
        assertEquals(record, f.files.bytes(f.dirOf(VAULT), "state.rec")!!.toList())
        assertEquals(emptyList<String>(), f.keys.deleted)

        // Setup makes a new vault with its own vault_id and its own lock directory.
        val chars = "a brand new password for an empty app".toCharArray()
        val created = await { f.engine.createVault(chars, chars.copyOf()) } as CreateOutcome.Created
        assertFalse(created.vaultId == VAULT)
        assertTrue(f.files.hasDir(f.dirOf(created.vaultId)))
        assertEquals(listOf(VAULT), f.port.asideVaults.map { it.vaultId })

        // The old passwords open again from their own lock screen, with their own master password.
        f.answerNotices()
        f.lock()
        await { f.engine.showAside(VAULT) }
        val field = f.field
        assertEquals(AsideLine.LockedOn(f.port.vault(VAULT)!!.putAsideAtMs!!), field.aside)
        assertNull(field.practice)
        val opened = f.unlockWith(PASSWORD) as MasterPasswordOutcome.Right
        assertEquals(VAULT, opened.proof.vaultId)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(listOf(created.vaultId), f.port.asideVaults.map { it.vaultId })
    }

    @Test
    fun f17step2_aVaultPutAsideWhileItsPinCouldNotBeChecked_opensWithItsPinWhenItCan() {
        val vault = f.seedVault()
        val slot = f.plantPin(vault)
        f.plantState()
        f.startInFront()
        f.keys.scriptHmac(Ks.Temporary(null)) // the Keystore does not answer now (F15c)
        f.type(PIN)
        assertEquals(MpReason.Pin(PinReason.CANNOT_CHECK), f.field.reason)

        assertTrue(await { f.engine.startEmpty() }) // no shortcut is usable now

        // Its PIN slot and key stay with it (decision 21, I9).
        assertEquals(slot, f.pinRecord().slot)
        assertTrue(PinWrap.alias(slot.id) in f.keys.aliases)
        f.restart() // a restart, and the Keystore answers again
        f.startInFront()
        assertEquals(listOf(VAULT), (f.state as LockState.NoVault).aside.map { it.vaultId })
        await { f.engine.showAside(VAULT) }
        assertTrue(f.screen is LockScreen.Pad)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(listOf("putAside", "swapIn $VAULT"), f.port.writingCalls)
    }

    // ---- step 4: open my previous passwords ---------------------------------------------------------

    @Test
    fun f17step4_itsOwnPadAndItsOwnFiveTries_andTheVaultsChangePlacesOnlyOnceItHasOpened() {
        val phone = twoVaults()
        f.startInFront()
        assertEquals(listOf(OTHER_VAULT), f.engine.summary.value.aside.map { it.vaultId })

        await { f.engine.showAside(OTHER_VAULT) }
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = AsideLine.LockedOn(lockedOn)), f.screen)

        // F17 step 7: wrong PINs count on that vault's own record, never on the current vault's.
        f.type(WRONG_PIN)
        assertEquals(PadStatus.WrongPin(4), f.pad.status)
        assertEquals(PinRecord.Guard(false, 1, 1), f.pinRecord(OTHER_VAULT).guard)
        f.type(PIN) // the current vault's PIN does not open it
        assertEquals(PadStatus.WrongPin(3), f.pad.status)
        assertEquals(PinRecord.Guard(false, 2, 2), f.pinRecord(OTHER_VAULT).guard)
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord(VAULT).guard)
        assertEquals(emptyList<String>(), f.port.writingCalls) // nothing has moved

        f.type(Secrets.OTHER_PIN)

        assertTrue(f.state is LockState.Unlocked)
        assertEquals(listOf("swapIn $OTHER_VAULT"), f.port.writingCalls)
        assertTrue(f.port.calls.indexOf("open aside $OTHER_VAULT") < f.port.calls.indexOf("swapIn $OTHER_VAULT"))
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId)
        assertEquals(OTHER_VAULT, f.port.openVaultId)
        assertEquals(listOf(VAULT), f.port.asideVaults.map { it.vaultId })
        // Its right PIN forgave the two slips of this visit (§6.3), so nothing is left to report.
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord(OTHER_VAULT).guard)
        assertEquals(0, f.pinRecord(OTHER_VAULT).unseen.count)
        assertEquals(emptyList<Notice>(), f.notices)
        // The vault that went aside keeps its records and its key.
        assertEquals(phone.pin, f.pinRecord(VAULT).slot)
        assertTrue(PinWrap.alias(phone.pin.id) in f.keys.aliases)
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertEquals(listOf(VAULT), f.engine.summary.value.aside.map { it.vaultId })

        // Locked again: the vault that is current now has the plain pad; the other one is put aside.
        f.lock()
        assertNull(f.pad.aside)
        f.type(Secrets.OTHER_PIN)
        assertEquals(OTHER_VAULT, f.port.openVaultId)
        assertEquals(listOf("swapIn $OTHER_VAULT"), f.port.writingCalls)
    }

    @Test
    fun f17step4_back_returnsToTheCurrentVaultAndChangesNothing() {
        twoVaults()
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        f.type("90") // a charge on that vault's record
        assertTrue(f.pinRecord(OTHER_VAULT).guard.charged)

        await { f.engine.goBack() }

        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord(OTHER_VAULT).guard) // refunded (§6.3 A), nothing counted
        assertEquals(emptyList<String>(), f.port.writes)
        assertEquals(listOf(OTHER_VAULT), f.port.asideVaults.map { it.vaultId })
        f.type(PIN)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(listOf(OTHER_VAULT), f.port.asideVaults.map { it.vaultId })

        // Back where there is nothing put aside on screen does nothing.
        f.lock()
        await { f.engine.goBack() }
        assertTrue(f.screen is LockScreen.Pad)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f17step4_itsOwnFingerprintOpensIt() {
        val phone = twoVaults(otherFingerprint = true)
        // Wrong PINs counted on that vault from an earlier visit.
        f.putPin(f.pinRecord(OTHER_VAULT).copy(guard = PinRecord.Guard(false, 2, 3)))
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        assertTrue(f.pad.fingerprintKey)

        await { f.engine.useFingerprint() }

        val prompt = f.keys.prompts.single()
        assertEquals(RecordCodec.FINGERPRINT_ALIAS_PREFIX + phone.otherFingerprint!!.id, prompt.alias)
        assertEquals(PromptUse.UNLOCK, prompt.use)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId)
        assertEquals(listOf("swapIn $OTHER_VAULT"), f.port.writingCalls)
        assertNull((f.state as LockState.Unlocked).proof)
        // A fingerprint unlock resets no count (§6.4, F3 step 5), also when it makes a vault put aside current.
        assertEquals(PinRecord.Guard(false, 2, 3), f.pinRecord(OTHER_VAULT).guard)
    }

    @Test
    fun f17step4_itsOwnMasterPasswordOpensIt_underItsOwnGuard_andTheProofIsItsOwn() {
        twoVaults()
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        await { f.engine.useMasterPassword() }
        val field = f.field
        assertEquals(AsideLine.LockedOn(lockedOn), field.aside)
        assertNull(field.reason)
        assertNull(field.practice)

        assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith(PASSWORD)) // the current vault's does not open it
        assertEquals(1, f.stateRecord(OTHER_VAULT).mpGuard.failures)
        assertEquals(0, f.stateRecord(VAULT).mpGuard.failures)
        assertEquals(emptyList<String>(), f.port.calls.filter { it == "openWithPassword current" })

        val right = f.unlockWith(Secrets.OTHER) as MasterPasswordOutcome.Right
        assertEquals(OTHER_VAULT, right.proof.vaultId)
        assertTrue(f.engine.core.holds(right.proof))
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId)
        assertEquals(0, f.stateRecord(OTHER_VAULT).mpGuard.failures)
        assertEquals(listOf(VAULT), f.port.asideVaults.map { it.vaultId })
    }

    @Test
    fun f17step4_aPendingPasswordOfThatVault_isCancelledByItsMasterPassword_andTheNoticeSaysSo() {
        val pending = StateRecord.PendingMp(
            f.clock.wallMs(), Shortcut.PIN, 0L, f.clock.elapsedMs(), "00000000-0000-4000-8000-0000000000e1",
            ByteArray(16) { 1 }, ByteArray(12) { 2 }, ByteArray(48) { 3 },
        )
        val withPending = StateRecord(
            OTHER_VAULT, StateRecord.Practice(f.clock.wallMs(), 3, null), StateRecord.MpGuard(0, null, 0), null, pending, null,
            StateRecord.MaxGeneration(1, f.clock.wallMs()),
        )
        twoVaults(otherState = withPending)
        f.startInFront()

        // Opened with its PIN: the request stands, and the notice of §13.4 item 2 follows the swap.
        await { f.engine.showAside(OTHER_VAULT) }
        f.type(Secrets.OTHER_PIN)
        assertEquals(1, f.notices.filterIsInstance<Notice.Pending>().size)
        assertNotNull(f.stateRecord(OTHER_VAULT).pendingMp)

        // Back aside, and opened with its own master password: that cancels it (F11 step 9), and says so.
        f.answerNotices()
        f.lock()
        await { f.engine.showAside(VAULT) }
        f.type(PIN)
        f.answerNotices()
        f.lock()
        await { f.engine.showAside(OTHER_VAULT) }
        await { f.engine.useMasterPassword() }
        assertTrue(f.unlockWith(Secrets.OTHER) is MasterPasswordOutcome.Right)
        assertNull(f.stateRecord(OTHER_VAULT).pendingMp)
        assertEquals(1, f.notices.filterIsInstance<Notice.PendingCancelled>().size)
        assertEquals(0, f.notices.filterIsInstance<Notice.Pending>().size)
    }

    @Test
    fun f17step4_withoutAWorkingShortcut_itsMasterPasswordField_noPractice_thenChooseAPin() {
        val overdue = StateRecord(
            OTHER_VAULT, StateRecord.Practice(f.clock.wallMs() - 60 * PracticeSchedule.DAY_MS, 0, null),
            StateRecord.MpGuard(0, null, 0), null, null, null, StateRecord.MaxGeneration(1, f.clock.wallMs()),
        )
        twoVaults(otherPin = false, otherState = overdue)
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        val field = f.field
        assertNull(field.practice) // practice is not asked there
        assertNull(field.reason) // no PIN was ever set for it
        assertEquals(AsideLine.LockedOn(lockedOn), field.aside)
        assertFalse(field.canUsePin)

        assertTrue(f.unlockWith(Secrets.OTHER) is MasterPasswordOutcome.Right)

        // It opened without a working PIN, so the app offers to choose one (ux.md §5.1).
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.NONE_HERE, offer.reason)
        assertFalse(offer.needsMasterPassword)
    }

    @Test
    fun f17step4_aVaultMarkedReplaced_offersItsMasterPasswordOnly_withItsDate() {
        val at = f.clock.wallMs() - 2 * PracticeSchedule.DAY_MS
        val marked = StateRecord(
            OTHER_VAULT, StateRecord.Practice(f.clock.wallMs(), 3, null), StateRecord.MpGuard(0, null, 0), null, null,
            StateRecord.Replaced(VAULT, at, Shortcut.PIN, 0L, f.clock.elapsedMs()), StateRecord.MaxGeneration(1, f.clock.wallMs()),
        )
        twoVaults(otherFingerprint = true, otherState = marked)
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        val field = f.field
        assertEquals(AsideLine.FromBefore(at), field.aside)
        assertEquals(MpReason.FromBefore(at), field.reason)
        assertFalse(field.canUsePin)
        assertNull(field.fingerprintLine)
        await { f.engine.goBack() }
        assertNull(f.pad.aside)
    }

    @Test
    fun f17step4_whenTheSwapFails_theVaultOpenedInPlaceIsDropped_andItsScreenStays() {
        twoVaults()
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        f.port.failWrites(FakeVaultPort.WriteFault.REFUSED)

        f.type(Secrets.OTHER_PIN)

        assertTrue(f.state is LockState.Locked)
        assertEquals(AsideLine.LockedOn(lockedOn), f.pad.aside)
        assertNull(f.port.openAsideId)
        assertNull(f.port.openVaultId)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord(OTHER_VAULT).guard) // the right PIN's record is written all the same

        f.type(Secrets.OTHER_PIN) // trying again works
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId)
    }

    @Test
    fun f17step4_theVisitEndsWhileTheVaultsChangePlaces_theSwapStands_andNothingOpens() {
        twoVaults()
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        f.port.beforeCall = { call -> if (call == "swapIn") f.engine.core.endVisitNow() } // the screen goes off now

        f.type(Secrets.OTHER_PIN)

        assertTrue(f.state is LockState.Locked) // I11
        assertNull(f.port.openVaultId)
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId) // the swap is on disk and stands
        assertNull(f.pad.aside) // its own pad, as the current vault's
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord(OTHER_VAULT).guard)
        f.port.beforeCall = null
        f.type(Secrets.OTHER_PIN)
        assertEquals(OTHER_VAULT, f.port.openVaultId)
    }

    @Test
    fun f17step4_fromTheOpenApp_theOpenVaultIsLockedFirst() {
        twoVaults()
        f.startInFront()
        await { f.engine.useMasterPassword() }
        val proof = (f.unlockWith(PASSWORD) as MasterPasswordOutcome.Right).proof

        await { f.engine.showAside(OTHER_VAULT) } // F16b reaches it so ("It wasn't me")

        assertEquals(AsideLine.LockedOn(lockedOn), f.pad.aside)
        assertNull(f.port.openVaultId)
        assertFalse(f.engine.core.holds(proof))
        assertEquals(emptyList<String>(), f.port.writingCalls)
        await { f.engine.goBack() } // Back: the current vault, locked
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
        f.type(PIN)
        assertEquals(VAULT, f.port.openVaultId)
    }

    @Test
    fun f17step4_anIdThatIsNotAVaultPutAside_changesNothing() {
        twoVaults()
        f.startInFront()
        await { f.engine.showAside(THIRD_VAULT) } // not on the phone
        assertNull(f.pad.aside)
        await { f.engine.showAside(VAULT) } // the current vault is not one put aside
        assertNull(f.pad.aside)
        f.type(PIN)
        await { f.engine.showAside(THIRD_VAULT) } // in the open app: it stays open
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f17step4_whenTheVaultsCannotBeListed_theLockSaysSo_asEverywhereElse_andTryAgainBringsItsScreenBack() {
        twoVaults()
        f.startInFront()
        await { f.engine.useMasterPassword() } // the "Forgot master password?" sheet lists the vault put aside
        f.port.scanFails = true // storage cannot list the vaults now

        await { f.engine.showAside(OTHER_VAULT) }

        // A storage error, never a crash and never "no vault" (§5.11 step 2, F1 step 1): what start-up and
        // every lock show when the vaults cannot be listed, with "Try again".
        assertEquals(LockState.Unreadable(CurrentVault.Why.IO, emptyList()), f.state)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        f.leaveAndReturn() // the lock screen is drawn again: nothing throws
        assertFalse(await { f.engine.startEmpty() }) // nothing to put aside from that screen
        f.port.scanFails = false
        await { f.engine.refresh() } // "Try again"
        assertTrue(f.screen is LockScreen.Pad)
        await { f.engine.showAside(OTHER_VAULT) }
        assertEquals(AsideLine.LockedOn(lockedOn), f.pad.aside)
        f.type(Secrets.OTHER_PIN)
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId)
        assertEquals(listOf("swapIn $OTHER_VAULT"), f.port.writingCalls)
    }

    // ---- step 4, build-plan §12 decision 6: a vault put aside whose copies cannot be opened ---------

    @Test
    fun f17step4_decision6_aRightPinThatFindsNoCopyThatOpens_staysOnThatVaultsScreen_withBackOnly() {
        val phone = twoVaults(otherFingerprint = true)
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        assertEquals(AsideLine.LockedOn(lockedOn), f.pad.aside)
        f.port.vault(OTHER_VAULT)!!.live.forEach { it.state = FakeVaultPort.FileState.DAMAGED } // since its lock screen was drawn

        f.type(Secrets.OTHER_PIN)

        // That vault's own screen stays and says so, never the current vault's "Your passwords can't be
        // opened" (§13.1 row 2), whose "Restore from a backup file" would put the current vault aside.
        val cannotOpen = LockScreen.CannotOpen(AsideLine.LockedOn(lockedOn))
        assertEquals(cannotOpen, f.screen)
        // The PIN was right, and its record says so (§6.3 E.6); nothing else changed: no swap, nothing
        // open, nothing deleted, no shortcut removed.
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord(OTHER_VAULT).guard)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(emptyList<String>(), f.port.writes)
        assertNull(f.port.openVaultId)
        assertNull(f.port.openAsideId)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(listOf(OTHER_VAULT), f.port.asideVaults.map { it.vaultId })
        assertEquals(phone.otherPin, f.pinRecord(OTHER_VAULT).slot)
        assertEquals(phone.otherFingerprint, f.fingerprintRecord(OTHER_VAULT).slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertEquals(listOf(OTHER_VAULT), f.engine.summary.value.aside.map { it.vaultId })

        // Back is all it offers: no pad, no field, no fingerprint, and it does not change by itself.
        f.type("1")
        await { f.engine.useMasterPassword() }
        await { f.engine.usePin() }
        await { f.engine.notNow() }
        await { f.engine.cantRemember() }
        await { f.engine.promptIfDue() }
        await { f.engine.useFingerprint() }
        await { f.engine.refresh() }
        assertEquals(cannotOpen, f.screen)
        assertFalse(f.pinRecord(OTHER_VAULT).guard.charged)
        assertTrue(f.keys.prompts.isEmpty())
        assertFalse(await { f.engine.startEmpty() })
        assertEquals(emptyList<String>(), f.port.writingCalls)

        // Back: the current vault's lock screen, as it was.
        await { f.engine.goBack() }
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
        f.type(PIN)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f17step4_decision6_aRightMasterPasswordThatFindsNoCopyThatOpens_isNotCounted_andNothingCanBeTypedThere() {
        twoVaults(otherPin = false)
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        assertEquals(AsideLine.LockedOn(lockedOn), f.field.aside)
        // A copy above the one its slot opens cannot be read now (an I/O error): storage never opens past it.
        f.port.vault(OTHER_VAULT)!!.save().state = FakeVaultPort.FileState.UNREADABLE

        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(Secrets.OTHER))

        assertEquals(LockScreen.CannotOpen(AsideLine.LockedOn(lockedOn)), f.screen)
        assertEquals(0, f.stateRecord(OTHER_VAULT).mpGuard.failures) // neither right nor wrong: not counted (§6.8)
        assertNull(f.port.openAsideId)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        // No field: a password sent anyway is not checked.
        val checks = f.port.calls.count { it.startsWith("openWithPassword") }
        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(Secrets.OTHER))
        assertEquals(checks, f.port.calls.count { it.startsWith("openWithPassword") })
        assertEquals(LockScreen.CannotOpen(AsideLine.LockedOn(lockedOn)), f.screen)
    }

    @Test
    fun f17step4_decision6_aFingerprintThatFindsNoCopyThatOpens_getsTheSameScreen() {
        val phone = twoVaults(otherFingerprint = true)
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        assertTrue(f.pad.fingerprintKey)
        f.port.vault(OTHER_VAULT)!!.live.first().state = FakeVaultPort.FileState.NEWER // written by a newer version of the app

        await { f.engine.useFingerprint() }

        assertEquals(1, f.keys.prompts.size)
        assertEquals(LockScreen.CannotOpen(AsideLine.LockedOn(lockedOn)), f.screen)
        assertEquals(phone.otherFingerprint, f.fingerprintRecord(OTHER_VAULT).slot) // nothing is removed (I6)
        assertEquals(VAULT, f.port.current!!.vaultId)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f17step4_decision6_theVaultFromBeforeAReplacement_keepsItsOwnLine_andIsAskedNothing() {
        val at = f.clock.wallMs() - 2 * PracticeSchedule.DAY_MS
        val marked = StateRecord(
            OTHER_VAULT, StateRecord.Practice(f.clock.wallMs(), 3, null), StateRecord.MpGuard(0, null, 0), null, null,
            StateRecord.Replaced(VAULT, at, Shortcut.PIN, 0L, f.clock.elapsedMs()), StateRecord.MaxGeneration(1, f.clock.wallMs()),
        )
        twoVaults(otherState = marked)
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        assertEquals(MpReason.FromBefore(at), f.field.reason)
        f.port.vault(OTHER_VAULT)!!.live.forEach { it.state = FakeVaultPort.FileState.DAMAGED }

        assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(Secrets.OTHER))

        // Its line, and not the question of F16b step 3: nothing opened, so there is nothing to ask.
        assertEquals(LockScreen.CannotOpen(AsideLine.FromBefore(at)), f.screen)
        assertNotNull(f.stateRecord(OTHER_VAULT).replaced)
        assertNull(f.port.openAsideId)
        assertEquals(emptyList<String>(), f.port.writingCalls)
        await { f.engine.goBack() }
        assertNull(f.pad.aside)
        assertEquals(listOf(OTHER_VAULT), f.port.asideVaults.map { it.vaultId })
    }

    @Test
    fun f17step4_decision6_theScreenLastsForItsVisit_andTheVaultOpensOnceItsFileCanBeRead() {
        twoVaults()
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        val generation = f.port.vault(OTHER_VAULT)!!.live.first()
        generation.state = FakeVaultPort.FileState.UNREADABLE // a passing storage error
        val pad = LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = AsideLine.LockedOn(lockedOn))

        f.type(Secrets.OTHER_PIN)
        assertTrue(f.screen is LockScreen.CannotOpen)

        // The app was left and is back: a new visit, and that vault's own pad again.
        f.leaveAndReturn()
        assertEquals(pad, f.screen)
        // A screen-off lock ends the visit too (lock.md §6.3, decision 29).
        f.type(Secrets.OTHER_PIN)
        assertTrue(f.screen is LockScreen.CannotOpen)
        f.lock(LockCause.SCREEN_OFF)
        assertEquals(pad, f.screen)

        // Its file can be read again: its PIN opens it, and only now do the two vaults change places.
        generation.state = FakeVaultPort.FileState.OK
        f.type(Secrets.OTHER_PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId)
        assertEquals(listOf("swapIn $OTHER_VAULT"), f.port.writingCalls)
    }

    @Test
    fun f17step1_welcomeListsEveryVaultPutAside_andEachOpensWithItsOwnPin() {
        val first = f.port.seedAside(OTHER_VAULT, bytes(Secrets.OTHER), putAsideAtMs = lockedOn)
        f.plantPin(first, pin = Secrets.OTHER_PIN)
        f.plantState(OTHER_VAULT)
        val second = f.port.seedAside(THIRD_VAULT, bytes(Secrets.THIRD), putAsideAtMs = lockedOn + 1_000)
        f.plantPin(second, pin = Secrets.THIRD_PIN)
        f.plantState(THIRD_VAULT)
        f.startInFront()
        assertEquals(listOf(THIRD_VAULT, OTHER_VAULT), (f.state as LockState.NoVault).aside.map { it.vaultId })
        assertEquals(listOf(THIRD_VAULT, OTHER_VAULT), f.engine.summary.value.aside.map { it.vaultId })

        await { f.engine.showAside(OTHER_VAULT) }
        f.type(Secrets.THIRD_PIN) // another vault's PIN is a wrong PIN here
        assertEquals(PadStatus.WrongPin(4), f.pad.status)
        await { f.engine.goBack() }
        assertTrue(f.state is LockState.NoVault) // Back on Welcome
        await { f.engine.showAside(THIRD_VAULT) }
        assertEquals(AsideLine.LockedOn(lockedOn + 1_000), f.pad.aside)
        f.type(Secrets.THIRD_PIN)
        assertEquals(THIRD_VAULT, f.port.current!!.vaultId)
        assertEquals(listOf(OTHER_VAULT), f.port.asideVaults.map { it.vaultId })
        assertEquals(1, f.pinRecord(OTHER_VAULT).guard.total) // its own count, as it was left
    }

    @Test
    fun f17step6_vaultsPutAsideAreNeverRemovedAutomatically() {
        twoVaults(otherFingerprint = true)
        val generations = filesOf(f, OTHER_VAULT)
        repeat(3) {
            f.clock.advance(40 * PracticeSchedule.DAY_MS)
            f.restart()
            f.startInFront()
            f.type(PIN)
            f.lock()
        }
        assertEquals(generations, filesOf(f, OTHER_VAULT))
        assertEquals(listOf("pin.rec", "fingerprint.rec", "state.rec").sorted(), f.files.names(f.dirOf(OTHER_VAULT)).sorted())
        assertNotNull(f.pinRecord(OTHER_VAULT).slot)
        assertNotNull(f.fingerprintRecord(OTHER_VAULT).slot)
        assertEquals(emptyList<String>(), f.keys.deleted) // the sweep keeps every key a record names
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun f17step7_everyVaultWithAPinHasItsOwnFiveTries() {
        val phone = twoVaults()
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        for (wrong in listOf("000001", "000002", "000003", "000004", "000005")) f.type(wrong)

        // Its PIN is turned off at its own limit, its key deleted (§6.5); its own master-password field shows.
        val record = f.pinRecord(OTHER_VAULT)
        assertNull(record.slot)
        assertEquals(PinRecord.OffReason.LIMIT, record.off?.reason)
        assertFalse(PinWrap.alias(phone.otherPin!!.id) in f.keys.aliases)
        assertEquals(MpReason.Pin(PinReason.LIMIT), f.field.reason)
        assertEquals(AsideLine.LockedOn(lockedOn), f.field.aside)
        // The current vault's PIN and its count are untouched.
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord(VAULT).guard)
        await { f.engine.goBack() }
        f.type(PIN)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }
}
