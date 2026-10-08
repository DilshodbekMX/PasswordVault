package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
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
 * lock.md test T14, crash injection, for **F5** (the PIN limit) and for **the start-up steps of
 * §5.11**: the fake file system and the fake storage stop at every boundary (before the temp write,
 * after the file sync, after the rename, after the directory sync; before and after every storage
 * step). After the "restart", as a kill (everything handed over stays) and as a power cut (what was
 * handed over less than a second before is lost): every vault on the phone opens with its own
 * master password; I1 to I6 hold; no key named by a durable record was deleted; and from the
 * moment a replacement is finished, the old vault has no usable shortcut at any stopping point.
 */
class CrashInjectionTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(600)

    private enum class Restart { KILL, POWER_CUT }

    /** The record files as the engine reaches them: dead as soon as the vault port has stopped. */
    private class Linked(private val inner: FakeRecordFiles, private val portAlive: () -> Boolean) : RecordFiles {
        private fun <T> alive(call: () -> T): T {
            if (!portAlive()) throw FakeVaultPort.Stopped(0, "the process is dead")
            return call()
        }

        override fun read(dir: String, name: String): ByteArray? = alive { inner.read(dir, name) }

        override fun replace(dir: String, name: String, bytes: ByteArray) = alive { inner.replace(dir, name, bytes) }

        override fun dirs(root: String): List<String> = alive { inner.dirs(root) }

        override fun deleteDir(dir: String) = alive { inner.deleteDir(dir) }

        override fun deleteStaleTmp(root: String) = alive { inner.deleteStaleTmp(root) }
    }

    /** A PIN key that checks I4 at every HMAC: the charge is on disk, and has been for at least 1,000 ms. */
    private class AgedCharge(private val inner: PinKey, private val f: LockFixture) : PinKey {
        var checks = 0

        override fun create(alias: String): KeyCreate = inner.create(alias)

        override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
            val durable = f.files.durableBytes(f.dirOf(VAULT), "pin.rec", PinGuard.CHARGE_AGE_MS)
            val record = (RecordCodec.Pin.decode(checkNotNull(durable) { "no pin.rec is durable" }, VAULT) as RecordRead.Ok).record
            assertTrue("I4: a PIN was checked without a durable charge at least 1,000 ms old", record.guard.charged)
            checks++
            return inner.hmac(alias, message)
        }
    }

    /** One phone whose process dies whole: when the record files or the vault port stop, the other is dead too. */
    private fun phone(): LockFixture {
        val f = LockFixture(seed = 14)
        f.port.processAlive = { f.files.alive }
        f.recordFiles = Linked(f.files) { f.port.alive }
        f.pinKey = AgedCharge(f.keys.pin, f)
        f.rebuild()
        return f
    }

    /** Runs [script] until the process stops; true when it did. */
    private fun untilStopped(script: () -> Unit): Boolean =
        try {
            script()
            false
        } catch (e: FakeRecordFiles.Stopped) {
            true
        } catch (e: FakeVaultPort.Stopped) {
            true
        }

    private fun restart(f: LockFixture, how: Restart) {
        if (how == Restart.POWER_CUT) f.files.powerCut(1_000)
        f.restart()
    }

    private fun vaultFiles(f: LockFixture): String =
        (listOfNotNull(f.port.current) + f.port.asideVaults).joinToString("\n") { it.vaultId + (it.putAsideAtMs ?: "") + it.generations }

    /**
     * For every record on disk that names a key, that key is still in the Keystore; except the keys in
     * [removing], those of slots whose removal was under way (lock.md §5.7: a key whose slot is removed
     * is deleted at once, and if the record write is then lost the next start removes the slot again).
     */
    private fun assertNoNamedKeyWasDeleted(f: LockFixture, where: String, removing: Set<String> = emptySet()) {
        for (dir in listOf(VAULT, OTHER_VAULT, THIRD_VAULT)) {
            (f.pinOnDisk(dir) as? RecordRead.Ok)?.record?.slot?.let { slot ->
                val alias = PinWrap.alias(slot.id)
                assertTrue("$where: pin.rec of $dir names a key that was deleted", alias in f.keys.aliases || alias in removing)
            }
            (f.fingerprintOnDisk(dir) as? RecordRead.Ok)?.record?.slot?.let { slot ->
                val alias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + slot.id
                assertTrue("$where: fingerprint.rec of $dir names a key that was deleted", alias in f.keys.aliases || alias in removing)
            }
        }
    }

    /** Every vault on the phone opens with its own master password, asked of storage as a new process would. */
    private fun assertEveryVaultOpens(f: LockFixture, where: String) {
        f.port.restart()
        assertTrue("$where: the current vault", await { f.port.openWithPassword(VaultRef.Current, bytes(PASSWORD)) } is PasswordOpen.Opened)
        for (aside in f.port.asideVaults) {
            val opened = await { f.port.openWithPassword(VaultRef.Aside(aside.vaultId), bytes("password of " + aside.vaultId)) }
            assertTrue("$where: the vault put aside ${aside.vaultId}", opened is PasswordOpen.Opened)
        }
        f.port.restart()
    }

    // ---------------------------------------------------------------------------------------------
    // F5: the PIN limit

    private class F5(val f: LockFixture, val pin: ShortcutSlot, val finger: ShortcutSlot)

    private fun f5Phone(): F5 {
        val f = phone()
        val vault = f.seedVault(generations = 2)
        val pin = f.plantPin(vault, row = 4, total = 4)
        val finger = f.plantFingerprint(vault)
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        return F5(f, pin, finger)
    }

    /** How the F5 script reaches the limit and opens the app afterwards. */
    private enum class F5Way { MASTER_PASSWORD, FINGERPRINT, IN_A_PROMPT }

    /**
     * F5 steps 1 to 4. On the pad ([F5Way.MASTER_PASSWORD], [F5Way.FINGERPRINT]): the fifth wrong PIN in
     * a row turns the PIN off and the fingerprint-only screen shows (§6.5 step 3); the user opens the
     * app with the fingerprint, or taps "Use master password" and types it. In a prompt of the open app
     * ([F5Way.IN_A_PROMPT]): the app is opened with the fingerprint, which resets no count, and the fifth
     * wrong PIN is typed where a flow asks for the current PIN (F6 step 2, F8 step 3); the PIN turns off
     * and the app stays open. Then the notices are answered and the app is locked again.
     */
    private fun f5Script(f: LockFixture, way: F5Way) {
        f.startInFront()
        if (way == F5Way.IN_A_PROMPT) {
            await { f.engine.promptIfDue() }
            assertTrue("opened by the fingerprint: ${f.state}", f.state is LockState.Unlocked)
            assertEquals(PinOutcome.Limit, await { f.engine.core.unlock.pinInPrompt(digits(WRONG_PIN)) })
            assertTrue("the app stays open", f.state is LockState.Unlocked)
        } else {
            f.type(WRONG_PIN)
            assertTrue("the fingerprint-only screen after the limit: ${f.state}", f.screen is LockScreen.FingerprintOnly)
            if (way == F5Way.FINGERPRINT) {
                await { f.engine.promptIfDue() }
            } else {
                await { f.engine.useMasterPassword() }
                assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
            }
        }
        for (notice in (f.state as LockState.Unlocked).notices) await { f.engine.answerNotice(notice.id, NoticeAnswer.OK) }
        f.lock()
    }

    private fun f5AfterRestart(phone: F5, where: String, files: String) {
        val f = phone.f
        val wrongPinChecked = (f.pinKey as AgedCharge).checks > 0
        val passwordChecked = f.port.calls.any { it.startsWith("openWithPassword") }
        // At the stopping point, before anything runs again: every key a record on disk names is
        // there, except the PIN's own, whose slot was being removed at the limit (§6.5: the key goes
        // at once, and a lost record write is counted again at the next start).
        assertNoNamedKeyWasDeleted(f, "$where, at the stopping point", removing = setOf(PinWrap.alias(phone.pin.id)))
        assertTrue("$where: the fingerprint key", RecordCodec.FINGERPRINT_ALIAS_PREFIX + phone.finger.id in f.keys.aliases)
        f.startInFront()

        // I1: nothing of the vault was written, renamed or deleted, and it opens with its master password.
        assertEquals(where, emptyList<String>(), f.port.writes)
        assertEquals(where, emptyList<String>(), f.port.writingCalls)
        assertEquals(where, files, vaultFiles(f))
        // A crash never leaves a record that cannot be read: old and new are both valid states (§5.9).
        val record = (f.pinOnDisk() as? RecordRead.Ok)?.record
        assertNotNull("$where: pin.rec is not readable", record)
        record!!
        assertTrue("$where: state.rec is not readable", f.stateOnDisk() is RecordRead.Ok)
        // A2: once the fifth wrong PIN was checked, the slot and its key are gone, whatever came in between.
        if (wrongPinChecked) {
            assertNull("$where: the slot survived the limit", record.slot)
            assertEquals(where, PinRecord.OffReason.LIMIT, record.off?.reason)
            assertFalse("$where: the key survived the limit", PinWrap.alias(phone.pin.id) in f.keys.aliases)
        }
        // The slot is gone exactly when the PIN was turned off at a limit (I6), and its key with it. A
        // master password typed afterwards returns the counts to zero; the slot stays gone (§6.4).
        assertEquals(where, record.off?.reason == PinRecord.OffReason.LIMIT, record.slot == null)
        if (record.slot != null) assertFalse("$where: a slot at a limit", PinGuard.atLimit(record.guard))
        assertEquals(where, record.slot != null, PinWrap.alias(phone.pin.id) in f.keys.aliases)
        // I5: the counts never went down, unless the master password was typed.
        assertTrue("$where: row ${record.guard.row}", record.guard.row in 0..5 && record.guard.total in 0..10)
        if (!passwordChecked) assertTrue("$where: counts went down to ${record.guard}", record.guard.row >= 4 && record.guard.total >= 4)
        assertFalse("$where: a charge is still held after start-up", record.guard.charged)
        // I6: the fingerprint slot is untouched, and no key named by a record was deleted.
        assertEquals(where, phone.finger, f.fingerprintRecord().slot)
        assertNoNamedKeyWasDeleted(f, where)

        // The master password opens the app (I10), and offers a new PIN when the old one was turned off.
        if (f.screen !is LockScreen.MasterPassword) await { f.engine.useMasterPassword() }
        val outcome = f.unlockWith(PASSWORD)
        assertTrue("$where: the master password gave $outcome", outcome is MasterPasswordOutcome.Right)
        val offers = f.notices.filterIsInstance<Notice.ChoosePin>()
        if (f.pinRecord().slot == null) {
            assertEquals(where, listOf(ChoosePinReason.TURNED_OFF to false), offers.map { it.reason to it.needsMasterPassword })
        }
        assertEquals(where, PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        assertEquals(where, emptyList<String>(), f.port.writes)
    }

    private fun f5(way: F5Way) {
        val whole = f5Phone()
        val files = vaultFiles(whole.f)
        f5Script(whole.f, way)
        val fileBoundaries = whole.f.files.boundaries
        val portBoundaries = whole.f.port.boundaries
        // Uninterrupted, the script deletes the stale temporary files (2 boundaries) and writes pin.rec
        // for the charge and for the limit (4 boundaries each); on the pad also for the notice answered,
        // and the master password adds the writes of its guard.
        assertTrue("the script writes records: $fileBoundaries boundaries", fileBoundaries >= if (way == F5Way.IN_A_PROMPT) 10 else 14)
        assertTrue("the script asks storage: $portBoundaries boundaries", portBoundaries >= 6)
        assertNull(whole.f.pinRecord().slot) // uninterrupted, it ends with the PIN turned off
        assertEquals(listOf(PinRecord.OffReason.LIMIT), listOf(whole.f.pinRecord().off?.reason))

        for (how in Restart.entries) {
            for (k in 1..fileBoundaries) {
                val phone = f5Phone()
                phone.f.files.stopAt(k)
                assertTrue("no stop at record boundary $k", untilStopped { f5Script(phone.f, way) })
                restart(phone.f, how)
                f5AfterRestart(phone, "$way, record boundary $k, $how", files)
            }
            for (k in 1..portBoundaries) {
                val phone = f5Phone()
                phone.f.port.stopAt(k)
                assertTrue("no stop at storage boundary $k", untilStopped { f5Script(phone.f, way) })
                restart(phone.f, how)
                f5AfterRestart(phone, "$way, storage boundary $k, $how", files)
            }
        }
    }

    @Test
    fun t14_f5_aStopAtEveryBoundary_thenTheMasterPassword() = f5(F5Way.MASTER_PASSWORD)

    @Test
    fun t14_f5_aStopAtEveryBoundary_thenTheFingerprint() = f5(F5Way.FINGERPRINT)

    @Test
    fun t14_f5_inAPromptOfTheOpenApp_aStopAtEveryBoundary() = f5(F5Way.IN_A_PROMPT)

    // ---------------------------------------------------------------------------------------------
    // The start-up steps of §5.11

    private fun pending(f: LockFixture, vkId: String) = StateRecord.PendingMp(
        requestedMs = f.clock.wallMs(), by = Shortcut.PIN, creditedMs = 1_000L, seenElapsedMs = f.clock.elapsedMs() - 60_000L,
        vkId = vkId, salt = ByteArray(16) { 1 }, nonce = ByteArray(12) { 2 }, ct = ByteArray(48) { 3 },
    )

    private fun replaced(f: LockFixture, newVaultId: String) =
        StateRecord.Replaced(newVaultId, f.clock.wallMs() - 5_000L, Shortcut.PIN, creditedMs = 0L, seenElapsedMs = f.clock.elapsedMs() - 60_000L)

    /** What the lock directories and the Keystore hold, without the times that differ from run to run. */
    private fun digest(f: LockFixture): String {
        val out = StringBuilder()
        for (dir in listOf(VAULT, OTHER_VAULT, THIRD_VAULT)) {
            out.append(dir).append('\n')
            out.append("  files ").append(f.files.names(f.dirOf(dir))).append('\n')
            when (val pin = f.pinOnDisk(dir)) {
                is RecordRead.Ok -> out.append("  pin slot ").append(pin.record.slot?.id).append(" off ").append(pin.record.off?.reason)
                    .append(' ').append(pin.record.guard).append(" unseen ").append(pin.record.unseen.count).append(pin.record.unseen.interrupted).append('\n')
                else -> out.append("  pin ").append(pin).append('\n')
            }
            when (val finger = f.fingerprintOnDisk(dir)) {
                is RecordRead.Ok -> out.append("  fingerprint slot ").append(finger.record.slot?.id).append(" off ").append(finger.record.off?.reason).append('\n')
                else -> out.append("  fingerprint ").append(finger).append('\n')
            }
            when (val state = f.stateOnDisk(dir)) {
                is RecordRead.Ok -> out.append("  state pending ").append(state.record.pendingMp?.creditedMs).append(" replaced ")
                    .append(state.record.replaced?.let { it.newVaultId + " " + it.creditedMs }).append(' ').append(state.record.mpGuard).append('\n')
                else -> out.append("  state ").append(state).append('\n')
            }
        }
        out.append("aliases ").append(f.keys.aliases.sorted()).append(" canary ").append(f.keys.canary)
        return out.toString()
    }

    private class Scenario(val name: String, val replacementCarriedOut: Boolean = false, val setUp: (LockFixture) -> Unit)

    private val scenarios = listOf(
        // Steps 1, 4, 5 and 6: stale .tmp files, an orphan key, a charge found (counted), a pending credit; a vault put aside with a charge of its own.
        Scenario("a charge, an orphan and a credit") { f ->
            val vault = f.seedVault()
            f.plantPin(vault, row = 1, total = 3, charged = true)
            f.plantFingerprint(vault)
            f.plantState(pendingMp = pending(f, vault.newestVkId))
            val old = f.port.seedAside(OTHER_VAULT, bytes("password of $OTHER_VAULT"))
            f.plantPin(old, charged = true)
            f.plantState(OTHER_VAULT)
            f.keys.putPinKey(RecordCodec.PIN_ALIAS_PREFIX + "00000000-0000-4000-8000-00000000aaaa", ByteArray(32) { 9 })
            f.files.put(f.dirOf(VAULT), "pin.rec.tmp", byteArrayOf(1))
            f.files.put(f.dirOf(OTHER_VAULT), "state.rec.tmp", byteArrayOf(2))
        },
        // Step 5 at a limit (F5: "a charge found at start that reaches either"): the PIN is turned off, its key deleted.
        Scenario("a charge that reaches the limit") { f ->
            val vault = f.seedVault()
            f.plantPin(vault, row = 4, total = 9, charged = true)
            f.plantFingerprint(vault)
            f.plantState()
        },
        // Step 3, first row: the replacement was carried out.
        Scenario("a replacement that was carried out", replacementCarriedOut = true) { f ->
            f.seedVault()
            f.plantState()
            val old = f.port.seedAside(OTHER_VAULT, bytes("password of $OTHER_VAULT"))
            f.plantPin(old, charged = true)
            f.plantFingerprint(old)
            f.plantState(OTHER_VAULT, pendingMp = pending(f, old.newestVkId), replaced = replaced(f, VAULT))
        },
        // Step 3, second row: the owner went back, and F16b step 5 did not finish.
        Scenario("the owner went back") { f ->
            val old = f.port.seed(OTHER_VAULT, bytes(PASSWORD))
            f.port.seedAside(VAULT, bytes("password of $VAULT"))
            f.plantState(VAULT)
            f.plantPin(old, row = 2, total = 2)
            f.plantFingerprint(old)
            f.plantState(OTHER_VAULT, pendingMp = pending(f, old.newestVkId), replaced = replaced(f, VAULT))
        },
        // Step 3, third row: the replacement did not take place.
        Scenario("a replacement that did not take place") { f ->
            val vault = f.seedVault()
            f.plantPin(vault)
            f.plantFingerprint(vault)
            f.plantState(pendingMp = pending(f, vault.newestVkId), replaced = replaced(f, THIRD_VAULT))
        },
    )

    /** The vault put aside and marked `replaced`, as §13.1 judges its lock screen from the records on disk: no PIN, no fingerprint. */
    private fun assertOldVaultHasNoUsableShortcut(f: LockFixture, where: String) {
        val aside = AsideVault(OTHER_VAULT, 0L, f.port.asideVaults.single().let { vault ->
            val newest = vault.live.first()
            io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo(newest.number, newest.vkId, newest.createdMs)
        })
        val decision = EntryPolicy.decide(
            EntryPolicy.Input(
                target = EntryPolicy.Target.Aside(aside),
                pin = f.pinOnDisk(OTHER_VAULT),
                fingerprint = f.fingerprintOnDisk(OTHER_VAULT),
                state = f.stateOnDisk(OTHER_VAULT),
                nowWallMs = f.clock.wallMs(),
                nowElapsedMs = f.clock.elapsedMs(),
                canAuthenticate = 0,
            ),
        ) as EntryPolicy.Decision.Locked
        assertFalse("$where: the old vault's PIN is usable", decision.pinUsable)
        assertFalse("$where: the old vault's fingerprint is usable", decision.fingerprintUsable)
        assertTrue(where, decision.screen is LockScreen.MasterPassword)
    }

    @Test
    fun t14_startUp_aStopAtEveryBoundary_endsWhereAnUninterruptedStartEnds() {
        for (scenario in scenarios) {
            val whole = phone()
            scenario.setUp(whole)
            val files = vaultFiles(whole)
            if (scenario.replacementCarriedOut) assertOldVaultHasNoUsableShortcut(whole, scenario.name + ", before any start")
            await { whole.engine.start() }
            val expected = digest(whole)
            val fileBoundaries = whole.files.boundaries
            val portBoundaries = whole.port.boundaries
            assertTrue(scenario.name + ": start-up writes records", fileBoundaries >= 6)
            assertEquals(scenario.name, 2, portBoundaries) // the vault port is only asked to list
            assertEquals(scenario.name, emptyList<String>(), whole.port.writes)

            for (how in Restart.entries) {
                for (k in 1..fileBoundaries + portBoundaries) {
                    val where = scenario.name + ", " + (if (k <= fileBoundaries) "record boundary $k" else "storage boundary ${k - fileBoundaries}") + ", $how"
                    val f = phone()
                    scenario.setUp(f)
                    if (k <= fileBoundaries) f.files.stopAt(k) else f.port.stopAt(k - fileBoundaries)
                    assertTrue("no stop: $where", untilStopped { await { f.engine.start() } })
                    restart(f, how)

                    // At the stopping point itself: a finished replacement leaves the old vault no usable shortcut.
                    if (scenario.replacementCarriedOut) assertOldVaultHasNoUsableShortcut(f, where)

                    await { f.engine.start() } // the next process start runs to its end
                    assertEquals(where, expected, digest(f))
                    assertNoNamedKeyWasDeleted(f, where)
                    assertEquals(where, emptyList<String>(), f.port.writes)
                    assertEquals(where, emptyList<String>(), f.port.writingCalls)
                    assertEquals(where, files, vaultFiles(f))
                    if (scenario.replacementCarriedOut) assertOldVaultHasNoUsableShortcut(f, "$where, after the restart")
                    assertEveryVaultOpens(f, where)
                }
            }
        }
    }

    /** The keys of one phone of [t14_startUp_theSweepDeletesNoKeyThatARecordNames]: those start-up removes with their slots, the orphans, the rest. */
    private class Keys(val removing: Set<String>, val orphans: Set<String>, val kept: Set<String>)

    /**
     * Steps 3, 4 and 5 of one start: a vault put aside whose replacement was carried out loses its two
     * slots and its pending password (step 3), an orphan key of each kind is swept (step 4), and a
     * charge found on the current vault reaches the limit (step 5). The current vault's fingerprint
     * key, which its record names, must stay.
     */
    private fun sweepPhone(f: LockFixture): Keys {
        val vault = f.seedVault()
        val pin = f.plantPin(vault, row = 4, total = 7, charged = true)
        val finger = f.plantFingerprint(vault)
        f.plantState()
        val old = f.port.seedAside(OTHER_VAULT, bytes("password of $OTHER_VAULT"))
        val oldPin = f.plantPin(old)
        val oldFinger = f.plantFingerprint(old)
        f.plantState(OTHER_VAULT, pendingMp = pending(f, old.newestVkId), replaced = replaced(f, VAULT))
        val orphanPin = RecordCodec.PIN_ALIAS_PREFIX + "00000000-0000-4000-8000-00000000aaaa"
        f.keys.putPinKey(orphanPin, ByteArray(32) { 9 })
        val orphanFinger = RecordCodec.FINGERPRINT_ALIAS_PREFIX + "00000000-0000-4000-8000-00000000bbbb"
        check(f.keys.fingerprint.create(orphanFinger) == KeyCreate.Ok)
        f.files.put(f.dirOf(VAULT), "pin.rec.tmp", byteArrayOf(1))
        return Keys(
            removing = setOf(PinWrap.alias(pin.id), PinWrap.alias(oldPin.id), RecordCodec.FINGERPRINT_ALIAS_PREFIX + oldFinger.id),
            orphans = setOf(orphanPin, orphanFinger),
            kept = setOf(RecordCodec.FINGERPRINT_ALIAS_PREFIX + finger.id),
        )
    }

    @Test
    fun t14_startUp_theSweepDeletesNoKeyThatARecordNames() {
        val whole = phone()
        val keys = sweepPhone(whole)
        await { whole.engine.start() }
        assertEquals(keys.kept, whole.keys.aliases.toSet()) // uninterrupted: the orphans and the removed slots' keys are gone
        assertEquals(keys.removing + keys.orphans, whole.keys.deleted.toSet())
        val fileBoundaries = whole.files.boundaries
        val portBoundaries = whole.port.boundaries
        assertTrue("start-up writes pin.rec twice, fingerprint.rec and state.rec: $fileBoundaries", fileBoundaries >= 18)

        for (how in Restart.entries) {
            for (k in 1..fileBoundaries + portBoundaries) {
                val where = (if (k <= fileBoundaries) "record boundary $k" else "storage boundary ${k - fileBoundaries}") + ", $how"
                val f = phone()
                val planted = sweepPhone(f)
                if (k <= fileBoundaries) f.files.stopAt(k) else f.port.stopAt(k - fileBoundaries)
                assertTrue("no stop: $where", untilStopped { await { f.engine.start() } })
                restart(f, how)

                // At the stopping point: a record on disk names no deleted key, other than those whose
                // slots start-up was removing; the current vault's fingerprint key is there.
                assertNoNamedKeyWasDeleted(f, where, planted.removing)
                assertTrue(where, planted.kept.all { it in f.keys.aliases })

                await { f.engine.start() } // the next start runs to its end
                assertNoNamedKeyWasDeleted(f, "$where, after the next start")
                assertEquals(where, planted.kept, f.keys.aliases.toSet())
                assertNull(where, f.pinRecord(OTHER_VAULT).slot)
                assertNull(where, f.fingerprintRecord(OTHER_VAULT).slot)
                assertNull(where, f.stateRecord(OTHER_VAULT).pendingMp)
                assertNotNull(where, f.stateRecord(OTHER_VAULT).replaced)
                assertEquals(where, PinRecord.OffReason.LIMIT, f.pinRecord().off?.reason)
                assertEquals(where, emptyList<String>(), f.port.writes)
                assertEveryVaultOpens(f, where)
            }
        }
    }
}
