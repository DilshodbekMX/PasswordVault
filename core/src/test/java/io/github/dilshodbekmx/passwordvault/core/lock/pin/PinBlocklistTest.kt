package io.github.dilshodbekmx.passwordvault.core.lock.pin

import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist.Reason
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** lock.md test T1: the blocklist of §7.2 over all 10^6 PINs, against appendix A. */
class PinBlocklistTest {

    private fun pin(digits: String): ByteArray = ByteArray(digits.length) { digits[it].code.toByte() }

    /** Every PIN from 000000 to 999999, in order, as the six ASCII bytes the pad produces. */
    private inline fun forEveryPin(action: (n: Int, pin: ByteArray) -> Unit) {
        val bytes = ByteArray(6)
        for (n in 0 until 1_000_000) {
            var rest = n
            for (i in 5 downTo 0) {
                bytes[i] = (0x30 + rest % 10).toByte()
                rest /= 10
            }
            action(n, bytes)
        }
    }

    @Test
    fun t1_refusesExactly126337PinsWithTheNineCountsAndTheHashOfAppendixA() {
        val counts = IntArray(10)
        val oracle = ByteArray(1_000_000) // appendix A: "1" for a refused PIN, "0" for an allowed one
        forEveryPin { n, pin ->
            val rule = PinBlocklist.rule(pin)
            counts[rule]++
            oracle[n] = if (rule == 0) '0'.code.toByte() else '1'.code.toByte()
        }
        assertEquals(126_337, 1_000_000 - counts[0])
        assertEquals(873_663, counts[0])
        assertArrayEquals(
            intArrayOf(12_700, 2_340, 424, 260, 2_066, 3_904, 3_698, 83_696, 17_249),
            counts.copyOfRange(1, 10),
        )
        // lock.md §7.2: without rule 9 the list would refuse 109,088.
        assertEquals(109_088, counts.copyOfRange(1, 9).sum())
        assertEquals("c6aa9b8f6e1124aae56ed5fcb4b495bb2c0cae4a792ec9ce6c5b22b64848241b", sha256(oracle))
    }

    @Test
    fun t1_givesTheReferenceRuleForEveryPin() {
        var differ = 0
        var first: String? = null
        forEveryPin { n, pin ->
            val digits = pinText(n)
            if (PinBlocklist.rule(pin) != ReferenceBlocklist.rule(digits)) {
                differ++
                if (first == null) first = digits
            }
        }
        assertEquals("PINs where the port and appendix A differ, the first: $first", 0, differ)
    }

    @Test
    fun t1_everyExampleOfTheTableLandsOnItsRule() {
        val examples = mapOf(
            1 to listOf("111111", "000001", "121111"),
            2 to listOf("121212", "123123", "112233", "111222", "123321"),
            3 to listOf("123456", "654321", "890123", "135791", "789456"),
            4 to listOf("147258", "159753", "852456"),
            5 to listOf("102030", "142536", "124578", "172839"),
            6 to listOf("123457", "001234"),
            7 to listOf("147896", "741236"),
            8 to listOf("250890", "082590", "900825", "071990"),
            9 to listOf("199012", "451987", "131990"),
        )
        for ((rule, pins) in examples) {
            for (digits in pins) assertEquals(digits, rule, PinBlocklist.rule(pin(digits)))
        }
    }

    @Test
    fun t1_theAllowedExamplesAreAllowed() {
        for (digits in listOf("582913", "143637", "905127", "368014", "321299")) {
            assertEquals(digits, 0, PinBlocklist.rule(pin(digits)))
            assertNull(digits, PinBlocklist.reason(pin(digits)))
        }
    }

    @Test
    fun t1_reasonGivesTheMessageGroupOfEachRule() {
        val byRule = mapOf(
            "111111" to Reason.REPEAT, // 1
            "121212" to Reason.PATTERN, // 2
            "123456" to Reason.SEQUENCE, // 3
            "147258" to Reason.KEYPAD, // 4
            "102030" to Reason.STEPS, // 5
            "123457" to Reason.SEQUENCE, // 6
            "147896" to Reason.KEYPAD, // 7
            "250890" to Reason.DATE, // 8
            "199012" to Reason.DATE, // 9
        )
        for ((digits, reason) in byRule) assertEquals(digits, reason, PinBlocklist.reason(pin(digits)))
    }

    @Test
    fun t1_refusesInputThatIsNotSixAsciiDigits() {
        val bad = listOf(
            ByteArray(0),
            pin("12345"),
            pin("1234567"),
            pin("12345a"),
            pin("12 456"),
            byteArrayOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x2F), // '/' is just below '0'
            byteArrayOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x3A), // ':' is just above '9'
            byteArrayOf(1, 2, 3, 4, 5, 6), // digit values, not ASCII digits
            "١٢٣٤٥٦".toByteArray(Charsets.UTF_8), // Arabic-Indic digits never reach the blocklist
        )
        for (bytes in bad) {
            assertThrows(IllegalArgumentException::class.java) { PinBlocklist.rule(bytes) }
            assertThrows(IllegalArgumentException::class.java) { PinBlocklist.reason(bytes) }
        }
    }

    @Test
    fun t1_leavesTheCallersBytesAsTheyWere() {
        val bytes = pin("250890")
        PinBlocklist.rule(bytes)
        PinBlocklist.reason(bytes)
        assertArrayEquals(pin("250890"), bytes)
    }

    private fun pinText(n: Int): String {
        val chars = CharArray(6)
        var rest = n
        for (i in 5 downTo 0) {
            chars[i] = '0' + rest % 10
            rest /= 10
        }
        return String(chars)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b ->
            val v = b.toInt() and 0xFF
            "${HEX[v shr 4]}${HEX[v and 0x0F]}"
        }

    private companion object {
        const val HEX = "0123456789abcdef"
    }

    /** lock.md appendix A as it is written there, on a `String`: the oracle for the port. */
    private object ReferenceBlocklist {
        private val row = intArrayOf(3, 0, 0, 0, 1, 1, 1, 2, 2, 2)
        private val col = intArrayOf(1, 0, 1, 2, 0, 1, 2, 0, 1, 2)
        private val lines: Set<String> =
            listOf("123", "456", "789", "147", "258", "369", "580", "159", "357").flatMap { listOf(it, it.reversed()) }.toSet()
        private val listed = setOf("246810", "108642", "135790", "097531")

        fun rule(pin: String): Int {
            require(pin.length == 6 && pin.all { it in '0'..'9' })
            val d = IntArray(6) { pin[it] - '0' }
            val a = pin.substring(0, 3)
            val b = pin.substring(3)
            // 1 repeat
            if ((0..9).any { digit -> d.count { it == digit } >= 4 }) return 1
            // 2 pattern
            if (pin == pin.substring(0, 2).repeat(3) || a == b) return 2
            if (d[0] == d[1] && d[2] == d[3] && d[4] == d[5]) return 2
            if (d[0] == d[1] && d[1] == d[2] && d[3] == d[4] && d[4] == d[5]) return 2
            if (pin == pin.reversed()) return 2
            // 3 sequence
            val step = (d[1] - d[0]).mod(10)
            if ((0 until 5).all { (d[it + 1] - d[it]).mod(10) == step }) return 3
            if (isRun3(d, 0) && isRun3(d, 3)) return 3
            if (pin in listed) return 3
            // 4 keypad lines
            if (a in lines && b in lines) return 4
            // 5 steps
            if (d[2] - d[0] == d[4] - d[2] && d[3] - d[1] == d[5] - d[3]) return 5
            // 6 run of four
            for (i in 0..2) {
                val t = d[i + 1] - d[i]
                if ((t == 1 || t == -1) && d[i + 2] - d[i + 1] == t && d[i + 3] - d[i + 2] == t) return 6
            }
            // 7 keypad path
            if (d.distinct().size == 6 && (0 until 5).all { neighbours(d[it], d[it + 1]) }) return 7
            // 8 date
            val x = d[0] * 10 + d[1]
            val y = d[2] * 10 + d[3]
            val z = d[4] * 10 + d[5]
            if (dayMonth(x, y) || dayMonth(y, x) || dayMonth(z, y)) return 8
            // 9 year
            if (x * 100 + y in 1940..2039 || y * 100 + z in 1940..2039) return 9
            return 0
        }

        private fun isRun3(d: IntArray, i: Int): Boolean {
            val t = (d[i + 1] - d[i]).mod(10)
            return (t == 1 || t == 9) && (d[i + 2] - d[i + 1]).mod(10) == t
        }
        private fun neighbours(p: Int, q: Int) =
            p != q && kotlin.math.abs(row[p] - row[q]) <= 1 && kotlin.math.abs(col[p] - col[q]) <= 1
        private fun dayMonth(day: Int, month: Int) = day in 1..31 && month in 1..12
    }
}
