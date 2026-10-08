package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * lock.md test T17, invariant I1: **the lock never writes vault data.** A spy `VaultPort` sees no
 * write in 1,000 random sequences of lock events outside F1, F8, F11, F13, F16b and F17: digits,
 * deleting, the fingerprint with every kind of prompt result, right and wrong master passwords on
 * the lock screen and in the open app, PINs typed in prompts, notices answered, locks, the app
 * leaving and coming back, kills and new processes, clocks that jump, records that fail to write or
 * to read, a Keystore that fails, loses keys or stops answering.
 *
 * (The other half of T17, that the lock package cannot import storage's writer, is the source rule
 * `CoreSourceRulesTest.r10_theLockDoesNotReachStorageOrTheVaultFile`.)
 */
class NoVaultWriteTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(600)

    @Test
    fun t17_aSpyVaultPortSeesNoWrite_in1000RandomSequencesOfLockEvents() {
        var events = 0
        val unlocks = IntArray(Way.entries.size)
        for (seed in 1L..1_000L) {
            val run = Sequence(seed)
            try {
                run.go()
            } catch (e: Throwable) {
                throw AssertionError("seed $seed failed after: " + run.log.joinToString(" > "), e)
            }
            events += run.log.size
            for (way in Way.entries) unlocks[way.ordinal] += run.unlocks[way.ordinal]
        }
        // The sequences are not idle: each way in opens the vault many times. With these seeds the
        // vault is opened 734 times with the PIN, 1,712 times with the master password and 101 times
        // with the fingerprint, in 46,246 events. Most tries that do not open it meet a practice screen,
        // which only the master password passes here ("Not now" is F10, package 3.13, and not a lock
        // event of this test), or a screen that does not take that way in (a PIN typed while the
        // master-password field shows, and the other way round). The floors are about 70 % of that.
        val counts = Way.entries.joinToString { "${it.name.lowercase()} ${unlocks[it.ordinal]}" }
        assertTrue("unlocks: $counts, in $events events", unlocks[Way.PIN.ordinal] >= 500)
        assertTrue("unlocks: $counts, in $events events", unlocks[Way.MASTER_PASSWORD.ordinal] >= 1_200)
        assertTrue("unlocks: $counts, in $events events", unlocks[Way.FINGERPRINT.ordinal] >= 70)
    }

    /** How the event that opened the vault went in. */
    private enum class Way { PIN, MASTER_PASSWORD, FINGERPRINT, OTHER }

    private class Sequence(seed: Long) {
        private val random = Random(seed)
        private val f = LockFixture(seed = seed)
        val log = ArrayList<String>()
        val unlocks = IntArray(Way.entries.size)
        private var way = Way.OTHER
        private lateinit var filesBefore: String
        private var clockBudgetMs = 18 * 3_600_000L // a pending master password of this test never becomes ready (F11 is not a lock event here)

        fun go() {
            setUp()
            filesBefore = vaultFiles()
            await { f.engine.start() }
            if (random.nextInt(10) != 0) comeToFront()
            repeat(30 + random.nextInt(30)) {
                val wasOpen = f.state is LockState.Unlocked
                way = Way.OTHER
                event()
                if (!wasOpen && f.state is LockState.Unlocked) unlocks[way.ordinal]++
                assertEquals("a write of vault data", emptyList<String>(), f.port.writes)
            }
            assertEquals(emptyList<String>(), f.port.writingCalls)
            assertEquals(filesBefore, vaultFiles())
            // Every vault on the phone still opens with its own master password.
            f.port.restart()
            f.port.noMemory = false
            assertTrue(await { f.port.openWithPassword(VaultRef.Current, bytes(PASSWORD)) } is PasswordOpen.Opened)
            for (aside in f.port.asideVaults) {
                assertTrue(await { f.port.openWithPassword(VaultRef.Aside(aside.vaultId), bytes("older " + aside.vaultId)) } is PasswordOpen.Opened)
            }
        }

        /** Every generation file of every vault, with what reading it gives: nothing of it may change. */
        private fun vaultFiles(): String =
            (listOfNotNull(f.port.current) + f.port.asideVaults).joinToString("\n") { it.vaultId + (it.putAsideAtMs ?: "") + it.generations }

        private fun setUp() {
            val vault = f.seedVault(generations = 1 + random.nextInt(3))
            if (vault.live.size > 1 && random.nextInt(8) == 0) vault.live.first().state = FakeVaultPort.FileState.DAMAGED
            if (random.nextInt(10) != 0) { // otherwise a vault without lock records (F14)
                if (random.nextInt(5) != 0) {
                    val row = random.nextInt(5)
                    val total = (row + random.nextInt(6)).coerceAtMost(9)
                    val unseen = random.nextInt(3)
                    f.plantPin(
                        vault,
                        row = row,
                        total = total,
                        charged = random.nextInt(6) == 0,
                        unseen = PinRecord.Unseen(unseen, if (unseen > 0) f.clock.wallMs() - 1_000 else null, false),
                    )
                }
                if (random.nextBoolean()) f.plantFingerprint(vault)
                if (random.nextInt(6) != 0) {
                    val pending = if (random.nextInt(6) == 0) {
                        StateRecord.PendingMp(f.clock.wallMs(), Shortcut.PIN, 0L, f.clock.elapsedMs(), vault.newestVkId, ByteArray(16), ByteArray(12), ByteArray(48))
                    } else {
                        null
                    }
                    f.plantState(
                        practice = StateRecord.Practice(f.clock.wallMs() - random.nextInt(30) * 86_400_000L, random.nextInt(4), null),
                        mpGuard = StateRecord.MpGuard(random.nextInt(7), if (random.nextBoolean()) f.clock.elapsedMs() - 5_000 else null, random.nextInt(3)),
                        pendingMp = pending,
                        maxGeneration = StateRecord.MaxGeneration(1L + random.nextInt(4), f.clock.wallMs() - 86_400_000L),
                    )
                }
            }
            if (random.nextInt(3) == 0) { // a vault put aside, with a lock directory of its own
                val other = f.port.seedAside(OTHER_VAULT, bytes("older $OTHER_VAULT"), putAsideAtMs = f.clock.wallMs() - 86_400_000L)
                if (random.nextBoolean()) f.plantPin(other, charged = random.nextInt(4) == 0)
                if (random.nextBoolean()) f.plantFingerprint(other)
                val replaced = when (random.nextInt(3)) {
                    0 -> StateRecord.Replaced(VAULT, f.clock.wallMs() - 3_600_000L, Shortcut.PIN, 0L, f.clock.elapsedMs()) // carried out
                    1 -> StateRecord.Replaced(THIRD_VAULT, f.clock.wallMs(), Shortcut.FINGERPRINT, 0L, f.clock.elapsedMs()) // did not take place
                    else -> null
                }
                f.plantState(OTHER_VAULT, replaced = replaced)
            }
            if (random.nextInt(6) == 0) f.keys.putPinKey(RecordCodec.PIN_ALIAS_PREFIX + "00000000-0000-4000-8000-00000000aaaa", ByteArray(32)) // an orphan
            if (random.nextInt(12) == 0) {
                val name = listOf("pin.rec", "fingerprint.rec", "state.rec")[random.nextInt(3)]
                if (name in f.files.names(f.dirOf(VAULT))) {
                    f.files.damage(f.dirOf(VAULT), name) { it.also { bytes -> bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte() } }
                }
            }
            f.keys.availability = listOf(0, 0, 0, 0, -1, 1, 11, 12, 15)[random.nextInt(9)]
        }

        private fun note(what: String) {
            log += what
        }

        private var inFront = false

        private fun comeToFront() {
            note("start")
            inFront = true
            await { f.engine.appStarted() }
        }

        private fun event() {
            // What stands in the way of an unlock passes after a while, and an open app is locked again
            // soon, so that a sequence goes through the lock screen many times.
            if (!inFront && random.nextInt(3) == 0) return comeToFront()
            if (f.port.noMemory && random.nextInt(3) == 0) {
                note("memory is back")
                f.port.noMemory = false
            }
            if (!f.keys.answering && random.nextInt(4) == 0) {
                note("the Keystore answers")
                f.keys.answering = true
            }
            if (f.state is LockState.Unlocked && random.nextInt(4) == 0) {
                note("lock")
                f.lock()
                return
            }
            when (random.nextInt(30)) {
                0, 1, 2 -> typed("the right PIN", PIN)
                3, 4 -> typed("a wrong PIN", listOf("582914", "111111", "905127", "368014")[random.nextInt(4)])
                5 -> typed("some digits", PIN.take(1 + random.nextInt(5)))
                6 -> { note("delete"); await { f.engine.deleteDigit() } }
                7 -> { note("clear"); await { f.engine.clearDigits() } }
                8 -> { note("use master password"); await { f.engine.useMasterPassword() } }
                9 -> usePin()
                10, 11 -> masterPassword("the right master password", PASSWORD)
                12 -> masterPassword("a wrong master password", "wrong " + random.nextInt(3))
                13, 14 -> fingerprint(auto = true)
                15 -> fingerprint(auto = false)
                16 -> { note("check the right master password"); f.check(PASSWORD) }
                17 -> { note("check a wrong master password"); f.check("no") }
                18 -> pinInPrompt()
                19 -> answerANotice()
                20, 21 -> { note("lock"); f.lock() }
                22 -> {
                    note("stop")
                    inFront = false
                    await { f.engine.appStopped() }
                }
                23 -> comeToFront()
                24 -> { note("refresh"); await { f.engine.refresh() } }
                25 -> newProcess()
                26 -> time()
                27 -> faultyFiles()
                28 -> keystore()
                29 -> {
                    note("memory")
                    f.port.noMemory = !f.port.noMemory
                }
            }
        }

        private fun typed(what: String, digits: String) {
            // Half of the time the user goes back to the pad first, when the screen offers that.
            if (random.nextBoolean()) usePin()
            note(what)
            way = Way.PIN
            f.type(digits)
        }

        private fun masterPassword(what: String, password: String) {
            // Half of the time the user asks for the field first ("Forgot PIN? Use master password").
            if (f.state is LockState.Locked && f.screen !is LockScreen.MasterPassword && random.nextBoolean()) {
                note("use master password")
                await { f.engine.useMasterPassword() }
            }
            note(what)
            way = Way.MASTER_PASSWORD
            val outcome = f.unlockWith(password)
            if (outcome is MasterPasswordOutcome.Wait && random.nextBoolean()) { // the user waits the 30 seconds and types it again
                val ms = (outcome.untilElapsedMs - f.clock.elapsedMs()).coerceIn(0, clockBudgetMs)
                note("wait $ms ms, and again")
                clockBudgetMs -= ms
                f.clock.advance(ms)
                f.unlockWith(password)
            }
        }

        private fun usePin() {
            // "Not now: use PIN" on a practice screen is F10, another package's flow.
            val screen = (f.state as? LockState.Locked)?.screen as? LockScreen.MasterPassword ?: return
            if (screen.practice?.notNow != null || !screen.canUsePin) return
            note("use PIN")
            await { f.engine.usePin() }
        }

        private fun fingerprint(auto: Boolean) {
            val prompt = when (random.nextInt(10)) {
                0 -> FakeKeys.Prompt.Close(listOf(10, 13, 5)[random.nextInt(3)])
                1 -> FakeKeys.Prompt.Error(listOf(7, 9, 1, 2, 11, 12)[random.nextInt(6)])
                2 -> FakeKeys.Prompt.SucceededButFailed
                3 -> FakeKeys.Prompt.NotShown
                else -> null // a finger
            }
            note((if (auto) "prompt by itself" else "fingerprint key") + (prompt?.let { " ($it)" } ?: ""))
            if (prompt != null) f.keys.scriptPrompt(prompt)
            way = Way.FINGERPRINT
            if (auto) await { f.engine.promptIfDue() } else await { f.engine.useFingerprint() }
        }

        private fun pinInPrompt() {
            val pin = if (random.nextBoolean()) PIN else "582914"
            note("PIN in a prompt")
            (await { f.engine.core.unlock.pinInPrompt(digits(pin)) } as? PinOutcome.Right)?.keys?.values?.forEach { it.fill(0) }
        }

        private fun answerANotice() {
            val notices = (f.state as? LockState.Unlocked)?.notices.orEmpty()
            if (notices.isEmpty()) return
            val notice = notices[random.nextInt(notices.size)]
            // "Cancel it" and "It wasn't me" begin F11 and F16b, which are not lock events of this test.
            val answer = when (notice) {
                is Notice.Pending, is Notice.PendingReady -> NoticeAnswer.OK
                is Notice.Replaced -> NoticeAnswer.OK
                is Notice.StorageUnreadable -> if (random.nextBoolean()) NoticeAnswer.SET_UP_AGAIN else NoticeAnswer.LATER
                else -> NoticeAnswer.entries.filter { it != NoticeAnswer.CANCEL_IT && it != NoticeAnswer.IT_WASNT_ME }.let { it[random.nextInt(it.size)] }
            }
            note("answer " + notice.javaClass.simpleName)
            await { f.engine.answerNotice(notice.id, answer) }
        }

        private fun newProcess() {
            val powerCut = random.nextInt(3) == 0
            note(if (powerCut) "power cut" else "kill")
            if (powerCut) f.files.powerCut(1_000)
            f.restart()
            inFront = false
            await { f.engine.start() }
            if (random.nextInt(4) != 0) comeToFront()
        }

        private fun time() {
            when (random.nextInt(4)) {
                0, 1 -> {
                    val ms = random.nextInt(90_000).toLong().coerceAtMost(clockBudgetMs)
                    note("wait $ms ms")
                    clockBudgetMs -= ms
                    f.clock.advance(ms)
                }
                2 -> {
                    val ms = ((1 + random.nextInt(3)) * 3_600_000L).coerceAtMost(clockBudgetMs)
                    note("wait $ms ms")
                    clockBudgetMs -= ms
                    f.clock.advance(ms)
                }
                else -> {
                    val days = random.nextInt(800) - 400
                    note("the date moves by $days days")
                    f.clock.jumpWall(days * 86_400_000L)
                }
            }
        }

        private fun faultyFiles() {
            when (random.nextInt(4)) {
                0 -> { note("the next write fails"); f.files.failNextWrite(FakeRecordFiles.WriteStep.entries[random.nextInt(4)]) }
                1 -> { note("the next read fails"); f.files.fail(FakeRecordFiles.Op.READ) }
                2 -> { note("storage is full"); f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1 + random.nextInt(4)) }
                else -> { note("storage works"); f.files.clearFaults() }
            }
        }

        private fun keystore() {
            when (random.nextInt(8)) {
                0 -> { note("the Keystore stops answering"); f.keys.answering = false }
                1, 2 -> { note("the Keystore answers"); f.keys.answering = true }
                3 -> { note("a finger is added"); f.keys.invalidateFingerprintKeys() }
                4 -> { note("every key is gone"); f.keys.dropAllKeys() }
                5 -> { note("the next HMAC is temporary"); f.keys.scriptHmac(Ks.Temporary(null)) }
                6 -> { note("the next probe is temporary"); f.keys.scriptProbe(Ks.Temporary(null)) }
                else -> {
                    val can = listOf(0, 0, -1, 1, 11, 15)[random.nextInt(6)]
                    note("canAuthenticate $can")
                    f.keys.availability = can
                }
            }
        }
    }
}
