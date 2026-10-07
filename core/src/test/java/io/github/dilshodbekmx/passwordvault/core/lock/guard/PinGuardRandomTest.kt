package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordStore
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T4: [PinGuard] over 100,000 random sequences of the events T4 lists: a digit, a
 * delete, the sixth digit (wrong or right) on a lock screen or in a prompt, a fingerprint success, a
 * master password, a kill, a power cut with a settle time of 1,000 ms, a reboot, a clock jump of ±1
 * year, a wait of 0 to 90 s, the end of a visit (also while a check is running), Keystore temporary,
 * Keystore gone, a write failure; and, so that a sequence goes on after a limit, a new PIN set after
 * a master password or after the current PIN (F6). Kills and power cuts also come while a check
 * waits for the age of its charge, right after the Keystore answered and before any record is
 * written (lock.md §6.10), and inside any record write; a failed write sometimes lands all the
 * same. Each sequence has its own share of wrong PINs, so that bursts of wrong PINs on one visit
 * are frequent.
 *
 * Each sequence keeps the truth beside the guard, counted at the moment the Keystore checks a PIN:
 * the wrong PINs checked since the last right PIN or master password, and those since the last
 * master password that no right PIN was allowed to forgive (a right PIN may forgive only wrong PINs
 * typed on a lock screen in the same visit, at most 60 s before it, and at most two). After every
 * step it checks:
 *
 * - (a) no HMAC without a durable charge at least 1,000 ms old (I4): at the moment of every HMAC the
 *   record a kill would leave and the record a power cut would leave are both charged;
 * - (b) wrong PINs since the last right PIN or master password ≤ 5;
 * - (c) wrong PINs since the last master password that nothing was allowed to forgive ≤ 10;
 * - (d) the slot is removed and the key deleted exactly when a limit is reached: a record at a limit
 *   has no slot; a limit removes the slot with `off = limit` and deletes the key at once; no key is
 *   deleted but at a limit or a GONE;
 * - (e) a refund never changes a count (giving up, the end of a visit, the same PIN again, Keystore
 *   temporary or gone), and only GONE removes the slot (I6);
 * - (f) a fingerprint success changes no count and no `unseen` (I5);
 * - (g) a check whose visit has ended opens nothing (I11).
 *
 * And, as the counts of the guard in memory: `row` and `total` never fall below the truth, so the
 * guard is never less strict than the rules. The run fails with the seed and the steps of the first
 * sequence that breaks a rule. `-Ppv.t4Sequences=<n>` changes the number of sequences.
 */
class PinGuardRandomTest {

    @Test
    fun t4_pinGuardKeepsItsRulesOver100000RandomSequences() {
        val sequences = System.getProperty("pv.t4Sequences")?.toInt() ?: 100_000
        val seen = Coverage()
        for (seed in 1..sequences) Sequence(seed.toLong(), seen).run()
        println("T4: $sequences sequences; paths taken: ${seen.counts}") // kept in the test report
        if (sequences >= 100_000) seen.assertEveryPathWasTaken()
    }
}

/** How often each path was taken over all sequences, so that the test shows it reached them. */
internal class Coverage {
    val counts = sortedMapOf<String, Long>()

    fun add(what: String) {
        counts[what] = (counts[what] ?: 0L) + 1
    }

    fun assertEveryPathWasTaken() {
        for (path in PATHS) {
            val n = counts[path] ?: 0L
            assertTrue("$path was reached $n times: $counts", n >= 100)
        }
    }

    companion object {
        val PATHS = listOf(
            "outcome Wrong", "outcome Limit", "outcome Right", "outcome TooLate", "outcome SameAsBefore",
            "outcome KeyGone", "outcome CannotCheck", "start interrupted", "start limit", "start storage full",
            "charge storage full", "ended during check", "died during check", "forgiven", "power cut lost a write",
            "new pin with master password", "new pin with current pin", "right in a prompt", "wrong in a prompt",
            "refund", "fingerprint", "master password", "kill before flush", "died after the check", "stopped in a write",
            "forgiven at the cap",
        )
    }
}

private class Sequence(private val seed: Long, private val seen: Coverage) {
    private enum class Ev(val weight: Int) {
        DIGIT(14), DELETE(3), SIXTH_WRONG(4), SIXTH_RIGHT(4), FINGERPRINT(3), MASTER_PASSWORD(2), LOCK(3), END_VISIT(3),
        KILL(2), POWER_CUT(2), REBOOT(1), CLOCK_JUMP(1), WAIT(6), KS_TEMPORARY(1), KS_GONE(1), WRITE_FAILURE(3),
        STOP_IN_WRITE(2),
    }

    private class Died(val how: Ev) : Error()

    private val rnd = Random(seed)
    private val log = ArrayList<String>()

    /** How often this sequence's sixth digit completes a wrong PIN rather than the right one. */
    private val shareWrong = listOf(0.3, 0.5, 0.8)[rnd.nextInt(3)]

    /** A calm sequence mostly types: entry after entry on one visit, the bursts that forgiving is about. */
    private val calm = rnd.nextInt(3) == 0

    // Time: the truth runs on; elapsedRealtime() starts again at a reboot; the date can jump.
    private var trueMs = 50_000_000L
    private var bootAt = trueMs - 3_600_000L
    private var wallOffset = 1_791_244_800_000L - trueMs
    private var duringWait: ((Long) -> Unit)? = null
    private val clock = object : Clock {
        override fun wallMs(): Long = trueMs + wallOffset
        override fun elapsedMs(): Long = trueMs - bootAt
        override suspend fun delayUntilElapsed(elapsedMs: Long) {
            duringWait?.invoke(elapsedMs)
            val now = trueMs - bootAt
            if (elapsedMs > now) trueMs += elapsedMs - now
        }
    }

    private val store = FakeRecordStore<PinRecord>(now = { trueMs })
    private val keys = FakeKeys(FakeRandom(seed), now = { trueMs }).also { it.canary = true }
    private val aead = CryptoFactory.aead()
    private val pins = HashMap<String, ByteArray>()
    private var hmacAt: Long? = null

    /** What the Keystore's last check found: true right, false wrong, null no check or no result. */
    private var checked: Boolean? = null

    /**
     * The PIN key as the guard uses it. At the moment of every HMAC it checks (a), and when the
     * Keystore gives a result the PIN has been checked: the truth counts it here, whatever happens to
     * the process next. Now and then the process dies right after, before the guard writes anything.
     */
    private val pinKey = object : PinKey {
        override fun create(alias: String): KeyCreate = keys.pin.create(alias)

        override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
            rule("(a) I4", store.current?.guard?.charged == true && store.durable(SETTLE_MS)?.guard?.charged == true)
            hmacAt = trueMs
            val result = keys.pin.hmac(alias, message)
            if (result is Ks.Ok) {
                val slotId = alias.removePrefix(PinWrap.ALIAS_PREFIX)
                val right = message.contentEquals(PinWrap.message(VAULT, slotId, pins.getValue(slotId)))
                checked = right
                val p = place!!
                if (right) {
                    consec = 0
                    if (p.isVisit) {
                        val fresh = truthVisit.count { trueMs - it <= 60_000 }
                        if (fresh > 0) seen.add("forgiven")
                        if (fresh > 2) seen.add("forgiven at the cap")
                        unforg -= minOf(2, fresh)
                    } else {
                        seen.add("right in a prompt")
                    }
                    truthVisit.clear()
                } else {
                    consec++
                    unforg++
                    if (p.isVisit) truthVisit += trueMs else seen.add("wrong in a prompt")
                }
                if (rnd.nextInt(100) < 3) {
                    seen.add("died after the check")
                    throw Died(listOf(Ev.KILL, Ev.POWER_CUT, Ev.REBOOT)[rnd.nextInt(3)])
                }
            }
            return result
        }
    }

    // The process: the guard, the visit or prompt, the digits typed.
    private var guard: PinGuard? = null
    private var place: Place? = null
    private var places = 0L
    private var locked = true
    private var digits = 0

    // The truth.
    private var consec = 0
    private var unforg = 0
    private val truthVisit = ArrayList<Long>()

    fun run() {
        val row = if (rnd.nextInt(4) == 0) rnd.nextInt(5) else 0
        val total = row + if (rnd.nextInt(4) == 0) rnd.nextInt(10 - row) else 0
        store.set(PinRecord(VAULT, newSlot(randomPin()), PinRecord.Guard(rnd.nextInt(5) == 0, row, total), null, PinRecord.Unseen(0, null, false)))
        repeat(STEPS) { step(choose()) }
    }

    private fun choose(): Ev {
        if (guard != null && digits in 1..4 && (calm || rnd.nextInt(10) < 8)) return Ev.DIGIT
        if (guard != null && digits == 5 && (calm || rnd.nextInt(10) < 7)) {
            return if (rnd.nextDouble() < shareWrong) Ev.SIXTH_WRONG else Ev.SIXTH_RIGHT
        }
        if (calm && guard != null && rnd.nextInt(100) < 85) return Ev.DIGIT
        var pick = rnd.nextInt(TOTAL_WEIGHT)
        for (e in Ev.entries) {
            pick -= e.weight
            if (pick < 0) return e
        }
        error("unreachable")
    }

    private fun step(e: Ev) {
        log += e.name
        val deletedBefore = keys.deleted.size
        var mayDelete = false
        try {
            when (e) {
                Ev.DIGIT -> digit()
                Ev.DELETE -> delete()
                Ev.SIXTH_WRONG, Ev.SIXTH_RIGHT -> mayDelete = sixth(e == Ev.SIXTH_RIGHT)
                Ev.FINGERPRINT -> fingerprint()
                Ev.MASTER_PASSWORD -> masterPassword()
                Ev.LOCK -> lock()
                Ev.END_VISIT -> endVisit()
                Ev.KILL, Ev.POWER_CUT, Ev.REBOOT -> die(e)
                Ev.CLOCK_JUMP -> wallOffset += if (rnd.nextBoolean()) YEAR else -YEAR
                Ev.WAIT -> trueMs += rnd.nextInt(90_001)
                Ev.KS_TEMPORARY -> keys.scriptHmac(Ks.Temporary(null))
                Ev.KS_GONE -> keys.scriptHmac(Ks.Gone("invalidated"))
                Ev.WRITE_FAILURE -> store.failNextWrite(landed = rnd.nextBoolean())
                Ev.STOP_IN_WRITE -> store.stopAtNextWrite(handedOver = false)
            }
        } catch (stopped: FakeRecordStore.Stopped) {
            log += "(stopped in a write)"
            seen.add("stopped in a write")
            die(if (rnd.nextBoolean()) Ev.KILL else Ev.POWER_CUT)
        }
        mayDelete = mayDelete || startedWithLimit // a start-up inside this step turned the PIN off
        startedWithLimit = false
        rule("(d) a key deleted without a limit or GONE", mayDelete || keys.deleted.size == deletedBefore)
        invariants()
    }

    private var startedWithLimit = false

    /** The app opens: a new process starts up (lock.md §5.11 step 5). */
    private fun process(): PinGuard {
        guard?.let { return it }
        val onDisk = store.current
        val g = PinGuard(VAULT, store, clock, pinKey, keys.device, aead, FakeRandom(rnd.nextLong()))
        val start = g.startUp()
        guard = g
        place = null
        digits = 0
        locked = true
        if (start.interrupted) seen.add("start interrupted")
        if (start.storageFull) seen.add("start storage full")
        if (start.limit) {
            seen.add("start limit")
            startedWithLimit = true
            rule("(d) a limit at start-up turns the PIN off", g.record!!.slot == null && g.record!!.off?.reason == PinRecord.OffReason.LIMIT)
            rule("(d) and deletes its key", PinWrap.alias(onDisk!!.slot!!.id) !in keys.aliases)
        }
        return g
    }

    /** The visit of the lock screen, or a prompt when the app is open; a new one when none is going on. */
    private fun currentPlace(): Place = place ?: (if (locked) Place.visit(++places) else Place.prompt(++places)).also { place = it }

    private fun digit() {
        val g = process()
        if (digits == 0) {
            val p = currentPlace()
            if (!g.canCharge(p)) return // no pad: the PIN is off, cannot be checked here, or storage is full
            when (g.charge(p)) {
                PinCharge.CHARGED -> digits = 1
                PinCharge.STORAGE_FULL -> seen.add("charge storage full")
                PinCharge.NOT_USABLE -> rule("charge refused though canCharge said yes", false)
            }
        } else if (digits < 5) {
            digits++
        }
    }

    private fun delete() {
        val g = guard ?: return
        if (digits == 0) return
        digits--
        if (digits == 0) refund(g) { g.giveUp() }
    }

    /** The sixth digit; returns whether a key may have been deleted. */
    private fun sixth(right: Boolean): Boolean {
        val g = guard ?: return false
        val p = place ?: return false
        if (digits != 5) return false
        digits = 0
        val before = g.record!!
        val slot = before.slot!!
        val correct = pins.getValue(slot.id)
        val pin = if (right) correct else wrongPin(correct)
        var endedDuringCheck = false
        hmacAt = null
        checked = null
        duringWait = { target ->
            val r = rnd.nextInt(100)
            if (r < 8) {
                p.end() // the screen goes off during "Opening…": the engine marks the visit ended at once
                endedDuringCheck = true
            } else if (r < 13) {
                trueMs += rnd.nextInt(maxOf(1L, target - clock.elapsedMs()).toInt())
                seen.add("died during check")
                throw Died(listOf(Ev.KILL, Ev.POWER_CUT, Ev.REBOOT)[rnd.nextInt(3)])
            }
        }
        val outcome = try {
            runNow { g.check(pin, setOf(VK_ID)) }
        } catch (died: Died) {
            log += "(died: ${died.how})" + if (checked != null) " after the check" else ""
            die(died.how)
            return false
        } finally {
            duringWait = null
        }
        log += "-> $outcome" + if (endedDuringCheck) " (ended)" else ""
        seen.add("outcome " + outcome.javaClass.simpleName)
        if (endedDuringCheck) seen.add("ended during check")
        val after = g.record!!
        var removedSlot = false

        when (outcome) {
            is PinOutcome.Wrong, PinOutcome.Limit -> {
                rule("a wrong PIN was not checked", checked == false && !right)
                rule("(a) counted", after.guard.row == minOf(before.guard.row + 1, 5) && after.guard.total == minOf(before.guard.total + 1, 10))
                if (outcome == PinOutcome.Limit) {
                    removedSlot = true
                    rule("(d) a limit removes the slot", after.slot == null && after.off?.reason == PinRecord.OffReason.LIMIT && PinGuard.atLimit(after.guard))
                    rule("(d) and deletes its key at once", PinWrap.alias(slot.id) !in keys.aliases)
                } else {
                    rule("Wrong(left)", outcome == PinOutcome.Wrong(PinGuard.left(after.guard)) && !PinGuard.atLimit(after.guard))
                }
            }
            is PinOutcome.Right, PinOutcome.TooLate -> {
                rule("(g) a check whose visit ended opened something", !(endedDuringCheck && outcome is PinOutcome.Right))
                rule("a right PIN was not checked", checked == true && right)
                rule("a right PIN gives row 0", after.guard.row == 0)
            }
            PinOutcome.SameAsBefore, PinOutcome.CannotCheck, PinOutcome.KeyGone -> {
                seen.add("refund")
                rule("(e) a refund changed a count", after.guard.row == before.guard.row && after.guard.total == before.guard.total && after.unseen == before.unseen)
                rule("(e) the refund leaves no charge", !after.guard.charged)
                rule("a refund after a result", checked == null)
                if (outcome != PinOutcome.KeyGone) rule("I6: a refund removed something", after.slot == before.slot && after.off == before.off)
                if (outcome == PinOutcome.SameAsBefore) rule("the same PIN was checked", hmacAt == null)
                if (outcome == PinOutcome.KeyGone) {
                    removedSlot = true
                    rule("GONE removes the slot and its key", after.slot == null && PinWrap.alias(slot.id) !in keys.aliases)
                }
            }
            PinOutcome.NotCharged -> rule("a held charge was not checked", false)
        }

        // What the screen does next.
        if (outcome is PinOutcome.Right && rnd.nextInt(20) == 0) {
            seen.add("kill before flush")
            die(Ev.KILL)
            return removedSlot
        }
        g.flush()
        val closes = outcome is PinOutcome.Right || outcome == PinOutcome.TooLate || endedDuringCheck || !p.isVisit &&
            (outcome == PinOutcome.Limit || outcome == PinOutcome.KeyGone || outcome == PinOutcome.CannotCheck)
        if (closes) {
            g.placeEnded(p)
            place = null
            truthVisit.clear()
            if (outcome is PinOutcome.Right && p.isVisit) locked = false
            if (endedDuringCheck && !p.isVisit) locked = true // the app stopped
        }
        if (outcome is PinOutcome.Right && !p.isVisit && rnd.nextInt(3) == 0) {
            // F6 "Change PIN" after the current PIN: total is kept
            val newPin = randomPin()
            if (g.setPin(newSlot(newPin), masterPasswordTyped = false)) seen.add("new pin with current pin")
        }
        return removedSlot
    }

    private fun fingerprint() {
        val g = guard ?: return
        val p = place ?: return
        if (!p.isVisit) return
        seen.add("fingerprint")
        val before = g.record
        g.giveUp() // a held charge is refunded (F3 step 5)
        val after = g.record
        if (before != null && after != null) {
            rule("(f) a fingerprint changed a count", before.guard.row == after.guard.row && before.guard.total == after.guard.total)
            rule("(f) a fingerprint changed unseen", before.unseen == after.unseen)
        }
        g.placeEnded(p)
        place = null
        digits = 0
        locked = false
        truthVisit.clear()
    }

    private fun masterPassword() {
        val g = process()
        seen.add("master password")
        place?.let { p ->
            refund(g) { g.giveUp() } // "Forgot PIN? Use master password", or "Use master password instead"
            g.placeEnded(p)
            place = null
        }
        consec = 0 // the master password was verified before the guard hears of it
        unforg = 0
        truthVisit.clear()
        val unseen = g.record?.unseen
        g.masterPasswordCorrect()
        locked = false
        digits = 0
        g.record?.let {
            rule("a master password gives 0 and 0", it.guard == PinRecord.Guard(charged = false, row = 0, total = 0))
            rule("a master password takes nothing off unseen", it.unseen == unseen)
        }
        if (rnd.nextBoolean()) {
            val newPin = randomPin()
            if (g.setPin(newSlot(newPin), masterPasswordTyped = true)) seen.add("new pin with master password")
        }
    }

    private fun lock() {
        val g = guard ?: return
        if (locked) return
        place?.let { p ->
            refund(g) { g.placeEnded(p) }
            place = null
        }
        digits = 0
        locked = true
    }

    private fun endVisit() {
        val g = guard ?: return
        val p = place ?: return
        refund(g) { g.placeEnded(p) }
        place = null
        digits = 0
        truthVisit.clear()
    }

    private fun die(how: Ev) {
        val beforeCut = store.current
        when (how) {
            Ev.KILL -> store.kill()
            Ev.POWER_CUT -> {
                store.powerCut(SETTLE_MS)
                if (store.current !== beforeCut) seen.add("power cut lost a write")
                reboot()
            }
            else -> {
                store.kill()
                reboot()
            }
        }
        guard = null
        place = null
        digits = 0
        locked = true
        truthVisit.clear()
    }

    private fun reboot() {
        trueMs += rnd.nextInt(120_000)
        bootAt = trueMs - (5_000 + rnd.nextInt(600_000))
    }

    private inline fun refund(g: PinGuard, action: () -> Unit) {
        val before = g.record
        action()
        val after = g.record
        if (before != null && after != null) {
            rule("(e) a refund changed a count", before.guard.row == after.guard.row && before.guard.total == after.guard.total && before.unseen == after.unseen)
            rule("(e) the refund leaves no charge", !after.guard.charged)
        }
    }

    private fun invariants() {
        rule("(b) more than 5 wrong PINs in a row", consec <= 5)
        rule("(c) more than 10 wrong PINs not forgiven", unforg <= 10)
        val r = guard?.record ?: return
        rule("counts within the record's rules", r.guard.row in 0..5 && r.guard.total in 0..10)
        rule("(d) a record at a limit has no slot", !PinGuard.atLimit(r.guard) || r.slot == null)
        rule("the guard counts less than the truth", r.guard.row >= consec && r.guard.total >= unforg)
    }

    private fun newSlot(pin: ByteArray): ShortcutSlot {
        val id = Uuid.make(ByteArray(16).also { rnd.nextBytes(it) })
        check(keys.pin.create(PinWrap.alias(id)) == KeyCreate.Ok)
        val kek = keys.hmacOf(PinWrap.alias(id), PinWrap.message(VAULT, id, pin))
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        pins[id] = pin
        return ShortcutSlot(id, clock.wallMs(), listOf(PinWrap.seal(aead, kek, nonce, VK, VAULT, VK_ID, id)))
    }

    private fun randomPin(): ByteArray = String.format(java.util.Locale.ROOT, "%06d", rnd.nextInt(1_000_000)).toByteArray(Charsets.US_ASCII)

    /** A wrong PIN: mostly one of five, so that the same wrong PIN comes again (§6.6). */
    private fun wrongPin(right: ByteArray): ByteArray {
        while (true) {
            val pin = if (rnd.nextInt(10) < 3) randomPin() else WRONG[rnd.nextInt(WRONG.size)]
            if (!pin.contentEquals(right)) return pin
        }
    }

    private fun rule(name: String, holds: Boolean) {
        if (!holds) throw AssertionError("$name\nseed $seed, steps: ${log.joinToString(" ")}\nrecord: ${guard?.record}\ntruth: consec $consec, unforgiven $unforg")
    }

    private companion object {
        const val STEPS = 60
        const val SETTLE_MS = 1_000L
        const val YEAR = 365L * 24 * 3_600_000
        const val VAULT = PinFixture.VAULT
        const val VK_ID = PinFixture.VK_ID
        val VK = PinFixture.VK
        val WRONG = listOf("111222", "905127", "143637", "368014", "321299").map { PinFixture.pin(it) }
        val TOTAL_WEIGHT = Ev.entries.sumOf { it.weight }
    }
}
