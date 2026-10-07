package io.github.dilshodbekmx.passwordvault.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device]: [SystemRandom] gives bytes of the length asked for, unbiased numbers
 * below a bound, and version 4 UUIDs in lower case (architecture.md §3.1).
 */
@RunWith(AndroidJUnit4::class)
class SystemRandomTest {
    private val random = SystemRandom()

    @Test
    fun bytesOfTheLengthAskedFor() {
        for (n in listOf(0, 1, 12, 16, 32, 1_000)) assertEquals(n, random.bytes(n).size)
        assertFalse("two keys differ", random.bytes(32).contentEquals(random.bytes(32)))
        // 64 KiB of output: every byte value turns up, none far more often than the others.
        val counts = IntArray(256)
        for (b in random.bytes(65_536)) counts[b.toInt() and 0xff]++
        assertTrue("each value 256 ± 128 times: ${counts.min()}..${counts.max()}", counts.all { it in 128..384 })
        try {
            random.bytes(-1)
            fail("a negative length")
        } catch (e: IllegalArgumentException) {
            // refused
        }
    }

    @Test
    fun numbersBelowTheBoundAndEvenlySpread() {
        for (bound in listOf(1, 2, 3, 10, 1_295, Int.MAX_VALUE)) repeat(1_000) { assertTrue(random.int(bound) in 0 until bound) }
        // 300,000 draws below 3: an unbiased choice gives about 100,000 of each (sd about 258).
        val counts = IntArray(3)
        repeat(300_000) { counts[random.int(3)]++ }
        assertTrue(counts.toList().toString(), counts.all { it in 98_500..101_500 })
        for (bad in listOf(0, -1)) {
            try {
                random.int(bad)
                fail("bound $bad")
            } catch (e: IllegalArgumentException) {
                // refused
            }
        }
    }

    @Test
    fun uuidsOfVersion4InLowerCase() {
        val ids = List(2_000) { random.uuid() }
        assertTrue(ids.all { Uuid.isValid(it) })
        assertEquals("no two alike", ids.size, ids.toSet().size)
    }
}
