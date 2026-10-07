package io.github.dilshodbekmx.passwordvault.core.lock.flow.core

import io.github.dilshodbekmx.passwordvault.core.crypto.CryptoFactory
import io.github.dilshodbekmx.passwordvault.core.lock.flow.KeyCreate
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockEngine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockState
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterPasswordOutcome
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.FileRecordStore
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKdf
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeKeys
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeRecordFiles
import io.github.dilshodbekmx.passwordvault.testing.lock.FakeVaultPort
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * One phone for the tests of the lock engine: the lock directories ([files]), the Keystore
 * ([keys]), the vaults ([port]) and both clocks ([clock]), with a [LockEngine] over them. [restart]
 * is a new process over the same phone.
 *
 * Setting a PIN (F6) and turning fingerprint unlock on (F9) are not part of the engine's core, so a
 * test puts the records and the keys of a shortcut on the phone as an earlier process left them:
 * [plantPin], [plantFingerprint], [plantState].
 *
 * The clock moves on by itself through every wait unless a test says otherwise, and every call of
 * the engine runs on the calling thread ([io] is `Dispatchers.Unconfined`), so [await] returns when
 * the call has finished.
 */
internal class LockFixture(val clock: FakeClock = FakeClock(elapsedMs = 3_600_000L), seed: Long = 1L) {
    val files = FakeRecordFiles(clock)
    val keys = FakeKeys(FakeRandom(seed + 5), now = clock::elapsedMs)
    val port = FakeVaultPort(clock, FakeRandom(seed + 11))
    val kdf = FakeKdf()
    val aead = CryptoFactory.aead()
    private val random = FakeRandom(seed + 21)

    /** What the engine is given for its record files; a crash test wraps [files] here. */
    var recordFiles: RecordFiles = files

    var engine: LockEngine = newEngine()
        private set

    private fun newEngine(): LockEngine =
        LockEngine(recordFiles, ROOT, clock, random, keys.pin, keys.fingerprint, keys.device, port, kdf, aead, Dispatchers.Unconfined)

    /** A new process over the same phone: nothing in memory survives; what was handed over to the files does. */
    fun restart(): LockEngine {
        files.kill()
        port.restart()
        engine = newEngine()
        return engine
    }

    /** Process start, with the app coming to the front right after it (the usual order). */
    fun startInFront(): LockEngine = engine.also {
        await { it.start() }
        await { it.appStarted() }
    }

    val state: LockState get() = engine.state.value
    val screen: LockScreen get() = (state as LockState.Locked).screen
    val pad: LockScreen.Pad get() = screen as LockScreen.Pad
    val field: LockScreen.MasterPassword get() = screen as LockScreen.MasterPassword
    val notices: List<Notice> get() = (state as LockState.Unlocked).notices

    // ---- the phone as an earlier process left it -------------------------------------------------

    /** A current vault whose master password is [password]. */
    fun seedVault(password: String = PASSWORD, vaultId: String = VAULT, generations: Int = 1): FakeVaultPort.FakeVault =
        port.seed(vaultId, bytes(password), generations)

    /** `pin.rec` with a slot for the newest key of [vault] under [pin], and the Keystore key behind it. */
    fun plantPin(
        vault: FakeVaultPort.FakeVault,
        pin: String = PIN,
        row: Int = 0,
        total: Int = 0,
        charged: Boolean = false,
        unseen: PinRecord.Unseen = PinRecord.Unseen(0, null, false),
        vkIds: List<String> = listOf(vault.newestVkId),
    ): ShortcutSlot {
        val slotId = random.uuid()
        val alias = PinWrap.alias(slotId)
        keys.canary = true
        keys.putPinKey(alias, random.bytes(32))
        val kek = keys.hmacOf(alias, PinWrap.message(vault.vaultId, slotId, digits(pin)))
        val wraps = vkIds.map { vkId -> PinWrap.seal(aead, kek, random.bytes(12), vault.vk(vkId), vault.vaultId, vkId, slotId) }
        val slot = ShortcutSlot(slotId, clock.wallMs(), wraps)
        putPin(PinRecord(vault.vaultId, slot, PinRecord.Guard(charged, row, total), off = null, unseen = unseen))
        return slot
    }

    /** `fingerprint.rec` with a slot for the newest key of [vault], and the Keystore key behind it. */
    fun plantFingerprint(vault: FakeVaultPort.FakeVault, vkIds: List<String> = listOf(vault.newestVkId)): ShortcutSlot {
        val slotId = random.uuid()
        val alias = RecordCodec.FINGERPRINT_ALIAS_PREFIX + slotId
        keys.canary = true
        check(keys.fingerprint.create(alias) == KeyCreate.Ok)
        val key = keys.fingerprintKeyOf(alias)!!
        val wraps = vkIds.map { vkId ->
            val iv = random.bytes(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            KeyWrap(vkId, iv, cipher.doFinal(vault.vk(vkId)))
        }
        val slot = ShortcutSlot(slotId, clock.wallMs(), wraps)
        putFingerprint(FingerprintRecord(vault.vaultId, slot, off = null))
        return slot
    }

    /** `state.rec` of [vaultId]; by default practice is not due (step 3, typed now) and nothing else is set. */
    fun plantState(
        vaultId: String = VAULT,
        practice: StateRecord.Practice = StateRecord.Practice(clock.wallMs(), 3, null),
        mpGuard: StateRecord.MpGuard = StateRecord.MpGuard(0, null, 0),
        mpChange: StateRecord.MpChange? = null,
        pendingMp: StateRecord.PendingMp? = null,
        replaced: StateRecord.Replaced? = null,
        maxGeneration: StateRecord.MaxGeneration = StateRecord.MaxGeneration(1, clock.wallMs()),
    ): StateRecord = StateRecord(vaultId, practice, mpGuard, mpChange, pendingMp, replaced, maxGeneration).also(::putState)

    fun putPin(record: PinRecord) = files.put(dirOf(record.vaultId), RecordCodec.Pin.fileName, RecordCodec.Pin.encode(record))

    fun putFingerprint(record: FingerprintRecord) =
        files.put(dirOf(record.vaultId), RecordCodec.Fingerprint.fileName, RecordCodec.Fingerprint.encode(record))

    fun putState(record: StateRecord) = files.put(dirOf(record.vaultId), RecordCodec.State.fileName, RecordCodec.State.encode(record))

    // ---- what is on disk now ----------------------------------------------------------------------

    fun dirOf(vaultId: String): String = FileRecordStore.dir(ROOT, vaultId)

    fun pinOnDisk(vaultId: String = VAULT): RecordRead<PinRecord> = FileRecordStore.pin(files, ROOT, vaultId).read()

    fun fingerprintOnDisk(vaultId: String = VAULT): RecordRead<FingerprintRecord> = FileRecordStore.fingerprint(files, ROOT, vaultId).read()

    fun stateOnDisk(vaultId: String = VAULT): RecordRead<StateRecord> = FileRecordStore.state(files, ROOT, vaultId).read()

    fun pinRecord(vaultId: String = VAULT): PinRecord = (pinOnDisk(vaultId) as RecordRead.Ok).record

    fun fingerprintRecord(vaultId: String = VAULT): FingerprintRecord = (fingerprintOnDisk(vaultId) as RecordRead.Ok).record

    fun stateRecord(vaultId: String = VAULT): StateRecord = (stateOnDisk(vaultId) as RecordRead.Ok).record

    // ---- the user ---------------------------------------------------------------------------------

    /** Types [pin] on the pad, digit by digit. */
    fun type(pin: String) = pin.forEach { digit -> await { engine.digit(digit - '0') } }

    /** Types [password] into the master-password field of the lock screen. */
    fun unlockWith(password: String): MasterPasswordOutcome {
        val chars = password.toCharArray()
        return await { engine.unlockWithMasterPassword(chars) }
    }

    /** Types [password] into a prompt of the open app. */
    fun check(password: String): MasterPasswordOutcome {
        val chars = password.toCharArray()
        return await { engine.checkMasterPassword(chars) }
    }

    /** Locks as `AutoLock` does, with the app staying in front. */
    fun lock() = await { engine.lock() }

    /** The user leaves the app (`ON_STOP`) and comes back (`ON_START`). */
    fun leaveAndReturn() {
        await { engine.appStopped() }
        await { engine.appStarted() }
    }

    companion object {
        const val ROOT = "/data/user/0/app/no_backup/lock"
        const val VAULT = "00000000-0000-4000-8000-000000000001"
        const val OTHER_VAULT = "00000000-0000-4000-8000-0000000000a1"
        const val THIRD_VAULT = "00000000-0000-4000-8000-0000000000b1"
        const val PASSWORD = "correct horse battery staple"
        const val PIN = "582913"
        const val WRONG_PIN = "582914"

        fun bytes(password: String): ByteArray = password.toByteArray(Charsets.UTF_8)

        fun digits(pin: String): ByteArray = pin.toByteArray(Charsets.US_ASCII)

        /** Runs a call of the engine to its end on this thread. */
        fun <T> await(block: suspend () -> T): T = runBlocking { block() }
    }
}
