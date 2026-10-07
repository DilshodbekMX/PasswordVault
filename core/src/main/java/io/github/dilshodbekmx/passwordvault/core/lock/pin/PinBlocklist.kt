package io.github.dilshodbekmx.passwordvault.core.lock.pin

/**
 * The PIN blocklist of lock.md §7.2: nine rules, tried in order, that refuse 126,337 of the
 * 1,000,000 six-digit PINs. It is applied only when a PIN is chosen, never at unlock, so a later
 * change of the rules cannot affect a PIN that is already set. There is no "use it anyway".
 *
 * This is the reference code of lock.md appendix A, ported from a `String` to a `ByteArray` of six
 * ASCII digits (`0x30` to `0x39`), which is what the pad produces (lock.md §4.2): the digits never
 * become a `String` on their way from the pad to the blocklist (architecture.md §5.6). The rules and
 * the counts do not change; test T1 checks every PIN against the reference, the nine counts and
 * the SHA-256 of appendix A.
 */
object PinBlocklist {
    /** A PIN has exactly this many digits (lock.md §7.1). */
    const val LENGTH = 6

    /**
     * Why a PIN is refused. The screen shows one of three messages (lock.md §7.3):
     * [DATE] "This PIN looks like a date or a year."; [REPEAT], [PATTERN] and [STEPS] "This PIN
     * repeats digits or follows a pattern."; [SEQUENCE] and [KEYPAD] "This PIN is a sequence or a
     * shape on the keypad."
     */
    enum class Reason { REPEAT, PATTERN, SEQUENCE, KEYPAD, STEPS, DATE }

    // Row and column of every key on the pad:   1 2 3 / 4 5 6 / 7 8 9 / . 0 .      (index = digit)
    private val row = intArrayOf(3, 0, 0, 0, 1, 1, 1, 2, 2, 2)
    private val col = intArrayOf(1, 0, 1, 2, 0, 1, 2, 0, 1, 2)

    /** The keypad lines of rule 4, each in either direction, as the number its three digits spell. */
    private val lines = BooleanArray(1000).also { line ->
        for (keys in intArrayOf(123, 456, 789, 147, 258, 369, 580, 159, 357)) {
            val first = keys / 100
            val middle = keys / 10 % 10
            val last = keys % 10
            line[keys] = true
            line[last * 100 + middle * 10 + first] = true
        }
    }

    /** The four listed PINs of rule 3, as numbers: 246810, 108642, 135790, 097531. */
    private val listed = intArrayOf(246_810, 108_642, 135_790, 97_531)

    /**
     * Returns 0 when [pin] is allowed, otherwise the number (1 to 9) of the first rule of lock.md
     * §7.2 that refuses it. [pin] is not changed.
     *
     * @throws IllegalArgumentException unless [pin] is exactly six ASCII digits.
     */
    fun rule(pin: ByteArray): Int {
        require(pin.size == LENGTH && pin.all { it >= 0x30 && it <= 0x39 })
        val d = IntArray(LENGTH) { pin[it] - 0x30 }
        try {
            return firstRule(d)
        } finally {
            d.fill(0)
        }
    }

    /**
     * The message group of lock.md §7.3, or null when [pin] is allowed.
     *
     * @throws IllegalArgumentException unless [pin] is exactly six ASCII digits.
     */
    fun reason(pin: ByteArray): Reason? = when (rule(pin)) {
        0 -> null
        1 -> Reason.REPEAT
        2 -> Reason.PATTERN
        3, 6 -> Reason.SEQUENCE
        4, 7 -> Reason.KEYPAD
        5 -> Reason.STEPS
        else -> Reason.DATE
    }

    /** The rules of appendix A in its order; [d] holds the six digits as numbers 0 to 9. */
    private fun firstRule(d: IntArray): Int {
        // 1 repeat: a digit that occurs 4 or more times
        if (someDigitAtLeastFourTimes(d)) return 1
        // 2 pattern: ababab; abcabc; aabbcc; aaabbb; the same backwards
        if (d[0] == d[2] && d[2] == d[4] && d[1] == d[3] && d[3] == d[5]) return 2
        if (d[0] == d[3] && d[1] == d[4] && d[2] == d[5]) return 2
        if (d[0] == d[1] && d[2] == d[3] && d[4] == d[5]) return 2
        if (d[0] == d[1] && d[1] == d[2] && d[3] == d[4] && d[4] == d[5]) return 2
        if (d[0] == d[5] && d[1] == d[4] && d[2] == d[3]) return 2
        // 3 sequence: one step throughout, counting 9 -> 0 as a step; two runs of three; the list
        val step = (d[1] - d[0]).mod(10)
        if ((0 until 5).all { (d[it + 1] - d[it]).mod(10) == step }) return 3
        if (isRun3(d, 0) && isRun3(d, 3)) return 3
        if (number(d, 0, 6) in listed) return 3
        // 4 keypad lines: both halves
        if (lines[number(d, 0, 3)] && lines[number(d, 3, 3)]) return 4
        // 5 steps: digits 1, 3, 5 and digits 2, 4, 6 each step by one amount (no wrap)
        if (d[2] - d[0] == d[4] - d[2] && d[3] - d[1] == d[5] - d[3]) return 5
        // 6 run of four, anywhere, stepping +1 or -1 (no wrap)
        for (i in 0..2) {
            val t = d[i + 1] - d[i]
            if ((t == 1 || t == -1) && d[i + 2] - d[i + 1] == t && d[i + 3] - d[i + 2] == t) return 6
        }
        // 7 keypad path: six different keys, each next to the one before
        if (allDifferent(d) && (0 until 5).all { neighbours(d[it], d[it + 1]) }) return 7
        // 8 date: DDMMYY, MMDDYY or YYMMDD
        val x = d[0] * 10 + d[1]
        val y = d[2] * 10 + d[3]
        val z = d[4] * 10 + d[5]
        if (dayMonth(x, y) || dayMonth(y, x) || dayMonth(z, y)) return 8
        // 9 year: the first four or the last four digits
        if (x * 100 + y in 1940..2039 || y * 100 + z in 1940..2039) return 9
        return 0
    }

    private fun someDigitAtLeastFourTimes(d: IntArray): Boolean {
        for (digit in 0..9) {
            var times = 0
            for (x in d) if (x == digit) times++
            if (times >= 4) return true
        }
        return false
    }

    private fun allDifferent(d: IntArray): Boolean {
        var seen = 0
        for (x in d) seen = seen or (1 shl x)
        return Integer.bitCount(seen) == d.size
    }

    /** The [count] digits of [d] from [from] as one decimal number. */
    private fun number(d: IntArray, from: Int, count: Int): Int {
        var n = 0
        for (i in from until from + count) n = n * 10 + d[i]
        return n
    }

    private fun isRun3(d: IntArray, i: Int): Boolean {
        val t = (d[i + 1] - d[i]).mod(10)
        return (t == 1 || t == 9) && (d[i + 2] - d[i + 1]).mod(10) == t
    }

    private fun neighbours(p: Int, q: Int) =
        p != q && kotlin.math.abs(row[p] - row[q]) <= 1 && kotlin.math.abs(col[p] - col[q]) <= 1

    private fun dayMonth(day: Int, month: Int) = day in 1..31 && month in 1..12
}
