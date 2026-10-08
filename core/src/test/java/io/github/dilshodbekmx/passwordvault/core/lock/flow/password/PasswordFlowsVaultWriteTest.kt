package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockCause
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.OlderPasswordAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ProofOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * lock.md I1 and T17 for the flows of build plan 4.4: **the lock asks storage to write only in the
 * flows that may, and only the step that flow names.** In 400 random sequences of lock events with
 * F8, F10 step 4, F11 and F16b among them (a change of the master password, "I can't remember it",
 * the proof, a request, "Cancel it", "Finish now", the new password on the lock screen, "It wasn't
 * me", every answer of F16b), interleaved with PINs, fingerprints, master passwords, notices, locks,
 * the app leaving and coming back, kills and time passing, every writing call that storage sees is
 * the one the event that caused it may make: `changePassword` for a change (F8 step 6),
 * `replaceVault` for finishing (F11 step 16), `putNewerAside` for "Open my passwords from before the
 * change" and `swapIn` for "Open my passwords from 7 Oct" (F16b) or a vault put aside that opened
 * (F17 step 4). Nothing else writes vault data, and no generation is ever deleted but by storage's
 * own retirement after a new master password was typed (F8 step 10). After every PIN unlock the PIN
 * opened the newest copy; after every sequence every vault opens with its own master password.
 */
class PasswordFlowsVaultWriteTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(600)

    @Test
    fun i1_theOnlyVaultWritesAreTheFlowsOwnSteps_in400RandomSequences() {
        val done = HashMap<String, Int>()
        var events = 0
        for (seed in 1L..400L) {
            val run = Run(seed)
            try {
                run.go()
            } catch (e: Throwable) {
                throw AssertionError("seed $seed failed after: " + run.log.joinToString(" > "), e)
            }
            events += run.log.size
            for ((call, n) in run.writes) done[call] = (done[call] ?: 0) + n
        }
        // The sequences reach every flow that writes. With these seeds storage is asked for changePassword
        // 484 times, replaceVault 305, swapIn 293 and putNewerAside 22, in 21,768 events; the floor is 10.
        val counts = done.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" }
        for (call in listOf("changePassword", "replaceVault", "putNewerAside", "swapIn")) {
            assertTrue("writing calls made: $counts, in $events events", (done[call] ?: 0) >= 10)
        }
    }

    private class Run(seed: Long) {
        private val random = Random(seed)
        private val f = LockFixture(seed = seed)
        val log = ArrayList<String>()
        val writes = HashMap<String, Int>()

        /** The master passwords these sequences type: the vault's, the two a change or a replacement chooses, and a wrong one. */
        private val passwords = listOf(PASSWORD, NEW_PASSWORD, THIRD_PASSWORD, "not one of them")

        fun go() {
            setUp()
            f.startInFront()
            repeat(40 + random.nextInt(30)) {
                val before = f.port.writingCalls.size
                val wasLocked = f.state is LockState.Locked
                val allowed = event()
                val made = f.port.writingCalls.drop(before).map { it.substringBefore(' ') }
                assertTrue("${log.last()} made $made", made.all { it in allowed })
                for (call in made) writes[call] = (writes[call] ?: 0) + 1
                if (wasLocked && log.last() == "the right PIN" && f.state is LockState.Unlocked && f.port.openVaultId != null) {
                    val open = f.port.vault(f.port.openVaultId!!)!!
                    assertEquals("the PIN opened an older copy", open.live.first().number, f.port.openGeneration)
                }
                assertTrue("vault data written: ${f.port.writes}", f.port.writes.all { line -> ALLOWED_WRITES.any { it.matches(line) } })
            }
            // Every vault opens with its own master password: the one of its newest copy's key.
            assertEveryVaultOpensWith(f, "the end") { String(it.password(it.live.first().vkId), Charsets.UTF_8) }
        }

        private fun setUp() {
            val vault = f.seedVault(generations = 1 + random.nextInt(2))
            val vkId1 = vault.newestVkId
            var keys = listOf(vkId1)
            if (random.nextInt(4) == 0) { // an unfinished change, as F8 leaves one that died after step 6
                val vkId2 = vault.rekey(bytes(NEW_PASSWORD))
                keys = listOf(vkId1, vkId2)
            }
            if (random.nextInt(5) != 0) f.plantPin(vault, vkIds = keys)
            if (random.nextInt(2) == 0) f.plantFingerprint(vault, vkIds = keys)
            val daysAgo = listOf(1L, 15L, 22L)[random.nextInt(3)]
            f.plantState(
                practice = StateRecord.Practice(f.clock.wallMs() - daysAgo * DAY, 3, null),
                mpChange = if (keys.size == 2) StateRecord.MpChange(keys[1], f.clock.wallMs()) else null,
                maxGeneration = StateRecord.MaxGeneration(vault.live.first().number, f.clock.wallMs()),
                pendingMp = if (random.nextInt(4) == 0) f.plantPending(vault, creditedMs = if (random.nextBoolean()) DAY else 0L) else null,
            )
        }

        /** The master password of the newest copy of [vaultId], and of each older key it still has, from the fake's own record of them. */
        private fun passwordsOf(vaultId: String): List<String> {
            val vault = f.port.vault(vaultId) ?: return emptyList()
            val newest = vault.live.first().vkId
            return (listOf(newest) + vault.live.map { it.vkId }.filter { it != newest }.distinct()).map { String(vault.password(it), Charsets.UTF_8) }
        }

        /**
         * Half of the events follow the flows of 4.4 where the screen leads (an answer where a question
         * is shown, the password of the vault whose screen it is, an older one, a pending one, "Finish
         * now" once it is ready, "It wasn't me" while a replaced vault is on the phone), so that every
         * writing step is reached often; null leaves the event to chance.
         */
        private fun led(): Set<String>? {
            if (random.nextBoolean()) return null
            val state = f.state
            if (state is LockState.Locked) {
                val screen = state.screen
                val target = f.engine.core.target ?: return null
                when (screen) {
                    is LockScreen.OlderPassword -> {
                        val answer = listOf(OlderPasswordAnswer.OPEN_FROM_BEFORE, OlderPasswordAnswer.OPEN_FROM_BEFORE, OlderPasswordAnswer.TRY_AGAIN, OlderPasswordAnswer.OK)[random.nextInt(4)]
                        log += "led: answer $answer"
                        f.answerOlder(answer)
                        return if (answer == OlderPasswordAnswer.OPEN_FROM_BEFORE) setOf("putNewerAside") else emptySet()
                    }
                    is LockScreen.OpenOlderSet -> {
                        val answer = if (random.nextInt(10) < 7) OlderPasswordAnswer.OPEN_OLDER_SET else OlderPasswordAnswer.KEEP_NEWER_SET
                        log += "led: answer $answer"
                        f.answerOlder(answer)
                        return if (answer == OlderPasswordAnswer.OPEN_OLDER_SET) setOf("swapIn") else emptySet()
                    }
                    is LockScreen.Pad, is LockScreen.FingerprintOnly -> {
                        log += "led: use master password"
                        await { f.engine.useMasterPassword() }
                        return emptySet()
                    }
                    is LockScreen.MasterPassword -> {
                        val own = passwordsOf(target.vaultId)
                        val candidates = own + listOf(NEW_PASSWORD, THIRD_PASSWORD)
                        val password = candidates[random.nextInt(candidates.size)]
                        log += "led: a master password on the lock screen of ${if (target.aside != null) "a vault put aside" else "the current vault"}"
                        f.unlockWith(password)
                        return setOf("replaceVault", "swapIn")
                    }
                }
            }
            if (state is LockState.Unlocked) {
                val pending = f.engine.summary.value.pending
                val marked = f.port.asideVaults.firstOrNull { (f.stateOnDisk(it.vaultId) as? RecordRead.Ok)?.record?.replaced != null }
                when {
                    pending != null && pending.ready -> {
                        log += "led: finish now"
                        f.finish(if (random.nextBoolean()) NEW_PASSWORD else THIRD_PASSWORD)
                        return setOf("replaceVault")
                    }
                    pending != null -> {
                        log += "led: a day passes"
                        f.passTime(DAY)
                        return emptySet()
                    }
                    marked != null && random.nextInt(3) == 0 -> {
                        log += "led: it wasn't me"
                        await { f.engine.itWasntMe(marked.vaultId) }
                        return emptySet()
                    }
                    random.nextBoolean() -> {
                        log += "led: proof now and a request"
                        if (f.prove(PIN) is ProofOutcome.Proven || f.prove(null) is ProofOutcome.Proven) {
                            f.request(if (random.nextBoolean()) NEW_PASSWORD else THIRD_PASSWORD)
                        }
                        return emptySet()
                    }
                    else -> return null
                }
            }
            return null
        }

        /** One event, led or random; returns the writing calls it may make. */
        private fun event(): Set<String> {
            led()?.let { return it }
            val locked = f.state is LockState.Locked
            val screen = (f.state as? LockState.Locked)?.screen
            val onAside = screen?.aside != null
            fun note(name: String) {
                log += name
            }
            when (random.nextInt(26)) {
                0, 1, 2 -> {
                    note("the right PIN")
                    f.type(PIN)
                    return if (onAside) setOf("swapIn") else emptySet()
                }
                3 -> {
                    note("a wrong PIN")
                    f.type(WRONG_PIN)
                }
                4 -> {
                    note("the fingerprint")
                    if (random.nextInt(3) == 0) f.keys.scriptPrompt(FakeKeys.Prompt.Close(10))
                    await { if (random.nextBoolean()) f.engine.promptIfDue() else f.engine.useFingerprint() }
                    return if (onAside) setOf("swapIn") else emptySet()
                }
                5, 6, 7 -> {
                    val password = passwords[random.nextInt(passwords.size)]
                    note("the master password ${passwords.indexOf(password)} on the lock screen")
                    if (locked && screen !is LockScreen.MasterPassword && random.nextBoolean()) await { f.engine.useMasterPassword() }
                    f.unlockWith(password)
                    return setOf("replaceVault", "swapIn") // F11 step 13 finishes there; a vault put aside that opens is F17 step 4
                }
                8 -> {
                    note("I can't remember it")
                    f.cantRemember()
                }
                9 -> {
                    note("use PIN or Not now")
                    await { if (random.nextBoolean()) f.engine.usePin() else f.engine.notNow() }
                }
                10, 11 -> {
                    val password = passwords[random.nextInt(passwords.size)]
                    note("a change of the master password, typed ${passwords.indexOf(password)}")
                    val proof = (f.state as? LockState.Unlocked)?.proof ?: run {
                        val checked = f.check(passwords[random.nextInt(2)])
                        (checked as? MasterPasswordOutcome.Right)?.proof
                    } ?: return emptySet()
                    if (random.nextInt(5) == 0) f.port.failWrites(FakeVaultPort.WriteFault.entries[random.nextInt(3)])
                    val pin = if (random.nextBoolean()) PIN else null
                    f.changeMasterPassword(proof, if (password == "not one of them") NEW_PASSWORD else password, pin = pin)
                    return setOf("changePassword")
                }
                12 -> {
                    note("proof now")
                    f.prove(if (random.nextBoolean()) PIN else null)
                }
                13 -> {
                    note("a new master password asked for")
                    f.request(if (random.nextBoolean()) NEW_PASSWORD else THIRD_PASSWORD)
                }
                14 -> {
                    note("cancel it")
                    await { f.engine.cancelPending() }
                }
                15, 16 -> {
                    val password = passwords[random.nextInt(passwords.size)]
                    note("finish now with ${passwords.indexOf(password)}")
                    if (random.nextInt(6) == 0) f.port.failWrites(FakeVaultPort.WriteFault.entries[random.nextInt(3)])
                    f.finish(password)
                    return setOf("replaceVault")
                }
                17 -> {
                    val answer = OlderPasswordAnswer.entries[random.nextInt(OlderPasswordAnswer.entries.size)]
                    note("answer $answer")
                    f.answerOlder(answer)
                    return when (answer) {
                        OlderPasswordAnswer.OPEN_FROM_BEFORE -> setOf("putNewerAside")
                        OlderPasswordAnswer.OPEN_OLDER_SET -> setOf("swapIn")
                        else -> emptySet()
                    }
                }
                18 -> {
                    note("it wasn't me")
                    val old = f.port.asideVaults.firstOrNull()?.vaultId
                    await { f.engine.itWasntMe(if (random.nextInt(4) == 0) null else old) }
                }
                19 -> {
                    note("go back")
                    await { f.engine.goBack() }
                }
                20 -> {
                    note("a notice answered")
                    val notices = (f.state as? LockState.Unlocked)?.notices.orEmpty()
                    if (notices.isNotEmpty()) {
                        val answer = NoticeAnswer.entries[random.nextInt(NoticeAnswer.entries.size)]
                        await { f.engine.answerNotice(notices.first().id, answer) }
                    }
                }
                21 -> {
                    val cause = LockCause.entries[random.nextInt(LockCause.entries.size)]
                    note("lock, $cause")
                    f.lock(cause)
                }
                22 -> {
                    note("leave and return")
                    f.leaveAndReturn()
                }
                23 -> {
                    val ms = listOf(1_000L, 30_000L, HOUR, 12 * HOUR, DAY)[random.nextInt(5)]
                    note("time passes: $ms ms")
                    f.passTime(ms)
                }
                24 -> {
                    note("a new process")
                    f.port.clearStop()
                    f.restart()
                    f.startInFront()
                }
                else -> {
                    note("the master password in a prompt")
                    f.check(passwords[random.nextInt(passwords.size)])
                }
            }
            return emptySet()
        }
    }

    private companion object {
        /** The lines of `FakeVaultPort.writes` that the writing calls above make, and storage's retirement (F8 step 10). */
        val ALLOWED_WRITES = listOf(
            Regex("""vault-\d+\.tmp staged"""),
            Regex("""vault-\d+\.tmp is \.pvault"""),
            Regex("""vault-\d+\.tmp is \.bad: it did not prove"""),
            Regex("""vault-\d+\.pvault is \.aside"""),
            Regex("""vault-\d+\.pvault deleted: retired with its key"""),
            Regex("""new-[0-9a-f-]+/ staged"""),
            Regex("""new-[0-9a-f-]+/ discarded: it did not prove"""),
            Regex("""new-[0-9a-f-]+/ is live/"""),
            Regex("""live/ is aside-\d+-[0-9a-f-]+/"""),
            Regex("""aside-…-[0-9a-f-]+/ is live/"""),
        )
    }
}
