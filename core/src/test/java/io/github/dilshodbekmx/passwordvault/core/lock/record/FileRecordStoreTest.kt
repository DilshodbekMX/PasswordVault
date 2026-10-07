package io.github.dilshodbekmx.passwordvault.core.lock.record

import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles.Op
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles.WriteStep
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * lock.md test T3, the files: a missing file is MISSING, an I/O error is UNREADABLE, and a record
 * read back is the record written, through [FileRecordStore] over [FakeRecordFiles]. Also the rows
 * "Reading a record" and "Writing a record" of the error table (§14.3): any write failure is "not
 * written", and a kill or a power cut at any boundary of a write leaves the old record or the new
 * one, never a damaged one (§5.9).
 */
class FileRecordStoreTest {
    private val clock = FakeClock()
    private val files = FakeRecordFiles(clock)
    private val pin = FileRecordStore.pin(files, ROOT, VAULT)
    private val fingerprint = FileRecordStore.fingerprint(files, ROOT, VAULT)
    private val state = FileRecordStore.state(files, ROOT, VAULT)

    @Test
    fun t3_aMissingFileIsMissing() {
        // No lock directory at all (lock.md F14), and a directory without this record.
        assertEquals(RecordRead.Missing, pin.read())
        assertEquals(RecordRead.Missing, fingerprint.read())
        assertEquals(RecordRead.Missing, state.read())
        state.write(stateRecord(1))
        assertEquals(RecordRead.Missing, pin.read())
        assertEquals(RecordRead.Missing, fingerprint.read())
        assertTrue(state.read() is RecordRead.Ok)
    }

    @Test
    fun t3_anIoErrorIsUnreadable() {
        pin.write(pinRecord(0))
        files.fail(Op.READ, times = -1) { it.endsWith("/pin.rec") }
        assertEquals(RecordRead.Unreadable, pin.read())
        assertEquals(RecordRead.Missing, fingerprint.read()) // one record's trouble is its own
        files.clearFaults()
        assertEquals(RecordRead.Ok(pinRecord(0)), pin.read()) // it may read fine next time
        // Any exception of the file layer, not only IOException, is UNREADABLE, never a crash.
        val odd = object : RecordFiles by files {
            override fun read(dir: String, name: String): ByteArray? = throw SecurityException("denied")
        }
        assertEquals(RecordRead.Unreadable, FileRecordStore.pin(odd, ROOT, VAULT).read())
    }

    @Test
    fun t3_whatIsWrittenIsReadBack() {
        pin.write(pinRecord(3))
        fingerprint.write(fingerprintRecord())
        state.write(stateRecord(9))
        assertEquals(RecordRead.Ok(pinRecord(3)), pin.read())
        assertEquals(RecordRead.Ok(fingerprintRecord()), fingerprint.read())
        assertEquals(RecordRead.Ok(stateRecord(9)), state.read())
        // in the vault's own directory, under the names of lock.md §5.2, made by the first write
        assertEquals("$ROOT/$VAULT", pin.dir)
        assertEquals(listOf("fingerprint.rec", "pin.rec", "state.rec"), files.names("$ROOT/$VAULT"))
        assertEquals(listOf("$ROOT/$VAULT"), files.dirs(ROOT))
        assertArrayEquals(RecordCodec.Pin.encode(pinRecord(3)), files.bytes("$ROOT/$VAULT", "pin.rec"))
        // A write replaces the whole record.
        pin.write(pinRecord(4))
        assertEquals(RecordRead.Ok(pinRecord(4)), pin.read())
    }

    @Test
    fun t3_damageOnDiskIsDamagedAndNeverRepaired() {
        pin.write(pinRecord(1))
        files.damage("$ROOT/$VAULT", "pin.rec") { it.copyOf(it.size - 1) }
        assertEquals(RecordRead.Damaged(ParseError(8, "length")), pin.read())
        assertEquals(RecordRead.Damaged(ParseError(8, "length")), pin.read())
        assertEquals(1, files.log.count { it.startsWith("replace") }) // reading wrote nothing
        // A zero-filled file, the usual shape of a lost write.
        files.damage("$ROOT/$VAULT", "pin.rec") { ByteArray(it.size) }
        assertEquals(RecordRead.Damaged(ParseError(0, "magic")), pin.read())
    }

    @Test
    fun t3_aRecordInTheWrongPlaceIsDamaged() {
        // pin.rec copied into another vault's directory, and state.rec's bytes under pin.rec's name
        val other = FileRecordStore.pin(files, ROOT, OTHER_VAULT)
        files.put("$ROOT/$OTHER_VAULT", "pin.rec", RecordCodec.Pin.encode(pinRecord(0)))
        assertEquals(RecordRead.Damaged(ParseError(12, "vault-id")), other.read())
        files.put("$ROOT/$VAULT", "pin.rec", RecordCodec.State.encode(stateRecord(0)))
        assertEquals(RecordRead.Damaged(ParseError(12, "kind")), pin.read())
        // a newer app's record is NEWER, and reading it changes nothing
        val newer = RecordCodec.Fingerprint.encode(fingerprintRecord())
        files.put("$ROOT/$VAULT", "fingerprint.rec", reframeWithVersion(newer, 2))
        assertEquals(RecordRead.Newer, fingerprint.read())
    }

    @Test
    fun theWriterRefusesAnotherVaultsRecordAndABrokenOneAndWritesNothing() {
        try {
            pin.write(pinRecord(0).copy(vaultId = OTHER_VAULT))
            fail("another vault's record was written")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains(OTHER_VAULT))
        }
        try {
            pin.write(pinRecord(0).copy(guard = PinRecord.Guard(false, 6, 0)))
            fail("a record with row 6 was written")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("out-of-range"))
        }
        assertTrue(files.log.none { it.startsWith("replace") })
        assertNull(files.bytes("$ROOT/$VAULT", "pin.rec"))
    }

    @Test
    fun aVaultIdMustBeAUuidBeforeItBecomesADirectory() {
        for (bad in listOf("..", "../x", "", VAULT.uppercase(), "$VAULT/..")) {
            try {
                FileRecordStore.dir(ROOT, bad)
                fail("'$bad' became a directory name")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message!!.contains(bad.ifEmpty { "\u0000" }))
            }
        }
        assertEquals("$ROOT/$VAULT", FileRecordStore.dir(ROOT, VAULT))
        // A store over a directory found on disk takes the vault id from its name.
        assertEquals(OTHER_VAULT, FileRecordStore(files, "$ROOT/$OTHER_VAULT", RecordCodec.State).vaultId)
    }

    @Test
    fun t3_anyWriteFailureIsNotWritten() {
        pin.write(pinRecord(1))
        for (step in WriteStep.entries) {
            files.failNextWrite(step)
            try {
                pin.write(pinRecord(2))
                fail("a write that failed at $step returned")
            } catch (_: IOException) {
                // not written
            }
            // Before the rename the old record stays; after it (a failed directory fsync) the new one
            // may already be what the next read sees, and both are whole records.
            val expected = if (step == WriteStep.DIR_SYNC) pinRecord(2) else pinRecord(1)
            assertEquals("$step", RecordRead.Ok(expected), pin.read())
            pin.write(pinRecord(1)) // the next write that succeeds makes it good
            files.deleteStaleTmp(ROOT)
        }
        assertEquals(listOf("pin.rec"), files.names("$ROOT/$VAULT"))
    }

    @Test
    fun t3_aKillOrPowerCutAtAnyBoundaryOfAWriteLeavesTheOldRecordOrTheNew() {
        val dir = "$ROOT/$VAULT"
        for (settle in listOf(0L, 1_000L)) {
            for (boundary in 1..4) {
                for (powerCut in listOf(false, true)) {
                    for (waited in listOf(0L, 1_500L)) {
                        val run = FakeRecordFiles(clock)
                        val store = FileRecordStore(run, dir, RecordCodec.Pin)
                        store.write(pinRecord(1))
                        clock.advance(2_000) // the first record has settled
                        run.stopAt(boundary)
                        try {
                            store.write(pinRecord(2))
                            fail("no stop at boundary $boundary")
                        } catch (_: FakeRecordFiles.Stopped) {
                            // the process died there
                        }
                        assertFalse(run.alive)
                        try {
                            store.read()
                            fail("a dead process read")
                        } catch (_: FakeRecordFiles.Stopped) {
                            // nothing runs after death
                        }
                        clock.advance(waited)
                        if (powerCut) run.powerCut(settle) else run.kill()
                        run.deleteStaleTmp(ROOT) // process start, lock.md §5.11 step 1
                        val read = store.read()
                        val what = "boundary $boundary, ${if (powerCut) "power cut, settle $settle" else "kill"}, waited $waited: $read"
                        assertTrue(what, read == RecordRead.Ok(pinRecord(1)) || read == RecordRead.Ok(pinRecord(2)))
                        val lostByPowerCut = powerCut && settle > 0 && waited < settle
                        val renamed = boundary >= 3
                        assertEquals(what, if (renamed && !lostByPowerCut) pinRecord(2) else pinRecord(1), (read as RecordRead.Ok).record)
                        assertEquals(what, listOf("pin.rec"), run.names(dir))
                    }
                }
            }
        }
    }

    private fun reframeWithVersion(bytes: ByteArray, version: Int): ByteArray {
        val json = String(bytes, 12, bytes.size - 44, Charsets.US_ASCII).replace("\"v\":1", "\"v\":$version").toByteArray()
        val len = json.size
        val head = "PVLOCK01".toByteArray() + byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())
        return head + json + java.security.MessageDigest.getInstance("SHA-256").digest(head + json)
    }

    private fun pinRecord(row: Int) = PinRecord(
        VAULT,
        slot = ShortcutSlot(SLOT, 1_759_752_000_000L, listOf(KeyWrap(VK, ByteArray(12) { 1 }, ByteArray(48) { 2 }))),
        guard = PinRecord.Guard(charged = row % 2 == 1, row = row, total = row),
        off = null,
        unseen = PinRecord.Unseen(row, if (row == 0) null else 1_759_752_000_000L + row, false),
    )

    private fun fingerprintRecord() = FingerprintRecord(VAULT, null, FingerprintRecord.Off(FingerprintRecord.OffReason.TURNED_OFF, 7L))

    private fun stateRecord(n: Long) = StateRecord(
        VAULT,
        StateRecord.Practice(1_759_752_000_000L, 1, null),
        StateRecord.MpGuard(0, null, 0),
        null,
        null,
        null,
        StateRecord.MaxGeneration(n, 1_759_752_000_000L),
    )

    private companion object {
        const val ROOT = "/data/user/0/io.github.dilshodbekmx.passwordvault/no_backup/lock"
        const val VAULT = "0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70"
        const val OTHER_VAULT = "00000000-0000-4000-8000-000000000001"
        const val SLOT = "7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4"
        const val VK = "3a1d0c5e-8f2b-4e6a-b1c9-7d4e2f6a8b0c"
    }
}
