package io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintOnOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinChangeOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * lock.md test T17, invariant I1, for the flows of build plan 3.13: **the lock never writes vault
 * data**, also when the events of F6 (set and change the PIN), F9 (fingerprint unlock on and off)
 * and F10 ("Not now" on a practice screen) are among the lock events. A spy `VaultPort` sees no
 * write in 1,000 random sequences of them, mixed with unlocks by every way in, locks, the app
 * leaving and coming back, kills and power cuts, clocks that jump by hours and days (so practice
 * falls due), records that fail to write, and a Keystore that fails, loses keys or stops answering.
 * At the end every vault still opens with its own master password.
 *
 * (3.10's `NoVaultWriteTest` is the same test over the events of the core; these flows did not
 * exist when it was written.)
 */
class ShortcutNoVaultWriteTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(600)

    @Test
    fun t17_withTheEventsOfF6F9AndF10_aSpyVaultPortSeesNoWrite_in1000RandomSequences() {
        val done = IntArray(Flow.entries.size)
        var events = 0
        for (seed in 1L..1_000L) {
            val run = Sequence(seed)
            try {
                run.go()
            } catch (e: Throwable) {
                throw AssertionError("seed $seed failed after: " + run.log.joinToString(" > "), e)
            }
            events += run.log.size
            for (flow in Flow.entries) done[flow.ordinal] += run.done[flow.ordinal]
        }
        // The sequences reach the flows: each of them finishes many times, not only fails early. With
        // these seeds a PIN is set 656 times, changed 421 times, fingerprint unlock is turned on 494
        // times and off 359 times, and practice is put off with "Not now" 395 times, in 40,076 events.
        // The floors are about 70 % of that.
        val counts = Flow.entries.joinToString { "${it.name.lowercase()} ${done[it.ordinal]}" }
        for (flow in Flow.entries) assertTrue("finished: $counts, in $events events", done[flow.ordinal] >= flow.floor)
    }

    /** The flows of 3.13 that a sequence carries to their end, and how often they must at least. */
    private enum class Flow(val floor: Int) { PIN_SET(450), PIN_CHANGED(290), FINGERPRINT_ON(340), FINGERPRINT_OFF(250), NOT_NOW(270) }

    private class Sequence(seed: Long) {
        private val random = Random(seed)
        private val f = LockFixture(seed = seed)
        val log = ArrayList<String>()
        val done = IntArray(Flow.entries.size)
        private var knownPin = PIN
        private var proof: MasterProof? = null
        private var inFront = false
        private lateinit var filesBefore: String

        fun go() {
            val vault = f.seedVault(generations = 1 + random.nextInt(2))
            if (random.nextInt(4) != 0) f.plantPin(vault, row = random.nextInt(3), total = random.nextInt(5))
            if (random.nextBoolean()) f.plantFingerprint(vault)
            if (random.nextInt(8) != 0) {
                val daysAgo = listOf(0, 0, 15, 16, 20, 25)[random.nextInt(6)].toLong()
                f.plantState(practice = StateRecord.Practice(f.clock.wallMs() - daysAgo * 86_400_000L, 3, null))
            }
            filesBefore = vaultFiles()
            await { f.engine.start() }
            comeToFront()
            repeat(30 + random.nextInt(20)) {
                event()
                assertEquals("a write of vault data", emptyList<String>(), f.port.writes)
            }
            assertEquals(emptyList<String>(), f.port.writingCalls)
            assertEquals(filesBefore, vaultFiles())
            f.port.restart()
            f.port.noMemory = false
            assertTrue(await { f.port.openWithPassword(VaultRef.Current, bytes(PASSWORD)) } is PasswordOpen.Opened)
        }

        private fun vaultFiles(): String =
            (listOfNotNull(f.port.current) + f.port.asideVaults).joinToString("\n") { it.vaultId + (it.putAsideAtMs ?: "") + it.generations }

        private fun note(what: String) {
            log += what
        }

        private fun comeToFront() {
            note("start")
            inFront = true
            await { f.engine.appStarted() }
        }

        private val unlocked: Boolean get() = f.state is LockState.Unlocked

        private fun event() {
            if (!inFront) return comeToFront()
            // An open app is often locked again, and a locked one opened, so that the flows of the open
            // app and the lock screen both come round many times.
            if (!unlocked && random.nextInt(3) == 0) return unlock()
            when (random.nextInt(20)) {
                0, 1 -> setPin()
                2, 3 -> changePin()
                4, 5 -> fingerprintOn()
                6 -> fingerprintOff()
                7, 8 -> notNow()
                9 -> unlock()
                10 -> { note("check the master password"); proof = (f.check(PASSWORD) as? MasterPasswordOutcome.Right)?.proof ?: proof }
                11, 12 -> { note("lock"); f.lock() }
                13 -> { note("leave and return"); f.leaveAndReturn() }
                14 -> newProcess()
                15 -> time()
                16 -> faultyFiles()
                17 -> keystore()
                18 -> answerANotice()
                else -> { note("a wrong PIN"); usePinScreen(); f.type(listOf("582914", "111111", "368014")[random.nextInt(3)]) }
            }
        }

        /** The way in that the screen shows, as the user would take it. */
        private fun unlock() {
            val screen = (f.state as? LockState.Locked)?.screen ?: return
            when {
                screen is LockScreen.MasterPassword && screen.practice?.notNow != null && random.nextBoolean() -> notNow()
                screen is LockScreen.Pad && random.nextInt(4) != 0 -> { note("type the PIN"); f.type(knownPin) }
                (screen is LockScreen.Pad && screen.fingerprintKey) || screen is LockScreen.FingerprintOnly -> {
                    note("prompt by itself")
                    await { f.engine.promptIfDue() }
                }
                else -> {
                    note("the master password")
                    if (screen !is LockScreen.MasterPassword) await { f.engine.useMasterPassword() }
                    val outcome = f.unlockWith(PASSWORD)
                    if (outcome is MasterPasswordOutcome.Wait) f.clock.advance(outcome.untilElapsedMs - f.clock.elapsedMs())
                    proof = (outcome as? MasterPasswordOutcome.Right)?.proof ?: proof
                }
            }
        }

        private fun usePinScreen() {
            val screen = (f.state as? LockState.Locked)?.screen as? LockScreen.MasterPassword ?: return
            if (screen.canUsePin && screen.practice == null) await { f.engine.usePin() }
        }

        private fun setPin() {
            val pin = listOf(NEW_PIN, THIRD_PIN, PIN, BLOCKED_PIN)[random.nextInt(4)]
            val with = proof ?: MasterProof.forFakes(bytes(PASSWORD), LockFixture.VAULT)
            note("set the PIN $pin")
            if (f.setPin(pin, with)) {
                knownPin = pin
                done[Flow.PIN_SET.ordinal]++
            }
        }

        private fun changePin() {
            val current = if (random.nextInt(4) != 0) knownPin else "582915"
            val new = listOf(NEW_PIN, THIRD_PIN, PIN, BLOCKED_PIN, knownPin)[random.nextInt(5)]
            note("change the PIN from $current to $new")
            if (f.changePin(current, new) == PinChangeOutcome.Changed) {
                knownPin = new
                done[Flow.PIN_CHANGED.ordinal]++
            }
        }

        private fun fingerprintOn() {
            val prompt = when (random.nextInt(8)) {
                0 -> FakeKeys.Prompt.Close(listOf(10, 13, 5)[random.nextInt(3)])
                1 -> FakeKeys.Prompt.Error(listOf(7, 1, 2)[random.nextInt(3)])
                2 -> FakeKeys.Prompt.NotShown
                else -> null
            }
            note("fingerprint on" + (prompt?.let { " ($it)" } ?: ""))
            if (prompt != null) f.keys.scriptPrompt(prompt)
            val with = proof ?: MasterProof.forFakes(bytes(PASSWORD), LockFixture.VAULT)
            // A script that this call did not use (it ended before its prompt) answers a later prompt: any result will do here.
            if (f.fingerprintOn(with) == FingerprintOnOutcome.On) done[Flow.FINGERPRINT_ON.ordinal]++
        }

        private fun fingerprintOff() {
            note("fingerprint off")
            val had = unlocked && (f.fingerprintOnDisk() as? RecordRead.Ok)?.record?.slot != null
            f.fingerprintOff()
            if (had) done[Flow.FINGERPRINT_OFF.ordinal]++
        }

        private fun notNow() {
            val screen = (f.state as? LockState.Locked)?.screen as? LockScreen.MasterPassword
            note("not now")
            if (random.nextBoolean()) await { f.engine.notNow() } else await { f.engine.usePin() }
            if (screen?.practice?.notNow != null && f.state is LockState.Locked && f.screen !is LockScreen.MasterPassword) {
                done[Flow.NOT_NOW.ordinal]++
            }
        }

        private fun newProcess() {
            val powerCut = random.nextInt(3) == 0
            note(if (powerCut) "power cut" else "kill")
            if (powerCut) f.files.powerCut(1_000)
            f.restart()
            proof = null
            await { f.engine.start() }
            comeToFront()
        }

        private fun time() {
            when (random.nextInt(4)) {
                0 -> { note("wait a minute"); f.clock.advance(60_000) }
                1 -> { note("wait 21 hours"); f.clock.advance(21 * 3_600_000L) }
                2 -> { note("wait 3 days"); f.clock.advance(3 * 86_400_000L) }
                else -> { val days = random.nextInt(60) - 30; note("the date moves by $days days"); f.clock.jumpWall(days * 86_400_000L) }
            }
        }

        private fun faultyFiles() {
            when (random.nextInt(3)) {
                0 -> { note("the next write fails"); f.files.failNextWrite(FakeRecordFiles.WriteStep.entries[random.nextInt(4)]) }
                1 -> { note("the next read fails"); f.files.fail(FakeRecordFiles.Op.READ) }
                else -> { note("storage works"); f.files.clearFaults() }
            }
        }

        private fun keystore() {
            when (random.nextInt(7)) {
                0 -> { note("the Keystore stops answering"); f.keys.answering = false }
                1, 2 -> { note("the Keystore answers"); f.keys.answering = true }
                3 -> { note("a finger is added"); f.keys.invalidateFingerprintKeys() }
                4 -> { note("every key is gone"); f.keys.dropAllKeys() }
                5 -> { note("the next HMAC is temporary"); f.keys.scriptHmac(Ks.Temporary(null)) }
                else -> { val can = listOf(0, 0, -1, 1, 11)[random.nextInt(5)]; note("canAuthenticate $can"); f.keys.availability = can }
            }
        }

        private fun answerANotice() {
            val notices = (f.state as? LockState.Unlocked)?.notices.orEmpty()
            if (notices.isEmpty()) return
            val notice = notices[random.nextInt(notices.size)]
            // "Cancel it" and "It wasn't me" begin F11 and F16b, which are not events of this test.
            val answer = when (notice) {
                is Notice.Pending, is Notice.PendingReady, is Notice.Replaced -> NoticeAnswer.OK
                else -> listOf(NoticeAnswer.OK, NoticeAnswer.LATER, NoticeAnswer.CHOOSE_PIN, NoticeAnswer.TURN_ON_FINGERPRINT)[random.nextInt(4)]
            }
            note("answer " + notice.javaClass.simpleName)
            await { f.engine.answerNotice(notice.id, answer) }
        }
    }
}
