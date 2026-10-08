package io.github.dilshodbekmx.passwordvault.core.lock.flow.shortcuts

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CreateOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintOnOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockSummary
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.VAULT
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.WRONG_PIN
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.await
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.bytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.core.LockFixture.Companion.digits
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Scripted runs of **F6 "Set"** (lock.md §12), through the engine: a PIN set with the master
 * password typed in the same flow (§8.2), from every place that leads there (F1 step 6, F5 step 4,
 * F7 step 3, F14, F15b, "Change PIN" › "Use master password instead"), and every way steps 4 to 8
 * can fail. Each run ends in the state lock.md describes: `pin.rec` with the new slot and one wrap
 * for the open vault's key, the guard of §6.4, the new key in the Keystore and the old one left to
 * the sweep (§5.7); or, when it fails, nothing changed and no key that no record names.
 */
class SetPinFlowTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val f = LockFixture()

    /** A vault with a PIN, its records, started and in front. */
    private fun withPin(row: Int = 0, total: Int = 0, unseen: PinRecord.Unseen = PinRecord.Unseen(0, null, false)): FakeVaultPort.FakeVault {
        val vault = f.seedVault()
        f.plantPin(vault, row = row, total = total, unseen = unseen)
        f.plantState()
        f.startInFront()
        return vault
    }

    /** The PIN slot on disk is the one [setPin] made: one wrap for [vault]'s newest key, which [pin] opens through its own key. */
    private fun assertNewSlot(vault: FakeVaultPort.FakeVault, pin: String, before: String?) {
        val record = f.pinRecord(vault.vaultId)
        val slot = record.slot!!
        assertNotEquals("a new slot id", before, PinWrap.alias(slot.id))
        assertEquals(listOf(vault.newestVkId), slot.wraps.map { it.vkId })
        assertEquals(12, slot.wraps.single().nonce.size)
        assertEquals(48, slot.wraps.single().ct.size)
        assertEquals(f.clock.wallMs(), slot.createdMs)
        assertNull(record.off)
        assertTrue("the new wrap opens with the new PIN", f.pinOpens(vault, pin))
        assertTrue(PinWrap.alias(slot.id) in f.keys.aliases)
    }

    // ---- where F6 "Set" begins ------------------------------------------------------------------------

    @Test
    fun f1_step6_aPinIsSetAtSetupWithTheProofOfTheNewMasterPassword_thenStep7TheFingerprint() {
        f.startInFront()
        assertTrue(f.state is LockState.NoVault)
        val created = await { f.engine.createVault(PASSWORD.toCharArray(), PASSWORD.toCharArray()) }
        assertTrue(created is CreateOutcome.Created)
        val unlocked = f.state as LockState.Unlocked
        assertEquals(ChoosePinReason.NONE_HERE, (unlocked.notices.single() as Notice.ChoosePin).reason)
        val proof = unlocked.proof!!
        val vault = f.port.current!!

        assertTrue(f.setPin(NEW_PIN, proof))
        assertNewSlot(vault, NEW_PIN, before = null)
        val record = f.pinRecord(vault.vaultId)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), record.guard)
        assertEquals(PinRecord.Unseen(0, null, false), record.unseen)
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)
        assertTrue("the canary was made first (F6 step 4)", f.keys.canary)
        // Choosing a PIN is not an unlock: nothing was checked against the slot (§7.3).
        assertEquals(2, f.keys.hmacCalls.size) // KEK_pin, and once more for the test of the wrap (step 6)
        assertTrue(f.keys.hmacCalls.all { it.alias == PinWrap.alias(record.slot!!.id) })

        // F1 step 7: the fingerprint offer, with the same proof.
        assertEquals(FingerprintOnOutcome.On, f.fingerprintOn(proof))
        assertTrue(f.fingerprintOpens(vault))
        assertEquals(emptyList<String>(), f.port.writes.filterNot { it.startsWith("new-") }) // only setup wrote vault data (F1)

        // The next lock shows the pad, and the new PIN opens the vault.
        f.lock()
        assertTrue(f.pad.fingerprintKey)
        f.type(NEW_PIN)
        assertEquals(LockState.Unlocked(emptyList(), null), f.state)
    }

    @Test
    fun f5_step4_afterTheLimit_theMasterPasswordUnlockGivesTheProof_andTheNewPinStartsTheCountAgain() {
        val vault = withPin(row = 4, total = 7)
        val old = f.pinAliasOnDisk()!!
        f.type(WRONG_PIN) // the fifth in a row: the PIN is turned off (§6.5)
        assertEquals(MpReason.Pin(PinReason.LIMIT), f.field.reason)
        assertFalse(old in f.keys.aliases) // the key of a slot removed at a limit goes at once

        val proof = f.unlockWithMasterPassword()
        val offer = f.notices.filterIsInstance<Notice.ChoosePin>().single()
        assertEquals(ChoosePinReason.TURNED_OFF to false, offer.reason to offer.needsMasterPassword)
        val unseen = f.pinRecord().unseen

        assertTrue(f.setPin(NEW_PIN, proof))
        assertNewSlot(vault, NEW_PIN, before = old)
        assertEquals(PinRecord.Guard(charged = false, row = 0, total = 0), f.pinRecord().guard)
        assertEquals("the wrong PINs nobody was told about stay to be told", unseen, f.pinRecord().unseen)
        assertEquals(LockSummary.Pin.On, f.engine.summary.value.pin)

        f.lock()
        assertNull(f.pad.status) // a fresh count: no warning
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun f7_step3_forgotThePin_theNewSlotReplacesTheOld_andTheOldKeyGoesAtTheNextStart() {
        val vault = withPin(row = 2, total = 6)
        val old = f.pinAliasOnDisk()!!
        val proof = f.unlockWithMasterPassword() // "Forgot PIN? Use master password"
        assertEquals(ChoosePinReason.FORGOTTEN, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)

        assertTrue(f.setPin(NEW_PIN, proof))
        assertNewSlot(vault, NEW_PIN, before = old)
        // §5.7: a key whose slot is replaced is deleted only by the sweep at a later start.
        assertTrue(old in f.keys.aliases)
        assertEquals(emptyList<String>(), f.keys.deleted)

        f.lock()
        f.type(PIN) // the old PIN opens nothing any more: it is checked, and wrong
        assertTrue(f.state is LockState.Locked)
        assertEquals(1, f.pinRecord().guard.row)
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)

        f.restart()
        f.startInFront()
        assertFalse(old in f.keys.aliases)
        assertEquals(listOf(old), f.keys.deleted)
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun changePin_useMasterPasswordInstead_inTheOpenApp() {
        val vault = withPin(row = 1, total = 4)
        f.type(PIN) // the daily unlock: a right PIN in the visit forgives nothing older (§6.3)
        assertEquals(PinRecord.Guard(false, 0, 4), f.pinRecord().guard)
        val old = f.pinAliasOnDisk()

        val proof = f.proofInTheOpenApp() // "Use master password instead": the counts go to zero
        assertTrue(f.setPin(NEW_PIN, proof))
        assertNewSlot(vault, NEW_PIN, before = old)
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard) // §6.4: the master password was typed
    }

    @Test
    fun f14_aVaultWithoutLockRecords_welcomeBack_thenAPinForThisPhone() {
        val vault = f.seedVault()
        f.startInFront()
        assertEquals(MpReason.WelcomeBack, f.field.reason)
        val proof = f.unlockWithMasterPassword()
        assertEquals(ChoosePinReason.NONE_HERE, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)

        assertTrue(f.setPin(NEW_PIN, proof))
        assertNewSlot(vault, NEW_PIN, before = null)
        assertTrue(f.stateOnDisk() is RecordRead.Ok) // made by the unlock (F14 step 2)
        f.lock()
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun aDamagedPinRecord_isSetUpAgain_whichWritesANewRecord() {
        val vault = withPin()
        f.files.damage(f.dirOf(VAULT), RecordCodec.Pin.fileName) { it.also { bytes -> bytes[20] = (bytes[20] + 1).toByte() } }
        f.restart()
        f.startInFront()
        assertEquals(MpReason.Pin(PinReason.STOPPED_WORKING), f.field.reason)
        val proof = f.unlockWithMasterPassword()
        assertEquals(ChoosePinReason.STOPPED_WORKING, f.notices.filterIsInstance<Notice.ChoosePin>().single().reason)

        assertTrue(f.setPin(NEW_PIN, proof)) // §5.3: never repaired in place; "set it up again" writes a new record
        assertNewSlot(vault, NEW_PIN, before = null)
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
    }

    // ---- what the action needs (§8.2) -------------------------------------------------------------------

    @Test
    fun setting_needsAProofThisEngineMadeForTheOpenVault_andNothingElseWillDo() {
        withPin()
        f.type(PIN) // a PIN unlock: no proof
        assertNull((f.state as LockState.Unlocked).proof)
        val before = f.files.bytes(f.dirOf(VAULT), RecordCodec.Pin.fileName)

        // A proof no master password stands behind, for this vault or another.
        assertFalse(f.setPin(NEW_PIN, MasterProof.forFakes(bytes(PASSWORD), VAULT)))
        assertFalse(f.setPin(NEW_PIN, MasterProof.forFakes(bytes(PASSWORD), LockFixture.OTHER_VAULT)))
        // A real proof, dropped when the app stopped being visible (§5.8).
        val proof = f.proofInTheOpenApp()
        f.leaveAndReturn()
        assertFalse(f.setPin(NEW_PIN, proof))

        assertArrayEquals(before.withCountsOf(f.pinRecord()), f.files.bytes(f.dirOf(VAULT), RecordCodec.Pin.fileName))
        assertEquals(1, f.keys.aliases.size) // no key was made
        assertEquals(0, f.keys.hmacCalls.count { it.alias != f.pinAliasOnDisk() })
    }

    /** The bytes [this] (a `pin.rec` read before) would have with the counts of [now]: the master password typed meanwhile zeroed them. */
    private fun ByteArray?.withCountsOf(now: PinRecord): ByteArray {
        val then = (RecordCodec.Pin.decode(this!!, VAULT) as RecordRead.Ok).record
        return RecordCodec.Pin.encode(then.copy(guard = now.guard))
    }

    @Test
    fun aProofIsDroppedOnLock_andTheLockedAppSetsNothing() {
        withPin()
        val proof = f.unlockWithMasterPassword()
        f.lock()
        assertFalse(f.setPin(NEW_PIN, proof))
        assertEquals(1, f.keys.aliases.size)
    }

    @Test
    fun aPinTheBlocklistRefuses_isNotSet_evenIfTheScreenLetItThrough() {
        val vault = withPin()
        val proof = f.unlockWithMasterPassword()
        val old = f.pinRecord()
        for (pin in listOf(BLOCKED_PIN, "111111", "250890", "199012", "147258")) {
            assertFalse(pin, f.setPin(pin, proof))
        }
        assertEquals(old, f.pinRecord())
        assertEquals(1, f.keys.aliases.size)
        assertTrue(f.pinOpens(vault, PIN))
    }

    @Test
    fun aPinThatIsNotSixDigits_isTheCallersMistake() {
        withPin()
        val proof = f.unlockWithMasterPassword()
        for (pin in listOf("12345", "1234567", "12345a", "")) {
            assertThrows(IllegalArgumentException::class.java) { await { f.engine.setPin(pin.toByteArray(Charsets.US_ASCII), proof) } }
        }
        assertEquals(1, f.keys.aliases.size)
    }

    @Test
    fun theCallersPinIsNotChanged() {
        withPin()
        val proof = f.unlockWithMasterPassword()
        val pin = digits(NEW_PIN)
        assertTrue(await { f.engine.setPin(pin, proof) })
        assertArrayEquals(digits(NEW_PIN), pin)
    }

    // ---- steps 4 to 8: what fails, and what is left -------------------------------------------------------

    @Test
    fun step4_aPhoneThatCannotKeepKeysInSecureHardware_F18_setsNoPin() {
        f.keys.softwareOnly = true // and no canary yet: F1 step 5 finds it
        val vault = f.seedVault()
        f.startInFront()
        val proof = f.unlockWithMasterPassword()
        assertFalse(f.setPin(NEW_PIN, proof))
        assertEquals(RecordRead.Missing, f.pinOnDisk())
        assertEquals(emptyList<String>(), f.keys.aliases)
        assertEquals(LockSummary.Pin.NoSecureHardware, f.engine.summary.value.pin)

        // A canary that was made before, and a PIN key that would be in software (§4.5): NOT NOW, for good.
        f.keys.canary = true
        assertFalse(f.setPin(NEW_PIN, proof))
        assertEquals(RecordRead.Missing, f.pinOnDisk())
        assertEquals(emptyList<String>(), f.keys.aliases)
        assertEquals(vault.vaultId, f.port.openVaultId)
    }

    @Test
    fun step4_aKeyThatCannotBeMadeNow_isNotNow_andTheHalfMadeAliasGoes() {
        val vault = withPin()
        val proof = f.unlockWithMasterPassword()
        val old = f.pinRecord()
        f.keys.scriptCreate(KeyCreate.NotNow)
        assertFalse(f.setPin(NEW_PIN, proof))
        assertEquals(old, f.pinRecord())
        assertEquals(1, f.keys.deleted.size) // the new alias: no record names it
        assertTrue(f.keys.deleted.single().startsWith(RecordCodec.PIN_ALIAS_PREFIX))
        assertNotEquals(f.pinAliasOnDisk(), f.keys.deleted.single())
        assertTrue(f.pinOpens(vault, PIN)) // "Your old PIN still works."

        // The Keystore does not answer at all (F15c): the canary cannot be checked; nothing changes.
        f.keys.answering = false
        assertFalse(f.setPin(NEW_PIN, proof))
        assertEquals(old, f.pinRecord())
        f.keys.answering = true
        assertTrue(f.setPin(NEW_PIN, proof)) // a later try works
    }

    @Test
    fun step5_noHmacFromTheNewKey_theNewAliasGoes() {
        val vault = withPin()
        val proof = f.unlockWithMasterPassword()
        val old = f.pinRecord()
        f.keys.scriptHmac(Ks.Temporary(null))
        assertFalse(f.setPin(NEW_PIN, proof))
        assertEquals(old, f.pinRecord())
        assertEquals(listOf(old.slot!!.let { PinWrap.alias(it.id) }), f.keys.aliases)
        assertTrue(f.pinOpens(vault, PIN))
    }

    /** A PIN key whose [n]-th HMAC gives other bytes: a wrap made with the first cannot be opened with it. */
    private class SecondHmacDiffers(private val inner: PinKey, private val n: Int) : PinKey {
        var calls = 0

        override fun create(alias: String): KeyCreate = inner.create(alias)

        override fun hmac(alias: String, message: ByteArray): Ks<ByteArray> {
            calls++
            val answer = inner.hmac(alias, message)
            if (calls != n || answer !is Ks.Ok) return answer
            return Ks.Ok(answer.value.map { (it.toInt() xor 1).toByte() }.toByteArray())
        }
    }

    @Test
    fun step6_aWrapThatTheTestDoesNotOpen_isNotWritten_andTheNewAliasGoes() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        val pinKey = SecondHmacDiffers(f.keys.pin, n = 2)
        f.pinKey = pinKey
        f.rebuild()
        f.startInFront()
        val proof = f.unlockWithMasterPassword()
        val old = f.pinRecord()

        assertFalse(f.setPin(NEW_PIN, proof)) // calls 1 and 2: KEK_pin, and the test that gave other bytes
        assertEquals(2, pinKey.calls)
        assertEquals(old, f.pinRecord())
        assertEquals(1, f.keys.deleted.size)
        assertEquals(listOf(PinWrap.alias(old.slot!!.id)), f.keys.aliases)

        // A sealing that does not give the bytes it was asked for fails the same test.
        f.aeadPort = object : Aead by f.aead {
            override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray =
                f.aead.seal(key, nonce, ByteArray(plaintext.size) { 7 }, aad)
        }
        f.pinKey = f.keys.pin
        f.restart()
        f.startInFront()
        assertFalse(f.setPin(NEW_PIN, f.unlockWithMasterPassword()))
        assertEquals(old.slot, f.pinRecord().slot)
        assertTrue(f.pinOpens(vault, PIN))
    }

    @Test
    fun step7_aRecordWriteThatFails_keepsTheOldRecord_andLeavesTheNewKeyToTheSweep() {
        val vault = withPin()
        val proof = f.unlockWithMasterPassword()
        val old = f.pinRecord()
        f.files.failNextWrite(FakeRecordFiles.WriteStep.TEMP_WRITE)
        assertFalse(f.setPin(NEW_PIN, proof))
        assertEquals(old, f.pinRecord())
        val orphan = f.keys.aliases.single { it != PinWrap.alias(old.slot!!.id) }
        assertEquals(emptyList<String>(), f.keys.deleted) // a record may name it: the sweep decides at the next start

        f.lock()
        f.type(PIN) // "Your old PIN still works."
        assertTrue(f.state is LockState.Unlocked)

        f.restart()
        f.startInFront()
        assertEquals(listOf(orphan), f.keys.deleted)
        f.type(PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.pinOpens(vault, PIN))
    }

    @Test
    fun step7_aWriteThatFailsAtTheDirectorySync_hasReplacedTheRecord_andItsKeyIsStillThere() {
        val vault = withPin()
        val proof = f.unlockWithMasterPassword()
        val old = PinWrap.alias(f.pinRecord().slot!!.id)
        f.files.failNextWrite(FakeRecordFiles.WriteStep.DIR_SYNC) // §5.9: "not written", although the rename is on disk
        assertFalse(f.setPin(NEW_PIN, proof))
        val onDisk = f.pinAliasOnDisk()!!
        assertNotEquals(old, onDisk)
        assertTrue("a record names the new key: it was not deleted", onDisk in f.keys.aliases)

        // The next process reads the record that is on disk: its PIN opens the vault, and the old key,
        // which no record names any more, is swept.
        f.restart()
        f.startInFront()
        assertEquals(listOf(old), f.keys.deleted)
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertTrue(f.pinOpens(vault, NEW_PIN))
    }

    @Test
    fun aPinRecordOfANewerApp_isNeverReplaced() {
        val vault = f.seedVault()
        f.plantPin(vault)
        f.plantState()
        val newer = newerRecord(f.files.bytes(f.dirOf(VAULT), RecordCodec.Pin.fileName)!!)
        f.files.put(f.dirOf(VAULT), RecordCodec.Pin.fileName, newer)
        f.startInFront()
        assertEquals(MpReason.Pin(PinReason.NEWER_APP), f.field.reason)
        val proof = f.unlockWithMasterPassword()
        val aliases = f.keys.aliases

        assertFalse(f.setPin(NEW_PIN, proof))
        assertArrayEquals(newer, f.files.bytes(f.dirOf(VAULT), RecordCodec.Pin.fileName))
        assertEquals(aliases, f.keys.aliases)
        assertEquals(emptyList<String>(), f.keys.deleted)
    }

    @Test
    fun aStartUpWriteThatFailed_storageFull_isOverByANewPin() {
        val vault = f.seedVault()
        f.plantPin(vault, row = 1, total = 1, charged = true)
        f.plantState()
        f.files.failNextWrite() // the start-up step cannot count the charge (§6.3 S.3)
        f.startInFront()
        assertEquals(MpReason.Pin(PinReason.STORAGE_FULL), f.field.reason)
        val proof = f.unlockWithMasterPassword()
        assertTrue(f.setPin(NEW_PIN, proof))
        assertEquals(PinRecord.Guard(false, 0, 0), f.pinRecord().guard)
        f.lock()
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun anEntryInterruptedBeforeTheNewPin_isNotTheNewPinsLine() {
        val vault = f.seedVault()
        f.plantPin(vault, row = 1, total = 1, charged = true) // the last entry of the old PIN was cut off
        f.plantState()
        f.startInFront()
        assertEquals(PadStatus.Interrupted(3), f.pad.status)
        val proof = f.unlockWithMasterPassword()
        assertTrue(f.setPin(NEW_PIN, proof))
        f.lock()
        assertNull("the line was about the old PIN", f.pad.status)
    }

    @Test
    fun theOfferAfterAFingerprintUnlock_comesAgainForANewPinThatIsTurnedOffInTheSameProcess() {
        val vault = f.seedVault()
        f.plantPin(vault, row = 4, total = 4)
        f.plantFingerprint(vault)
        f.plantState()
        f.startInFront()
        f.type(WRONG_PIN) // the limit: the fingerprint-only screen
        await { f.engine.promptIfDue() }
        assertEquals(true, f.notices.filterIsInstance<Notice.ChoosePin>().single().needsMasterPassword)
        f.answerEveryNotice()
        assertTrue(f.setPin(NEW_PIN, f.proofInTheOpenApp()))

        // The new PIN is turned off at a limit in this same process: the offer is made again.
        f.lock()
        for (wrong in listOf("582914", "582915", "582916", "582917", "582918")) f.type(wrong)
        assertTrue(f.screen is LockScreen.FingerprintOnly)
        await { f.engine.promptIfDue() }
        assertEquals(true, f.notices.filterIsInstance<Notice.ChoosePin>().singleOrNull()?.needsMasterPassword)
    }

    @Test
    fun setTwiceInOneProcess_eachKeyThatWasReplacedIsLeftToTheSweep() {
        val vault = withPin()
        val first = f.pinAliasOnDisk()!!
        val proof = f.unlockWithMasterPassword()
        assertTrue(f.setPin(NEW_PIN, proof))
        val second = f.pinAliasOnDisk()!!
        assertTrue(f.setPin(THIRD_PIN, proof))
        val third = f.pinAliasOnDisk()!!
        assertEquals(setOf(first, second, third), f.keys.aliases.toSet())
        assertTrue(f.pinOpens(vault, THIRD_PIN))

        f.restart()
        f.startInFront()
        assertEquals(setOf(first, second), f.keys.deleted.toSet())
        assertEquals(listOf(third), f.keys.aliases)
        f.type(THIRD_PIN)
        assertTrue(f.state is LockState.Unlocked)
    }

    @Test
    fun aPinSetOnAnOlderCopy_F16a_wrapsTheKeyOfTheCopyThatIsOpen() {
        val vault = f.seedVault()
        val vk1 = vault.newestVkId
        val vk2 = vault.rekey(bytes("the new one")) // a change of master password …
        vault.live.first().state = FakeVaultPort.FileState.DAMAGED // … whose copy is damaged now
        f.plantState(maxGeneration = StateRecord.MaxGeneration(1, f.clock.wallMs()))
        f.startInFront()
        val proof = f.unlockWithMasterPassword() // the master password of the copy that opens
        assertEquals(1L, f.port.openGeneration)

        assertTrue(f.setPin(NEW_PIN, proof))
        assertEquals(listOf(vk1), f.pinRecord().slot!!.wraps.map { it.vkId })
        assertNotEquals(vk1, vk2)
        assertTrue(f.pinOpens(vault, NEW_PIN, vk1))
        f.lock()
        f.type(NEW_PIN)
        assertTrue(f.state is LockState.Unlocked)
        assertNotNull(f.notices.filterIsInstance<Notice.OlderCopy>().singleOrNull())
    }

    @Test
    fun theFieldOfTheNextLockScreen_isThePad() {
        withPin()
        f.unlockWithMasterPassword().let { proof -> assertTrue(f.setPin(NEW_PIN, proof)) }
        f.lock()
        assertEquals(LockScreen.Pad(digits = 0, status = null, fingerprintKey = false, opening = false, aside = null), f.screen)
    }
}
