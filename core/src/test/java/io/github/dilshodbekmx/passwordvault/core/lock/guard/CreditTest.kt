package io.github.dilshodbekmx.passwordvault.core.lock.guard

import java.lang.reflect.Modifier
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** lock.md test T10: `Credit`, the time that a reboot cannot shorten (§6.9). */
class CreditTest {

    @Test
    fun t10_isTheHelperOfLockMd69() {
        // The values a phone gives: both times at least 0.
        val random = Random(610)
        repeat(100_000) {
            val credited = random.nextInt(Int.MAX_VALUE).toLong()
            val seen = random.nextInt(Int.MAX_VALUE).toLong()
            val now = if (random.nextBoolean()) seen + random.nextInt(1_000_000) else random.nextInt(Int.MAX_VALUE).toLong()
            val gained = if (now >= seen) now - seen else now // lock.md §6.9, as written there
            assertEquals(Credit(credited + gained, now), Credit(credited, seen).observe(now))
        }
    }

    @Test
    fun t10_isExactOnOneBoot() {
        val random = Random(1)
        repeat(1_000) {
            val start = random.nextInt(1_000_000_000).toLong()
            var now = start
            var credit = Credit.start(start)
            repeat(random.nextInt(50)) {
                now += random.nextInt(10 * HOUR.toInt())
                credit = credit.observe(now)
                assertEquals(now - start, credit.creditedMs)
                assertEquals(now, credit.seenElapsedMs)
            }
        }
    }

    @Test
    fun t10_overRandomObservationsAndRebootsNeverExceedsTheTimeThatPassed() {
        val random = Random(2)
        repeat(2_000) { run ->
            // A phone that is switched on and off at random; the app looks at random moments while it is on.
            var real = random.nextInt(DAY.toInt()).toLong() // real time, which the phone cannot see
            var bootAt = real - random.nextInt(DAY.toInt()) // elapsedRealtime() = real - bootAt
            val requestAt = real
            var onTime = 0L // phone-on time since the request
            var credit = Credit.start(real - bootAt)
            var previous = credit.creditedMs
            repeat(random.nextInt(200)) {
                if (random.nextInt(8) == 0) {
                    // Switched off right after the last look (so on-time is counted at its least), on again.
                    bootAt = real + random.nextInt(3 * DAY.toInt()) + 1
                    real = bootAt + random.nextInt(3 * HOUR.toInt()) // the next look, some time after the boot
                    onTime += real - bootAt
                } else {
                    val step = random.nextInt(6 * HOUR.toInt()).toLong()
                    real += step
                    onTime += step
                }
                credit = credit.observe(real - bootAt)
                val label = "run $run"
                assertTrue(label, credit.creditedMs <= real - requestAt) // never more than really passed
                assertTrue(label, credit.creditedMs <= onTime) // never more than the phone was on
                assertTrue(label, credit.creditedMs >= previous) // never less than before
                previous = credit.creditedMs
            }
        }
    }

    @Test
    fun t10_aRestartCreditsTheNewUptimeOnlyWhenItIsLessThanTheLastLook() {
        val seen = Credit(5 * HOUR, 14 * HOUR) // last look: 14 hours after the boot before
        assertEquals(Credit(7 * HOUR, 2 * HOUR), seen.observe(2 * HOUR)) // up 2 hours: all of it
        assertEquals(Credit(6 * HOUR, 15 * HOUR), seen.observe(15 * HOUR)) // up 15 hours: just the difference
        assertEquals(Credit(5 * HOUR, 14 * HOUR), seen.observe(14 * HOUR))
        assertEquals(Credit(5 * HOUR, 0), seen.observe(0))
    }

    @Test
    fun t10_takesNoBootCountAndNoDate() {
        // The one way in is elapsedRealtime(): the constructor takes the two values it keeps, and the
        // only way to move a credit on takes one elapsedRealtime() value.
        val constructors = Credit::class.java.constructors.map { it.parameterTypes.toList() }
        assertEquals(listOf(listOf(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)), constructors)
        val moving = Credit::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.returnType == Credit::class.java && !it.isSynthetic }
            .filter { it.name != "copy" && it.name != "copy\$default" }
        assertEquals(listOf("observe"), moving.map { it.name })
        assertEquals(listOf(Long::class.javaPrimitiveType), moving.single().parameterTypes.toList())
    }

    @Test
    fun t10_staysOnTheSideOfCreditingLessOutsideWhatAPhoneGives() {
        assertEquals(Credit(10, 0), Credit(10, 5).observe(-3)) // elapsedRealtime() is never negative
        assertEquals(Credit(10, 7), Credit(10, -1).observe(7)) // a seen value that was never a reading
        assertEquals(Long.MAX_VALUE, Credit(Long.MAX_VALUE - 1, 0).observe(5).creditedMs)
    }

    @Test
    fun t10_startReachedAndRemaining() {
        val start = Credit.start(1_000)
        assertEquals(Credit(0, 1_000), start)
        val later = start.observe(1_000 + 20 * HOUR)
        assertFalse(later.reached(24 * HOUR))
        assertEquals(4 * HOUR, later.remainingMs(24 * HOUR))
        val done = later.observe(1_000 + 24 * HOUR)
        assertTrue(done.reached(24 * HOUR))
        assertEquals(0, done.remainingMs(24 * HOUR))
        assertEquals(0, done.observe(1_000 + 30 * HOUR).remainingMs(24 * HOUR))
    }

    // --- appendix F: how long 24 credited hours take on a phone that is switched off every night ---

    @Test
    fun t10_theThreeRowsOfLockMd69ComeOutOfTheScenariosOfAppendixF() {
        // appendix F's program, run with Python's own random numbers (seed 1), prints these medians
        // and longest numbers of days; lock.md §6.9 rounds them to 1.5 days, 3 days (up to 8) and
        // 3 weeks.
        assertEquals(1.5 to 1.5, days(listOf(9, 21)))
        assertEquals(3.0 to 8.0, days(listOf(21)))
        assertEquals(22.0 to 33.0, days(listOf(9)))
    }

    /**
     * lock.md appendix F, with `Credit` in place of its `observe`: the phone is on from about 07:00 to
     * 23:00 and the app is opened at the hours in [opens] for one minute; every time varies by up to
     * ten minutes. Returns the median and the longest number of days until 24 hours are credited.
     */
    private fun days(opens: List<Int>, trials: Int = 2_000): Pair<Double, Double> {
        val random = PythonRandom(1)
        val jitter = { random.uniform(-10.0, 10.0) * 60_000 }
        val out = ArrayList<Double>()
        repeat(trials) {
            var credit: Credit? = null
            var day = 0
            var done: Double? = null
            while (done == null) {
                val boot = 7 * HOUR + jitter()
                for (o in opens) {
                    val t = o * HOUR + jitter()
                    for (now in doubleArrayOf(t - boot, t - boot + 60_000)) { // ON_START and ON_STOP of the visit
                        val elapsed = Math.round(now) // elapsedRealtime() counts whole milliseconds
                        credit = credit?.observe(elapsed) ?: Credit.start(elapsed) // the request is made at the first open
                        if (done == null && credit.reached(24 * HOUR)) done = day + (o - opens[0]) / 24.0
                    }
                }
                day++
            }
            out.add(done)
        }
        out.sort()
        return out[out.size / 2] to out.last()
    }

    /**
     * Python's `random.Random(seed)`: MT19937 seeded by `init_by_array`, and `uniform(a, b)` =
     * `a + (b - a) * random()` with 53-bit `random()`, so that appendix F runs here on the same numbers.
     */
    private class PythonRandom(seed: Int) {
        private val mt = IntArray(624)
        private var index = 624

        init {
            mt[0] = 19650218
            for (i in 1 until 624) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
            val key = intArrayOf(seed)
            var i = 1
            var j = 0
            for (k in maxOf(624, key.size) downTo 1) {
                mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525)) + key[j] + j
                i++
                j++
                if (i >= 624) {
                    mt[0] = mt[623]
                    i = 1
                }
                if (j >= key.size) j = 0
            }
            for (k in 623 downTo 1) {
                mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941)) - i
                i++
                if (i >= 624) {
                    mt[0] = mt[623]
                    i = 1
                }
            }
            mt[0] = 0x80000000.toInt()
        }

        private fun next32(): Int {
            if (index >= 624) {
                for (k in 0 until 624) {
                    val y = (mt[k] and 0x80000000.toInt()) or (mt[(k + 1) % 624] and 0x7fffffff)
                    mt[k] = mt[(k + 397) % 624] xor (y ushr 1) xor (if (y and 1 != 0) 0x9908b0df.toInt() else 0)
                }
                index = 0
            }
            var y = mt[index++]
            y = y xor (y ushr 11)
            y = y xor ((y shl 7) and 0x9d2c5680.toInt())
            y = y xor ((y shl 15) and 0xefc60000.toInt())
            y = y xor (y ushr 18)
            return y
        }

        fun random(): Double {
            val a = (next32() ushr 5).toLong()
            val b = (next32() ushr 6).toLong()
            return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
        }

        fun uniform(a: Double, b: Double): Double = a + (b - a) * random()
    }

    @Test
    fun pythonRandomGivesPythonsNumbers() {
        // python3 -c "import random; r = random.Random(1); print([r.random() for _ in range(3)])"
        val random = PythonRandom(1)
        assertEquals(0.13436424411240122, random.random(), 0.0)
        assertEquals(0.8474337369372327, random.random(), 0.0)
        assertEquals(0.763774618976614, random.random(), 0.0)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
