package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.lock.flow.DeviceKeys
import io.github.dilshodbekmx.passwordvault.core.lock.flow.DirRecords
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Sweep
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.THIRD_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * lock.md test T18, the key sweep of §5.7: it never deletes an alias named by any record of any
 * vault directory; it does nothing when any record is DAMAGED, NEWER or UNREADABLE, or when
 * `aliases()` is empty; it deletes real orphans; it never touches `androidxBiometric`.
 */
class SweepTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private val f = LockFixture()

    private fun pinAlias(slot: ShortcutSlot) = PinWrap.alias(slot.id)

    private fun fingerAlias(slot: ShortcutSlot) = RecordCodec.FINGERPRINT_ALIAS_PREFIX + slot.id

    /** A Keystore key that no record names. */
    private fun orphanPin(id: String): String = (RecordCodec.PIN_ALIAS_PREFIX + id).also { f.keys.putPinKey(it, ByteArray(32) { 1 }) }

    private fun orphanFinger(id: String): String =
        (RecordCodec.FINGERPRINT_ALIAS_PREFIX + id).also { check(f.keys.fingerprint.create(it) == KeyCreate.Ok) }

    // ---- through process start ---------------------------------------------------------------------

    @Test
    fun t18_deletesRealOrphans_andNoAliasNamedByAnyRecordOfAnyVaultDirectory() {
        val current = f.seedVault()
        val pin = f.plantPin(current)
        val finger = f.plantFingerprint(current)
        f.plantState()
        val old = f.port.seedAside(OTHER_VAULT, bytes("an older password"))
        val oldPin = f.plantPin(old) // a vault put aside keeps its directory and its keys (§5.2)
        val gone = f.port.seedAside(THIRD_VAULT, bytes("x"))
        val gonePin = f.plantPin(gone)
        await { f.port.deleteAside(THIRD_VAULT) } // a lock directory whose vault is no longer on the phone: still a record that names a key
        val orphans = setOf(orphanPin("00000000-0000-4000-8000-00000000aaaa"), orphanFinger("00000000-0000-4000-8000-00000000bbbb"))
        f.restart()
        f.startInFront()

        assertEquals(orphans, f.keys.deleted.toSet())
        assertEquals(setOf(pinAlias(pin), fingerAlias(finger), pinAlias(oldPin), pinAlias(gonePin)), f.keys.aliases.toSet())
        assertTrue(f.keys.canary) // never deleted by the app
    }

    @Test
    fun t18_aKeyThatANewSlotReplaced_goesAtALaterStart_onceTheReplacingRecordIsReadBackFromDisk() {
        val vault = f.seedVault()
        val old = f.plantPin(vault)
        val new = f.plantPin(vault) // F6 wrote a record with a new slot; the old key is left to the sweep
        f.plantState()
        assertEquals(2, f.keys.aliases.size)
        f.startInFront()
        assertEquals(listOf(pinAlias(old)), f.keys.deleted)
        assertEquals(listOf(pinAlias(new)), f.keys.aliases)
    }

    @Test
    fun t18_whenTheReplacingRecordWasLost_theSurvivingRecordStillHasItsKey() {
        val vault = f.seedVault()
        val old = f.plantPin(vault)
        val surviving = f.pinRecord()
        val new = f.plantPin(vault)
        f.putPin(surviving) // a power cut lost the write of the new record
        f.plantState()
        f.startInFront()
        assertEquals(listOf(pinAlias(new)), f.keys.deleted) // the key no record names
        assertEquals(listOf(pinAlias(old)), f.keys.aliases)
        f.type(LockFixture.PIN)
        assertTrue(f.state is io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState.Unlocked) // and the old PIN still opens
    }

    @Test
    fun t18_doesNothingWhenAnyRecordOfAnyDirectoryIsDamaged() {
        for (name in listOf("pin.rec", "fingerprint.rec", "state.rec")) {
            for (dir in listOf(VAULT, OTHER_VAULT)) {
                val g = LockFixture()
                val current = g.seedVault()
                g.plantPin(current)
                g.plantFingerprint(current)
                g.plantState()
                val old = g.port.seedAside(OTHER_VAULT, bytes("an older password"))
                g.plantPin(old)
                g.plantFingerprint(old)
                g.plantState(OTHER_VAULT)
                g.keys.putPinKey(RecordCodec.PIN_ALIAS_PREFIX + "00000000-0000-4000-8000-00000000aaaa", ByteArray(32))
                g.files.damage(g.dirOf(dir), name) { it.also { bytes -> bytes[20] = (bytes[20] + 1).toByte() } }
                g.startInFront()
                assertEquals("$dir/$name", emptyList<String>(), g.keys.deleted)
                assertEquals(5, g.keys.aliases.size)
            }
        }
    }

    @Test
    fun t18_doesNothingWhenARecordCannotBeRead_andSweepsAtTheStartThatCanReadIt() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        val orphan = orphanPin("00000000-0000-4000-8000-00000000aaaa")
        f.files.fail(FakeRecordFiles.Op.READ, times = -1) { it.endsWith("/state.rec") }
        f.startInFront()
        assertEquals(emptyList<String>(), f.keys.deleted)

        f.files.clearFaults()
        f.restart()
        f.startInFront()
        assertEquals(listOf(orphan), f.keys.deleted)
    }

    @Test
    fun t18_doesNothingWhenTheLockDirectoriesCannotBeListed() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        orphanPin("00000000-0000-4000-8000-00000000aaaa")
        f.files.fail(FakeRecordFiles.Op.DIRS)
        f.startInFront()
        assertEquals(emptyList<String>(), f.keys.deleted)
        assertTrue(f.pad.digits == 0) // the current vault's own records were still read
    }

    @Test
    fun t18_doesNothingWhenTheKeystoreListsNoAlias() {
        val vault = f.seedVault()
        val pin = f.plantPin(vault)
        f.plantState()
        val orphan = orphanPin("00000000-0000-4000-8000-00000000aaaa")
        f.keys.answering = false // a failing Keystore gives an empty list, and deletes nothing
        f.startInFront()
        assertEquals(emptyList<String>(), f.keys.deleted)

        f.keys.answering = true
        f.restart()
        f.startInFront()
        assertEquals(listOf(orphan), f.keys.deleted)
        assertEquals(listOf(pinAlias(pin)), f.keys.aliases)
    }

    // ---- the sweep by itself -----------------------------------------------------------------------

    /** A Keystore that lists whatever it is given and notes what it is asked to delete. */
    private class Listed(val all: List<String>, val failDelete: Boolean = false) : DeviceKeys {
        val deleted = ArrayList<String>()

        override fun ensureCanary(): KeyCreate = KeyCreate.Ok

        override fun aliases(): List<String> = all

        override fun delete(alias: String) {
            if (failDelete) throw IllegalStateException("the Keystore did not answer")
            deleted += alias
        }
    }

    private fun slot(id: String, vkId: String = "00000000-0000-4000-8000-0000000000c1") =
        ShortcutSlot(id, 0L, listOf(KeyWrap(vkId, ByteArray(12), ByteArray(48))))

    private fun pinRead(slotId: String?): RecordRead<PinRecord> = RecordRead.Ok(
        PinRecord(VAULT, slotId?.let { slot(it) }, PinRecord.Guard(false, 0, 0), null, PinRecord.Unseen(0, null, false)),
    )

    private fun fingerRead(slotId: String?): RecordRead<FingerprintRecord> = RecordRead.Ok(FingerprintRecord(VAULT, slotId?.let { slot(it) }, null))

    private val noState: RecordRead<StateRecord> = RecordRead.Missing

    @Test
    fun t18_neverTouchesAndroidxBiometric_norTheCanary_norAnyAliasThatIsNotOurs() {
        val keys = Listed(listOf("androidxBiometric", "pv_canary", "pv_pin", "pv_fpx", "PV_PIN_UPPER", "other_app_key", "pv_pin_orphan", "pv_fp_orphan"))
        val deleted = Sweep.run(listOf(DirRecords(VAULT, RecordRead.Missing, RecordRead.Missing, noState)), keys)
        assertEquals(listOf("pv_pin_orphan", "pv_fp_orphan"), deleted)
        assertEquals(listOf("pv_pin_orphan", "pv_fp_orphan"), keys.deleted)
        assertFalse(Sweep.isOurs("androidxBiometric"))
        assertFalse(Sweep.isOurs("pv_canary"))
    }

    @Test
    fun t18_everyBlockingReadStopsIt_andAMissingRecordDoesNot() {
        val aliases = listOf("pv_pin_orphan")
        val blocking = listOf<RecordRead<Nothing>>(RecordRead.Damaged(ParseError(0, "hash")), RecordRead.Newer, RecordRead.Unreadable)
        for (read in blocking) {
            assertEquals(emptyList<String>(), Sweep.run(listOf(DirRecords(VAULT, read, RecordRead.Missing, noState)), Listed(aliases)))
            assertEquals(emptyList<String>(), Sweep.run(listOf(DirRecords(VAULT, RecordRead.Missing, read, noState)), Listed(aliases)))
            assertEquals(emptyList<String>(), Sweep.run(listOf(DirRecords(VAULT, RecordRead.Missing, RecordRead.Missing, read)), Listed(aliases)))
            // ... in any directory, also when another directory reads fine.
            val dirs = listOf(DirRecords(VAULT, pinRead(null), fingerRead(null), noState), DirRecords(OTHER_VAULT, RecordRead.Missing, read, noState))
            assertEquals(emptyList<String>(), Sweep.run(dirs, Listed(aliases)))
        }
        assertEquals(aliases, Sweep.run(listOf(DirRecords(VAULT, RecordRead.Missing, RecordRead.Missing, noState)), Listed(aliases)))
        assertEquals(aliases, Sweep.run(emptyList(), Listed(aliases))) // no lock directory at all: nothing names a key
        assertEquals(emptyList<String>(), Sweep.run(null, Listed(aliases))) // the directories could not be listed
        assertEquals(emptyList<String>(), Sweep.run(listOf(DirRecords(null, RecordRead.Missing, RecordRead.Missing, noState)), Listed(aliases))) // not a vault's directory
    }

    @Test
    fun t18_errorsFromDeletingAreIgnored() {
        val keys = Listed(listOf("pv_pin_orphan", "pv_fp_orphan"), failDelete = true)
        assertEquals(emptyList<String>(), Sweep.run(emptyList(), keys)) // the next start tries again
    }

    @Test
    fun t18_overRandomPhones_anAliasNamedByARecordIsNeverDeleted_andEveryOrphanIsWhenNothingBlocks() {
        val random = Random(18)
        val ids = FakeRandom(18)
        repeat(2_000) { round ->
            val named = HashSet<String>()
            var blocked = false
            val dirs = List(random.nextInt(4)) {
                fun <T> read(ok: () -> RecordRead<T>): RecordRead<T> = when (random.nextInt(10)) {
                    0 -> RecordRead.Missing
                    1 -> RecordRead.Damaged(ParseError(1, "hash")).also { blocked = true }
                    2 -> RecordRead.Newer.also { blocked = true }
                    3 -> RecordRead.Unreadable.also { blocked = true }
                    else -> ok()
                }
                val pinSlot = if (random.nextBoolean()) ids.uuid() else null
                val fingerSlot = if (random.nextBoolean()) ids.uuid() else null
                val pin = read { pinRead(pinSlot) }
                val finger = read { fingerRead(fingerSlot) }
                if (pin is RecordRead.Ok && pinSlot != null) named += RecordCodec.PIN_ALIAS_PREFIX + pinSlot
                if (finger is RecordRead.Ok && fingerSlot != null) named += RecordCodec.FINGERPRINT_ALIAS_PREFIX + fingerSlot
                DirRecords(ids.uuid(), pin, finger, read { noState })
            }
            val orphans = List(random.nextInt(4)) { (if (random.nextBoolean()) RecordCodec.PIN_ALIAS_PREFIX else RecordCodec.FINGERPRINT_ALIAS_PREFIX) + ids.uuid() }
            val others = listOf("androidxBiometric", "pv_canary")
            val present = (named.filter { random.nextInt(4) != 0 } + orphans + others).shuffled(random)
            val keys = Listed(present)

            val deleted = Sweep.run(dirs, keys)

            assertTrue("round $round: a named alias was deleted", deleted.none { it in named })
            assertTrue("round $round: an alias that is not ours was deleted", deleted.none { it in others })
            if (blocked) {
                assertEquals("round $round", emptyList<String>(), deleted)
            } else {
                assertEquals("round $round", orphans.toSet(), deleted.toSet())
            }
        }
    }
}
