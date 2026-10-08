package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.proofInTheOpenApp
import io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults.AgedChargeKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults.Restart
import io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults.assertNoNamedKeyWasDeleted
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
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
 * lock.md tests **T15**, a master-password change killed after every step, and **T14**, crash
 * injection, for **F8** (build plan 4.4). The fake file system and the fake storage stop at every
 * boundary of a script that changes the master password (before the temp write, after the file sync,
 * after the rename, after the directory sync; before and after every storage step). After the
 * "restart", as a kill (everything handed over stays) and as a power cut (what was handed over less
 * than a second before is lost), the next process finds the table of F8:
 *
 * - **Before step 5**: nothing changed.
 * - **After step 5, before the new file is in place**: the newest copy is still under the key
 *   before. The PIN opens it with its first wrap, and the unused wrap goes at that unlock (§5.4); the
 *   old master password opens it and clears `mp_change`.
 * - **The new file is in place, and the process dies before step 8**: the new master password and
 *   the PIN open it, and a typed master password that opens it clears `mp_change`; the old one
 *   reaches "Open my passwords from before the change"; the fingerprint key is hidden unless step 7
 *   had finished.
 * - **After step 6, PIN step skipped**: the new master password once, then "Choose a PIN".
 * - **Step 6 failed and the change is started again**, and **changed twice in one process**: a slot
 *   never holds more than two wraps.
 *
 * And at every stopping point (T15): `mp_change` is set exactly while the new copy is unproven, from
 * the write of step 5 to the write of step 8 (or to the write that undoes a change that failed, or
 * for good after "Saving may not have finished"), and durably so before storage is asked; only then,
 * and only for the copy the change made, does the old password reach "Open my passwords from before
 * the change". And (T14): every vault opens with its own master password; the only vault data
 * written is `changePassword`'s own generation, and no generation changes or goes (I1); no PIN is
 * checked without a durable charge a second old (I4, by `AgedChargeKey`); the counts go down only as
 * stated (I5); no shortcut is removed (I6); no key named by a record was deleted; the PIN never opens
 * an older copy while a newer one is intact.
 */
class ChangeMasterPasswordCrashTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(900)

    private enum class Script(val fingerprint: Boolean, val pinTyped: Boolean = true) {
        /** A wrong PIN at step 3, then the right one; a finger at step 7. */
        PLAIN(fingerprint = true),

        /** "Not now" at step 3. Afterwards the new master password once. */
        PIN_STEP_SKIPPED(fingerprint = false, pinTyped = false),

        /** Step 6 fails before the new file is in place; the change is started again. */
        STEP_6_FAILED_AND_STARTED_AGAIN(fingerprint = true),

        /** Step 6 leaves a file that does not prove, in quarantine; the change is started again. */
        STEP_6_QUARANTINED_AND_STARTED_AGAIN(fingerprint = false),

        /** Two changes before the app is restarted. */
        CHANGED_TWICE_IN_ONE_PROCESS(fingerprint = true),

        /** Step 6 ends with "Saving may not have finished". */
        SAVING_MAY_NOT_HAVE_FINISHED(fingerprint = true),
    }

    private fun setUp(f: LockFixture, script: Script) {
        val vault = f.seedVault(generations = 2)
        f.plantPin(vault, total = 3) // wrong PINs from earlier visits, so that a count that goes down where it must not shows (I5)
        if (script.fingerprint) f.plantFingerprint(vault)
        f.plantState(maxGeneration = StateRecord.MaxGeneration(2, f.clock.wallMs()))
    }

    private fun run(f: LockFixture, script: Script) {
        f.startInFront()
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        val proof = f.proofInTheOpenApp() // F8 step 1
        when (script) {
            Script.PLAIN -> {
                assertEquals(ChangeOutcome.PinWrong(4), f.changeMasterPassword(proof, pin = WRONG_PIN))
                assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = true), f.changeMasterPassword(proof, pin = PIN))
            }
            Script.PIN_STEP_SKIPPED -> assertEquals(ChangeOutcome.Changed(pinKept = false, fingerprintKept = false), f.changeMasterPassword(proof, pin = null))
            Script.STEP_6_FAILED_AND_STARTED_AGAIN -> {
                f.port.failWrites(FakeVaultPort.WriteFault.NOTHING_CHANGED)
                assertEquals(ChangeOutcome.NothingChanged, f.changeMasterPassword(proof, pin = PIN))
                assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = true), f.changeMasterPassword(proof, pin = PIN))
            }
            Script.STEP_6_QUARANTINED_AND_STARTED_AGAIN -> {
                f.port.failWrites(FakeVaultPort.WriteFault.QUARANTINED)
                assertEquals(ChangeOutcome.NothingChanged, f.changeMasterPassword(proof, pin = PIN))
                assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = false), f.changeMasterPassword(proof, pin = PIN))
            }
            Script.CHANGED_TWICE_IN_ONE_PROCESS -> {
                assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = true), f.changeMasterPassword(proof, NEW_PASSWORD, pin = PIN))
                val second = f.proofInTheOpenApp(NEW_PASSWORD)
                assertEquals(ChangeOutcome.Changed(pinKept = true, fingerprintKept = true), f.changeMasterPassword(second, THIRD_PASSWORD, pin = PIN))
            }
            Script.SAVING_MAY_NOT_HAVE_FINISHED -> {
                f.port.failWrites(FakeVaultPort.WriteFault.UNCLEAR)
                assertEquals(ChangeOutcome.Unclear, f.changeMasterPassword(proof, pin = PIN))
            }
        }
        f.answerAll()
        f.lock()
        // The next unlock, in the same process.
        if (script.pinTyped) {
            f.type(PIN)
        } else {
            assertEquals(MpReason.MasterChanged, f.field.reason)
            assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
        }
        assertTrue("the app opens at the end: ${f.state}", f.state is LockState.Unlocked)
        f.answerAll()
        f.lock()
    }

    /** The master password whose slot wraps the key [vkId] of [vault]. */
    private fun passwordOf(vault: FakeVaultPort.FakeVault, vkId: String): String = String(vault.password(vkId), Charsets.UTF_8)

    /**
     * Whether `mp_change` must be set after a kill at the end of [trace] (T15, §5.6): from the write
     * of step 5 of the latest change that asked storage for a key, until the write that clears it:
     * step 8 once storage has said that the new copy is proven, or the undoing of a change whose
     * step 6 failed; and for good after "Saving may not have finished".
     */
    private fun markExpectedAfterKill(trace: List<String>): Boolean {
        val started = trace.lastIndexOf("call: newKey")
        if (started < 0) return false
        val after = trace.subList(started + 1, trace.size)
        fun stateWrites(part: List<String>) = part.count { it.endsWith("/state.rec: after the rename") }
        if (stateWrites(after) == 0) return false // step 5 has not written yet
        val published = after.indexOfFirst { it.startsWith("storage: changePassword: published") }
        return when {
            published < 0 -> stateWrites(after) == 1 // set by step 5; a second write is the undoing of a failed step 6
            after[published] != "storage: changePassword: published" -> true // "Saving may not have finished": it stays
            published == after.lastIndex -> true // the process died before the lock was told
            else -> stateWrites(after.subList(published + 1, after.size)) == 0 // until step 8 is written
        }
    }

    private fun afterRestart(script: Script, run: CrashRun) {
        val f = run.f
        val where = run.where
        val trace = run.trace
        val vault = f.port.current!!
        val newest = vault.live.first()
        val original = vault.vkIds.first()
        val keptKeys = vault.live.map { it.vkId }.toSet()

        // ---- At the stopping point, before anything runs again ----------------------------------------
        // Every record reads: a crash never leaves one that cannot be read (§5.9), and never a third wrap (§5.4).
        val pin = (f.pinOnDisk() as? RecordRead.Ok)?.record ?: throw AssertionError("$where: pin.rec is ${f.pinOnDisk()}")
        val state = (f.stateOnDisk() as? RecordRead.Ok)?.record ?: throw AssertionError("$where: state.rec is ${f.stateOnDisk()}")
        val slot = pin.slot ?: throw AssertionError("$where: the PIN's slot is gone (I6)")
        assertTrue("$where: the PIN's slot holds ${slot.wraps.size} wraps", slot.wraps.size in 1..2)
        if (script.fingerprint) {
            val finger = (f.fingerprintOnDisk() as? RecordRead.Ok)?.record ?: throw AssertionError("$where: fingerprint.rec is ${f.fingerprintOnDisk()}")
            assertTrue("$where: the fingerprint's slot", finger.slot != null && finger.slot!!.wraps.size in 1..2)
        }
        // I1: the lock asked storage for nothing but the change, and no generation that was there changed or went.
        assertTrue("$where: writing calls ${f.port.writingCalls}", f.port.writingCalls.all { it.startsWith("changePassword ") })
        val ownFile = Regex("""vault-\d+\.tmp (staged|is \.pvault|is \.bad: it did not prove)""")
        assertTrue("$where: vault files written: ${f.port.writes}", f.port.writes.all { ownFile.matches(it) })
        for (n in 1L..2L) {
            val planted = vault.generation(n)
            assertTrue("$where: generation $n is $planted", planted.suffix == FakeVaultPort.Suffix.LIVE && planted.state == FakeVaultPort.FileState.OK && planted.vkId == original)
        }
        // No key was deleted at all: nothing in a change removes a shortcut (I6), and no record names a key that is gone.
        assertEquals(where, emptyList<String>(), f.keys.deleted)
        assertNoNamedKeyWasDeleted(f, where)

        // T15: `mp_change` is set exactly while the new copy is unproven.
        val mark = state.mpChange
        val reachedTheDisk = newest.vkId != original
        val toldProven = trace.withIndex().any { (i, at) -> at == "storage: changePassword: published" && i < trace.lastIndex }
        if (run.how == Restart.KILL) {
            assertEquals("$where: mp_change is $mark", markExpectedAfterKill(trace), mark != null)
        } else if (mark == null && reachedTheDisk) {
            // A power cut may bring an earlier state.rec back, but never one without the mark under a copy
            // the lock was not told is proven: the mark was a second old before storage was asked.
            assertTrue("$where: the newest copy is unproven, and no mark is on disk", toldProven)
        }
        if (mark != null) {
            assertTrue("$where: the mark was set by a change that asked for a key", "call: newKey" in trace)
            if (reachedTheDisk && trace.last().startsWith("storage: changePassword: published")) assertEquals(where, newest.vkId, mark.toVkId)
        }
        val offered = mark != null && mark.toVkId == newest.vkId // "Open my passwords from before the change"

        // ---- The next process -------------------------------------------------------------------------
        f.startInFront()
        // I5: nothing is charged after start-up, and no count went down unless a master password was typed.
        val guard = f.pinRecord().guard
        assertFalse("$where: a charge is held after start-up", guard.charged)
        val masterPasswordTyped = "storage: checkPassword: after" in trace
        if (!masterPasswordTyped) assertTrue("$where: the counts went down to $guard", guard.total >= 3)
        if (script == Script.PLAIN && run.hmacCalls >= 2 && !toldProven) {
            assertTrue("$where: the wrong PIN of step 3 is not counted: $guard", guard.total >= 1) // only step 8 returns it to zero
        }
        // I6: both slots are the ones that were there.
        assertEquals(where, slot.id, f.pinRecord().slot?.id)

        // The PIN: usable exactly while its slot holds a wrap for the newest copy's key, and then it opens that copy.
        val pinFits = newest.vkId in f.pinWraps()
        if (script.pinTyped) assertTrue("$where: the PIN was kept, and has no wrap for the newest copy: ${f.pinWraps()}", pinFits)
        if (pinFits) {
            val pad = f.screen as? LockScreen.Pad ?: throw AssertionError("$where: ${f.screen}")
            val fingerFits = script.fingerprint && newest.vkId in f.fingerprintWraps()
            assertEquals("$where: the fingerprint key (hidden unless step 7 had finished)", fingerFits, pad.fingerprintKey)
            if (script.fingerprint && !reachedTheDisk) assertTrue("$where: the fingerprint opens the copy from before", fingerFits)
            f.type(PIN)
            assertTrue("$where: the PIN does not open: ${f.state}", f.state is LockState.Unlocked)
            assertEquals("$where: the PIN opened an older copy", newest.number, f.port.openGeneration)
            assertTrue("$where: wraps after the unlock: ${f.pinWraps()}", f.pinWraps().all { it in keptKeys } && newest.vkId in f.pinWraps())
            if (!reachedTheDisk) {
                assertEquals("$where: the unused wrap is dropped at that unlock (§5.4)", listOf(original), f.pinWraps())
                assertNull("$where: a change that never reached the disk is forgotten (§5.6)", f.stateRecord().mpChange)
            }
            f.answerAll()
            f.lock()
            if (fingerFits) {
                await { f.engine.promptIfDue() }
                assertTrue("$where: the fingerprint does not open: ${f.state}", f.state is LockState.Unlocked)
                assertEquals(where, newest.number, f.port.openGeneration)
                f.answerAll()
                f.lock()
            }
            await { f.engine.useMasterPassword() }
        } else {
            // "After step 6, PIN step skipped": the master-password screen says why.
            assertEquals(where, MpReason.MasterChanged, f.field.reason)
        }

        // The master passwords from before: nothing opens. The one from directly before the change gets
        // "Open my passwords from before the change" exactly while the change's copy is unproven.
        val before = vault.live.first { it.vkId != newest.vkId || !reachedTheDisk }.takeIf { reachedTheDisk }
        if (before != null) {
            val changedAt = vault.live.last { it.vkId == newest.vkId }.createdMs
            assertEquals(where, MasterPasswordOutcome.OlderPassword(changedAt, unfinishedChange = offered), f.unlockWith(passwordOf(vault, before.vkId)))
            assertEquals(where, LockScreen.OlderPassword(changedAt, unfinishedChange = offered), f.screen)
            if (!offered) {
                f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE) // not on that screen: nothing
                assertEquals(where, LockScreen.OlderPassword(changedAt, unfinishedChange = false), f.screen)
                assertTrue(where, f.port.writingCalls.none { it.startsWith("putNewerAside") })
            }
            f.answerOlder(if (offered) OlderPasswordAnswer.TRY_AGAIN else OlderPasswordAnswer.OK)
        }
        // The vault's master password as it stands opens the newest copy, and clears the mark.
        assertTrue("$where: the master password", f.unlockWith(passwordOf(vault, newest.vkId)) is MasterPasswordOutcome.Right)
        assertEquals(where, newest.number, f.port.openGeneration)
        assertNull("$where: a typed master password that opens the vault clears mp_change", f.stateRecord().mpChange)
        if (!pinFits) {
            // "The new master password once, then 'Choose a PIN'."
            assertEquals(where, ChoosePinReason.MASTER_CHANGED, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
        }
        assertEveryVaultOpensWith(f, where) { passwordOf(it, it.live.first().vkId) }

        // ---- "Open my passwords from before the change", on the same phone once more ----------------
        if (offered && before != null) openFromBefore(script, run, before.number)
    }

    /** F16b step by step for the copy [generation] from before an unfinished change, on the phone of [run] made again. */
    private fun openFromBefore(script: Script, run: CrashRun, generation: Long) {
        val g = run.again()
        val where = run.where + ", from before"
        val vault = g.port.current!!
        val newer = vault.generations.filter { it.number > generation }.map { it.number }
        val copy = vault.generation(generation)
        val slot = g.pinRecord().slot!!.id
        g.startInFront()
        await { g.engine.useMasterPassword() }
        val older = g.unlockWith(passwordOf(vault, copy.vkId))
        assertTrue("$where: $older", older is MasterPasswordOutcome.OlderPassword && older.unfinishedChange)

        g.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)

        assertTrue("$where: ${g.state}", g.state is LockState.Unlocked)
        assertEquals(where, generation, g.port.openGeneration)
        assertEquals("$where: the copy from before is the newest now", generation, vault.live.first().number)
        // Storage put the newer generations aside, never deleting them (I1).
        assertEquals(where, newer, vault.generations.filter { it.number > generation }.map { it.number })
        assertTrue(where, vault.generations.filter { it.number > generation }.none { it.suffix == FakeVaultPort.Suffix.LIVE })
        assertEquals(where, listOf("putNewerAside $generation"), g.port.writingCalls.filter { !it.startsWith("changePassword") })
        val state = g.stateRecord()
        assertNull("$where: mp_change = null", state.mpChange)
        assertEquals("$where: max_generation = this copy", generation, state.maxGeneration.n)
        // No shortcut is removed; the wraps for the abandoned key go by the rule of §5.4.
        assertEquals(where, slot, g.pinRecord().slot?.id)
        assertEquals(where, emptyList<String>(), g.keys.deleted)
        assertTrue("$where: wraps ${g.pinWraps()}", g.pinWraps().all { id -> vault.live.any { it.vkId == id } })
        assertEquals("$where: not reported as older than last time (F16c)", 0, g.notices.filterIsInstance<Notice.OlderThanLastTime>().size)
        g.answerAll()
        g.lock()
        if (script.pinTyped) {
            g.type(PIN)
            assertTrue("$where: the PIN after going back: ${g.state}", g.state is LockState.Unlocked)
            assertEquals(where, generation, g.port.openGeneration)
        }
        assertEveryVaultOpensWith(g, where) { passwordOf(it, it.live.first().vkId) }
    }

    private fun t15(script: Script) {
        val whole = atEveryBoundary(
            name = script.name,
            seed = SEED,
            setUp = { setUp(it, script) },
            script = { run(it, script) },
            minFileBoundaries = 40,
            minPortBoundaries = 14,
        ) { afterRestart(script, it) }
        assertTrue("I4 was checked at the PINs of the run", (whole.pinKey as AgedChargeKey).checks > 0)
        assertNotNull(whole.port.current)
        assertEquals(VAULT, whole.port.current!!.vaultId)
        assertEquals(PASSWORD, passwordOf(whole.port.current!!, whole.port.current!!.vkIds.first()))
    }

    @Test
    fun t15_t14_f8_thePlainChange_aStopAtEveryBoundary() = t15(Script.PLAIN)

    @Test
    fun t15_t14_f8_thePinStepSkipped_aStopAtEveryBoundary() = t15(Script.PIN_STEP_SKIPPED)

    @Test
    fun t15_t14_f8_step6FailedAndStartedAgain_aStopAtEveryBoundary() = t15(Script.STEP_6_FAILED_AND_STARTED_AGAIN)

    @Test
    fun t15_t14_f8_step6QuarantinedAndStartedAgain_aStopAtEveryBoundary() = t15(Script.STEP_6_QUARANTINED_AND_STARTED_AGAIN)

    @Test
    fun t15_t14_f8_changedTwiceInOneProcess_aStopAtEveryBoundary() = t15(Script.CHANGED_TWICE_IN_ONE_PROCESS)

    @Test
    fun t15_t14_f8_savingMayNotHaveFinished_aStopAtEveryBoundary() = t15(Script.SAVING_MAY_NOT_HAVE_FINISHED)

    private companion object {
        const val SEED = 408L
    }
}
