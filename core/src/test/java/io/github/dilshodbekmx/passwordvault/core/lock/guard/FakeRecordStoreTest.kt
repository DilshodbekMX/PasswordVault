package io.github.dilshodbekmx.passwordvault.core.lock.guard

import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordStore
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record-level fake of :testing that the guards' tests use (lock.md §15.1): handed over, durable, failures. */
class FakeRecordStoreTest {
    private var now = 10_000L
    private val store = FakeRecordStore<String>(now = { now })

    private fun failsWith(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        throw AssertionError("nothing was thrown")
    }

    @Test
    fun aWriteIsReadAtOnceAndAKillKeepsIt() {
        assertEquals(RecordRead.Missing, store.read())
        store.write("a")
        assertEquals(RecordRead.Ok("a"), store.read())
        store.kill()
        assertEquals(RecordRead.Ok("a"), store.read())
        assertEquals(listOf("a"), store.writes.map { it.record })
        assertEquals(listOf(10_000L), store.writes.map { it.atMs })
        assertEquals(3, store.reads)
    }

    @Test
    fun aPowerCutKeepsOnlyWhatSettled() {
        store.set("base")
        store.write("one")
        now += 1_000
        store.write("two")
        now += 999
        assertEquals("one", store.durable(1_000)) // asking changes nothing
        assertEquals("two", store.current)
        store.powerCut(1_000)
        assertEquals("one", store.current)

        store.write("three")
        store.powerCut(1_000) // nothing settled since: what was there before
        assertEquals("one", store.current)
        assertEquals(listOf("one", "two", "three"), store.writes.map { it.record })

        store.set(null)
        store.write("four")
        store.powerCut(0)
        assertEquals("four", store.current)
        store.write("five")
        store.powerCut(1)
        assertEquals("four", store.current)
        store.set(null)
        store.powerCut(1_000)
        assertNull(store.current)
    }

    @Test
    fun writesCanFailBeforeOrAfterTheyLand() {
        store.write("a")
        store.failNextWrite()
        store.failNextWrite(landed = true)
        assertTrue(failsWith { store.write("b") } is IOException)
        assertEquals("a", store.current) // not handed over
        assertTrue(failsWith { store.write("c") } is IOException)
        assertEquals("c", store.current) // handed over all the same, as after a failed directory fsync
        assertTrue(store.writes.last().failed)
        store.write("d")
        assertEquals(2, store.failedWrites)

        store.failNextWrite()
        store.kill() // pending faults go with the process
        store.write("e")
        assertEquals("e", store.current)

        store.full = true
        assertTrue(failsWith { store.write("f") } is IOException)
        assertEquals("e", store.current)
        store.full = false
        store.write("g")
        assertEquals("g", store.current)
    }

    @Test
    fun aStopEndsTheProcessInsideTheWrite() {
        store.write("a")
        store.stopAtNextWrite(handedOver = false)
        val before = failsWith { store.write("b") }
        assertTrue(before is FakeRecordStore.Stopped && !before.handedOver)
        assertFalse(before is Exception) // no catch (e: Exception) in the code under test stops it
        assertEquals("a", store.current)
        store.stopAtNextWrite(handedOver = true)
        assertTrue(failsWith { store.write("c") } is FakeRecordStore.Stopped)
        assertEquals("c", store.current)
        store.write("d") // only the next write stops
        assertEquals("d", store.current)
    }

    @Test
    fun theOtherReadResultsCanBeScripted() {
        store.write("a")
        val damaged = RecordRead.Damaged(ParseError(4, "hash"))
        for (result in listOf(damaged, RecordRead.Newer, RecordRead.Unreadable, RecordRead.Missing)) {
            store.readAs = result
            assertEquals(result, store.read())
        }
        store.readAs = null
        assertEquals(RecordRead.Ok("a"), store.read())
    }
}
