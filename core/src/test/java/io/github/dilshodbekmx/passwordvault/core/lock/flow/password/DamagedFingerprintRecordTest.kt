package io.github.dilshodbekmx.passwordvault.core.lock.flow.password

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintOnOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.NoticeAnswer
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.OTHER_VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.fingerprintOn
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.fingerprintOpens
import io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts.unlockWithMasterPassword
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The owner's decision of 2026-10-08 on lock.md §5.3 against §13.3 and §13.4 (build plan §12,
 * decision 1): **a damaged `fingerprint.rec` is handled as F15a handles a fingerprint key that is
 * gone.** The lock screen says so with a fingerprint line; the next master-password unlock offers
 * "Turn fingerprint unlock on again?"; and whatever the answer, the damaged record goes at that
 * unlock, so that it no longer blocks the key sweep (§5.7).
 *
 * Before it the lock said nothing and offered nothing for such a record, and it blocked the sweep
 * until the user happened to turn fingerprint unlock on again in Settings.
 */
class DamagedFingerprintRecordTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()
    private val file = RecordCodec.Fingerprint.fileName

    /** The line a damaged record shows: the one of a fingerprint key that is gone (F15a), until the lock screens have a sentence of its own. */
    private val line: FingerprintLine = EntryPolicy.DAMAGED_FINGERPRINT_LINE

    private lateinit var vault: FakeVaultPort.FakeVault
    private lateinit var fingerKey: String

    /** A vault with fingerprint unlock, and a PIN unless [pin] is false, whose `fingerprint.rec` no longer passes its hash. */
    private fun withDamagedRecord(pin: Boolean = true, vaultId: String = VAULT) {
        vault = f.seedVault(vaultId = vaultId)
        if (pin) f.plantPin(vault)
        fingerKey = RecordCodec.FINGERPRINT_ALIAS_PREFIX + f.plantFingerprint(vault).id
        f.plantState(vaultId)
        f.files.damage(f.dirOf(vaultId), file) { it.also { b -> b[b.size - 1] = (b[b.size - 1] + 1).toByte() } }
        assertTrue(f.fingerprintOnDisk(vaultId) is RecordRead.Damaged)
    }

    private fun onDisk(vaultId: String = VAULT) = f.files.bytes(f.dirOf(vaultId), file)!!

    @Test
    fun theLineIsTheOneOfAFingerprintKeyThatIsGone() {
        assertEquals(FingerprintLine.TurnedOff, line)
    }

    @Test
    fun onThePad_theLockScreenSaysThatFingerprintUnlockStoppedWorking_andNothingIsOfferedOrWritten() {
        withDamagedRecord()
        val damaged = onDisk()
        f.startInFront()

        assertEquals(LockScreen.Pad(digits = 0, status = line, fingerprintKey = false, opening = false, aside = null), f.screen)
        await { f.engine.promptIfDue() }
        await { f.engine.useFingerprint() }
        assertEquals(emptyList<FakeKeys.PromptCall>(), f.keys.prompts)
        assertEquals(LockSummary.Fingerprint.Off, f.engine.summary.value.fingerprint) // Settings can turn it on again
        assertArrayEquals("never repaired in place (§5.3)", damaged, onDisk())
    }

    @Test
    fun withoutAPin_theMasterPasswordScreenShowsTheLineUnderItsReason() {
        withDamagedRecord(pin = false)
        f.startInFront()

        assertEquals(line, f.field.fingerprintLine)
        assertNull(f.field.reason)
    }

    @Test
    fun aPinUnlock_offersNothing_andTheDamagedRecordStays() {
        withDamagedRecord()
        val damaged = onDisk()
        f.startInFront()

        f.type(PIN)

        assertEquals(emptyList<Notice>(), f.notices)
        assertArrayEquals(damaged, onDisk())
    }

    @Test
    fun theNextMasterPasswordUnlock_offersToTurnItOnAgain_andTheDamagedRecordGoes_whateverTheAnswer() {
        for (answer in listOf(NoticeAnswer.LATER, NoticeAnswer.TURN_ON_FINGERPRINT, null)) {
            val g = LockFixture()
            val vault = g.seedVault()
            g.plantPin(vault)
            g.plantFingerprint(vault)
            g.plantState()
            g.files.damage(g.dirOf(VAULT), file) { it.also { b -> b[b.size - 1] = (b[b.size - 1] + 1).toByte() } }
            g.startInFront()

            g.unlockWithMasterPassword()

            val offer = g.notices.filterIsInstance<Notice.FingerprintAgain>().single()
            // The record went at the unlock itself, as F15a leaves one: no slot, `off = key_gone`.
            assertEquals("$answer", FingerprintRecord(VAULT, slot = null, off = FingerprintRecord.Off(FingerprintRecord.OffReason.KEY_GONE, g.clock.wallMs())), g.fingerprintRecord())
            if (answer != null) {
                await { g.engine.answerNotice(offer.id, answer) }
                assertEquals("$answer: the explanation was given once (§5.4)", FingerprintRecord(VAULT, slot = null, off = null), g.fingerprintRecord())
            }
            // The lock screen has nothing more to say about it.
            g.lock()
            assertEquals("$answer", LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), g.screen)
        }
    }

    @Test
    fun anOfferThatWasNeverAnswered_isMadeAgainAfterTheNextUnlock_asAfterAKeyThatIsGone() {
        withDamagedRecord()
        f.startInFront()
        f.unlockWithMasterPassword()
        assertEquals(1, f.notices.count { it is Notice.FingerprintAgain })

        f.restart() // the process ends before the offer is answered
        f.startInFront()
        f.type(PIN)

        assertEquals(1, f.notices.count { it is Notice.FingerprintAgain })
    }

    @Test
    fun theDamagedRecordBlocksTheKeySweep_untilTheMasterPasswordUnlock_andNoLonger() {
        withDamagedRecord()
        // A key that a PIN change replaced earlier: no record names it, and the sweep is to delete it (§5.7).
        val orphan = RecordCodec.PIN_ALIAS_PREFIX + "00000000-0000-4000-8000-00000000aaaa"
        f.keys.putPinKey(orphan, ByteArray(32) { 9 })
        f.startInFront()
        assertEquals("a damaged record blocks the sweep: its key may be named there", emptyList<String>(), f.keys.deleted)
        f.type(PIN)
        f.restart()
        f.startInFront()
        assertEquals("still blocked after a PIN unlock", emptyList<String>(), f.keys.deleted)

        f.unlockWithMasterPassword()
        f.restart()
        f.startInFront()

        // Every record reads again: the orphan goes, and so does the fingerprint key that the damaged record named.
        assertEquals(setOf(orphan, fingerKey), f.keys.deleted.toSet())
        assertTrue(f.keys.aliases.single().startsWith(RecordCodec.PIN_ALIAS_PREFIX))
    }

    @Test
    fun turnItOnAgain_setsUpANewSlot() {
        withDamagedRecord()
        f.startInFront()
        val proof = f.unlockWithMasterPassword()
        val offer = f.notices.filterIsInstance<Notice.FingerprintAgain>().single()
        await { f.engine.answerNotice(offer.id, NoticeAnswer.TURN_ON_FINGERPRINT) }

        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))

        assertTrue(f.fingerprintOpens(vault))
        assertEquals(LockSummary.Fingerprint.On, f.engine.summary.value.fingerprint)
        f.lock()
        assertTrue(f.pad.fingerprintKey)
        assertNull(f.pad.status)
    }

    @Test
    fun whenTheReplacingWriteFails_theRecordStaysDamaged_andTheNextMasterPasswordUnlockTriesAgain() {
        withDamagedRecord()
        val damaged = onDisk()
        f.startInFront()
        f.files.fail(FakeRecordFiles.Op.REPLACE, times = 1) { it.endsWith("/$file") } // the disk is full for this one write

        f.unlockWithMasterPassword()

        assertEquals(1, f.notices.count { it is Notice.FingerprintAgain })
        assertArrayEquals(damaged, onDisk())
        f.lock()
        assertEquals(line, f.pad.status)

        f.unlockWithMasterPassword()
        assertEquals(1, f.notices.count { it is Notice.FingerprintAgain })
        assertTrue(f.fingerprintOnDisk() is RecordRead.Ok)
    }

    @Test
    fun onAPhoneThatCannotKeepKeys_nothingIsOffered_andTheDamagedRecordGoesAllTheSame() {
        withDamagedRecord(pin = false)
        f.keys.dropAllKeys()
        f.keys.softwareOnly = true // F18: no key of this phone is kept in secure hardware
        f.startInFront()

        assertTrue(f.unlockWith(PASSWORD) is MasterPasswordOutcome.Right)

        assertEquals(emptyList<Notice>(), f.notices)
        assertTrue(f.fingerprintOnDisk() is RecordRead.Ok)
        assertNull(f.fingerprintRecord().slot)
    }

    @Test
    fun onTheLockScreenOfAVaultPutAside_itsOwnDamagedRecordIsSaidTheSameWay() {
        f.seedVault()
        f.plantState()
        val old = f.port.seedAside(OTHER_VAULT, bytes("the other vault's own words"))
        f.plantPin(old, pin = "905127")
        f.plantFingerprint(old)
        f.plantState(OTHER_VAULT)
        f.files.damage(f.dirOf(OTHER_VAULT), file) { it.also { b -> b[b.size - 1] = (b[b.size - 1] + 1).toByte() } }
        f.startInFront()

        await { f.engine.showAside(OTHER_VAULT) }

        assertEquals(line, f.pad.status)
        assertFalse(f.pad.fingerprintKey)
        assertTrue(f.pad.aside != null)
    }
}
