package io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintOnOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
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
 * lock.md test T14, crash injection, for **F6** and **F9**: the fake file system and the fake
 * storage stop at every boundary (before the temp write, after the file sync, after the rename,
 * after the directory sync; before and after every storage step) of a script that sets or changes
 * the PIN, or turns fingerprint unlock on or off, then locks and opens the app with the shortcut.
 * After the "restart", as a kill (everything handed over stays) and as a power cut (what was handed
 * over less than a second before is lost):
 *
 * - every vault on the phone opens with its own master password, and the lock never wrote vault
 *   data (I1, I10);
 * - every record is readable: old and new are both valid states (§5.9);
 * - no key named by a durable record was deleted (§5.7), and after the next start no key is left
 *   that no record names (the sweep);
 * - the PIN on disk is the old one or the new one, and that one opens the newest copy (never an
 *   older copy while a newer one is intact); the fingerprint likewise;
 * - the counts never went down except as §6.4 allows (I5), and no PIN was checked without a durable
 *   charge at least 1,000 ms old (I4);
 * - the shortcut the flow did not touch is as it was (I6).
 */
class ShortcutCrashTest {
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

    /**
     * A PIN key that checks I4 at every HMAC under the key of the slot that `pin.rec` names: that
     * record is durable with a charge, and has been for at least 1,000 ms. An HMAC under a key that
     * no record names yet is the making of a new PIN (F6 steps 5 and 6), which checks no PIN.
     */
    private class AgedCharge(private val inner: PinKey, private val f: LockFixture) : PinKey {
        var checks = 0

        override fun create(alias: String): KeyCreate = inner.create(alias)

        override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
            if (alias == f.pinAliasOnDisk()) {
                val durable = f.files.durableBytes(f.dirOf(VAULT), RecordCodec.Pin.fileName, PinGuard.CHARGE_AGE_MS)
                val record = (RecordCodec.Pin.decode(checkNotNull(durable) { "no pin.rec is durable" }, VAULT) as RecordRead.Ok).record
                assertTrue("I4: a PIN was checked without a durable charge at least 1,000 ms old", record.guard.charged)
                checks++
            }
            return inner.hmac(alias, message)
        }
    }

    /** One phone whose process dies whole: when the record files or the vault port stop, the other is dead too. */
    private fun phone(): LockFixture {
        val f = LockFixture(seed = 33)
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

    /** The aliases the records on disk name. */
    private fun named(f: LockFixture): Set<String> = setOfNotNull(f.pinAliasOnDisk(), f.fingerprintAliasOnDisk())

    // ---------------------------------------------------------------------------------------------
    // The scripts

    /** What a script starts from, and what it does. */
    private enum class Script(val pin: Boolean, val fingerprint: Boolean, val state: Boolean, val row: Int = 0, val total: Int = 0) {
        /** F7 then F6 "Set": "Forgot PIN? Use master password", the master password, a new PIN in place of the old. */
        SET_AFTER_FORGOT_PIN(pin = true, fingerprint = true, state = true, row = 1, total = 3),

        /** F14 then F6 "Set": "Welcome back", the master password, the first PIN on this phone. */
        SET_ON_A_PHONE_WITHOUT_LOCK_RECORDS(pin = false, fingerprint = false, state = false),

        /** F6 "Change": the PIN opens the app, then "Change PIN" with the current PIN. */
        CHANGE(pin = true, fingerprint = true, state = true, row = 0, total = 2),

        /** F9 "On" from Settings, with the master password: the first fingerprint key. */
        FINGERPRINT_ON(pin = true, fingerprint = false, state = true),

        /** F9 "On" again (F15a step 3, F15d): a new key in place of the old one. */
        FINGERPRINT_ON_AGAIN(pin = true, fingerprint = true, state = true),

        /** F9 "Off": no proof; the key goes at once. */
        FINGERPRINT_OFF(pin = true, fingerprint = true, state = true),
    }

    /** What was on the phone before the script, for the checks after the restart. */
    private class Before(val vault: FakeVaultPort.FakeVault, val pin: ShortcutSlot?, val fingerprint: ShortcutSlot?, val files: String)

    private fun setUp(f: LockFixture, script: Script): Before {
        val vault = f.seedVault(generations = 2)
        val pin = if (script.pin) f.plantPin(vault, row = script.row, total = script.total) else null
        val fingerprint = if (script.fingerprint) f.plantFingerprint(vault) else null
        if (script.state) f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
        return Before(vault, pin, fingerprint, vaultFiles(f))
    }

    private fun run(f: LockFixture, script: Script) {
        f.startInFront()
        when (script) {
            Script.SET_AFTER_FORGOT_PIN, Script.SET_ON_A_PHONE_WITHOUT_LOCK_RECORDS -> {
                val proof = f.unlockWithMasterPassword()
                assertTrue(f.setPin(NEW_PIN, proof))
                f.answerEveryNotice()
                f.lock()
                f.type(NEW_PIN)
            }
            Script.CHANGE -> {
                f.type(PIN)
                assertTrue(f.state is LockState.Unlocked)
                assertEquals(PinChangeOutcome.Changed, f.changePin(PIN, NEW_PIN))
                f.answerEveryNotice()
                f.lock()
                f.type(NEW_PIN)
            }
            Script.FINGERPRINT_ON, Script.FINGERPRINT_ON_AGAIN -> {
                val proof = f.unlockWithMasterPassword()
                assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
                f.answerEveryNotice()
                f.lock()
                await { f.engine.promptIfDue() }
            }
            Script.FINGERPRINT_OFF -> {
                f.type(PIN)
                assertTrue(f.state is LockState.Unlocked)
                f.fingerprintOff()
                f.lock()
                f.type(PIN)
            }
        }
        assertTrue("the shortcut opens the app at the end: ${f.state}", f.state is LockState.Unlocked)
        f.answerEveryNotice()
        f.lock()
    }

    // ---------------------------------------------------------------------------------------------
    // The checks

    private fun afterRestart(f: LockFixture, script: Script, before: Before, where: String) {
        val passwordChecked = f.port.calls.any { it.startsWith("openWithPassword") || it.startsWith("checkPassword") }

        // At the stopping point, before anything runs again: every key a record on disk names is there,
        // except a fingerprint key whose slot "Off" was removing (§5.7: deleted at once).
        val removing = if (script == Script.FINGERPRINT_OFF) setOf(RecordCodec.FINGERPRINT_ALIAS_PREFIX + before.fingerprint!!.id) else emptySet()
        for (alias in named(f)) assertTrue("$where, at the stopping point: $alias was deleted", alias in f.keys.aliases || alias in removing)

        f.startInFront()

        // I1: nothing of the vault was written, renamed or deleted.
        assertEquals(where, emptyList<String>(), f.port.writes)
        assertEquals(where, emptyList<String>(), f.port.writingCalls)
        assertEquals(where, before.files, vaultFiles(f))
        // Every record is readable: a crash never leaves one that cannot be read (§5.9).
        val pinRead = f.pinOnDisk()
        val fingerprintRead = f.fingerprintOnDisk()
        assertTrue("$where: pin.rec $pinRead", pinRead is RecordRead.Ok || pinRead == RecordRead.Missing && !script.pin)
        assertTrue("$where: fingerprint.rec $fingerprintRead", fingerprintRead is RecordRead.Ok || fingerprintRead == RecordRead.Missing && !script.fingerprint)
        assertTrue("$where: state.rec", f.stateOnDisk() is RecordRead.Ok || f.stateOnDisk() == RecordRead.Missing && !script.state)

        // §5.7: the start's sweep has run over records that all read: every key there is named by a
        // record, and every key a record names is there (but the one "Off" was removing).
        assertEquals(where, emptySet<String>(), f.keys.aliases.toSet() - named(f))
        assertEquals(where, emptySet<String>(), named(f) - f.keys.aliases.toSet() - removing)

        val pin = (pinRead as? RecordRead.Ok)?.record
        val finger = (fingerprintRead as? RecordRead.Ok)?.record
        when (script) {
            Script.SET_AFTER_FORGOT_PIN, Script.SET_ON_A_PHONE_WITHOUT_LOCK_RECORDS, Script.CHANGE -> {
                // I6: the fingerprint is untouched.
                assertEquals(where, before.fingerprint, finger?.slot)
                // The PIN on disk is the old one or the new one.
                val slot = pin?.slot
                when {
                    slot == null -> assertNull("$where: a slot vanished", before.pin)
                    slot == before.pin -> assertTrue(where, f.pinOpens(before.vault, PIN))
                    else -> assertTrue("$where: a new slot the new PIN does not open", f.pinOpens(before.vault, NEW_PIN))
                }
            }
            Script.FINGERPRINT_ON, Script.FINGERPRINT_ON_AGAIN, Script.FINGERPRINT_OFF -> {
                // I6: the PIN slot is untouched.
                assertEquals(where, before.pin, pin?.slot)
                val slot = finger?.slot
                when {
                    slot == null -> assertTrue("$where: a slot vanished", before.fingerprint == null || script == Script.FINGERPRINT_OFF)
                    // The old slot: it opens as before, unless "Off" had deleted its key already (its next probe then removes it).
                    slot == before.fingerprint -> if (script != Script.FINGERPRINT_OFF) assertTrue(where, f.fingerprintOpens(before.vault))
                    else -> assertTrue("$where: a new slot that does not wrap the key", f.fingerprintOpens(before.vault))
                }
            }
        }
        // I5: the counts never went down unless the master password was typed; nothing is charged after start-up.
        if (pin != null) {
            assertFalse("$where: a charge is held after start-up", pin.guard.charged)
            assertTrue("$where: ${pin.guard}", pin.guard.row in 0..5 && pin.guard.total in 0..10)
            if (!passwordChecked && before.pin != null) assertTrue("$where: counts went down to ${pin.guard}", pin.guard.total >= script.total)
        }

        // The shortcut on disk opens the newest copy; the master password opens the app (I10).
        val newest = before.vault.live.first().number
        val pinSlot = pin?.slot
        if (pinSlot != null && f.screen is LockScreen.Pad) {
            f.type(if (pinSlot == before.pin) PIN else NEW_PIN)
            assertTrue("$where: the PIN on disk does not open: ${f.state}", f.state is LockState.Unlocked)
            assertEquals(where, newest, f.port.openGeneration)
            f.lock()
        }
        if (finger?.slot != null && (f.screen as? LockScreen.Pad)?.fingerprintKey != false) {
            val promptsBefore = f.keys.prompts.size
            await { f.engine.promptIfDue() }
            if (f.keys.prompts.size > promptsBefore) {
                assertTrue("$where: the fingerprint on disk does not open: ${f.state}", f.state is LockState.Unlocked)
                assertEquals(where, newest, f.port.openGeneration)
                f.lock()
            }
        }
        if (f.screen !is LockScreen.MasterPassword) await { f.engine.useMasterPassword() }
        assertTrue("$where: the master password", f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
        assertEquals(where, emptyList<String>(), f.port.writes)

        // Every vault on the phone opens with its own master password, asked of storage as a new process would.
        f.port.restart()
        assertTrue("$where: the current vault", await { f.port.openWithPassword(VaultRef.Current, bytes(PASSWORD)) } is PasswordOpen.Opened)
        f.port.restart()
    }

    private fun t14(script: Script) {
        // Uninterrupted, the script ends as lock.md describes.
        val whole = phone()
        val before = setUp(whole, script)
        run(whole, script)
        val fileBoundaries = whole.files.boundaries
        val portBoundaries = whole.port.boundaries
        assertTrue("$script writes records: $fileBoundaries boundaries", fileBoundaries >= 10)
        assertTrue("$script asks storage: $portBoundaries boundaries", portBoundaries >= 6)
        when (script) {
            Script.SET_AFTER_FORGOT_PIN, Script.SET_ON_A_PHONE_WITHOUT_LOCK_RECORDS, Script.CHANGE ->
                assertTrue(whole.pinOpens(before.vault, NEW_PIN))
            Script.FINGERPRINT_ON, Script.FINGERPRINT_ON_AGAIN -> assertTrue(whole.fingerprintOpens(before.vault))
            Script.FINGERPRINT_OFF -> assertEquals(FingerprintRecord.OffReason.TURNED_OFF, whole.fingerprintRecord().off?.reason)
        }
        if (script != Script.FINGERPRINT_ON && script != Script.FINGERPRINT_ON_AGAIN) {
            assertTrue("I4 was checked at the unlocks with the PIN", (whole.pinKey as AgedCharge).checks > 0)
        }

        var runs = 0
        for (how in Restart.entries) {
            for (k in 1..fileBoundaries + portBoundaries) {
                val where = "$script, " + (if (k <= fileBoundaries) "record boundary $k" else "storage boundary ${k - fileBoundaries}") + ", $how"
                val f = phone()
                val planted = setUp(f, script)
                if (k <= fileBoundaries) f.files.stopAt(k) else f.port.stopAt(k - fileBoundaries)
                assertTrue("no stop: $where", untilStopped { run(f, script) })
                restart(f, how)
                afterRestart(f, script, planted, where)
                runs++
            }
        }
        assertEquals(2 * (fileBoundaries + portBoundaries), runs)
    }

    @Test
    fun t14_f6_setAfterForgotPin_aStopAtEveryBoundary() = t14(Script.SET_AFTER_FORGOT_PIN)

    @Test
    fun t14_f6_setOnAPhoneWithoutLockRecords_aStopAtEveryBoundary() = t14(Script.SET_ON_A_PHONE_WITHOUT_LOCK_RECORDS)

    @Test
    fun t14_f6_change_aStopAtEveryBoundary() = t14(Script.CHANGE)

    @Test
    fun t14_f9_on_aStopAtEveryBoundary() = t14(Script.FINGERPRINT_ON)

    @Test
    fun t14_f9_onAgain_aStopAtEveryBoundary() = t14(Script.FINGERPRINT_ON_AGAIN)

    @Test
    fun t14_f9_off_aStopAtEveryBoundary() = t14(Script.FINGERPRINT_OFF)

    /**
     * The one write of F6 that matters, looked at closely: at each of the four boundaries of the
     * `pin.rec` write that carries the new slot, and with a power cut at each moment up to a second
     * later, the record on disk names a key that is there.
     */
    @Test
    fun t14_f6_theNewSlotsWrite_atEachOfItsBoundaries_andAPowerCutUpToASecondLater() {
        for (boundary in 1..4) {
            for (laterMs in listOf(0L, 1L, 999L, 1_000L, 1_001L)) {
                val where = "boundary $boundary of the new slot's write, power cut ${laterMs} ms later"
                val f = phone()
                val before = setUp(f, Script.SET_AFTER_FORGOT_PIN)
                f.startInFront()
                val proof = f.unlockWithMasterPassword()
                f.files.stopAt(boundary)
                assertTrue(where, untilStopped { f.setPin(NEW_PIN, proof) })
                f.clock.advance(laterMs)
                f.files.powerCut(1_000)
                f.restart()
                val alias = f.pinAliasOnDisk()
                assertNotNull(where, alias)
                assertTrue("$where: $alias", alias in f.keys.aliases)
                f.startInFront()
                val record = f.pinRecord()
                val pin = if (record.slot == before.pin) PIN else NEW_PIN
                if (f.screen !is LockScreen.Pad) await { f.engine.usePin() }
                f.type(pin)
                assertTrue("$where: ${f.state}", f.state is LockState.Unlocked)
                assertEquals(where, setOf(PinWrap.alias(f.pinRecord().slot!!.id)), f.keys.aliases.filter { it.startsWith(RecordCodec.PIN_ALIAS_PREFIX) }.toSet())
            }
        }
    }
}
