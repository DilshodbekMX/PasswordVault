package io.github.dilshodbekmx.passwordvault.platform

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device]: [AndroidClock] is the phone's wall clock and `elapsedRealtime()`
 * (architecture.md §3.1), and `delayUntilElapsed` ends on or after its target, in steps of at most
 * a second, and is cancellable.
 */
@RunWith(AndroidJUnit4::class)
class AndroidClockTest {
    @Test
    fun theWallClockIsTheSystemsWallClock() {
        val before = System.currentTimeMillis()
        val wall = AndroidClock.wallMs()
        val after = System.currentTimeMillis()
        assertTrue(wall in before..after)
    }

    @Test
    fun theElapsedClockIsElapsedRealtime() {
        val before = SystemClock.elapsedRealtime()
        val elapsed = AndroidClock.elapsedMs()
        val after = SystemClock.elapsedRealtime()
        assertTrue(elapsed in before..after)
    }

    @Test
    fun aWaitEndsOnOrAfterItsTarget() = runBlocking {
        for (wait in listOf(1L, 250L, 1_600L)) { // the last one takes two steps
            val target = SystemClock.elapsedRealtime() + wait
            AndroidClock.delayUntilElapsed(target)
            val now = SystemClock.elapsedRealtime()
            assertTrue("$wait ms: ended ${now - target} ms after the target", now >= target && now < target + 1_000)
        }
    }

    @Test
    fun aTargetInThePastReturnsAtOnce() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        AndroidClock.delayUntilElapsed(start - 60_000)
        AndroidClock.delayUntilElapsed(0)
        AndroidClock.delayUntilElapsed(start)
        assertTrue(SystemClock.elapsedRealtime() - start < 500)
    }

    @Test
    fun aWaitCanBeCancelled() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        try {
            withContext(Dispatchers.Default) {
                withTimeout(300) { AndroidClock.delayUntilElapsed(start + 60_000) }
            }
            fail("the wait should have been cancelled")
        } catch (e: TimeoutCancellationException) {
            assertTrue(SystemClock.elapsedRealtime() - start < 2_000)
        }
    }
}
