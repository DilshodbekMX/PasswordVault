package io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.DeviceKeys
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A scripted run of lock.md **F17 step 5**, Settings › Backup & transfer › "Old passwords" (build
 * plan 3.14): "Open" adds the accounts of a vault put aside and "Delete" deletes it, each with that
 * vault's own master password, checked under that vault's own guard (§6.8) and nothing else; and
 * "Delete" settles another vault's `replaced` mark first, so that the shortcuts of a replaced vault
 * cannot come back once the vault made from it is gone (§5.11 step 3).
 */
class OldPasswordsFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private val f = LockFixture()

    private val lockedOn = 1_790_500_000_000L

    private val current = Entry(id = "00000000-0000-4000-8000-0000000000d1", title = "Telegram", createdAt = 1L, updatedAt = 1L)
    private val older = Entry(id = "00000000-0000-4000-8000-0000000000d2", title = "Payme", createdAt = 1L, updatedAt = 1L)

    private class Old(val pin: ShortcutSlot, val fingerprint: ShortcutSlot)

    /** The current vault with its PIN; [OTHER_VAULT] put aside with its own PIN (row 2, total 3), fingerprint and accounts. */
    private fun phone(otherState: (LockFixture) -> Unit = { it.plantState(OTHER_VAULT) }): Old {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        vault.content = Vault(entries = listOf(current))
        val old = f.port.seedAside(OTHER_VAULT, bytes(Secrets.OTHER), putAsideAtMs = lockedOn)
        val pin = f.plantPin(old, pin = Secrets.OTHER_PIN, row = 2, total = 3)
        val fingerprint = f.plantFingerprint(old)
        otherState(f)
        old.content = Vault(entries = listOf(older))
        return Old(pin, fingerprint)
    }

    private fun openTheApp() {
        f.startInFront()
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    private fun open(vaultId: String, password: String): AsideOutcome {
        val chars = password.toCharArray()
        return await { f.engine.mergeAside(vaultId, chars) }
    }

    private fun delete(vaultId: String, password: String): AsideOutcome {
        val chars = password.toCharArray()
        return await { f.engine.deleteAside(vaultId, chars) }
    }

    private fun pending(vkId: String) = StateRecord.PendingMp(
        requestedMs = f.clock.wallMs(), by = Shortcut.PIN, creditedMs = 0L, seenElapsedMs = f.clock.elapsedMs(),
        vkId = vkId, salt = ByteArray(16) { 1 }, nonce = ByteArray(12) { 2 }, ct = ByteArray(48) { 3 },
    )

    // ---- "Open" ---------------------------------------------------------------------------------------

    @Test
    fun open_addsItsAccountsToTheCurrentVault_withThatVaultsOwnMasterPassword() {
        val old = phone()
        openTheApp()
        val generations = filesOf(f, OTHER_VAULT)

        assertEquals(AsideOutcome.Done, open(OTHER_VAULT, Secrets.OTHER))

        assertEquals(listOf("mergeAside $OTHER_VAULT"), f.port.writingCalls)
        assertTrue(f.port.calls.indexOf("openWithPassword aside $OTHER_VAULT") < f.port.calls.indexOf("mergeAside $OTHER_VAULT"))
        assertEquals(listOf(current.id, older.id), f.port.current!!.content.entries.map { it.id })
        // The vault put aside stays where it is, with its files, records and keys.
        assertEquals(listOf(OTHER_VAULT), f.port.asideVaults.map { it.vaultId })
        assertEquals(generations, filesOf(f, OTHER_VAULT))
        assertEquals(old.pin, f.pinRecord(OTHER_VAULT).slot)
        assertEquals(old.fingerprint, f.fingerprintRecord(OTHER_VAULT).slot)
        assertEquals(emptyList<String>(), f.keys.deleted)
        // It counts as that vault's master password: its PIN counts are back at zero (§6.4) and its practice moved on.
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord(OTHER_VAULT).guard)
        assertEquals(f.clock.wallMs(), f.stateRecord(OTHER_VAULT).practice.lastOkMs)
        assertTrue(f.state is LockState.Unlocked)
        assertEquals(VAULT, f.port.openVaultId)
    }

    @Test
    fun open_isCheckedUnderThatVaultsOwnGuard_notTheCurrentOnes() {
        phone()
        openTheApp()
        assertEquals(AsideOutcome.Wrong, open(OTHER_VAULT, PASSWORD)) // the current vault's master password
        assertEquals(StateRecord.MpGuard(1, f.clock.elapsedMs(), 1), f.stateRecord(OTHER_VAULT).mpGuard)
        assertEquals(0, f.stateRecord(VAULT).mpGuard.failures)
        repeat(4) { assertEquals(AsideOutcome.Wrong, open(OTHER_VAULT, "not it $it")) }
        val last = f.clock.elapsedMs()

        // From the sixth try that vault's own field waits 30 seconds (§6.8), its right password too.
        assertEquals(AsideOutcome.Wait(last + 30_000), open(OTHER_VAULT, Secrets.OTHER))
        assertEquals(AsideOutcome.Wait(last + 30_000), delete(OTHER_VAULT, Secrets.OTHER))
        // The current vault's own guard does not wait.
        assertTrue(f.check(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(emptyList<String>(), f.port.writingCalls)

        f.clock.advance(30_000)
        assertEquals(AsideOutcome.Done, open(OTHER_VAULT, Secrets.OTHER))
        assertEquals(0, f.stateRecord(OTHER_VAULT).mpGuard.failures)
    }

    @Test
    fun aWaitStartedOnItsOwnLockScreen_isTheWaitInSettingsToo() {
        phone()
        f.startInFront()
        await { f.engine.showAside(OTHER_VAULT) }
        await { f.engine.useMasterPassword() }
        repeat(5) { assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it $it")) }
        assertNotNull(f.field.waitUntilElapsedMs)
        await { f.engine.goBack() }
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)

        assertTrue(open(OTHER_VAULT, Secrets.OTHER) is AsideOutcome.Wait)
        assertTrue(delete(OTHER_VAULT, Secrets.OTHER) is AsideOutcome.Wait)
        f.clock.advance(30_000)
        assertEquals(AsideOutcome.Done, open(OTHER_VAULT, Secrets.OTHER))
    }

    @Test
    fun open_andDelete_areRefusedInTheLockedApp_forTheCurrentVault_andForOneNotOnThePhone() {
        phone()
        f.startInFront()
        assertEquals(AsideOutcome.NotSaved, open(OTHER_VAULT, Secrets.OTHER)) // Settings is in the open app only
        assertEquals(AsideOutcome.NotSaved, delete(OTHER_VAULT, Secrets.OTHER))
        f.type(PIN)
        assertEquals(AsideOutcome.NotSaved, delete(VAULT, PASSWORD)) // the current vault is never deleted here
        assertEquals(AsideOutcome.NotSaved, open(VAULT, PASSWORD))
        assertEquals(AsideOutcome.NotSaved, delete(THIRD_VAULT, Secrets.THIRD)) // not on the phone
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(emptyList<String>(), f.port.calls.filter { it.startsWith("openWithPassword") })
        assertEquals(0, f.stateRecord(OTHER_VAULT).mpGuard.failures)
        assertNotNull(f.port.vault(VAULT))
    }

    // ---- "Delete" -------------------------------------------------------------------------------------

    @Test
    fun delete_needsThatVaultsOwnMasterPassword_andNothingElse() {
        val old = phone { it.plantState(OTHER_VAULT, pendingMp = pending(it.port.vault(OTHER_VAULT)!!.newestVkId)) }
        openTheApp()
        assertEquals(AsideOutcome.Wrong, delete(OTHER_VAULT, PASSWORD)) // not the current vault's master password
        assertEquals(AsideOutcome.Wrong, delete(OTHER_VAULT, "the pending words")) // not a pending one (F11)
        f.clock.advance(400 * PracticeSchedule.DAY_MS) // not the passing of time
        assertEquals(AsideOutcome.Wrong, delete(OTHER_VAULT, ""))
        assertNotNull(f.port.vault(OTHER_VAULT))
        assertTrue(f.files.hasDir(f.dirOf(OTHER_VAULT)))
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertEquals(3, f.stateRecord(OTHER_VAULT).mpGuard.failures)

        assertEquals(AsideOutcome.Done, delete(OTHER_VAULT, Secrets.OTHER))

        assertEquals(listOf("deleteAside $OTHER_VAULT"), f.port.writingCalls)
        assertNull(f.port.vault(OTHER_VAULT))
        assertFalse(f.files.hasDir(f.dirOf(OTHER_VAULT)))
        val aliases = listOf(PinWrap.alias(old.pin.id), RecordCodec.FINGERPRINT_ALIAS_PREFIX + old.fingerprint.id)
        assertEquals(aliases.sorted(), f.keys.deleted.sorted())
        assertTrue(aliases.none { it in f.keys.aliases })
        assertEquals(emptyList<AsideVault>(), f.engine.summary.value.aside)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(listOf("pin.rec", "state.rec"), f.files.names(f.dirOf(VAULT)).sorted())
    }

    @Test
    fun delete_theFilesGoFirst_thenTheLockDirectory_thenTheKeys() {
        val old = phone()
        val timeline = ArrayList<String>()
        f.files.onBoundary = { _, at -> timeline += "files: $at" }
        f.port.onBoundary = { _, at -> timeline += "port: $at" }
        val inner = f.keys.device
        f.deviceKeys = object : DeviceKeys {
            override fun ensureCanary(): KeyCreate = inner.ensureCanary()

            override fun aliases(): List<String> = inner.aliases()

            override fun delete(alias: String) {
                timeline += "keys: delete $alias"
                inner.delete(alias)
            }
        }
        f.rebuild()
        openTheApp()
        timeline.clear()

        assertEquals(AsideOutcome.Done, delete(OTHER_VAULT, Secrets.OTHER))

        val files = timeline.indexOf("port: deleteAside: after")
        val dir = timeline.indexOf("files: deleteDir ${f.dirOf(OTHER_VAULT)}: before")
        val keys = timeline.indexOf("keys: delete ${PinWrap.alias(old.pin.id)}")
        assertTrue(timeline.toString(), files in 0 until dir)
        assertTrue(timeline.toString(), dir < keys)
        assertTrue(timeline.contains("keys: delete ${RecordCodec.FINGERPRINT_ALIAS_PREFIX + old.fingerprint.id}"))
    }

    @Test
    fun delete_aLockWhileThePasswordIsChecked_deletesNothing() {
        phone()
        openTheApp()
        f.port.beforeCall = { call -> if (call == "openWithPassword aside $OTHER_VAULT") f.engine.core.endPromptsNow() } // the app locks now

        assertEquals(AsideOutcome.NotSaved, delete(OTHER_VAULT, Secrets.OTHER))
        assertEquals(AsideOutcome.NotSaved, open(OTHER_VAULT, Secrets.OTHER))

        f.port.beforeCall = null
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertNotNull(f.port.vault(OTHER_VAULT))
        assertTrue(f.files.hasDir(f.dirOf(OTHER_VAULT)))
        assertEquals(0, f.stateRecord(OTHER_VAULT).mpGuard.failures) // the password was right, and counted as such (§6.8 step 8)
    }

    @Test
    fun delete_whenTheLockDirectoryCannotBeDeleted_theVaultIsGone_andTheDirectoryStaysWithItsKeys() {
        val old = phone()
        openTheApp()
        f.files.fail(FakeRecordFiles.Op.DELETE_DIR)

        assertEquals(AsideOutcome.Done, delete(OTHER_VAULT, Secrets.OTHER))

        assertNull(f.port.vault(OTHER_VAULT))
        assertTrue(f.files.hasDir(f.dirOf(OTHER_VAULT))) // left: a directory whose vault is gone is left alone at every start
        assertTrue(PinWrap.alias(old.pin.id) in f.keys.aliases) // its records still name its keys, so they stay
        assertTrue(RecordCodec.FINGERPRINT_ALIAS_PREFIX + old.fingerprint.id in f.keys.aliases)
        f.restart()
        f.startInFront()
        assertTrue(f.files.hasDir(f.dirOf(OTHER_VAULT)))
        assertTrue(PinWrap.alias(old.pin.id) in f.keys.aliases)
        f.type(PIN)
        assertEquals(VAULT, f.port.openVaultId)
        assertEquals(emptyList<AsideVault>(), f.engine.summary.value.aside)
    }

    @Test
    fun delete_theKeysGoASecondAfterTheDirectory_soThatNoRecordAPowerCutBringsBackNamesADeletedKey() {
        val old = phone()
        val deletedAt = ArrayList<Long>()
        var dirGoneAt = -1L
        f.files.onBoundary = { _, at -> if (at == "deleteDir ${f.dirOf(OTHER_VAULT)}: after") dirGoneAt = f.clock.elapsedMs() }
        val inner = f.keys.device
        f.deviceKeys = object : DeviceKeys {
            override fun ensureCanary(): KeyCreate = inner.ensureCanary()

            override fun aliases(): List<String> = inner.aliases()

            override fun delete(alias: String) {
                deletedAt += f.clock.elapsedMs()
                inner.delete(alias)
            }
        }
        f.rebuild()
        openTheApp()

        assertEquals(AsideOutcome.Done, delete(OTHER_VAULT, Secrets.OTHER))

        assertEquals(2, deletedAt.size)
        assertTrue("$deletedAt, $dirGoneAt", dirGoneAt >= 0 && deletedAt.all { it - dirGoneAt >= 1_000 })
        assertFalse(PinWrap.alias(old.pin.id) in f.keys.aliases)
    }

    // ---- "Delete" settles another vault's replaced mark first ----------------------------------------

    /**
     * [OTHER_VAULT] put aside with its PIN, fingerprint and a pending password; [THIRD_VAULT] put aside;
     * the app opened with the current vault's PIN; then, in this process, [OTHER_VAULT] marked as
     * replaced by [THIRD_VAULT], as F11 step 15 marks it (build plan 4.4): its slots stay until a later
     * start (F11 step 18).
     */
    private fun replacedInThisProcess(): Old {
        val old = phone { it.plantState(OTHER_VAULT, pendingMp = pending(it.port.vault(OTHER_VAULT)!!.newestVkId)) }
        val third = f.port.seedAside(THIRD_VAULT, bytes(Secrets.THIRD), putAsideAtMs = lockedOn + 1_000)
        f.plantPin(third, pin = Secrets.THIRD_PIN)
        f.plantState(THIRD_VAULT)
        openTheApp()
        val mark = StateRecord.Replaced(THIRD_VAULT, f.clock.wallMs(), Shortcut.PIN, 0L, f.clock.elapsedMs())
        val core = f.engine.core
        assertTrue(await { core.locked { core.lockOf(OTHER_VAULT).master.update(bestEffort = false) { it.copy(replaced = mark) } } })
        assertNotNull(f.pinRecord(OTHER_VAULT).slot)
        assertNotNull(f.fingerprintRecord(OTHER_VAULT).slot)
        return old
    }

    @Test
    fun delete_settlesAnotherVaultsReplacedMarkFirst_onDisk_aSecondBeforeTheFilesGo() {
        val old = replacedInThisProcess()
        val timeline = ArrayList<Pair<String, Long>>()
        f.files.onBoundary = { _, at -> timeline += "files: $at" to f.clock.elapsedMs() }
        f.port.onBoundary = { _, at -> timeline += "port: $at" to f.clock.elapsedMs() }
        val thirdPin = PinWrap.alias(f.pinRecord(THIRD_VAULT).slot!!.id)

        assertEquals(AsideOutcome.Done, delete(THIRD_VAULT, Secrets.THIRD))

        // What start-up's first row would do to the vault that names it: both slots gone, their keys
        // deleted, its pending password gone; the mark stays.
        val pin = f.pinRecord(OTHER_VAULT)
        assertNull(pin.slot)
        assertEquals(PinRecord.OffReason.REPLACED, pin.off?.reason)
        val fingerprint = f.fingerprintRecord(OTHER_VAULT)
        assertNull(fingerprint.slot)
        assertEquals(FingerprintRecord.OffReason.REPLACED, fingerprint.off?.reason)
        assertFalse(PinWrap.alias(old.pin.id) in f.keys.aliases)
        assertFalse(RecordCodec.FINGERPRINT_ALIAS_PREFIX + old.fingerprint.id in f.keys.aliases)
        val state = f.stateRecord(OTHER_VAULT)
        assertNull(state.pendingMp)
        assertEquals(THIRD_VAULT, state.replaced?.newVaultId)
        // Then vault N went: its files, its lock directory and its keys.
        assertNull(f.port.vault(THIRD_VAULT))
        assertFalse(f.files.hasDir(f.dirOf(THIRD_VAULT)))
        assertFalse(thirdPin in f.keys.aliases)
        // First, on disk, and a second old before the files went.
        val otherDir = f.dirOf(OTHER_VAULT)
        val settled = timeline.last { (at, _) -> at.startsWith("files: replace $otherDir/") && at.endsWith("after the directory sync") }
        val deleted = timeline.first { (at, _) -> at == "port: deleteAside: before" }
        assertTrue(timeline.toString(), timeline.indexOf(settled) < timeline.indexOf(deleted))
        assertTrue(timeline.toString(), deleted.second - settled.second >= 1_000)

        // At the next start vault N is gone: the third row clears the mark, and there is nothing left
        // to keep. The old vault opens with its own master password only.
        f.restart()
        f.startInFront()
        assertNull(f.stateRecord(OTHER_VAULT).replaced)
        await { f.engine.showAside(OTHER_VAULT) }
        assertTrue(f.screen is LockScreen.MasterPassword)
        assertFalse(f.field.canUsePin)
        assertTrue(f.unlockWith(Secrets.OTHER) is MasterPasswordOutcome.Right)
        assertEquals(OTHER_VAULT, f.port.current!!.vaultId)
    }

    @Test
    fun delete_aLockWhileTheMarkIsBeingSettled_settlesIt_butDeletesNothing() {
        replacedInThisProcess()
        val stateRec = "${f.dirOf(OTHER_VAULT)}/state.rec"
        f.files.onBoundary = { _, at -> if (at == "replace $stateRec: after the directory sync") f.engine.core.endPromptsNow() } // the app locks now

        assertEquals(AsideOutcome.NotSaved, delete(THIRD_VAULT, Secrets.THIRD))

        f.files.onBoundary = null
        assertNull(f.pinRecord(OTHER_VAULT).slot) // what was settled stays settled, as start-up would leave it
        assertNotNull(f.port.vault(THIRD_VAULT)) // and nothing was deleted
        assertTrue(f.files.hasDir(f.dirOf(THIRD_VAULT)))
        assertEquals(emptyList<String>(), f.port.writingCalls)
    }

    @Test
    fun delete_isRefusedWhileTheSettlingCannotBeWritten_andWorksOnceItCan() {
        replacedInThisProcess()
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = -1, matches = { it.contains("/$OTHER_VAULT/") })

        assertEquals(AsideOutcome.NotSaved, delete(THIRD_VAULT, Secrets.THIRD))

        assertNotNull(f.port.vault(THIRD_VAULT))
        assertTrue(f.files.hasDir(f.dirOf(THIRD_VAULT)))
        assertEquals(emptyList<String>(), f.port.writingCalls)
        assertNotNull(f.pinRecord(OTHER_VAULT).slot) // on disk, the write failed

        f.files.clearFaults()
        assertEquals(AsideOutcome.Done, delete(THIRD_VAULT, Secrets.THIRD)) // the records it means to have are written again
        assertNull(f.pinRecord(OTHER_VAULT).slot)
        assertNull(f.fingerprintRecord(OTHER_VAULT).slot)
        assertNull(f.stateRecord(OTHER_VAULT).pendingMp)
        assertNull(f.port.vault(THIRD_VAULT))
    }

    @Test
    fun delete_aRemovalStartUpMadeInMemoryWhoseWriteFailed_isWrittenBeforeTheFilesGo() {
        // The mark is on disk from an earlier process; start-up's first row removes the slots (the keys
        // at once), and the writes of both slot records fail.
        phone {
            it.plantState(OTHER_VAULT, replaced = StateRecord.Replaced(THIRD_VAULT, it.clock.wallMs(), Shortcut.PIN, 0L, it.clock.elapsedMs()))
        }
        f.port.seedAside(THIRD_VAULT, bytes(Secrets.THIRD), putAsideAtMs = lockedOn + 1_000)
        f.plantState(THIRD_VAULT)
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 2, matches = { it.contains("/$OTHER_VAULT/") && !it.endsWith("state.rec") })
        openTheApp()
        assertNotNull(f.pinRecord(OTHER_VAULT).slot) // still on disk
        assertNotNull(f.fingerprintRecord(OTHER_VAULT).slot)

        assertEquals(AsideOutcome.Done, delete(THIRD_VAULT, Secrets.THIRD))

        assertNull(f.pinRecord(OTHER_VAULT).slot)
        assertNull(f.fingerprintRecord(OTHER_VAULT).slot)
        assertNull(f.port.vault(THIRD_VAULT))
    }

    @Test
    fun delete_isRefusedWhileAnotherVaultsStateCannotBeRead_forItMayNameTheVault() {
        phone()
        f.port.seedAside(THIRD_VAULT, bytes(Secrets.THIRD), putAsideAtMs = lockedOn + 1_000)
        f.plantState(THIRD_VAULT)
        f.files.fail(FakeRecordFiles.Op.READ, times = -1, matches = { it.endsWith("/$OTHER_VAULT/state.rec") })
        openTheApp()

        assertEquals(AsideOutcome.NotSaved, delete(THIRD_VAULT, Secrets.THIRD))
        assertNotNull(f.port.vault(THIRD_VAULT))

        f.files.clearFaults()
        f.restart()
        openTheApp()
        assertEquals(AsideOutcome.Done, delete(THIRD_VAULT, Secrets.THIRD))
        assertNull(f.port.vault(THIRD_VAULT))
    }
}
