package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PendingWrap
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ProofOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.RequestOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults.AgedChargeKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.vaults.assertNoNamedKeyWasDeleted
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
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
 * lock.md test **T14**, crash injection, for **F11** (asking, and finishing) and for **both
 * branches of F16b** (build plan 4.4), with the crash tables of those flows and the parts of **T16**
 * that a crash can reach: the fake file system and the fake storage stop at every boundary of each
 * script (before the temp write, after the file sync, after the rename, after the directory sync;
 * before and after every storage step), and the next process starts after a kill (everything handed
 * over stays) and after a power cut (what was handed over less than a second before is lost).
 *
 * At every stopping point: every vault on the phone opens with its own master password; the only
 * vault data written is the flow's own storage step (I1); no PIN is checked without a durable charge
 * a second old (I4, by `AgedChargeKey`); counts go down only as stated (I5); no shortcut is removed
 * but where the flow removes it (I6); no key named by a durable record was deleted; the PIN never
 * opens an older copy while a newer one is intact; and from the moment a replacement is finished,
 * the old vault has no usable shortcut.
 */
class ReplacementCrashTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(900)

    /** What setUp planted, for the checks. */
    private class Planted(val pin: ShortcutSlot, val fingerprint: ShortcutSlot?)

    private lateinit var planted: Planted

    /** The rows of each flow's crash table that the stopping points reached: every row must be reached. */
    private val reached = HashSet<String>()

    /** The vault with a PIN and fingerprint unlock (each when asked); practice past its grace unless said otherwise. */
    private fun plant(f: LockFixture, fingerprint: Boolean = true, practiceDaysAgo: Long = 22, pending: Boolean = false): FakeVaultPort.FakeVault {
        val vault = f.seedVault()
        val pin = f.plantPin(vault, total = 2)
        val finger = if (fingerprint) f.plantFingerprint(vault) else null
        f.plantState(
            practice = StateRecord.Practice(f.clock.wallMs() - practiceDaysAgo * DAY, 3, null),
            pendingMp = if (pending) f.plantPending(vault) else null,
        )
        planted = Planted(pin, finger)
        return vault
    }

    /** Whether the pending wrap on disk of [vaultId] opens with [password] and gives that vault's key. */
    private fun pendingOpens(f: LockFixture, vaultId: String, password: String): Boolean {
        val p = (f.stateOnDisk(vaultId) as? RecordRead.Ok)?.record?.pendingMp ?: return false
        val kek = await { f.kdf.kek(MasterPasswordBytes.of(password.toCharArray()), p.salt) }
        val vk = PendingWrap.open(f.aead, kek, p.nonce, p.ct, vaultId, p.vkId) ?: return false
        return vk.contentEquals(f.port.vault(vaultId)!!.vk(p.vkId))
    }

    private fun aliasesOf(slot: ShortcutSlot?, fingerprint: Boolean): Set<String> =
        setOfNotNull(slot?.let { if (fingerprint) RecordCodec.FINGERPRINT_ALIAS_PREFIX + it.id else PinWrap.alias(it.id) })

    /** Opens the vault whose lock screen is shown with its PIN, and checks that it is that vault's newest copy. */
    private fun openWithPin(f: LockFixture, where: String) {
        if (f.screen !is LockScreen.Pad) await { f.engine.usePin() }
        f.type(PIN)
        assertTrue("$where: the PIN does not open: ${f.state}", f.state is LockState.Unlocked)
        val opened = f.port.vault(f.port.openVaultId!!)!!
        assertEquals("$where: the PIN opened an older copy", opened.live.first().number, f.port.openGeneration)
    }

    // ---------------------------------------------------------------------------------------------
    // F11, asking

    private fun askingCheck(run: CrashRun, viaLockScreen: Boolean) {
        val f = run.f
        val where = run.where
        // At the stopping point.
        assertEquals("$where: I1", emptyList<String>(), f.port.writingCalls)
        assertEquals(where, emptyList<String>(), f.port.writes)
        assertEquals(where, emptyList<String>(), f.keys.deleted)
        assertNoNamedKeyWasDeleted(f, where)
        assertEquals("$where: I6", planted.pin, (f.pinOnDisk() as RecordRead.Ok).record.slot)
        assertEquals("$where: I6", planted.fingerprint, (f.fingerprintOnDisk() as RecordRead.Ok).record.slot)
        val state = (f.stateOnDisk() as? RecordRead.Ok)?.record ?: throw AssertionError("$where: state.rec ${f.stateOnDisk()}")
        val pending = state.pendingMp
        reached += if (pending != null) "asked" else "not asked"
        if (pending != null) {
            // Never a request that does not work: it opens with the password chosen, and its wait starts at 0.
            assertTrue("$where: the pending wrap", pendingOpens(f, VAULT, NEW_PASSWORD))
            assertEquals(where, Shortcut.PIN, pending.by)
            assertTrue("$where: credited ${pending.creditedMs}", pending.creditedMs < HOUR)
        }

        // The next process.
        f.startInFront()
        val guard = f.pinRecord().guard
        assertFalse(where, guard.charged)
        assertTrue("$where: I5: $guard", guard.total >= 2)
        if (pending != null) {
            // Practice is not asked while a new master password is pending; it cannot finish before its time.
            openWithPin(f, where)
            assertEquals(where, MasterPasswordOutcome.NotFinished, f.finish(NEW_PASSWORD))
            assertNotNull(where, f.engine.summary.value.pending)
            assertNull(where, f.engine.summary.value.practiceOverdueSinceMs)
        } else {
            if (viaLockScreen) {
                assertEquals(where, MpReason.PracticeDue, f.field.reason)
                f.cantRemember()
            }
            openWithPin(f, where)
            assertEquals("$where: the route works again", ProofOutcome.Proven(Shortcut.PIN), f.prove(if (viaLockScreen) null else PIN))
            assertTrue(where, f.request() is RequestOutcome.Requested)
        }
        assertEveryVaultOpensWith(f, where) { PASSWORD }
    }

    @Test
    fun t14_f11_askingFromTheLockScreen_aStopAtEveryBoundary() {
        val whole = atEveryBoundary(
            "F11 asking, lock screen",
            seed = SEED,
            setUp = { plant(it) },
            script = { f ->
                f.startInFront()
                f.cantRemember() // "I can't remember it" (F10 step 4)
                f.type(PIN)
                assertTrue(f.request(NEW_PASSWORD) is RequestOutcome.Requested)
                f.answerAll()
                f.lock()
                f.type(PIN)
                f.answerAll()
                f.lock()
            },
            minFileBoundaries = 20,
            minPortBoundaries = 6,
        ) { askingCheck(it, viaLockScreen = true) }
        assertTrue("I4 was checked", (whole.pinKey as AgedChargeKey).checks >= 2)
        assertEquals(setOf("asked", "not asked"), reached)
    }

    @Test
    fun t14_f11_askingInTheOpenApp_aStopAtEveryBoundary() {
        val whole = atEveryBoundary(
            "F11 asking, open app",
            seed = SEED,
            setUp = { plant(it, practiceDaysAgo = 1) },
            script = { f ->
                f.startInFront()
                await { f.engine.promptIfDue() } // the fingerprint opens the app: not enough
                assertEquals(ProofOutcome.Proven(Shortcut.PIN), f.prove(PIN)) // the PIN typed now, in a prompt
                assertTrue(f.request(NEW_PASSWORD) is RequestOutcome.Requested)
                f.answerAll()
                f.lock()
                f.type(PIN)
                f.answerAll()
                f.lock()
            },
            minFileBoundaries = 16,
            minPortBoundaries = 6,
        ) { askingCheck(it, viaLockScreen = false) }
        assertTrue("I4 was checked", (whole.pinKey as AgedChargeKey).checks >= 2)
        assertEquals(setOf("asked", "not asked"), reached)
    }

    // ---------------------------------------------------------------------------------------------
    // F11, finishing

    private fun finishingCheck(run: CrashRun) {
        val f = run.f
        val where = run.where
        val calls = f.port.writingCalls
        assertTrue("$where: I1: $calls", calls.size <= 1 && calls.all { it.startsWith("replaceVault ") })
        val n = calls.singleOrNull()?.substringAfter(' ')
        val old = f.port.vault(VAULT)!!
        assertEquals("$where: the old vault's files, whole", listOf(1L), old.live.map { it.number })
        assertEquals(where, emptyList<String>(), f.keys.deleted)
        assertNoNamedKeyWasDeleted(f, where)
        // The old vault's directory is not touched by finishing (step 18): its slots are as planted.
        assertEquals(where, planted.pin, (f.pinOnDisk() as RecordRead.Ok).record.slot)
        assertEquals(where, planted.fingerprint, (f.fingerprintOnDisk() as RecordRead.Ok).record.slot)
        val oldState = (f.stateOnDisk() as RecordRead.Ok).record
        assertNotNull("$where: pending_mp stays until a later start", oldState.pendingMp)
        val current = f.port.current?.vaultId

        when {
            current == VAULT -> {
                // Before step 15, or after it and before storage's last step: the old vault as it was.
                reached += if (oldState.replaced != null) "old vault, marked" else "old vault"
                assertEquals(where, emptyList<String>(), f.port.asideVaults.map { it.vaultId })
                f.startInFront()
                assertNull("$where: start-up clears a mark whose vault N is not on the phone", f.stateRecord().replaced)
                assertEveryVaultOpensWith(f, where) { PASSWORD }
                openWithPin(f, where)
                assertEquals(where, VAULT, f.port.openVaultId)
                assertTrue("$where: the pending password finishes at the next try", f.finish(NEW_PASSWORD) is MasterPasswordOutcome.Right)
            }
            current == null -> {
                // Inside storage's last step: no vault is current, and Welcome lists the old one.
                reached += "no vault"
                assertEquals(where, listOf(VAULT), f.port.asideVaults.map { it.vaultId })
                f.startInFront()
                assertEquals(where, listOf(VAULT), (f.state as LockState.NoVault).aside.map { it.vaultId })
                assertNull("$where: start-up cleared its mark", f.stateRecord().replaced)
                await { f.engine.showAside(VAULT) }
                openWithPin(f, where) // its PIN opens it and makes it current again
                assertEquals(where, VAULT, f.port.current!!.vaultId)
                assertEveryVaultOpensWith(f, where) { PASSWORD }
            }
            else -> {
                // After storage's last step: N is current, and the old vault is put aside and marked, durably.
                assertEquals(where, n, current)
                reached += "N"
                assertEquals(where, listOf(VAULT), f.port.asideVaults.map { it.vaultId })
                assertEquals("$where: the mark is on disk", n, oldState.replaced?.newVaultId)
                assertFalse("$where: the old vault offers a shortcut", hasUsableShortcut(f, VAULT))
                val stateOfN = f.stateOnDisk(current)
                if (stateOfN == RecordRead.Missing) reached += "N without lock records"
                f.startInFront()
                assertFalse("$where, after the next start", hasUsableShortcut(f, VAULT))
                assertNull(where, (f.pinOnDisk(VAULT) as RecordRead.Ok).record.slot)
                assertNull(where, f.stateRecord(VAULT).pendingMp)
                if (stateOfN == RecordRead.Missing) assertEquals("$where: before step 17", MpReason.WelcomeBack, f.field.reason)
                assertTrue(where, f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
                assertEquals(where, ChoosePinReason.NONE_HERE, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
                assertEveryVaultOpensWith(f, where) { if (it.vaultId == VAULT) PASSWORD else NEW_PASSWORD }
            }
        }
    }

    @Test
    fun t14_f11_finishingInTheOpenApp_aStopAtEveryBoundary() {
        val whole = atEveryBoundary(
            "F11 finishing, open app",
            seed = SEED,
            setUp = { plant(it, practiceDaysAgo = 1, pending = true) },
            script = { f ->
                f.startInFront()
                f.type(PIN)
                f.answerAll()
                assertTrue(f.finish(NEW_PASSWORD) is MasterPasswordOutcome.Right)
                f.answerAll()
                f.lock()
                assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
                f.answerAll()
                f.lock()
            },
            minFileBoundaries = 20,
            minPortBoundaries = 12,
        ) { finishingCheck(it) }
        assertTrue((whole.pinKey as AgedChargeKey).checks >= 1)
        assertTrue(whole.port.current!!.vaultId != VAULT)
        assertEquals(setOf("old vault", "old vault, marked", "no vault", "N", "N without lock records"), reached)
    }

    @Test
    fun t14_f11_finishingOnTheLockScreen_aStopAtEveryBoundary() {
        atEveryBoundary(
            "F11 finishing, lock screen",
            seed = SEED,
            setUp = { plant(it, practiceDaysAgo = 1, pending = true) },
            script = { f ->
                f.startInFront()
                await { f.engine.useMasterPassword() }
                assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
                f.answerAll()
                f.lock()
                assertTrue(f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
                f.answerAll()
                f.lock()
            },
            minFileBoundaries = 16,
            minPortBoundaries = 12,
        ) { finishingCheck(it) }
        assertEquals(setOf("old vault", "old vault, marked", "no vault", "N", "N without lock records"), reached)
    }

    // ---------------------------------------------------------------------------------------------
    // F16b, after an ordinary change

    @Test
    fun t14_f16b_afterAnOrdinaryChange_openMyPasswordsFromBeforeTheChange_aStopAtEveryBoundary() {
        lateinit var vkId1: String
        lateinit var vkId2: String
        atEveryBoundary(
            "F16b after a change",
            seed = SEED,
            setUp = { f ->
                val vault = f.seedVault(generations = 2)
                vkId1 = vault.newestVkId
                vkId2 = vault.rekey(bytes(NEW_PASSWORD))
                val pin = f.plantPin(vault, vkIds = listOf(vkId1, vkId2), total = 2)
                val finger = f.plantFingerprint(vault, vkIds = listOf(vkId1))
                f.plantState(mpChange = StateRecord.MpChange(vkId2, f.clock.wallMs()), maxGeneration = StateRecord.MaxGeneration(3, f.clock.wallMs()))
                planted = Planted(pin, finger)
            },
            script = { f ->
                f.startInFront()
                await { f.engine.useMasterPassword() }
                assertTrue((f.unlockWith(PASSWORD) as MasterPasswordOutcome.OlderPassword).unfinishedChange)
                f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE)
                assertTrue(f.state is LockState.Unlocked)
                f.answerAll()
                f.lock()
                f.type(PIN)
                f.answerAll()
                f.lock()
            },
            minFileBoundaries = 16,
            minPortBoundaries = 10,
        ) { run ->
            val f = run.f
            val where = run.where
            val vault = f.port.current!!
            // I1: storage was asked for this step only, and put the newer copy aside without deleting it.
            assertTrue("$where: ${f.port.writingCalls}", f.port.writingCalls.all { it == "putNewerAside 2" })
            assertEquals(where, listOf(1L, 2L, 3L), vault.generations.map { it.number })
            val newerAside = vault.generation(3).suffix == FakeVaultPort.Suffix.ASIDE
            reached += if (newerAside) "put aside" else "not yet"
            val state = (f.stateOnDisk() as RecordRead.Ok).record
            if (!newerAside) assertNotNull("$where: the copy of the change is unproven and in use, and no mark is on disk", state.mpChange)
            // No shortcut is removed, and no key deleted.
            assertEquals(where, planted.pin.id, (f.pinOnDisk() as RecordRead.Ok).record.slot?.id)
            assertEquals(where, planted.fingerprint, (f.fingerprintOnDisk() as RecordRead.Ok).record.slot)
            assertEquals(where, emptyList<String>(), f.keys.deleted)
            assertNoNamedKeyWasDeleted(f, where)

            f.startInFront()
            assertFalse(where, f.pinRecord().guard.charged)
            openWithPin(f, where)
            assertEquals(where, if (newerAside) 2L else 3L, f.port.openGeneration)
            f.answerAll()
            f.lock()
            await { f.engine.useMasterPassword() }
            val older = f.unlockWith(PASSWORD)
            if (newerAside) {
                assertTrue("$where: after step 2 the old master password simply opens the vault: $older", older is MasterPasswordOutcome.Right)
            } else {
                assertEquals(where, MasterPasswordOutcome.OlderPassword(vault.generation(3).createdMs, unfinishedChange = true), older)
                f.answerOlder(OlderPasswordAnswer.OPEN_FROM_BEFORE) // the step can be repeated
                assertTrue("$where: ${f.state}", f.state is LockState.Unlocked)
            }
            assertEquals(where, 2L, f.port.openGeneration)
            assertNull(where, f.stateRecord().mpChange)
            assertEquals(where, listOf(vkId1), f.pinWraps())
            assertEveryVaultOpensWith(f, where) { PASSWORD }
        }
        assertEquals(setOf("put aside", "not yet"), reached)
    }

    // ---------------------------------------------------------------------------------------------
    // F16b, after a replacement

    @Test
    fun t14_f16b_afterAReplacement_openMyPasswordsFrom7Oct_aStopAtEveryBoundary() {
        var n = ""
        lateinit var oldAliases: Set<String>
        atEveryBoundary(
            "F16b after a replacement",
            seed = SEED,
            setUp = { f ->
                plant(f, practiceDaysAgo = 1)
                oldAliases = aliasesOf(planted.pin, false) + aliasesOf(planted.fingerprint, true)
                f.startInFront()
                f.type(PIN)
                n = f.replaceMasterPassword()
                f.answerAll()
                f.lock()
                f.files.reboot() // what this process wrote is on the flash; the script is a later process
                f.restart()
            },
            script = { f ->
                f.startInFront() // start-up removes the old vault's slots (§5.11 step 3)
                assertEquals(MasterPasswordOutcome.Wrong, f.unlockWith(PASSWORD)) // "… open your passwords from before."
                await { f.engine.showAside(VAULT) }
                assertEquals(MasterPasswordOutcome.TooLate, f.unlockWith(PASSWORD))
                assertTrue(f.screen is LockScreen.OpenOlderSet)
                f.answerOlder(OlderPasswordAnswer.OPEN_OLDER_SET)
                assertTrue(f.state is LockState.Unlocked)
                f.answerAll()
                f.lock()
                assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
                f.answerAll()
                f.lock()
            },
            minFileBoundaries = 12,
            minPortBoundaries = 14,
        ) { run ->
            val f = run.f
            val where = run.where
            val calls = f.port.writingCalls.filter { !it.startsWith("replaceVault ") }
            assertTrue("$where: I1: $calls", calls.all { it == "swapIn $VAULT" } && calls.size <= 1)
            assertEquals(where, listOf(1L), f.port.vault(VAULT)!!.live.map { it.number })
            assertEquals(where, listOf(1L), f.port.vault(n)!!.live.map { it.number })
            // The old vault's keys go at the start of the script; a record that a power cut brings back may name them.
            assertNoNamedKeyWasDeleted(f, where, removing = oldAliases)
            val current = f.port.current?.vaultId
            val oldMark = (f.stateOnDisk(VAULT) as RecordRead.Ok).record.replaced
            reached += when (current) {
                n -> "before the swap"
                null -> "inside the swap"
                else -> if (oldMark != null) "after the swap, marked" else "after the swap"
            }
            when (current) {
                n -> {
                    // Before step 4: nothing changed. The old vault is put aside and marked, with no usable shortcut.
                    assertEquals(where, listOf(VAULT), f.port.asideVaults.map { it.vaultId })
                    assertNotNull(where, oldMark)
                    assertFalse(where, hasUsableShortcut(f, VAULT))
                    f.startInFront()
                    assertTrue(where, f.unlockWith(NEW_PASSWORD) is MasterPasswordOutcome.Right)
                    assertEveryVaultOpensWith(f, where) { if (it.vaultId == VAULT) PASSWORD else NEW_PASSWORD }
                }
                null -> {
                    // Inside the swap: no vault is current, and Welcome lists both.
                    assertEquals(where, setOf(VAULT, n), f.port.asideVaults.map { it.vaultId }.toSet())
                    if (oldMark != null) assertFalse(where, hasUsableShortcut(f, VAULT))
                    f.startInFront()
                    assertEquals(where, setOf(VAULT, n), (f.state as LockState.NoVault).aside.map { it.vaultId }.toSet())
                    assertEveryVaultOpensWith(f, where) { if (it.vaultId == VAULT) PASSWORD else NEW_PASSWORD }
                }
                VAULT -> {
                    // After step 4: the old vault is current; whatever step 5 did not write, start-up does.
                    assertEquals(where, listOf(n), f.port.asideVaults.map { it.vaultId })
                    f.startInFront()
                    assertNull(where, f.stateRecord(VAULT).replaced)
                    assertNull(where, f.pinRecord(VAULT).slot)
                    assertNull(where, f.fingerprintRecord(VAULT).slot)
                    assertNull(where, f.stateRecord(VAULT).pendingMp)
                    assertTrue(where, f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)
                    assertEquals(VAULT, f.port.openVaultId)
                    assertEveryVaultOpensWith(f, where) { if (it.vaultId == VAULT) PASSWORD else NEW_PASSWORD }
                }
                else -> throw AssertionError("$where: $current is current")
            }
        }
        assertEquals(setOf("before the swap", "inside the swap", "after the swap, marked", "after the swap"), reached)
    }

    private companion object {
        const val SEED = 411L
    }
}
