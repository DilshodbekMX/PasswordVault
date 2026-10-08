package io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * lock.md test **T14**, crash injection, for **F13**, **F14** and **F17 steps 3 and 4** (build plan
 * 3.14), and for the settling of a replaced mark in **F17 step 5**: the fake file system and the fake
 * storage stop at every boundary of a script (before the temp write, after the file sync, after the
 * rename, after the directory sync; before and after every storage step). After the "restart", as a
 * kill (everything handed over stays) and as a power cut (what was handed over less than a second
 * before is lost):
 *
 * - every vault on the phone opens with its own master password;
 * - I1 to I6 hold: the only vault data written is the flow's own step (`createVault`, `swapIn`,
 *   `deleteAside`), no generation of any vault changes; every PIN that is checked has a durable
 *   charge a second old (I4, by `AgedChargeKey`); counts go down only as stated (I5); no shortcut is
 *   removed (I6), except those F17 step 5 settles;
 * - no key named by a durable record was deleted;
 * - the PIN never opens an older copy while a newer one is intact;
 * - from the moment a replacement is finished, the old vault has no usable shortcut at any stopping
 *   point, and once the vault made from it is deleted, never again (F17 step 5);
 *
 * and the next process gets every vault open again with its own PIN or master password, from the
 * screen it finds (the pad, "Welcome back", Welcome with the vaults put aside).
 */
class VaultCrashTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(900)

    /**
     * Runs [script] on a phone made by [setUp] once without a stop, then once for every boundary it
     * passes, stopped there; after each stop and each way of restarting, [check] looks at the phone
     * (before anything runs) and goes on with a new process.
     */
    private fun atEveryBoundary(
        name: String,
        setUp: (LockFixture) -> Unit,
        script: (LockFixture) -> Unit,
        minFileBoundaries: Int,
        minPortBoundaries: Int,
        check: (LockFixture, String) -> Unit,
    ) {
        val whole = crashablePhone(SEED)
        setUp(whole)
        script(whole)
        val fileBoundaries = whole.files.boundaries
        val portBoundaries = whole.port.boundaries
        assertTrue("$name: the script writes records: $fileBoundaries boundaries", fileBoundaries >= minFileBoundaries)
        assertTrue("$name: the script asks storage: $portBoundaries boundaries", portBoundaries >= minPortBoundaries)
        var checked = 0
        for (how in Restart.entries) {
            for (k in 1..fileBoundaries + portBoundaries) {
                val where = "$name, " + (if (k <= fileBoundaries) "record boundary $k" else "storage boundary ${k - fileBoundaries}") + ", $how"
                val f = crashablePhone(SEED)
                setUp(f)
                if (k <= fileBoundaries) f.files.stopAt(k) else f.port.stopAt(k - fileBoundaries)
                assertTrue("no stop: $where", untilStopped { script(f) })
                restartAfter(f, how)
                check(f, where)
                checked++
            }
        }
        assertEquals(2 * (fileBoundaries + portBoundaries), checked)
    }

    /** Opens [vaultId] with its own PIN, on its pad if it is current and from its own lock screen if not, then locks again. */
    private fun openWithItsPin(f: LockFixture, vaultId: String, where: String) {
        if (f.state is LockState.Unlocked) f.lock()
        if (f.port.current?.vaultId != vaultId) await { f.engine.showAside(vaultId) }
        assertTrue("$where: the pad of $vaultId, not ${f.state}", (f.state as? LockState.Locked)?.screen is LockScreen.Pad)
        f.type(Secrets.pinOf(vaultId))
        assertTrue("$where: $vaultId did not open with its PIN: ${f.state}", f.state is LockState.Unlocked)
        assertEquals(where, vaultId, f.port.openVaultId)
        assertEquals("$where: the PIN opened an older copy", f.port.current!!.live.first().number, f.port.openGeneration)
        f.answerNotices()
        f.lock()
    }

    /** Opens the current vault with its master password on the screen the lock shows, which may be "Welcome back". */
    private fun openCurrentWithItsPassword(f: LockFixture, where: String) {
        if (f.state is LockState.Unlocked) f.lock()
        val vaultId = f.port.current!!.vaultId
        if (f.screen !is LockScreen.MasterPassword) await { f.engine.useMasterPassword() }
        val outcome = f.unlockWith(Secrets.passwordOf(vaultId))
        assertTrue("$where: $vaultId gave $outcome", outcome is MasterPasswordOutcome.Right)
        assertEquals(where, vaultId, f.port.openVaultId)
        assertTrue("$where: state.rec of $vaultId after an unlock", f.stateOnDisk(vaultId) is RecordRead.Ok)
        f.answerNotices()
        f.lock()
    }

    private fun writingCallsAre(f: LockFixture, where: String, allowed: Set<String>) {
        val calls = f.port.writingCalls
        assertTrue("$where: writing calls $calls", calls.all { it in allowed })
        assertTrue("$where: the same step twice: $calls", calls.size == calls.toSet().size)
    }

    // ---------------------------------------------------------------------------------------------
    // F13: restore on a new phone

    @Test
    fun t14_f13_restoreOnANewPhone_aStopAtEveryBoundary() {
        var restoredId: String? = null
        atEveryBoundary(
            "F13",
            setUp = {},
            script = { f ->
                f.startInFront()
                val outcome = f.restoreBackup()
                restoredId = (outcome as CreateOutcome.Created).vaultId
                f.answerNotices()
                f.lock()
            },
            minFileBoundaries = 6, // the stale *.tmp sweep, and state.rec
            minPortBoundaries = 10, // the listings, and createVault's steps
        ) { f, where ->
            val id = checkNotNull(restoredId)
            assertNoNamedKeyWasDeleted(f, where)
            writingCallsAre(f, where, setOf("createVault $id putCurrentAside"))
            assertEquals(where, emptyList<AsideVault>(), f.port.asideVaults.map { AsideVault(it.vaultId, 0L, null) })
            val vault = f.port.current
            if (vault == null) {
                // Before storage's last step: nothing on the phone; the staged copy went with the process.
                f.startInFront()
                assertEquals(where, LockState.NoVault(emptyList()), f.state)
                assertTrue(where, f.restoreBackup() is CreateOutcome.Created) // the restore can be made again
            } else {
                assertEquals(where, id, vault.vaultId)
                assertEquals(where, listOf(1L), vault.live.map { it.number })
                when (val state = f.stateOnDisk(id)) {
                    RecordRead.Missing -> Unit // the next start is F14
                    is RecordRead.Ok -> assertEquals(where, 3, state.record.practice.step) // F13 step 5
                    else -> throw AssertionError("$where: state.rec is $state")
                }
                f.startInFront()
                val reason = f.field.reason
                assertEquals(where, if (f.stateOnDisk(id) == RecordRead.Missing) MpReason.WelcomeBack else null, reason)
                openCurrentWithItsPassword(f, where)
            }
            assertEveryVaultOpens(f, where)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // F14: a vault without lock records

    @Test
    fun t14_f14_welcomeBack_aStopAtEveryBoundary() {
        lateinit var files: String
        atEveryBoundary(
            "F14",
            setUp = { f ->
                f.seedVault(generations = 2)
                files = filesOf(f, VAULT)
            },
            script = { f ->
                f.startInFront()
                assertEquals(MpReason.WelcomeBack, f.field.reason)
                assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith("not it"))
                assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
                f.answerNotices()
                f.lock()
            },
            minFileBoundaries = 6,
            minPortBoundaries = 8,
        ) { f, where ->
            assertNoNamedKeyWasDeleted(f, where)
            assertEquals(where, emptyList<String>(), f.port.writes) // I1: F14 writes no vault data
            assertEquals(where, emptyList<String>(), f.port.writingCalls)
            assertEquals(where, files, filesOf(f, VAULT))
            val state = f.stateOnDisk()
            assertTrue("$where: state.rec is $state", state == RecordRead.Missing || state is RecordRead.Ok)
            (state as? RecordRead.Ok)?.let { assertEquals(where, 0, it.record.practice.step) }
            f.startInFront()
            assertEquals(where, if (state == RecordRead.Missing) MpReason.WelcomeBack else null, f.field.reason)
            assertTrue(where, f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
            assertEquals(where, ChoosePinReason.NONE_HERE, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
            assertTrue(where, f.stateOnDisk() is RecordRead.Ok)
            assertEveryVaultOpens(f, where)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // F17 step 3: a restore over a vault on the phone

    @Test
    fun t14_f17step3_aRestoreOverAVault_aStopAtEveryBoundary() {
        var restoredId: String? = null
        lateinit var records: Map<String, List<Byte>>
        lateinit var keys: List<String>
        lateinit var files: String
        atEveryBoundary(
            "F17 step 3",
            setUp = { f ->
                val vault = f.seedVault(generations = 2)
                val pin = f.plantPin(vault)
                val finger = f.plantFingerprint(vault)
                f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
                records = listOf("pin.rec", "fingerprint.rec", "state.rec").associateWith { f.files.bytes(f.dirOf(VAULT), it)!!.toList() }
                keys = listOf(PinWrap.alias(pin.id), RecordCodec.FINGERPRINT_ALIAS_PREFIX + finger.id)
                files = filesOf(f, VAULT)
            },
            script = { f ->
                f.startInFront()
                await { f.engine.useMasterPassword() } // the "Forgot master password?" sheet is on that screen
                restoredId = (f.restoreBackup() as CreateOutcome.Created).vaultId
                f.answerNotices()
                f.lock()
            },
            minFileBoundaries = 6,
            minPortBoundaries = 11,
        ) { f, where ->
            val id = checkNotNull(restoredId)
            // The lock did nothing to the vault that went aside (F17): its records, byte for byte, and its keys.
            assertNoNamedKeyWasDeleted(f, where)
            for ((name, bytes) in records) assertEquals("$where: $name", bytes, f.files.bytes(f.dirOf(VAULT), name)!!.toList())
            assertTrue(where, keys.all { it in f.keys.aliases })
            assertEquals(where, files, filesOf(f, VAULT)) // archived, never deleted (I1)
            writingCallsAre(f, where, setOf("createVault $id putCurrentAside"))
            val currentId = f.port.current?.vaultId
            when (currentId) {
                VAULT -> assertEquals(where, emptyList<String>(), f.port.asideVaults.map { it.vaultId }) // before the new vault was in place
                null -> assertEquals(where, listOf(VAULT), f.port.asideVaults.map { it.vaultId }) // between storage's two renames
                id -> assertEquals(where, listOf(VAULT), f.port.asideVaults.map { it.vaultId })
                else -> throw AssertionError("$where: $currentId is current")
            }

            f.startInFront()
            when (currentId) {
                VAULT -> openWithItsPin(f, VAULT, where)
                null -> {
                    assertEquals(where, listOf(VAULT), (f.state as LockState.NoVault).aside.map { it.vaultId }) // Welcome lists it
                    openWithItsPin(f, VAULT, where) // its own PIN opens it from its own lock screen, and it is current again
                }
                else -> {
                    openCurrentWithItsPassword(f, where)
                    openWithItsPin(f, VAULT, where)
                    assertEquals(where, listOf(id), f.port.asideVaults.map { it.vaultId })
                }
            }
            assertEquals(where, VAULT, f.port.current!!.vaultId)
            assertEveryVaultOpens(f, where)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // F17 step 4: open my previous passwords

    private enum class Way { PIN, FINGERPRINT, MASTER_PASSWORD }

    private fun f17step4(way: Way) {
        lateinit var slots: Map<String, Any?>
        lateinit var keys: List<String>
        val files = HashMap<String, String>()
        atEveryBoundary(
            "F17 step 4 by $way",
            setUp = { f ->
                // Both PINs have wrong tries counted from earlier visits, so that a count that goes down
                // where §6.4 does not say so can be seen (I5).
                val vault = f.seedVault(generations = 2)
                val pin = f.plantPin(vault, row = 1, total = 4)
                f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
                val old = f.port.seedAside(OTHER_VAULT, bytes(Secrets.OTHER), putAsideAtMs = f.clock.wallMs() - 86_400_000L)
                val oldPin = f.plantPin(old, pin = Secrets.OTHER_PIN, row = 2, total = 3)
                val oldFinger = f.plantFingerprint(old)
                f.plantState(OTHER_VAULT)
                slots = mapOf("pin $VAULT" to pin, "pin $OTHER_VAULT" to oldPin, "fingerprint $OTHER_VAULT" to oldFinger)
                keys = listOf(PinWrap.alias(pin.id), PinWrap.alias(oldPin.id), RecordCodec.FINGERPRINT_ALIAS_PREFIX + oldFinger.id)
                files[VAULT] = filesOf(f, VAULT)
                files[OTHER_VAULT] = filesOf(f, OTHER_VAULT)
            },
            script = { f ->
                f.startInFront()
                await { f.engine.showAside(OTHER_VAULT) }
                when (way) {
                    Way.PIN -> f.type(Secrets.OTHER_PIN)
                    Way.FINGERPRINT -> await { f.engine.useFingerprint() }
                    Way.MASTER_PASSWORD -> {
                        await { f.engine.useMasterPassword() }
                        assertTrue(f.unlockWith(Secrets.OTHER) is MasterPasswordOutcome.Right)
                    }
                }
                assertTrue(f.state is LockState.Unlocked)
                f.answerNotices()
                f.lock()
            },
            minFileBoundaries = 2,
            minPortBoundaries = 12,
        ) { f, where ->
            // No shortcut was removed (I6) and no named key deleted.
            assertNoNamedKeyWasDeleted(f, where)
            assertTrue(where, keys.all { it in f.keys.aliases })
            assertEquals(where, slots["pin $VAULT"], f.pinRecord(VAULT).slot)
            assertEquals(where, slots["pin $OTHER_VAULT"], f.pinRecord(OTHER_VAULT).slot)
            assertEquals(where, slots["fingerprint $OTHER_VAULT"], f.fingerprintRecord(OTHER_VAULT).slot)
            // I5: the counts go down only as §6.4 says. The current vault's are not touched. The other
            // vault's wrong PINs were typed on another visit, so its right PIN here forgives none of them
            // (row 0, total as it was), its fingerprint resets nothing, and only its master password
            // returns both to 0. A charge left on disk is counted by the next process (§6.3 S), not here.
            assertEquals(where, PinRecord.Guard(false, 1, 4), f.pinRecord(VAULT).guard)
            val otherGuard = f.pinRecord(OTHER_VAULT).guard
            val allowed = when (way) {
                Way.PIN -> setOf(2 to 3, 0 to 3)
                Way.FINGERPRINT -> setOf(2 to 3)
                Way.MASTER_PASSWORD -> setOf(2 to 3, 0 to 0)
            }
            assertTrue("$where: $otherGuard", (otherGuard.row to otherGuard.total) in allowed)
            // I1: the swap is the only vault data written; no generation changed.
            writingCallsAre(f, where, setOf("swapIn $OTHER_VAULT"))
            assertEquals(where, files[VAULT], filesOf(f, VAULT))
            assertEquals(where, files[OTHER_VAULT], filesOf(f, OTHER_VAULT))
            val currentId = f.port.current?.vaultId
            val asideIds = f.port.asideVaults.map { it.vaultId }
            when (currentId) {
                VAULT -> assertEquals(where, listOf(OTHER_VAULT), asideIds)
                OTHER_VAULT -> assertEquals(where, listOf(VAULT), asideIds)
                null -> assertEquals(where, setOf(VAULT, OTHER_VAULT), asideIds.toSet()) // inside the swap: Welcome lists both
                else -> throw AssertionError("$where: $currentId is current")
            }

            // Each vault's own PIN opens it again, whichever is current now (decision 21).
            f.startInFront()
            if (currentId == null) assertEquals(where, setOf(VAULT, OTHER_VAULT), (f.state as LockState.NoVault).aside.map { it.vaultId }.toSet())
            val first = currentId ?: OTHER_VAULT
            openWithItsPin(f, first, where)
            openWithItsPin(f, if (first == VAULT) OTHER_VAULT else VAULT, where)
            assertEveryVaultOpens(f, where)
        }
    }

    @Test
    fun t14_f17step4_openedWithItsPin_aStopAtEveryBoundary() = f17step4(Way.PIN)

    @Test
    fun t14_f17step4_openedWithItsFingerprint_aStopAtEveryBoundary() = f17step4(Way.FINGERPRINT)

    @Test
    fun t14_f17step4_openedWithItsMasterPassword_aStopAtEveryBoundary() = f17step4(Way.MASTER_PASSWORD)

    // ---------------------------------------------------------------------------------------------
    // F17 step 5: "Delete" settles another vault's replaced mark first

    /** Whether the vault [vaultId] put aside offers a PIN or a fingerprint by the records on disk (§13.1). */
    private fun hasUsableShortcut(f: LockFixture, vaultId: String): Boolean {
        val newest = f.port.vault(vaultId)!!.live.first()
        val decision = EntryPolicy.decide(
            EntryPolicy.Input(
                target = EntryPolicy.Target.Aside(AsideVault(vaultId, 0L, GenerationInfo(newest.number, newest.vkId, newest.createdMs))),
                pin = f.pinOnDisk(vaultId),
                fingerprint = f.fingerprintOnDisk(vaultId),
                state = f.stateOnDisk(vaultId),
                nowWallMs = f.clock.wallMs(),
                nowElapsedMs = f.clock.elapsedMs(),
                canAuthenticate = 0,
            ),
        ) as EntryPolicy.Decision.Locked
        val keyOf = { slot: io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot?, alias: (String) -> String ->
            slot != null && alias(slot.id) in f.keys.aliases
        }
        val pinKey = keyOf((f.pinOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot) { PinWrap.alias(it) }
        val fingerprintKey = keyOf((f.fingerprintOnDisk(vaultId) as? RecordRead.Ok)?.record?.slot) { RecordCodec.FINGERPRINT_ALIAS_PREFIX + it }
        return (decision.pinUsable && pinKey) || (decision.fingerprintUsable && fingerprintKey)
    }

    @Test
    fun t14_f17step5_deleteSettlesTheReplacedMarkFirst_aStopAtEveryBoundary() {
        lateinit var removing: Set<String>
        atEveryBoundary(
            "F17 step 5",
            setUp = { f ->
                val vault = f.seedVault()
                f.plantPin(vault)
                f.plantState()
                val old = f.port.seedAside(OTHER_VAULT, bytes(Secrets.OTHER), putAsideAtMs = f.clock.wallMs() - 86_400_000L)
                val oldPin = f.plantPin(old, pin = Secrets.OTHER_PIN)
                val oldFinger = f.plantFingerprint(old)
                f.plantState(
                    OTHER_VAULT,
                    pendingMp = StateRecord.PendingMp(
                        f.clock.wallMs(), Shortcut.PIN, 0L, f.clock.elapsedMs(), old.newestVkId,
                        ByteArray(16) { 1 }, ByteArray(12) { 2 }, ByteArray(48) { 3 },
                    ),
                )
                val third = f.port.seedAside(THIRD_VAULT, bytes(Secrets.THIRD), putAsideAtMs = f.clock.wallMs() - 3_600_000L)
                f.plantPin(third, pin = Secrets.THIRD_PIN)
                f.plantState(THIRD_VAULT)
                removing = setOf(PinWrap.alias(oldPin.id), RecordCodec.FINGERPRINT_ALIAS_PREFIX + oldFinger.id)
            },
            script = { f ->
                f.startInFront()
                f.type(PIN)
                // F11 step 15 in this process (build plan 4.4): vault THIRD was made from OTHER, whose
                // slots stay until a later start (F11 step 18). The owner then opens Settings.
                val core = f.engine.core
                val mark = StateRecord.Replaced(THIRD_VAULT, f.clock.wallMs(), Shortcut.PIN, 0L, f.clock.elapsedMs())
                assertTrue(await { core.locked { core.lockOf(OTHER_VAULT).master.update(bestEffort = false) { it.copy(replaced = mark) } } })
                f.clock.advance(5_000)
                assertEquals(AsideOutcome.Done, await { f.engine.deleteAside(THIRD_VAULT, Secrets.THIRD.toCharArray()) })
                f.lock()
            },
            minFileBoundaries = 30,
            minPortBoundaries = 12,
        ) { f, where ->
            val thirdGone = f.port.vault(THIRD_VAULT) == null
            val marked = (f.stateOnDisk(OTHER_VAULT) as RecordRead.Ok).record.replaced != null
            writingCallsAre(f, where, setOf("deleteAside $THIRD_VAULT"))
            assertNoNamedKeyWasDeleted(f, where, removing)
            if (thirdGone) {
                // Once vault N is gone, the vault it was made from holds no slot and no pending password on disk.
                assertTrue("$where: N went before its mark was on disk", marked)
                assertNull(where, (f.pinOnDisk(OTHER_VAULT) as RecordRead.Ok).record.slot)
                assertNull(where, (f.fingerprintOnDisk(OTHER_VAULT) as RecordRead.Ok).record.slot)
                assertNull(where, (f.stateOnDisk(OTHER_VAULT) as RecordRead.Ok).record.pendingMp)
            }
            // From the moment a replacement is finished (its mark on disk), the old vault has no usable shortcut.
            if (marked) assertFalse("$where: a replaced vault offers a shortcut", hasUsableShortcut(f, OTHER_VAULT))

            // The next start settles what is left (§5.11 step 3) and every vault opens as it should.
            f.startInFront()
            assertNoNamedKeyWasDeleted(f, "$where, after the next start")
            if (marked) {
                assertFalse("$where: the old vault offers a shortcut after the next start", hasUsableShortcut(f, OTHER_VAULT))
                assertNull(where, f.stateRecord(OTHER_VAULT).pendingMp)
            }
            if (thirdGone) {
                assertNull("$where: the mark of a vault whose N is gone (§5.11 step 3, third row)", f.stateRecord(OTHER_VAULT).replaced)
                // Its lock directory goes right after its files. A process that dies in between leaves it,
                // and every start leaves alone a lock directory whose vault is gone (§5.11); its keys are
                // named by its records then, and the sweep keeps them.
            } else {
                assertNotNull(where, f.port.vault(THIRD_VAULT))
                if (!marked) openWithItsPin(f, OTHER_VAULT, where) // an ordinary vault put aside: its own PIN still opens it
            }
            assertEveryVaultOpens(f, where)
        }
    }

    private companion object {
        const val SEED = 314L
    }
}
