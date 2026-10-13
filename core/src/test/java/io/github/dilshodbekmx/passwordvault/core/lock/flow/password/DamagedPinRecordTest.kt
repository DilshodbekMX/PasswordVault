package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.NEW_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.pinOpens
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.setPin
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.unlockWithMasterPassword
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The owner's decision of 2026-10-13 on lock.md §5.3 (build plan §12, decision 16): **a damaged
 * `pin.rec` is handled like a damaged `fingerprint.rec` (decision 1)**, as F15b handles a PIN key
 * that is gone. The lock screen says that the PIN stopped working; the next master-password unlock
 * offers "Choose a new PIN" and replaces the damaged record with one without a slot
 * (`off = key_gone`), whatever the answer, so that it no longer blocks the key sweep (§5.7).
 *
 * Before it a damaged `pin.rec` stayed on disk until a new PIN was set, and with it the key sweep
 * stayed blocked: every key that a PIN change or a fingerprint change had replaced stayed in the
 * Keystore.
 */
class DamagedPinRecordTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private val file = RecordCodec.Pin.fileName

    private lateinit var vault: FakeVaultPort.FakeVault
    private lateinit var pinKey: String

    /** A vault with a PIN, and fingerprint unlock when [fingerprint], whose `pin.rec` no longer passes its hash. */
    private fun withDamagedRecord(fingerprint: Boolean = false, vaultId: String = VAULT) {
        vault = f.seedVault(vaultId = vaultId)
        pinKey = PinWrap.alias(f.plantPin(vault, row = 2, total = 3, unseen = PinRecord.Unseen(3, f.clock.wallMs(), false)).id)
        if (fingerprint) f.plantFingerprint(vault)
        f.plantState(vaultId)
        damage(vaultId)
    }

    private fun damage(vaultId: String = VAULT) {
        f.files.damage(f.dirOf(vaultId), file) { it.also { b -> b[b.size - 1] = (b[b.size - 1] + 1).toByte() } }
        assertTrue(f.pinOnDisk(vaultId) is RecordRead.Damaged)
    }

    private fun onDisk(vaultId: String = VAULT) = f.files.bytes(f.dirOf(vaultId), file)!!

    /** The record that takes a damaged one's place: no slot, `off = key_gone` now, as F15b leaves one; no counts, nothing unseen. */
    private fun replacement(vaultId: String = VAULT, at: Long = f.clock.wallMs()) = PinRecord(
        vaultId,
        slot = null,
        guard = PinRecord.Guard(charged = false, row = 0, total = 0),
        off = PinRecord.Off(PinRecord.OffReason.KEY_GONE, at),
        unseen = PinRecord.Unseen(0, null, false),
    )

    @Test
    fun onTheLockScreen_thePinStoppedWorking_andNothingIsWritten() {
        withDamagedRecord()
        val damaged = onDisk()
        f.startInFront()

        assertEquals(MpReason.Pin(PinReason.STOPPED_WORKING), f.field.reason)
        assertEquals(LockSummary.Pin.Off(PinReason.STOPPED_WORKING), f.engine.summary.value.pin)
        f.restart()
        f.startInFront()
        assertArrayEquals("never repaired in place (§5.3), and nothing before a master password", damaged, onDisk())
    }

    @Test
    fun theNextMasterPasswordUnlock_offersAChoiceOfANewPin_andTheDamagedRecordGoes_whateverTheAnswer() {
        for (answer in listOf(NoticeAnswer.LATER, NoticeAnswer.CHOOSE_PIN, null)) {
            val g = LockFixture()
            val v = g.seedVault()
            g.plantPin(v)
            g.plantState()
            g.files.damage(g.dirOf(VAULT), file) { it.also { b -> b[b.size - 1] = (b[b.size - 1] + 1).toByte() } }
            g.startInFront()

            g.unlockWithMasterPassword()

            val offer = g.notices.filterIsInstance<Notice.ChoosePin>().single()
            assertEquals("$answer", ChoosePinReason.STOPPED_WORKING to false, offer.reason to offer.needsMasterPassword)
            // The record went at the unlock itself, as F15b leaves one: no slot, `off = key_gone`.
            assertEquals("$answer", replacement(at = g.clock.wallMs()), g.pinRecord())
            if (answer != null) await { g.engine.answerNotice(offer.id, answer) }
            // The lock screen still says that the PIN stopped working, and the next master password offers a new one again.
            g.lock()
            assertEquals("$answer", MpReason.Pin(PinReason.STOPPED_WORKING), g.field.reason)
            g.unlockWithMasterPassword()
            assertEquals("$answer", ChoosePinReason.STOPPED_WORKING, g.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
        }
    }

    @Test
    fun aFingerprintUnlock_writesNothing_andTheDamagedRecordStays() {
        withDamagedRecord(fingerprint = true)
        val damaged = onDisk()
        f.startInFront()
        assertEquals(LockScreen.FingerprintOnly(status = null, pinReason = PinReason.STOPPED_WORKING, aside = null), f.screen)

        await { f.engine.promptIfDue() }

        assertTrue(f.state is LockState.Unlocked)
        // The offer that still needs the master password (F5 step 4), as before.
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.STOPPED_WORKING to true, offer.reason to offer.needsMasterPassword)
        assertArrayEquals(damaged, onDisk())
    }

    @Test
    fun theDamagedRecordBlocksTheKeySweep_untilTheMasterPasswordUnlock_andNoLonger() {
        withDamagedRecord(fingerprint = true)
        // A key that a PIN change replaced earlier: no record names it, and the sweep is to delete it (§5.7).
        val orphan = PinWrap.alias("00000000-0000-4000-8000-00000000aaaa")
        f.keys.putPinKey(orphan, ByteArray(32) { 9 })
        f.startInFront()
        assertEquals("a damaged record blocks the sweep: its key may be named there", emptyList<String>(), f.keys.deleted)
        await { f.engine.promptIfDue() } // a fingerprint unlock
        f.restart()
        f.startInFront()
        assertEquals("still blocked after a fingerprint unlock", emptyList<String>(), f.keys.deleted)

        f.unlockWithMasterPassword()
        f.restart()
        f.startInFront()

        // Every record reads again: the orphan goes, and so does the PIN key that the damaged record named.
        assertEquals(setOf(orphan, pinKey), f.keys.deleted.toSet())
        assertTrue(f.keys.aliases.single().startsWith(RecordCodec.FINGERPRINT_ALIAS_PREFIX))
    }

    @Test
    fun chooseANewPin_setsUpANewSlot_andThePinWorksAgain() {
        withDamagedRecord()
        f.startInFront()
        val proof = f.unlockWithMasterPassword()
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        await { f.engine.answerNotice(offer.id, NoticeAnswer.CHOOSE_PIN) }

        assertTrue(f.setPin(NEW_PIN, proof))

        assertTrue(f.pinOpens(vault, NEW_PIN))
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)
        f.lock()
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.notices.none { it is Notice.ChoosePin })
    }

    @Test
    fun whenTheReplacingWriteFails_theRecordStaysDamaged_andTheNextMasterPasswordUnlockTriesAgain() {
        withDamagedRecord()
        val damaged = onDisk()
        f.startInFront()
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1) { it.endsWith("/$file") } // the disk is full for this one write

        f.unlockWithMasterPassword()

        assertEquals(ChoosePinReason.STOPPED_WORKING, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
        assertArrayEquals(damaged, onDisk())
        f.lock()
        assertEquals(MpReason.Pin(PinReason.STOPPED_WORKING), f.field.reason)

        f.unlockWithMasterPassword()
        assertEquals(ChoosePinReason.STOPPED_WORKING, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
        assertEquals(replacement(), f.pinRecord())
    }

    @Test
    fun onAPhoneThatCannotKeepKeys_nothingIsOffered_andTheDamagedRecordGoesAllTheSame() {
        withDamagedRecord()
        f.keys.dropAllKeys()
        f.keys.softwareOnly = true // F18: no key of this phone is kept in secure hardware
        f.startInFront()

        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)

        assertEquals(emptyList<Notice>(), f.notices)
        assertTrue(f.pinOnDisk() is RecordRead.Ok)
        assertNull(f.pinRecord().slot)
    }

    @Test
    fun aVaultPutAsideWithADamagedRecord_isSaidTheSameWay_andItsRecordGoesWhenItsMasterPasswordOpensIt() {
        f.seedVault()
        f.plantState()
        val words = "the other vault's own words"
        val old = f.port.seedAside(OTHER_VAULT, bytes(words))
        pinKey = PinWrap.alias(f.plantPin(old, pin = "905127").id)
        f.plantState(OTHER_VAULT)
        damage(OTHER_VAULT)
        val damaged = onDisk(OTHER_VAULT)
        f.startInFront()

        await { f.engine.showAside(OTHER_VAULT) }

        assertEquals(MpReason.Pin(PinReason.STOPPED_WORKING), f.field.reason)
        assertTrue(f.field.aside != null)
        assertArrayEquals(damaged, onDisk(OTHER_VAULT))

        assertTrue(f.unlockWith(words) is MasterPasswordOutcome.Right) // F17 step 4: the two vaults change places

        assertEquals(OTHER_VAULT, f.port.current!!.vaultId)
        assertEquals(ChoosePinReason.STOPPED_WORKING, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)
        assertEquals(replacement(vaultId = OTHER_VAULT), f.pinRecord(OTHER_VAULT))
    }
}
