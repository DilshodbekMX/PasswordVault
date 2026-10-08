package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Credit
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MasterPasswordGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinStartUp
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Place
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.record.FileRecordStore
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordStore
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * What the flows of the lock share (lock.md §5.8, §6.3, §9.2, §11; architecture.md §5.1, §5.2):
 * the lock mutex, the ports, the three records of every vault as the lock controller means to have
 * them on disk, the visit of the lock screen with its digits, the prompts of the open app, the
 * fingerprint launch numbers, the proofs handed out, the notices, and what `LockEngine` publishes.
 *
 * The flow files (`UnlockFlows`, `StartUp`, `Sweep`, and `ShortcutFlows`, `VaultFlows`,
 * `PasswordFlows`, `OlderCopies` of packages 3.13, 3.14 and 4.4) hold the steps; everything they
 * need of each other goes through [LockCore].
 *
 * **The rule every function here follows.** A function marked "holds the mutex" is called only
 * inside [LockCore.locked]. A function marked "any thread" may be called without it, at once, and
 * touches only the few fields that are safe for that (they are volatile or guarded by their own
 * monitor). Nothing here calls [LockCore.locked] from inside it: the mutex is not re-entrant.
 */

/** How the vault was opened: the two shortcuts, or the master password. */
internal enum class UnlockBy { PIN, FINGERPRINT, MASTER_PASSWORD }

/** The vault that is open now, as the vault port said when it opened or made it. */
internal class OpenVault(
    val vaultId: String,
    val generation: Long,
    val vkId: String,
    val createdMs: Long,
    /** A newer generation is on disk and could not be used (the notice of lock.md §13.4 item 1). */
    val olderCopy: Boolean,
)

/**
 * `fingerprint.rec` of one vault as the lock controller means to have it on disk (lock.md §5.5,
 * §6.3): what `PinGuard` is for `pin.rec`, without a counter. Every write stores the whole record,
 * so a write that failed is made good by the next one that succeeds. A record that read DAMAGED,
 * NEWER or UNREADABLE is not used and not rewritten (§5.3); only turning fingerprint unlock on
 * ([setSlot], "set it up again") replaces a DAMAGED or UNREADABLE one, and nothing replaces a NEWER
 * one. Called under the lock mutex, with I/O on the engine's dispatcher.
 *
 * It deletes a fingerprint key only where lock.md says "at once": when its slot is removed. A key
 * whose slot is replaced by [setSlot] is left to the sweep (§5.7).
 */
internal class FingerprintKeeper(
    val vaultId: String,
    private val store: RecordStore<FingerprintRecord>,
    private val clock: Clock,
    private val deviceKeys: DeviceKeys,
) {
    private var lastRead: RecordRead<FingerprintRecord> = RecordRead.Missing
    private var rec: FingerprintRecord? = null

    /** The record this keeper means to have on disk; null while there is none it may use. */
    val record: FingerprintRecord? get() = rec

    /** The record as the entry policy takes it (lock.md §13.1): the one in memory, or what reading the file gave. */
    val read: RecordRead<FingerprintRecord> get() = rec?.let { RecordRead.Ok(it) } ?: lastRead

    /** The slot, or null when fingerprint unlock is off for this vault or its record cannot be used. */
    val slot: ShortcutSlot? get() = rec?.slot

    /** Reads `fingerprint.rec`: step 2 of lock.md §5.11. What was in memory is forgotten. */
    fun load(): RecordRead<FingerprintRecord> {
        val result = try {
            store.read()
        } catch (e: IOException) {
            RecordRead.Unreadable
        }
        lastRead = result
        rec = (result as? RecordRead.Ok)?.record
        return result
    }

    /** The wrap of the vault key [vkId], or null. */
    fun wrapFor(vkId: String): KeyWrap? = rec?.slot?.wraps?.firstOrNull { it.vkId == vkId }

    /**
     * Fingerprint unlock is turned on (lock.md F9 step 8): writes [slot] with `off = null`. It must
     * reach the disk: when the write reports a failure the old record stays here, in memory, and it
     * returns false. Such a write may still have replaced the record on disk (a failed directory
     * `fsync` counts as not written, §5.9, while the rename is already there), so the flow leaves the
     * new alias to the sweep and never deletes it (F9 step 9, as F6 step 8). The earlier key is left
     * to the sweep too. A record that read NEWER is never replaced: false.
     */
    fun setSlot(slot: ShortcutSlot): Boolean {
        require(Uuid.isValid(slot.id)) { "not a lower-case UUID of version 4" }
        require(slot.wraps.size in 1..RecordCodec.MAX_WRAPS && slot.wraps.map { it.vkId }.toSet().size == slot.wraps.size) {
            "a slot holds one or two wraps with different vk_ids"
        }
        if (rec == null && lastRead is RecordRead.Newer) return false
        val next = FingerprintRecord(vaultId, slot, off = null)
        if (!write(next)) return false
        rec = next
        lastRead = RecordRead.Ok(next)
        return true
    }

    /**
     * A master-password change keeps fingerprint unlock (lock.md F8 step 7): the wrap for the key
     * before the change, [currentVkId], is kept, any other dropped, and [add], the wrap of the new
     * key, added, so that a slot never holds three (§5.4). Best effort, as `PinGuard.changeWraps`;
     * false without a slot.
     */
    fun changeWraps(currentVkId: String, add: KeyWrap): Boolean {
        val r = rec ?: return false
        val slot = r.slot ?: return false
        val kept = slot.wraps.filter { it.vkId == currentVkId && it.vkId != add.vkId }
        return keep(r.copy(slot = slot.copy(wraps = kept + add)))
    }

    /**
     * The wraps kept at an unlock (lock.md §5.4): drops every wrap whose `vk_id` is not in
     * [keptVkIds]. A slot left without a wrap is removed (`off = master_changed`) and its key
     * deleted. Best effort; returns whether the record is on disk.
     */
    fun keepWrapsFor(keptVkIds: Set<String>): Boolean {
        val r = rec ?: return true
        val slot = r.slot ?: return true
        val kept = slot.wraps.filter { it.vkId in keptVkIds }
        if (kept.size == slot.wraps.size) return true
        if (kept.isNotEmpty()) return keep(r.copy(slot = slot.copy(wraps = kept)))
        return removeSlot(FingerprintRecord.OffReason.MASTER_CHANGED)
    }

    /**
     * Removes the slot for [reason] (`off`) and deletes its key at once (lock.md §5.7): the user
     * turns fingerprint unlock off (F9), section 14 says the key or the wrap is gone (F15a, F15b),
     * a slot left without a wrap, or a vault that a replacement put aside (§5.11 step 3, F16b step
     * 5). Best effort, as §5.11 step 3 asks: the slot is gone from memory even if the write fails.
     * Returns whether the record is on disk.
     */
    fun removeSlot(reason: FingerprintRecord.OffReason): Boolean {
        val r = rec ?: return true
        val slot = r.slot ?: return true
        val written = keep(r.copy(slot = null, off = FingerprintRecord.Off(reason, clock.wallMs())))
        deviceKeys.delete(alias(slot.id))
        return written
    }

    /** The one-time explanation of `off` was given (lock.md §5.4, "only for the one-time explanation"): `off = null`. Best effort. */
    fun explained(): Boolean {
        val r = rec ?: return true
        if (r.off == null) return true
        return keep(r.copy(off = null))
    }

    private fun keep(next: FingerprintRecord): Boolean {
        rec = next
        return write(next)
    }

    private fun write(record: FingerprintRecord): Boolean =
        try {
            store.write(record)
            true
        } catch (e: IOException) {
            false
        }

    companion object {
        /** `"pv_fp_" + slotId` (lock.md §5.5). */
        fun alias(slotId: String): String = RecordCodec.FINGERPRINT_ALIAS_PREFIX + slotId
    }
}

/**
 * The lock state of one vault (lock.md §5.2: one directory per vault): its three records behind
 * their keepers, [pin] (`pin.rec`, the attempt counter of §6), [master] (`state.rec`, the
 * master-password guard of §6.8 and every other field of the record) and [fingerprint]. One per
 * vault for the life of the process; the current vault and each vault put aside have their own
 * records, counts and waits (F17 step 7, §6.8). Called under the lock mutex.
 */
internal class VaultLock(
    val vaultId: String,
    files: RecordFiles,
    root: String,
    clock: Clock,
    pinKey: PinKey,
    deviceKeys: DeviceKeys,
    aead: Aead,
    random: RandomSource,
) {
    private val pinStore: RecordStore<PinRecord> = FileRecordStore.pin(files, root, vaultId)
    private var pinAtLoad: RecordRead<PinRecord> = RecordRead.Missing

    val pin: PinGuard = PinGuard(vaultId, pinStore, clock, pinKey, deviceKeys, aead, random)
    val master: MasterPasswordGuard = MasterPasswordGuard(vaultId, FileRecordStore.state(files, root, vaultId), clock)
    val fingerprint: FingerprintKeeper = FingerprintKeeper(vaultId, FileRecordStore.fingerprint(files, root, vaultId), clock, deviceKeys)

    /** The counter's start-up step (lock.md §6.3 S) ran for this vault in this process. */
    var pinStarted: Boolean = false
        private set

    /**
     * That step counted an interrupted entry, and no PIN has been typed since: the pad says "Your
     * last PIN entry was interrupted and counted as a try." (§13.3).
     */
    var interruptedAtStart: Boolean = false

    /**
     * The "Choose a new PIN" offer that needs the master password was made after a fingerprint
     * unlock in this process (lock.md F5 step 4): it is not made again until the next process.
     */
    var choosePinOffered: Boolean = false

    /**
     * Reads the three records without changing anything: step 2 of lock.md §5.11. `pin.rec` is only
     * looked at here; its counter's start-up step is [startPin].
     */
    fun load() {
        master.load()
        fingerprint.load()
        pinAtLoad = try {
            pinStore.read()
        } catch (e: IOException) {
            RecordRead.Unreadable
        }
    }

    /**
     * The counter's start-up step, lock.md §6.3 S, once per process and vault: for the current vault
     * at step 5 of §5.11, for a vault put aside when its own lock screen is opened (F17 step 4) or
     * when start-up has to remove its slots (§5.11 step 3). Null when it has run already.
     */
    fun startPin(): PinStartUp? {
        if (pinStarted) return null
        pinStarted = true
        val result = pin.startUp()
        interruptedAtStart = result.interrupted
        return result
    }

    /** `pin.rec` as the entry policy takes it (lock.md §13.1): the record in memory, or what reading the file gave. */
    val pinRead: RecordRead<PinRecord>
        get() = if (pinStarted) pin.record?.let { RecordRead.Ok(it) } ?: pin.lastRead else pinAtLoad

    /** `state.rec` the same way, with the counts of now. */
    val stateRead: RecordRead<StateRecord> get() = master.record?.let { RecordRead.Ok(it) } ?: master.lastRead

    /** `state.rec` as the lock means to have it; null while there is none it may use. */
    val state: StateRecord? get() = master.record
}

/** The vault whose lock screen is shown: the current one, or one put aside (lock.md F17 step 4). */
internal class LockTarget(
    val ref: VaultRef,
    val lock: VaultLock,
    /** The vault put aside this is, with its line; null for the current vault. */
    val aside: AsideVault?,
) {
    val vaultId: String get() = lock.vaultId
}

/**
 * One visit of a lock screen (lock.md §6.3, §5.8, §11): it starts when a lock screen becomes
 * visible in a locked app and ends when the app is unlocked or stops being visible. Everything here
 * is in memory only and goes with the visit. Fields are read and written under the lock mutex;
 * [place] alone may be ended from any thread.
 */
internal class LockVisit(val place: Place, val target: LockTarget) {
    /** The six PIN digits as ASCII bytes (lock.md §5.8); [count] of them are typed. */
    val digits = ByteArray(PinWrap.PIN_SIZE)
    var count = 0

    /** The sixth digit is being checked, or Argon2id runs: "Opening…". */
    var opening = false

    /** "Forgot PIN? Use master password" or "Use master password" was chosen (F7 step 1); "Use PIN" takes it back. */
    var masterPasswordChosen = false

    /**
     * Practice is not asked on this visit (lock.md §13.1 rows 3 and 4 are skipped): "I can't
     * remember it" was chosen, and F11 step 3 wants the PIN or the fingerprint now (F10 step 4); or
     * "Not now" was chosen while `state.rec` cannot hold the snooze (F10 step 3). `ShortcutFlows`
     * sets it for "Not now" (build plan 3.13) and `PasswordFlows` for "I can't remember it" (4.4);
     * nothing in the core does.
     */
    var practiceSkipped = false

    var pinEvent: EntryPolicy.PinEvent? = null
    var fingerprintHidden = false
    var fingerprintLine: FingerprintLine? = null
    var needsOtherKeyCreatedMs: Long? = null

    /** `canAuthenticate(BIOMETRIC_STRONG)`, asked once per visit (lock.md §13.1); null until then. */
    var canAuthenticate: Int? = null
    var canAuthenticateAsked = false

    /** The prompt opened by itself on this visit (lock.md §9.2 rule 7): not again. */
    var autoPromptDone = false

    /** The line after the last master-password try on this visit. */
    var mpError: MpError? = null

    /** The typed master password opens only a copy from before a change (F16b): its screen is shown until it is answered. */
    var olderPassword: PasswordOpen.OlderPassword? = null

    /** What the entry policy decided last on this visit. */
    var decision: EntryPolicy.Decision.Locked? = null

    /**
     * Runs once, right after this visit has unlocked the app, with how it was opened: for a flow
     * that begins on the lock screen and goes on in the open app (F11 from "I can't remember it").
     * `ShortcutFlows` also uses it, to snooze practice in the `state.rec` that this visit's unlock
     * makes (F10 step 3). A flow that sets it chains the function already set and never replaces it.
     */
    var afterUnlock: (suspend (UnlockBy) -> Unit)? = null
}

/** One lock directory as read at process start (lock.md §5.11 step 2); [vaultId] null when its name is not an id. */
internal class DirRecords(
    val vaultId: String?,
    val pin: RecordRead<PinRecord>,
    val fingerprint: RecordRead<FingerprintRecord>,
    val state: RecordRead<StateRecord>,
)

internal class LockCore(
    val files: RecordFiles,
    val root: String,
    val clock: Clock,
    val random: RandomSource,
    val pinKey: PinKey,
    val fingerprintKey: FingerprintKey,
    val deviceKeys: DeviceKeys,
    val vault: VaultPort,
    val kdf: MasterKdf,
    val aead: Aead,
    private val io: CoroutineDispatcher,
    private val mutex: Mutex,
) {
    // The flows; LockEngine sets them once, before anything runs.
    lateinit var unlock: UnlockFlows
    lateinit var shortcuts: ShortcutFlows
    lateinit var vaults: VaultFlows
    lateinit var passwords: PasswordFlows
    lateinit var olderCopies: OlderCopies

    /** What `LockEngine.state` shows. Written under the mutex. */
    val state = MutableStateFlow<LockState>(LockState.Starting)

    /** What `LockEngine.summary` shows. Written under the mutex. */
    val summary = MutableStateFlow(LockSummary.NONE)

    // ---- under the mutex ------------------------------------------------------------------------

    /** The vaults on the phone as storage last listed them; null when they could not be listed. */
    var scan: VaultScan? = null
        private set

    /** The vault whose lock screen is shown while the app is locked; null: no vault, or none that can be read. */
    var target: LockTarget? = null

    /** The open vault while the app is unlocked. */
    var open: OpenVault? = null

    /** `canAuthenticate(BIOMETRIC_STRONG)` as it was last asked, for the summary (lock.md §9.4); null: never asked. */
    var canAuthenticate: Int? = null
        private set

    /** This phone cannot keep a key in secure hardware (lock.md F18), as F1 step 5 found in this process. */
    var noSecureHardware: Boolean = false
        private set

    /** The text of F18 has been shown on this phone ("first time only"); the engine's owner keeps that. */
    var noSecureHardwareTold: Boolean = false

    private val locks = HashMap<String, VaultLock>()
    private var visits = 0L
    private var promptNumbers = 0L
    private var launches = 0
    private var noticeIds = 0L

    // ---- read without the mutex too -------------------------------------------------------------

    /** The app is visible: between the process-level `ON_START` and `ON_STOP` (lock.md §10.2). */
    @Volatile var visible: Boolean = false

    /** The visit of the lock screen shown now; null while the app is unlocked, not visible, or has no lock screen. */
    @Volatile var visit: LockVisit? = null
        private set

    /** The number of the fingerprint launch that is outstanding, or 0 (lock.md §9.2 rule 8). */
    @Volatile var outstandingLaunch: Int = 0
        private set

    /**
     * Counts the PIN entries: it moves on whenever the engine itself empties the dots (a check
     * ended, a charge failed, the visit ended). A key press reads it when it arrives and is dropped
     * if it has moved on by the time the press is handled: "the pad ignores input from here until
     * the result is shown" (lock.md §6.3 E).
     */
    @Volatile var inputEpoch: Long = 0L
        private set

    private val prompts = ArrayList<Place>() // guarded by itself
    private val proofs = ArrayList<MasterProof>() // guarded by itself

    // ---------------------------------------------------------------------------------------------
    // The mutex

    /**
     * Runs [block] under the lock mutex (architecture.md §5.2) on the engine's dispatcher. Waiting
     * for the mutex can be cancelled; once [block] has started it runs to its end, so that a vault
     * the port opened is either shown or dropped and a record that was changed is written.
     */
    suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { withContext(NonCancellable + io) { block() } }

    // ---------------------------------------------------------------------------------------------
    // The vaults and their records (hold the mutex)

    /** The lock state of the vault [vaultId], read from its directory the first time it is asked for. */
    fun lockOf(vaultId: String): VaultLock =
        locks.getOrPut(vaultId) { VaultLock(vaultId, files, root, clock, pinKey, deviceKeys, aead, random).also { it.load() } }

    /** The lock state of [vaultId] if this process has read it. */
    fun knownLock(vaultId: String): VaultLock? = locks[vaultId]

    /** Every vault whose records this process has read. */
    fun knownLocks(): List<VaultLock> = locks.values.toList()

    /**
     * Forgets the lock state of [vaultId]: its vault was deleted, and its lock directory and keys with
     * it (lock.md F17 step 5; architecture.md §3.4). The next [lockOf] reads the directory afresh.
     */
    fun forgetLock(vaultId: String) {
        locks.remove(vaultId)
    }

    /**
     * Asks storage which vaults are on the phone (lock.md §5.11 step 2) and keeps the answer. A
     * failure to list them is null: a storage error, never "no vault" (F1 step 1). A header whose
     * `vault_id` is not an id (§5.1) cannot name a lock directory: such a current vault counts as
     * damaged, and such a vault put aside is left out.
     */
    fun rescan(): VaultScan? {
        val listed = try {
            vault.vaults()
        } catch (e: Exception) {
            null
        }
        scan = listed?.let { found ->
            val current = found.current
            VaultScan(
                if (current is CurrentVault.Present && !Uuid.isValid(current.vaultId)) CurrentVault.Unreadable(CurrentVault.Why.DAMAGED) else current,
                found.aside.filter { Uuid.isValid(it.vaultId) },
            )
        }
        return scan
    }

    /**
     * Makes the current vault the one whose lock screen is shown, from the last [rescan]; none when
     * there is no vault or none that can be read. Its counter's start-up step runs if it has not
     * (lock.md §5.11 step 5).
     */
    fun retarget() {
        val present = scan?.current as? CurrentVault.Present
        target = present?.let { found ->
            val lock = lockOf(found.vaultId)
            lock.startPin()
            LockTarget(VaultRef.Current, lock, null)
        }
    }

    /** The newest readable generation of [target] as storage listed it. */
    fun newest(target: LockTarget): GenerationInfo? =
        if (target.aside != null) target.aside.newest else (scan?.current as? CurrentVault.Present)?.newest

    /**
     * The `vk_id`s among the kept generations of [target] (lock.md §5.4), for trimming the wraps at
     * an unlock; null when no wrap may be trimmed: storage could not say, the vault is one put
     * aside, or its newest readable generation is an older copy. Storage lists the keys of the
     * generations it can read; a damaged one above them is still on disk and may carry another key,
     * and nothing is removed for that mismatch (§5.10, F16a, I6). Its wrap goes once storage has
     * put the damaged file in quarantine.
     */
    fun keptVkIds(target: LockTarget): Set<String>? {
        if (target.aside != null) return null
        val present = (scan?.current as? CurrentVault.Present)?.takeIf { it.vaultId == target.vaultId } ?: return null
        return if (present.olderCopyFirst) null else present.keptVkIds
    }

    // ---------------------------------------------------------------------------------------------
    // Visits (lock.md §6.3)

    /**
     * Any thread: the visit of the lock screen ends now. Its place is marked ended, so that a check
     * running under the mutex opens nothing (I11); an outstanding fingerprint launch is ended and
     * its prompt cancelled (§9.2 rule 8). [closeVisit] must follow under the mutex. Returns the visit
     * that was ended, or null when there was none.
     */
    fun endVisitNow(): LockVisit? {
        val ended = visit
        ended?.place?.end()
        endLaunchNow()
        return ended
    }

    /**
     * Holds the mutex: what the end of the visit does to the records and to memory (lock.md §6.3 A,
     * §5.8). A charge held there is refunded, the wrong tries of the visit are forgotten (they stay
     * counted), the digits are zeroed.
     */
    fun closeVisit() {
        val ended = visit ?: return
        visit = null
        ended.place.end()
        endLaunchNow()
        ended.target.lock.pin.placeEnded(ended.place)
        ended.target.lock.master.placeEnded(ended.place)
        Bytes.wipe(ended.digits)
        ended.count = 0
        ended.afterUnlock = null
        inputEpoch++
    }

    /**
     * Holds the mutex: a visit of [target]'s lock screen starts, if the app is visible and there is
     * a lock screen to show. A visit that is open is closed first.
     */
    fun startVisit() {
        closeVisit()
        val shown = target ?: return
        if (!visible) return
        shown.lock.startPin()
        visit = LockVisit(Place.visit(++visits), shown)
        inputEpoch++
    }

    /** Holds the mutex: the dots are emptied by the engine (a check ended, a charge failed): later key presses start a new entry. */
    fun newEntry(visit: LockVisit) {
        Bytes.wipe(visit.digits)
        visit.count = 0
        inputEpoch++
    }

    // ---------------------------------------------------------------------------------------------
    // The lock screen (lock.md §13.1)

    /** Holds the mutex: lock.md §13.1 for [target], with what has happened on [visit]. */
    fun decide(target: LockTarget, visit: LockVisit?): EntryPolicy.Decision {
        val lock = target.lock
        val current = scan
        val policyTarget = if (target.aside != null) {
            EntryPolicy.Target.Aside(target.aside)
        } else {
            val listed = checkNotNull(current) { "the current vault was listed" }
            EntryPolicy.Target.Current(listed, (listed.current as? CurrentVault.Present)?.olderCopyFirst == true)
        }
        var stateRead = lock.stateRead
        var nowWall = clock.wallMs()
        if (visit?.practiceSkipped == true) {
            // EntryPolicy has no input that skips rows 3 and 4. Until it has one, practice is made
            // "not due" for this decision only: the clock at the last correct master password, or,
            // without a record to hold that time, a record that asks for no practice.
            val record = (stateRead as? RecordRead.Ok)?.record
            if (record != null) nowWall = record.practice.lastOkMs else stateRead = RecordRead.Unreadable
        }
        return EntryPolicy.decide(
            EntryPolicy.Input(
                target = policyTarget,
                pin = lock.pinRead,
                fingerprint = lock.fingerprint.read,
                state = stateRead,
                nowWallMs = nowWall,
                nowElapsedMs = clock.elapsedMs(),
                canAuthenticate = visit?.canAuthenticate,
                pinStartUpFailed = lock.pin.storageFull,
                noSecureHardware = noSecureHardware && !noSecureHardwareTold,
                visit = EntryPolicy.Visit(
                    masterPasswordChosen = visit?.masterPasswordChosen == true,
                    pinEvent = visit?.pinEvent,
                    interruptedAtStart = lock.interruptedAtStart,
                    fingerprintHidden = visit?.fingerprintHidden == true,
                    fingerprintLine = visit?.fingerprintLine,
                    needsOtherKeyCreatedMs = visit?.needsOtherKeyCreatedMs,
                ),
            ),
        )
    }

    /**
     * Holds the mutex: decides the screen of the locked app again and shows it (lock.md §13.1,
     * §13.2). Without a vault to lock it shows Welcome or "Your passwords can't be opened". The
     * first time a visit needs it, `canAuthenticate` is asked; until its answer is there the pad is
     * shown without the fingerprint key. Any other screen waits for the answer, which may change it
     * (to the fingerprint-only screen, or to a practice screen once a shortcut is usable). An entry
     * on a pad that is no longer shown is given up (§6.3 A).
     */
    fun showLockScreen() {
        val shown = target
        if (shown == null) {
            val listed = scan
            state.value = when (val current = listed?.current) {
                null -> LockState.Unreadable(CurrentVault.Why.IO, emptyList())
                CurrentVault.NoVault -> LockState.NoVault(listed.aside)
                is CurrentVault.Unreadable -> LockState.Unreadable(current.why, listed.aside)
                is CurrentVault.Present -> LockState.Unreadable(CurrentVault.Why.IO, listed.aside) // listed, and its records could not be reached
            }
            return
        }
        val now = visit?.takeIf { it.target === shown && !it.place.hasEnded }
        var decision = decide(shown, now) as? EntryPolicy.Decision.Locked ?: return
        if (now != null && decision.fingerprintWaitsForAnswer && !now.canAuthenticateAsked) {
            if (decision.screen is LockScreen.Pad) {
                now.decision = decision
                state.value = LockState.Locked(screenOf(decision, now, shown))
            }
            now.canAuthenticateAsked = true
            now.canAuthenticate = askAvailability()
            decision = decide(shown, now) as? EntryPolicy.Decision.Locked ?: return
        }
        if (now != null) {
            now.decision = decision
            if (decision.screen !is LockScreen.Pad && now.count > 0) {
                shown.lock.pin.giveUp()
                newEntry(now)
            }
        }
        state.value = LockState.Locked(screenOf(decision, now, shown))
    }

    /**
     * Holds the mutex: shows the screen that was decided last on [visit] again, with its dots and
     * its "Opening…" as they are now. Nothing is decided again: while a PIN is being typed or a
     * check runs, the screen does not change under the user because the time moved on.
     */
    fun showAgain(visit: LockVisit) {
        val decision = visit.decision
        if (decision == null || visit.place.hasEnded || this.visit !== visit) return
        state.value = LockState.Locked(screenOf(decision, visit, visit.target))
    }

    private fun screenOf(decision: EntryPolicy.Decision.Locked, visit: LockVisit?, target: LockTarget): LockScreen {
        val older = visit?.olderPassword
        if (older != null) {
            return LockScreen.OlderPassword(older.changedAtMs, unfinishedChange = target.lock.state?.mpChange != null, aside = decision.screen.aside)
        }
        return when (val screen = decision.screen) {
            is LockScreen.Pad -> screen.copy(digits = visit?.count ?: 0, opening = visit?.opening == true)
            is LockScreen.MasterPassword -> screen.copy(
                waitUntilElapsedMs = target.lock.master.waitUntilElapsedMs(),
                error = visit?.mpError,
                opening = visit?.opening == true,
            )
            is LockScreen.FingerprintOnly, is LockScreen.OlderPassword -> screen
        }
    }

    /**
     * Holds the mutex: `canAuthenticate(BIOMETRIC_STRONG)`, off the main thread (lock.md §13.1; on
     * Android 10 every call makes a key). An answer that cannot be had counts as "hardware
     * unavailable", which probes nothing and removes nothing.
     */
    fun askAvailability(): Int {
        val answer = try {
            fingerprintKey.availability()
        } catch (e: RuntimeException) {
            KeystoreErrors.BIOMETRIC_ERROR_HW_UNAVAILABLE
        }
        canAuthenticate = answer
        return answer
    }

    /**
     * Holds the mutex: F1 step 5, "Can this phone hold a key?": makes `pv_canary` if it is missing
     * and checks it (lock.md §4.5). `NoSecureHardware` is F18, remembered for this process;
     * `NotNow` is a passing failure and changes nothing.
     */
    fun canHoldKeys(): KeyCreate {
        val made = try {
            deviceKeys.ensureCanary()
        } catch (e: RuntimeException) {
            KeyCreate.NotNow
        }
        when (made) {
            KeyCreate.NoSecureHardware -> noSecureHardware = true
            KeyCreate.Ok -> noSecureHardware = false
            KeyCreate.NotNow, KeyCreate.NoneEnrolled -> Unit
        }
        return made
    }

    // ---------------------------------------------------------------------------------------------
    // Prompts inside the open app (lock.md §6.3)

    /**
     * Any thread: a prompt of the open app opens (a master-password field, "Enter your current
     * PIN"). It has a number of its own; a lock or the process-level `ON_STOP` ends it, and a check
     * that belongs to it then opens and proves nothing (I11).
     */
    fun openPrompt(): Place {
        synchronized(prompts) {
            val place = Place.prompt(++promptNumbers)
            prompts += place
            return place
        }
    }

    /** Any thread: the prompt [place] closed. A charge held in it was refunded by whoever closed it under the mutex. */
    fun closePrompt(place: Place) {
        place.end()
        synchronized(prompts) { prompts.remove(place) }
    }

    /** Any thread: every open prompt ends now (a lock, `ON_STOP`). [promptsEnded] must follow under the mutex. */
    fun endPromptsNow(): List<Place> {
        val ended = synchronized(prompts) {
            val all = prompts.toList()
            prompts.clear()
            all
        }
        ended.forEach { it.end() }
        return ended
    }

    /** Holds the mutex: an entry in one of [ended] is given up, and its charge refunded (lock.md §6.3 A). */
    fun promptsEnded(ended: List<Place>) {
        if (ended.isEmpty()) return
        for (lock in locks.values) {
            for (place in ended) {
                if (lock.pinStarted) lock.pin.placeEnded(place)
                lock.master.placeEnded(place)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fingerprint launches (lock.md §9.2 rule 8)

    /** Holds the mutex: the next launch number; it is outstanding from now until [launchEnded] or [endLaunchNow]. */
    fun newLaunch(): Int {
        val number = ++launches
        outstandingLaunch = number
        return number
    }

    /**
     * Holds the mutex: the result of launch [number] arrived. True when that launch was still
     * outstanding, and it is not any more; false when it had been ended meanwhile, and the result is
     * dropped.
     */
    fun launchEnded(number: Int): Boolean {
        if (outstandingLaunch != number) return false
        outstandingLaunch = 0
        return true
    }

    /** Any thread: no launch is outstanding from now on, and an open prompt is cancelled (a lock, the end of a visit). */
    fun endLaunchNow() {
        if (outstandingLaunch == 0) return
        outstandingLaunch = 0
        try {
            fingerprintKey.cancel()
        } catch (e: RuntimeException) {
            // Nothing to cancel, or it cannot be reached: its result is dropped either way.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Proofs (lock.md §5.8, §8.2)

    /**
     * Holds the mutex: the proof of a master password that has just opened a slot of the vault
     * [vaultId] ([bytes], copied). It is dropped at the next lock and at `ON_STOP`.
     */
    fun newProof(bytes: ByteArray, vaultId: String): MasterProof {
        val proof = MasterProof(bytes, vaultId)
        synchronized(proofs) { proofs += proof }
        return proof
    }

    /** Whether this engine made [proof] and has not dropped it: a flow accepts no other. */
    fun holds(proof: MasterProof): Boolean = synchronized(proofs) { proofs.any { it === proof } }

    /**
     * Any thread: [proof] alone is zeroed and no longer held, because the flow it was made for has
     * ended (lock.md §5.8; F12 step 2: "The typed bytes are kept only until the offer is answered").
     * The other proofs stay. A proof this engine does not hold is zeroed all the same.
     */
    fun dropProof(proof: MasterProof) {
        synchronized(proofs) { proofs.removeAll { it === proof } }
        proof.drop()
    }

    /** Any thread: every proof handed out is zeroed (a lock, `ON_STOP`). */
    fun dropProofs() {
        val all = synchronized(proofs) {
            val copy = proofs.toList()
            proofs.clear()
            copy
        }
        all.forEach { it.drop() }
    }

    // ---------------------------------------------------------------------------------------------
    // Notices (lock.md §13.4)

    /** Holds the mutex: an id for a new notice, unique in the process. */
    fun nextNoticeId(): Long = ++noticeIds

    /** Holds the mutex: one more notice at the end of those shown, if the app is unlocked. */
    fun addNotice(make: (id: Long) -> Notice) {
        val now = state.value as? LockState.Unlocked ?: return
        state.value = now.copy(notices = now.notices + make(nextNoticeId()))
    }

    /** Holds the mutex: the notice [id] is taken off; it is returned, or null when it is not shown. */
    fun removeNotice(id: Long): Notice? {
        val now = state.value as? LockState.Unlocked ?: return null
        val notice = now.notices.firstOrNull { it.id == id } ?: return null
        state.value = now.copy(notices = now.notices - notice)
        return notice
    }

    // ---------------------------------------------------------------------------------------------
    // Time that a reboot cannot shorten (lock.md §6.9)

    /**
     * Holds the mutex: observes the credit of every `pending_mp` and every `replaced` in any lock
     * directory this process has read, and writes it back (best effort). Called at process start
     * (§5.11 step 6), at every process-level `ON_START` and `ON_STOP`, at every unlock and whenever
     * the summary is asked for again.
     */
    fun observeCredits() {
        val now = clock.elapsedMs()
        for (lock in locks.values) {
            val record = lock.state ?: continue
            val pending = record.pendingMp?.let { it to Credit(it.creditedMs, it.seenElapsedMs).observe(now) }
            val replaced = record.replaced?.let { it to Credit(it.creditedMs, it.seenElapsedMs).observe(now) }
            val pendingMoved = pending != null && (pending.second.creditedMs != pending.first.creditedMs || pending.second.seenElapsedMs != pending.first.seenElapsedMs)
            val replacedMoved = replaced != null && (replaced.second.creditedMs != replaced.first.creditedMs || replaced.second.seenElapsedMs != replaced.first.seenElapsedMs)
            if (!pendingMoved && !replacedMoved) continue
            lock.master.update { current ->
                current.copy(
                    pendingMp = current.pendingMp?.let { p ->
                        val credit = Credit(p.creditedMs, p.seenElapsedMs).observe(now)
                        StateRecord.PendingMp(p.requestedMs, p.by, credit.creditedMs, credit.seenElapsedMs, p.vkId, p.salt, p.nonce, p.ct)
                    },
                    replaced = current.replaced?.let { r ->
                        val credit = Credit(r.creditedMs, r.seenElapsedMs).observe(now)
                        r.copy(creditedMs = credit.creditedMs, seenElapsedMs = credit.seenElapsedMs)
                    },
                )
            }
        }
    }

    /** Holds the mutex: whether [lock] holds a new master password whose 24 credited hours are over (F11 step 13). */
    fun pendingReady(lock: VaultLock): Boolean {
        val pending = lock.state?.pendingMp ?: return false
        return Credit(pending.creditedMs, pending.seenElapsedMs).observe(clock.elapsedMs()).reached(EntryPolicy.PENDING_WAIT_MS)
    }

    /**
     * Holds the mutex: the line a wrong master password adds while the passwords from before a
     * replacement are on the phone (lock.md §13.3, F16b): the latest vault put aside that is marked
     * `replaced`; its id when it is the only one.
     */
    fun replacedLine(): ReplacedLine? {
        val marked = scan?.aside.orEmpty().mapNotNull { aside -> locks[aside.vaultId]?.state?.replaced?.let { aside.vaultId to it } }
        val latest = marked.maxByOrNull { it.second.atMs } ?: return null
        return ReplacedLine(latest.second.atMs, latest.second.by, if (marked.size == 1) latest.first else null)
    }

    // ---------------------------------------------------------------------------------------------
    // The summary (architecture.md §3.5 `LockStatus`)

    /** Holds the mutex: publishes the lock's facts as they are now. */
    fun publishSummary() {
        summary.value = summaryNow()
    }

    private fun summaryNow(): LockSummary {
        val listed = scan
        val aside = listed?.aside.orEmpty()
        val present = listed?.current as? CurrentVault.Present
        val opened = open
        val lock = (opened?.vaultId ?: present?.vaultId)?.let { locks[it] } ?: return LockSummary.NONE.copy(aside = aside)
        val vkId = opened?.takeIf { it.vaultId == lock.vaultId }?.vkId ?: present?.newest?.vkId
        val record = lock.state
        val nowElapsed = clock.elapsedMs()

        val pending = record?.pendingMp?.let { p ->
            val credit = Credit(p.creditedMs, p.seenElapsedMs).observe(nowElapsed)
            LockSummary.Pending(
                requestedAtMs = p.requestedMs,
                by = p.by,
                creditedMs = credit.creditedMs,
                remainingMs = credit.remainingMs(EntryPolicy.PENDING_WAIT_MS),
                ready = credit.reached(EntryPolicy.PENDING_WAIT_MS),
            )
        }
        val replaced = aside
            .mapNotNull { old -> locks[old.vaultId]?.state?.replaced?.takeIf { it.newVaultId == lock.vaultId }?.let { old.vaultId to it } }
            .filter { !Credit(it.second.creditedMs, it.second.seenElapsedMs).observe(nowElapsed).reached(REPLACED_NOTICE_MS) }
            .maxByOrNull { it.second.atMs }
            ?.let { LockSummary.Replaced(it.second.atMs, it.second.by, it.first) }
        val overdue = record?.let {
            PracticeSchedule(it.practice.lastOkMs, it.practice.step, it.practice.snoozedUntilMs).bannerSinceMs(clock.wallMs(), it.pendingMp != null)
        }
        return LockSummary(pinFact(lock, vkId), fingerprintFact(lock, vkId), pending, replaced, overdue, aside)
    }

    private fun pinFact(lock: VaultLock, vkId: String?): LockSummary.Pin {
        if (noSecureHardware) return LockSummary.Pin.NoSecureHardware
        return when (val read = lock.pinRead) {
            RecordRead.Missing -> LockSummary.Pin.NotSet
            is RecordRead.Damaged -> LockSummary.Pin.Off(PinReason.STOPPED_WORKING)
            RecordRead.Newer -> LockSummary.Pin.Off(PinReason.NEWER_APP)
            RecordRead.Unreadable -> LockSummary.Pin.CannotCheck
            is RecordRead.Ok -> {
                val record = read.record
                val slot = record.slot
                when {
                    slot == null -> when (record.off?.reason) {
                        PinRecord.OffReason.LIMIT -> LockSummary.Pin.Off(PinReason.LIMIT)
                        PinRecord.OffReason.KEY_GONE, PinRecord.OffReason.BAD_WRAP -> LockSummary.Pin.Off(PinReason.STOPPED_WORKING)
                        PinRecord.OffReason.MASTER_CHANGED, PinRecord.OffReason.REPLACED, null -> LockSummary.Pin.NotSet
                    }
                    PinGuard.atLimit(record.guard) -> LockSummary.Pin.Off(PinReason.LIMIT)
                    lock.pin.storageFull -> LockSummary.Pin.Off(PinReason.STORAGE_FULL)
                    vkId != null && slot.wraps.none { it.vkId == vkId } -> LockSummary.Pin.NotSet // a change of master password did not keep it
                    else -> LockSummary.Pin.On
                }
            }
        }
    }

    private fun fingerprintFact(lock: VaultLock, vkId: String?): LockSummary.Fingerprint {
        if (noSecureHardware) return LockSummary.Fingerprint.NoSecureHardware
        val can = canAuthenticate
        if (can != null && !KeystoreErrors.mayProbe(can)) return LockSummary.Fingerprint.Unavailable(can)
        val record = lock.fingerprint.record ?: return LockSummary.Fingerprint.Off
        val slot = record.slot
        return when {
            slot == null -> if (record.off?.reason == FingerprintRecord.OffReason.MASTER_CHANGED) LockSummary.Fingerprint.TurnOnAgain else LockSummary.Fingerprint.Off
            vkId != null && slot.wraps.none { it.vkId == vkId } -> LockSummary.Fingerprint.TurnOnAgain
            else -> LockSummary.Fingerprint.On
        }
    }

    // ---------------------------------------------------------------------------------------------
    // For the debug status of lock.md §15.3 (nothing here is secret)

    /** Holds the mutex: the inputs and the result of lock.md §13.1, the counts and the aliases present, as lines of text. */
    fun describe(): String {
        val out = StringBuilder()
        out.append("state: ").append(stateName()).append('\n')
        out.append("visible: ").append(visible).append(", visit: ").append(visit?.place?.toString() ?: "none")
        out.append(", launch outstanding: ").append(outstandingLaunch).append('\n')
        out.append("canAuthenticate: ").append(canAuthenticate?.toString() ?: "not asked").append(", no secure hardware: ").append(noSecureHardware).append('\n')
        val listed = scan
        out.append("vaults: ").append(
            when (val current = listed?.current) {
                null -> "could not be listed"
                CurrentVault.NoVault -> "none"
                is CurrentVault.Unreadable -> "unreadable (" + current.why + ")"
                is CurrentVault.Present -> current.vaultId + " generation " + current.newest.number
            },
        ).append(", put aside: ").append(listed?.aside?.size ?: 0).append('\n')
        val shown = target
        if (shown != null) {
            val decision = decide(shown, visit?.takeIf { it.target === shown })
            if (decision is EntryPolicy.Decision.Locked) {
                out.append("policy: row ").append(decision.row).append(", pin ").append(decision.pin)
                out.append(", fingerprint usable ").append(decision.fingerprintUsable).append('\n')
            }
        }
        for (lock in locks.values.sortedBy { it.vaultId }) {
            out.append("lock ").append(lock.vaultId).append('\n')
            out.append("  pin.rec: ").append(readName(lock.pinRead))
            lock.pin.record?.let { r ->
                out.append(", slot ").append(if (r.slot != null) "set, " + r.slot.wraps.size + " wrap(s)" else "none")
                out.append(", off ").append(r.off?.reason?.stored ?: "none")
                out.append(", charged ").append(r.guard.charged).append(", row ").append(r.guard.row).append(", total ").append(r.guard.total)
                out.append(", unseen ").append(r.unseen.count)
            }
            if (lock.pin.storageFull) out.append(", start-up write failed")
            out.append('\n')
            out.append("  fingerprint.rec: ").append(readName(lock.fingerprint.read))
            lock.fingerprint.record?.let { r ->
                out.append(", slot ").append(if (r.slot != null) "set, " + r.slot.wraps.size + " wrap(s)" else "none")
                out.append(", off ").append(r.off?.reason?.stored ?: "none")
            }
            out.append('\n')
            out.append("  state.rec: ").append(readName(lock.stateRead))
            out.append(", wrong master passwords ").append(lock.master.counts.failures).append(", unseen ").append(lock.master.counts.unseen)
            lock.state?.let { r ->
                out.append(", practice step ").append(r.practice.step)
                out.append(", change ").append(if (r.mpChange != null) "started" else "none")
                out.append(", pending ").append(if (r.pendingMp != null) "yes" else "no")
                out.append(", replaced ").append(if (r.replaced != null) "yes" else "no")
                out.append(", max generation ").append(r.maxGeneration.n)
            }
            out.append('\n')
        }
        val aliases = try {
            deviceKeys.aliases().sorted()
        } catch (e: RuntimeException) {
            emptyList()
        }
        out.append("aliases: ").append(if (aliases.isEmpty()) "none listed" else aliases.joinToString(" "))
        return out.toString()
    }

    private fun stateName(): String = when (val now = state.value) {
        LockState.Starting -> "starting"
        is LockState.NoVault -> "no vault"
        is LockState.Unreadable -> "unreadable (" + now.why + ")"
        is LockState.Locked -> "locked: " + when (now.screen) {
            is LockScreen.Pad -> "pad"
            is LockScreen.FingerprintOnly -> "fingerprint only"
            is LockScreen.MasterPassword -> "master password"
            is LockScreen.OlderPassword -> "older master password"
        }
        is LockState.Unlocked -> "unlocked, " + now.notices.size + " notice(s)"
    }

    private fun readName(read: RecordRead<*>): String = when (read) {
        is RecordRead.Ok -> "OK"
        RecordRead.Missing -> "MISSING"
        is RecordRead.Damaged -> "DAMAGED"
        RecordRead.Newer -> "NEWER"
        RecordRead.Unreadable -> "UNREADABLE"
    }

    companion object {
        /** How long the "replaced" notice is shown: 7 days, credited by lock.md §6.9 (F11 step 20, §13.4 item 3). */
        const val REPLACED_NOTICE_MS: Long = 7 * 24 * 3_600_000L
    }
}
