package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T20, the simulation of appendix D: how often an honest owner loses the PIN. The
 * owner unlocks `U` times a day and mistypes each entry with probability `p`; after a typo they give
 * up the visit with probability `quit` (fingerprint, or the phone put down), otherwise they retype at
 * once, inside the 60-second window; the master password is typed every `days` days.
 *
 * [PythonRandom] is CPython's own generator (MT19937 and `random()`), so the port draws exactly the
 * numbers the Python of appendix D drew, and every cell of its two tables comes out as the count the
 * Python gave on this machine (`run(...)` with seed 1 and 20,000 trials). The acceptance line is
 * the third row: under 1 % of 14-day periods lose the PIN at 20 unlocks a day and 3 % typos.
 *
 * Then the same owners are driven through the real [PinGuard] instead of the model's two counters,
 * and each period ends the same way as in the model: the PIN off or not, and the same `row` and
 * `total`.
 */
class OwnerSimulationTest {

    @Test
    fun t20_under1PercentOf14DayPeriodsLoseThePinAt20UnlocksADayAnd3PercentTypos() {
        val lost = OwnerModel.run(unlocksADay = 20, typo = 0.03, quit = 0.0, days = 14, forgiving = true)
        assertEquals(0, lost) // the Python gives 0 of 20,000 too
        assertTrue(lost < 20_000 / 100)
        // Design 1, which counted every wrong PIN until the master password, lost it in 37 %
        assertEquals(7_417, OwnerModel.run(unlocksADay = 20, typo = 0.03, quit = 0.0, days = 14, forgiving = false))
    }

    @Test
    fun t20_theFirstTableOfAppendixD() {
        // days, unlocks a day, typos, given up; Design 1 and this design, of 20,000 periods (appendix D)
        val rows = listOf(
            Row(14, 8, 0.031, 0.0, design1 = 101, final = 0), // 0.5 %   0.0 %
            Row(14, 10, 0.05, 0.0, design1 = 4_294, final = 2), // 21.5 %  0.0 %
            Row(14, 20, 0.03, 0.0, design1 = 7_417, final = 0), // 37.1 %  0.0 %
            Row(14, 20, 0.05, 0.2, design1 = 18_277, final = 88), // 91.4 %  0.4 %
            Row(21, 20, 0.03, 0.2, design1 = 16_530, final = 26), // 82.7 %  0.1 %
            Row(21, 20, 0.05, 0.2, design1 = 19_960, final = 882), // 99.8 %  4.4 %
        )
        for (row in rows) {
            assertEquals("$row, Design 1", row.design1, OwnerModel.run(row.unlocks, row.typo, row.quit, row.days, forgiving = false))
            assertEquals("$row, this design", row.final, OwnerModel.run(row.unlocks, row.typo, row.quit, row.days, forgiving = true))
        }
    }

    @Test
    fun t20_theSecondTableOfAppendixDAnOwnerWhoNeverTypesTheMasterPassword() {
        // 8 unlocks a day, 3.1 % typos; PIN off within 90, 180 and 365 days, of 20,000
        val expected = mapOf(
            0.2 to listOf(955, 11_753, 19_907), // 4.8 %  58.8 %  99.5 %
            0.1 to listOf(22, 1_265, 12_771), // 0.1 %  6.3 %  63.9 %
            0.0 to listOf(0, 0, 0),
        )
        for ((quit, counts) in expected) {
            val actual = listOf(90, 180, 365).map { OwnerModel.run(8, 0.031, quit, it, forgiving = true) }
            assertEquals("given up $quit", counts, actual)
        }
    }

    @Test
    fun t20_thePinGuardLosesThePinExactlyWhenTheModelDoes() {
        // the acceptance row, and a row in which the PIN is lost now and then
        assertEquals(0, OwnerOnGuard(unlocksADay = 20, typo = 0.03, quit = 0.0, days = 14).runAgainstTheModel(periods = 600))
        val lost = OwnerOnGuard(unlocksADay = 20, typo = 0.05, quit = 0.2, days = 14).runAgainstTheModel(periods = 1_500)
        assertTrue("the second row should lose the PIN in some periods (88 of 20,000 in the model): $lost", lost > 0)
    }

    private data class Row(val days: Int, val unlocks: Int, val typo: Double, val quit: Double, val design1: Int, val final: Int)
}

/** The simulation of lock.md appendix D, line for line. */
internal object OwnerModel {
    const val FORGIVE_MAX = 2

    /** `run(U, p, quit, days, design)` of appendix D, as the number of periods (of [trials]) that lose the PIN. */
    fun run(unlocksADay: Int, typo: Double, quit: Double, days: Int, forgiving: Boolean, trials: Int = 20_000, seed: Long = 1): Int {
        val rnd = PythonRandom(seed)
        var off = 0
        repeat(trials) {
            if (period(rnd, unlocksADay, typo, quit, days, forgiving) == null) off++
        }
        return off
    }

    /** One period: null when the PIN turned off, otherwise `row` and `total` at its end. */
    fun period(rnd: PythonRandom, unlocksADay: Int, typo: Double, quit: Double, days: Int, forgiving: Boolean, onVisit: ((Visit) -> Unit)? = null): Pair<Int, Int>? {
        var row = 0
        var total = 0
        repeat(days * unlocksADay) {
            var visit = 0
            val steps = ArrayList<Boolean>() // the entries of this visit: false a typo, true the right PIN
            var dead = false
            while (true) {
                if (rnd.random() < typo) {
                    row += 1
                    total += 1
                    visit += 1
                    steps += false
                    if (row >= 5 || total >= 10) {
                        dead = true
                        break
                    }
                    if (rnd.random() < quit) break // given up: stays counted
                } else {
                    if (forgiving) total -= minOf(FORGIVE_MAX, visit) // retyped at once: within the 60 s window
                    row = 0
                    steps += true
                    break
                }
            }
            onVisit?.invoke(Visit(steps, row, total, dead))
            if (dead) return null
        }
        return row to total
    }

    /** One visit of the owner: its entries, and the model's counts after it. */
    class Visit(val entries: List<Boolean>, val row: Int, val total: Int, val dead: Boolean)
}

/**
 * The owners of appendix D on the real [PinGuard]: the same random draws as the model, each visit a
 * [Place], each typo a wrong PIN (a different one each time since the last right PIN, as typos
 * are), each right entry the PIN, a visit given up ended by the fingerprint. One minute or more
 * between visits, one second per entry (the age of the charge).
 */
private class OwnerOnGuard(val unlocksADay: Int, val typo: Double, val quit: Double, val days: Int) {

    /** Runs [periods] periods with the guard beside the model; returns how many lost the PIN. */
    fun runAgainstTheModel(periods: Int): Int {
        val rnd = PythonRandom(1)
        var lost = 0
        repeat(periods) { n ->
            val owner = Owner()
            val modelEnd = OwnerModel.period(rnd, unlocksADay, typo, quit, days, forgiving = true) { visit -> owner.live(visit, n) }
            val guardEnd = owner.end()
            assertEquals("period $n", modelEnd, guardEnd)
            if (modelEnd == null) lost++
        }
        return lost
    }

    private inner class Owner {
        val clock = FakeClock(elapsedMs = 7 * 3_600_000L)
        val store = FakeRecordStore(record(), now = clock::elapsedMs)
        val keys = FakeKeys(FakeRandom(3), now = clock::elapsedMs).also {
            it.canary = true
            it.putPinKey(PinWrap.alias(SLOT), ByteArray(32) { 0x42 })
        }
        val guard = PinGuard(VAULT, store, clock, keys.pin, keys.device, aead, FakeRandom(4)).also { it.startUp() }
        var places = 0L
        var wrongSinceRight = 0

        fun live(visit: OwnerModel.Visit, period: Int) {
            clock.advance(3_600_000L * 24 / unlocksADay)
            val place = Place.visit(++places)
            for ((i, right) in visit.entries.withIndex()) {
                val pin = if (right) RIGHT else WRONG[wrongSinceRight++]
                assertEquals(PinCharge.CHARGED, guard.charge(place))
                val outcome = runNow { guard.check(pin, setOf(VK_ID)) }
                when {
                    right -> {
                        assertTrue("period $period: $outcome", outcome is PinOutcome.Right)
                        guard.flush()
                        wrongSinceRight = 0
                    }
                    visit.dead && i == visit.entries.lastIndex -> assertEquals("period $period", PinOutcome.Limit, outcome)
                    else -> assertTrue("period $period: $outcome", outcome is PinOutcome.Wrong)
                }
            }
            guard.placeEnded(place) // unlocked, given up, or the PIN turned off
            if (!visit.dead) {
                val counts = guard.record!!.guard
                assertEquals("period $period", visit.row to visit.total, counts.row to counts.total)
            }
        }

        fun end(): Pair<Int, Int>? {
            val r = guard.record!!
            return if (r.slot == null) null else r.guard.row to r.guard.total
        }
    }

    private companion object {
        const val VAULT = PinFixture.VAULT
        const val SLOT = PinFixture.SLOT
        const val VK_ID = PinFixture.VK_ID
        val aead = CryptoFactory.aead()
        val RIGHT = PinFixture.RIGHT
        val WRONG = listOf("111222", "905127", "368014", "143637", "321299").map { PinFixture.pin(it) }

        fun record(): PinRecord {
            val keys = FakeKeys(FakeRandom(3)).also { it.putPinKey(PinWrap.alias(SLOT), ByteArray(32) { 0x42 }) }
            val kek = keys.hmacOf(PinWrap.alias(SLOT), PinWrap.message(VAULT, SLOT, RIGHT))
            val wrap = PinWrap.seal(aead, kek, PinFixture.NONCE, PinFixture.VK, VAULT, VK_ID, SLOT)
            return PinRecord(VAULT, ShortcutSlot(SLOT, 0L, listOf(wrap)), PinRecord.Guard(false, 0, 0), null, PinRecord.Unseen(0, null, false))
        }
    }
}

/**
 * CPython's `random.Random(seed)` for a non-negative integer seed below 2^32, and its `random()`:
 * MT19937 seeded by `init_by_array([seed])` (Modules/_randommodule.c), and a double from two 32-bit
 * outputs, 53 random bits. With it the port of appendix D draws exactly what the Python drew.
 */
internal class PythonRandom(seed: Long) {
    private val mt = LongArray(N)
    private var index = N + 1

    init {
        require(seed in 0..0xffffffffL) { "a seed of one 32-bit word" }
        initByArray(longArrayOf(seed))
    }

    /** `random.random()`: `(a * 67108864.0 + b) * (1.0 / 9007199254740992.0)`. */
    fun random(): Double {
        val a = next() ushr 5
        val b = next() ushr 6
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    private fun initGenrand(s: Long) {
        mt[0] = s and MASK
        for (i in 1 until N) mt[i] = (1812433253L * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i) and MASK
        index = N
    }

    private fun initByArray(key: LongArray) {
        initGenrand(19650218L)
        var i = 1
        var j = 0
        var k = maxOf(N, key.size)
        while (k > 0) {
            mt[i] = ((mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525L)) + key[j] + j) and MASK
            i++
            j++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            mt[i] = ((mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941L)) - i) and MASK
            i++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            k--
        }
        mt[0] = 0x80000000L
    }

    private fun next(): Long {
        if (index >= N) {
            for (kk in 0 until N - M) mt[kk] = mt[kk + M] xor twist(mt[kk], mt[kk + 1])
            for (kk in N - M until N - 1) mt[kk] = mt[kk + M - N] xor twist(mt[kk], mt[kk + 1])
            mt[N - 1] = mt[M - 1] xor twist(mt[N - 1], mt[0])
            index = 0
        }
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680L)
        y = y xor ((y shl 15) and 0xefc60000L)
        y = y xor (y ushr 18)
        return y and MASK
    }

    private fun twist(upper: Long, lower: Long): Long {
        val y = (upper and 0x80000000L) or (lower and 0x7fffffffL)
        return (y ushr 1) xor (if (y and 1L != 0L) MATRIX_A else 0L)
    }

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX_A = 0x9908b0dfL
        const val MASK = 0xffffffffL
    }
}
