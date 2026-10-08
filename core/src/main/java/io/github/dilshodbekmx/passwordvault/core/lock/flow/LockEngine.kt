package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * The top-level state of the app as the lock decides it (lock.md §13.1, §13.2; ux.md §4): the
 * engine's side of `LockUiState` (architecture.md §3.5), case for case. Like [LockScreen] and
 * [Notice] it holds facts, never a sentence.
 */
sealed interface LockState {
    /** The lock records and the vault files are being read at process start (lock.md §5.11). */
    data object Starting : LockState

    /** No vault on this phone (§13.1 row 1): Welcome, listing the vaults put aside (F17). */
    data class NoVault(val aside: List<AsideVault>) : LockState

    /** No generation can be read (§13.1 row 2): "Your passwords can't be opened", with "Try again" ([LockEngine.refresh]). Never an offer to delete. */
    data class Unreadable(val why: CurrentVault.Why, val aside: List<AsideVault>) : LockState

    /** Locked: the lock screen [screen], chosen by `EntryPolicy` and filled in as the visit goes on. */
    data class Locked(val screen: LockScreen) : LockState

    /**
     * Unlocked. [notices] are shown one at a time, in the order of lock.md §13.4, and answered with
     * [LockEngine.answerNotice]. [proof] is the master password of this unlock when it was typed
     * (§5.8), for the next step of the same flow; null after a PIN or fingerprint unlock, and after
     * the app stopped being visible.
     */
    data class Unlocked(val notices: List<Notice>, val proof: MasterProof?) : LockState
}

/**
 * The lock's facts for Settings › App lock, the list's banners, Welcome and the "Forgot master
 * password?" sheet: the engine's side of `LockStatus` (architecture.md §3.5), field for field.
 * Facts and numbers, never a sentence.
 *
 * @property pin the PIN of the current vault: "Change PIN" or "Set a PIN" (lock.md F6).
 * @property fingerprint the fingerprint switch and its reason (lock.md §9.4).
 * @property pending a new master password waiting for its 24 credited hours (lock.md §13.4 item 2).
 * @property replaced the master password was replaced, while its notice is shown (§13.4 item 3).
 * @property practiceOverdueSinceMs practice is past its grace and no new master password is
 *   pending: the standing banner of §13.4 item 6, with the wall-clock time of the last correct
 *   master password.
 * @property aside the vaults put aside (F17).
 */
data class LockSummary(
    val pin: Pin,
    val fingerprint: Fingerprint,
    val pending: Pending?,
    val replaced: Replaced?,
    val practiceOverdueSinceMs: Long?,
    val aside: List<AsideVault>,
) {
    /** The PIN of the current vault. */
    sealed interface Pin {
        /** A PIN that works with the vault's current key: "Change PIN". */
        data object On : Pin

        /** No PIN: never set, skipped at setup, or not kept by a change of the master password. "Set a PIN". */
        data object NotSet : Pin

        /** The PIN cannot be used, for [reason]: LIMIT, STOPPED_WORKING, NEWER_APP or STORAGE_FULL; never CANNOT_CHECK, which is [CannotCheck]. */
        data class Off(val reason: PinReason) : Pin

        /** Its record cannot be read right now (lock.md §5.3 UNREADABLE); nothing was removed. */
        data object CannotCheck : Pin

        /** This phone cannot keep a key in secure hardware (F18): the master password only. */
        data object NoSecureHardware : Pin
    }

    /** Fingerprint unlock of the current vault. */
    sealed interface Fingerprint {
        /** On, with a wrap for the vault's current key. */
        data object On : Fingerprint

        /** Off; the switch can turn it on. */
        data object Off : Fingerprint

        /** A change of the master password did not renew it (F8 step 7): "Fingerprint unlock: turn on again". */
        data object TurnOnAgain : Fingerprint

        /** `canAuthenticate(BIOMETRIC_STRONG)` gave [canAuthenticate] (11, 12, -2, 1, 15 …) when it was last asked: its reason is shown (§9.4). */
        data class Unavailable(val canAuthenticate: Int) : Fingerprint

        /** "This phone can't keep a fingerprint key in protected hardware." */
        data object NoSecureHardware : Fingerprint
    }

    /** A new master password waiting to start working (F11): [creditedMs] of the 24 hours are credited (§6.9), [remainingMs] are left. */
    data class Pending(val requestedAtMs: Long, val by: Shortcut, val creditedMs: Long, val remainingMs: Long, val ready: Boolean)

    /** "Your master password was replaced on 7 Oct using the PIN" (F11 step 20); [oldVaultId] is the vault "It wasn't me" opens (F16b). */
    data class Replaced(val atMs: Long, val by: Shortcut, val oldVaultId: String)

    companion object {
        /** No PIN, fingerprint off, nothing pending, replaced or put aside: before the records are read, and without a vault. */
        val NONE = LockSummary(Pin.NotSet, Fingerprint.Off, null, null, null, emptyList())
    }
}

/** What a typed master password came to (lock.md §6.8, §13.3): the engine's side of `MpResult`. */
sealed interface MasterPasswordOutcome {
    /** Right: [proof] is the verified password for the next step of the same flow (§5.8). On the lock screen the app is open now. */
    class Right(val proof: MasterProof) : MasterPasswordOutcome {
        override fun toString(): String = "MasterPasswordOutcome.Right"
    }

    /** No slot opened (§6.8 step 7). The try is counted. */
    data object Wrong : MasterPasswordOutcome

    /** The field waits until `elapsedRealtime()` reaches [untilElapsedMs] (§6.8 step 1). Nothing was tried. */
    data class Wait(val untilElapsedMs: Long) : MasterPasswordOutcome

    /** Argon2id could not run: not counted, not wrong (§6.8 step 3). */
    data object NoMemory : MasterPasswordOutcome

    /** It opens only a copy from before a change (F16b); [unfinishedChange] when `mp_change` is set. Nothing was opened, and the try stays counted. */
    data class OlderPassword(val changedAtMs: Long, val unfinishedChange: Boolean) : MasterPasswordOutcome

    /**
     * Nothing opens: the visit or prompt that asked has ended, although the password may have been
     * right and was counted as usual (§6.8 step 8, I11); or there was nothing to check against; or
     * no generation of the vault could be used, which counts neither as right nor as wrong
     * ([LockEngine.state] then says "Your passwords can't be opened"); or the password was right for
     * a vault from before a replacement, and [LockEngine.state] asks first
     * ([LockScreen.OpenOlderSet], F16b step 3); or the new vault of a replacement is in place but
     * perhaps not durable (F11 step 16), and [LockEngine.state] shows the lock screen of what is
     * there now.
     */
    data object TooLate : MasterPasswordOutcome

    /**
     * The new master password was typed right, and the replacement could not be finished (F11 steps
     * 13 to 16): "Couldn't finish now. Nothing was changed. Try again later." The mark could not be
     * written, or storage could not make the new vault; the pending password stays ready, and PIN and
     * fingerprint work as before. Also [LockEngine.finishPending] before the 24 hours are credited:
     * nothing was tried. The try is not counted.
     */
    data object NotFinished : MasterPasswordOutcome
}

/** What creating a vault came to (lock.md F1 steps 2 and 3, F13, F17 step 3): the engine's side of `CreateResult`. */
sealed interface CreateOutcome {
    /** The vault [vaultId] was written, proven and opened; the app is unlocked, and setup goes on at the PIN (F1 step 5). */
    data class Created(val vaultId: String) : CreateOutcome

    /** The two typings differ. Nothing was derived. */
    data object Mismatch : CreateOutcome

    /** The password holds [codePoint], which Unicode has not assigned or which is an unpaired surrogate (§8.1, [V-C9]). */
    data class Unassigned(val codePoint: Int) : CreateOutcome

    /** Argon2id could not run. Nothing was written. */
    data object NoMemory : CreateOutcome

    /** Writing or proving failed: "Couldn't save. Your phone's storage may be full." Nothing else exists yet. */
    data object NotSaved : CreateOutcome
}

/** What setting a PIN with the master password of the flow came to (lock.md F6 "Set", steps 4 to 8). */
sealed interface PinSetOutcome {
    /** The new record is on disk: "Your PIN is set." */
    data object Set : PinSetOutcome

    /**
     * Nothing changed: the proof is not one this engine holds for the open vault, the PIN is one
     * the blocklist refuses, the key or the wrap could not be made, or the record was not written
     * and the one read back is the one from before (F6 step 8). A PIN that worked before still
     * works: "Couldn't save the new PIN. Your old PIN still works." or "The PIN can't be set up
     * right now. Try again later."
     */
    data object NotSet : PinSetOutcome

    /**
     * The record write reported a failure, and the lock cannot say that the old record is the one
     * on disk (F6 step 8): the record read back is the new one, which this process goes on with (a
     * failed directory sync, with the rename already there), or it could not be read back. "The new
     * PIN may not have been saved. Next time try the new PIN first. If it is not accepted, use the
     * old PIN or your master password."
     */
    data object MayNotBeSaved : PinSetOutcome
}

/** What "Change PIN" came to (lock.md F6): the engine's side of `PinChange`. */
sealed interface PinChangeOutcome {
    data object Changed : PinChangeOutcome

    /** The current PIN was wrong and counted: "Wrong PIN. [left] tries left." */
    data class Wrong(val left: Int) : PinChangeOutcome

    /** The same wrong PIN as before: not counted again (§6.6). */
    data object SameWrongAgain : PinChangeOutcome

    /** A limit was reached and the PIN was turned off (§6.5). The app stays open. */
    data object Limit : PinChangeOutcome

    /** The PIN cannot be checked right now; the try was refunded. */
    data object CannotCheck : PinChangeOutcome

    /** The new PIN equals the current one. */
    data object SameAsCurrent : PinChangeOutcome

    /** The new key or record could not be made (F6 step 8): the old PIN still works. */
    data object NotSaved : PinChangeOutcome

    /** As [PinSetOutcome.MayNotBeSaved]: the current PIN was right, and the new record may or may not be on disk (F6 step 8). */
    data object MayNotBeSaved : PinChangeOutcome
}

/** What turning fingerprint unlock on came to (lock.md F9 steps 4 to 9): the engine's side of `FingerprintOnResult`. */
sealed interface FingerprintOnOutcome {
    data object On : FingerprintOnOutcome

    /** The prompt was closed (codes 10, 13, 5). */
    data object Cancelled : FingerprintOnOutcome

    /** No finger is enrolled: "Add a fingerprint in your phone's settings first." */
    data object NoneEnrolled : FingerprintOnOutcome

    /** "This phone can't keep a fingerprint key in protected hardware." */
    data object NoSecureHardware : FingerprintOnOutcome

    /** Anything else: "Fingerprint unlock could not be turned on. You can try again later in Settings." */
    data object Failed : FingerprintOnOutcome

    /** The prompt could not be shown now (§9.2 rule 3). */
    data object NotShown : FingerprintOnOutcome

    /**
     * The record write reported a failure, and the lock cannot say that the earlier record is the
     * one on disk (F9 step 9): the record read back is the new one, which this process goes on
     * with, or it could not be read back. "Fingerprint unlock may not have been turned on. If your
     * fingerprint is not accepted, turn it on again in Settings."
     */
    data object MayNotBeOn : FingerprintOnOutcome
}

/** What changing the master password came to (lock.md F8): the engine's side of `ChangeResult`. */
sealed interface ChangeOutcome {
    /** Changed and proven. [pinKept], [fingerprintKept]: the shortcut works with the new key. */
    data class Changed(val pinKept: Boolean, val fingerprintKept: Boolean) : ChangeOutcome

    data object Mismatch : ChangeOutcome

    data class Unassigned(val codePoint: Int) : ChangeOutcome

    /** The PIN typed to keep it was wrong and counted; nothing changed yet. */
    data class PinWrong(val left: Int) : ChangeOutcome

    data object NoMemory : ChangeOutcome

    /** "Couldn't change your master password. Nothing was changed. …" */
    data object NothingChanged : ChangeOutcome

    /** "Saving may not have finished. Next time, try your new master password first, then your old one." */
    data object Unclear : ChangeOutcome
}

/** What asking for a new master password came to (lock.md F11 steps 3 to 5): the engine's side of `RequestResult`. */
sealed interface RequestOutcome {
    /** Asked for: it starts working once [remainingMs] more are credited. */
    data class Requested(val remainingMs: Long) : RequestOutcome

    data object Mismatch : RequestOutcome

    data class Unassigned(val codePoint: Int) : RequestOutcome

    /** The proof of step 3 went while the request was made: the app locked or stopped being visible. Nothing is pending. */
    data object Cancelled : RequestOutcome

    /**
     * No proof of step 3 is held (F11 step 3, lock.md T16): the PIN was not typed now, on the lock
     * screen after "I can't remember it" or in [LockEngine.proveForNewMasterPassword]; or a
     * fingerprint was the proof and a PIN exists.
     */
    data object NotAllowed : RequestOutcome

    data object NoMemory : RequestOutcome

    /** The record could not be written. Nothing is pending. */
    data object NotSaved : RequestOutcome
}

/** What "Old passwords" › "Open" or "Delete" came to (lock.md F17 step 5): the engine's side of `AsideResult`. */
sealed interface AsideOutcome {
    data object Done : AsideOutcome

    /** Not that vault's master password. */
    data object Wrong : AsideOutcome

    /** That vault's own guard makes the field wait (§6.8). */
    data class Wait(val untilElapsedMs: Long) : AsideOutcome

    data object NoMemory : AsideOutcome

    /** The merge or the delete failed; nothing changed. */
    data object NotSaved : AsideOutcome
}

/**
 * What "Proof now" came to (lock.md F11 step 3) in the open app: [LockEngine.proveForNewMasterPassword].
 * "An app that is already open is not enough": the PIN typed now whenever there is one, a
 * fingerprint only when there is none, and never one in place of the other.
 */
sealed interface ProofOutcome {
    /**
     * The proof is held, given with [by]: the next step is the new master password (F11 step 4) and
     * [LockEngine.requestNewMasterPassword]. It is dropped when that request is made, at a lock and
     * when the app stops being visible (§5.8).
     */
    data class Proven(val by: Shortcut) : ProofOutcome

    /** A PIN exists, so the PIN must be typed now: "Enter your app PIN". Nothing was checked; a fingerprint is not accepted in its place. */
    data object NeedsPin : ProofOutcome

    /** The PIN was wrong and counted, as always: "Wrong PIN. [left] tries left." */
    data class PinWrong(val left: Int) : ProofOutcome

    /** The same wrong PIN as before: not counted again (§6.6). */
    data object SamePinAgain : ProofOutcome

    /**
     * The PIN can no longer be the proof: a limit was reached and it was turned off (§6.5), its key
     * is gone (F15b), or there is none any more. The summary says which. When fingerprint is usable
     * the flow goes on with it: [LockEngine.proveForNewMasterPassword] without a PIN.
     */
    data object PinGone : ProofOutcome

    /**
     * The PIN cannot be checked right now (TEMPORARY, storage full, UNREADABLE): "Your PIN can't be
     * checked right now. Try again later." The flow stops; it does not fall back to the fingerprint.
     */
    data object CannotCheck : ProofOutcome

    /** The fingerprint prompt was closed (codes 10, 13, 5), or the app locked or stopped being visible meanwhile. */
    data object Cancelled : ProofOutcome

    /** The fingerprint did not confirm, and nothing was removed (TEMPORARY, lock.md §14): [line] is its line of §13.3. */
    data class FingerprintFailed(val line: FingerprintLine) : ProofOutcome

    /** The fingerprint prompt could not be shown now (§9.2 rules 3 and 4). */
    data object NotShown : ProofOutcome

    /** Neither a PIN nor a usable fingerprint: the route is not offered. */
    data object NotOffered : ProofOutcome
}

/**
 * Why the app locks (lock.md §10.1, §10.4; architecture.md §5.5 step 1): the engine's side of
 * `LockReason` (architecture.md §3.5), value for value. [LockEngine.lock] takes it because one of
 * them, [SCREEN_OFF], also ends the visit of a lock screen that is shown (lock.md §6.3).
 */
enum class LockCause {
    /** The lock delay passed since the app was left, or "Immediately" at `ON_STOP`. */
    LEFT,

    /** The screen turned off (`ACTION_SCREEN_OFF`, also when Android delivers it late). */
    SCREEN_OFF,

    /** Five minutes without a touch while the app was in front. */
    NO_TOUCH,

    /** More than 10 minutes in a file picker or share sheet that the app opened (§10.3). */
    EXTERNAL_TIMEOUT,

    /** Settings › "Lock now". */
    LOCK_NOW,
}

/**
 * The buttons of "This is an older master password" (lock.md F16b, [LockScreen.OlderPassword]), and
 * of the question before the passwords from before a replacement are opened
 * ([LockScreen.OpenOlderSet], F16b step 3).
 */
enum class OlderPasswordAnswer {
    /** The change did not finish: "Try again", with the new master password. */
    TRY_AGAIN,

    /** The change did not finish: "Open my passwords from before the change", after its question was answered with "Open". */
    OPEN_FROM_BEFORE,

    /** The change was proven: "OK", back to the field. */
    OK,

    /** On [LockScreen.OpenOlderSet]: "Open my passwords from 7 Oct". The two vaults change places (F16b steps 4 to 6). */
    OPEN_OLDER_SET,

    /** On [LockScreen.OpenOlderSet]: "Cancel". Nothing changes, and the vault that opened in place is dropped again. */
    KEEP_NEWER_SET,
}

/**
 * The lock of the app over its ports (lock.md; architecture.md §2.1, §3.4): every decision and
 * every step of the flows F1 to F18 and of process start (§5.11), without one Android call.
 * `DefaultLockManager` (build plan 4.1) owns one for the life of the process and turns its
 * [state] and [summary] into `LockUiState` and `LockStatus`.
 *
 * **What it holds** (lock.md §5.8, §11; architecture.md §5.1): the three records of every vault as
 * it means to have them on disk; the visit of the lock screen with its six PIN digits, its charge
 * and its wrong tries; the prompts of the open app; the number of the fingerprint launch that is
 * outstanding; the proofs handed out. Nothing of it lives in an activity, so a rotation or a change
 * of language changes none of it.
 *
 * **Threads.** Every entry point may be called from any thread, the main thread included, and
 * never blocks it: each suspends for the lock mutex (architecture.md §5.2; first come, first
 * served) and runs on [io], because records are files and Keystore calls are binder calls. Once an
 * entry point holds the mutex it runs to its end, also when its caller is cancelled. Call the
 * entry points of one screen in the order of the user's taps, each from a coroutine that starts at
 * once (`Dispatchers.Main.immediate`, or `CoroutineStart.UNDISPATCHED`): what must happen at the
 * moment of a signal ([appStopped], [lock], a key press being stamped with its entry) happens before
 * the first suspension point. No mutex is held while a fingerprint prompt is open.
 *
 * **Which package wrote what.** Process start, the key sweep, the visit and prompt bookkeeping, the
 * launch numbers, and F1 to F5 and F7 are here and in `LockCore`, `UnlockFlows`, `StartUp` and
 * `Sweep` (build plan 3.10). The other flows are entry points here that go straight to
 * `ShortcutFlows` (F6, F9, F10: 3.13), `VaultFlows` (F12, F13, F17: 3.14), `PasswordFlows` (F8,
 * F11: 4.4) and `OlderCopies` (F16b: 4.4).
 *
 * @param records the files of the lock records, and [lockRoot] the directory they are under:
 *   `<noBackupFilesDir>/lock` (lock.md §5.2, build check R-3).
 * @param io the dispatcher of everything done under the mutex: `Dispatchers.IO` in the app.
 * @param mutex the lock mutex; its owner passes it in when something else of the lock must run
 *   under it.
 */
class LockEngine(
    records: RecordFiles,
    lockRoot: String,
    clock: Clock,
    random: RandomSource,
    pinKey: PinKey,
    fingerprintKey: FingerprintKey,
    deviceKeys: DeviceKeys,
    vault: VaultPort,
    kdf: MasterKdf,
    aead: Aead,
    io: CoroutineDispatcher,
    mutex: Mutex = Mutex(),
) {
    /** What the flows share. Internal, so that the flow files and their tests reach it; nothing outside `:core` can. */
    internal val core = LockCore(records, lockRoot, clock, random, pinKey, fingerprintKey, deviceKeys, vault, kdf, aead, io, mutex)
    private val unlock = UnlockFlows(core)
    private val startUp = StartUp(core)
    private val shortcuts = ShortcutFlows(core)
    private val vaults = VaultFlows(core)
    private val passwords = PasswordFlows(core)
    private val olderCopies = OlderCopies(core)

    init {
        core.unlock = unlock
        core.shortcuts = shortcuts
        core.vaults = vaults
        core.passwords = passwords
        core.olderCopies = olderCopies
    }

    /** The top-level state. It starts as [LockState.Starting] and leaves it when [start] has run. */
    val state: StateFlow<LockState> = core.state.asStateFlow()

    /** The lock's facts. [refresh] brings the parts that depend on time, and on the phone's settings, up to date. */
    val summary: StateFlow<LockSummary> = core.summary.asStateFlow()

    /**
     * Runs [block] under the lock mutex (`LockCore.locked`), and then lets `OlderCopies` drop what a
     * lock-screen visit that ended in the meantime left behind: the bytes kept for "Open my passwords
     * from before the change", and the vault that opened in place for the question of F16b step 3
     * (I11). `LockCore.closeVisit` cannot do the second, because it does not suspend; so every entry
     * point that holds the mutex here ends with it.
     */
    private suspend fun <T> step(block: suspend () -> T): T = core.locked {
        try {
            block()
        } finally {
            olderCopies.visitMayHaveEnded()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The process

    /**
     * Process start, lock.md §5.11, before any lock screen is drawn and before any flow can make a
     * key: stale `*.tmp` files, every record of every lock directory, the vaults on the phone, the
     * replaced vaults, the key sweep, the counter's start-up step, the credits. Then the first
     * screen is decided. A second call does nothing.
     *
     * @param noSecureHardwareTold the text of F18 was shown on this phone before ("first time
     *   only"); see [noSecureHardwareShown].
     */
    suspend fun start(noSecureHardwareTold: Boolean = false) = step {
        if (core.state.value !is LockState.Starting) return@step
        core.noSecureHardwareTold = noSecureHardwareTold
        startUp.run()
    }

    /**
     * The process-level `ON_START` (lock.md §10.2): the app is visible. While it is locked, a visit
     * of the lock screen starts (§6.3). The credits of §6.9 are observed.
     */
    suspend fun appStarted() {
        core.visible = true
        step {
            core.observeCredits()
            // A visit that an ON_STOP has ended but not yet closed is closed here too (startVisit),
            // so that the pad never stays without a visit while the app is visible.
            if (core.state.value is LockState.Locked && core.visit?.place?.hasEnded != false) {
                core.startVisit()
                core.showLockScreen()
            }
            core.publishSummary()
        }
    }

    /**
     * The process-level `ON_STOP`: the app is no longer visible. At once, before the mutex: the
     * visit and every prompt of the open app end, so that a check that is running opens and proves
     * nothing (I11); an open fingerprint prompt is cancelled and its launch ended; the proofs are
     * dropped (§5.8). Then, under the mutex: a held charge is refunded (§6.3 A), the digits are
     * zeroed, a proof that a step running meanwhile made is dropped too (a master password's, and
     * the proof of F11 step 3), the credits observed. It does not lock: `AutoLock` decides that
     * (§10.2). Only the visit it ended is closed: one that a later `ON_START` has begun meanwhile
     * stays.
     */
    suspend fun appStopped() {
        core.visible = false
        val ended = core.endVisitNow()
        val prompts = core.endPromptsNow()
        core.dropProofs()
        step {
            if (ended != null && core.visit === ended) core.closeVisit()
            core.promptsEnded(prompts)
            // A step that held the mutex when the app stopped being visible (an unlock, a check, setup)
            // made its proof after the drop above: it goes now (§5.8). The mutex is fair, so nothing
            // that began after this ON_STOP has run yet.
            core.dropProofs()
            passwords.dropProof()
            core.observeCredits()
            val now = core.state.value
            if (now is LockState.Unlocked && now.proof != null) core.state.value = now.copy(proof = null)
            if (now is LockState.Locked) core.showLockScreen() // as the next visit will find it
            core.publishSummary()
        }
    }

    /**
     * Locks (lock.md §10.4; architecture.md §5.5 steps 3 and 4), called only by `AutoLock` after
     * its draft flush, with the reason of the lock ([cause]). At once: the prompts end, the proofs
     * are dropped, a fingerprint prompt of the open app is cancelled. Under the mutex: a proof that
     * an unlock running meanwhile made is dropped too, the vault port drops its session (it waits
     * for a save that is running), the vaults are listed again, the lock screen is chosen (§13.1),
     * and a new visit starts if the app is visible. It never writes vault data.
     *
     * **A screen-off lock while a lock screen shows ends its visit** (lock.md §6.3: a visit ends at
     * `ON_STOP` or at a screen-off lock, whichever comes first; decision 29). At once, before the
     * mutex: the visit's place is ended, so that a PIN or master-password check that is running
     * counts as usual and opens nothing (I11), and an open fingerprint prompt is cancelled and its
     * launch ended, so that its result is dropped. Then, under the mutex: a held charge is refunded,
     * the digits go (§6.3 A), and a new visit of the same lock screen starts, because the screen can
     * come back on before the app was ever stopped (`ON_STOP` follows the screen-off by about 700
     * ms, and not at all when the screen is on again by then). Without this a PIN typed just before
     * the power button could open the vault with the screen off, until the `ON_STOP` arrived.
     *
     * For any other [cause] a lock while the app is not unlocked does nothing more.
     */
    suspend fun lock(cause: LockCause) {
        val wasOpen = core.state.value is LockState.Unlocked
        val prompts = core.endPromptsNow()
        core.dropProofs()
        if (wasOpen) core.endLaunchNow()
        val ended = if (cause == LockCause.SCREEN_OFF) core.endVisitNow() else null
        step {
            core.promptsEnded(prompts)
            passwords.dropProof()
            if (core.state.value !is LockState.Unlocked) {
                // The lock screen was showing when the screen went off: its visit is closed, and the
                // next one starts. A visit that an ON_STOP closed meanwhile, or that an ON_START began
                // after this lock was asked for, is left as it is.
                if (ended != null && core.visit === ended) {
                    core.startVisit()
                    if (core.state.value is LockState.Locked) core.showLockScreen()
                    core.publishSummary()
                }
                return@step
            }
            core.dropProofs() // and the proof of an unlock that held the mutex when the lock came (§5.8, §10.4 step 2)
            try {
                core.vault.lock()
            } finally {
                unlock.relock()
            }
        }
    }

    /**
     * Brings what depends on time and on the phone up to date. Locked: the lock screen is decided
     * again (a wait that is over, a new master password that became ready), except while a PIN is
     * being typed, when the pad stays as it is until the entry ends. "Your passwords can't be
     * opened" or Welcome: the vaults are listed again ("Try again"). Unlocked: `canAuthenticate` is
     * asked again and the summary published, for Settings › App lock (§9.4) and the banners
     * (§13.4). The credits of §6.9 are observed in every case.
     */
    suspend fun refresh() = step { unlock.refresh() }

    /**
     * The text of F18, "This phone can't keep a PIN or fingerprint key in protected hardware …",
     * has been shown: it is not given as a reason again ("first time only", lock.md §13.3). The
     * caller keeps that across processes and passes it to [start].
     */
    suspend fun noSecureHardwareShown() = step {
        if (core.noSecureHardwareTold) return@step
        core.noSecureHardwareTold = true
        if (core.state.value is LockState.Locked) core.showLockScreen()
    }

    /** The debug status of lock.md §15.3: the inputs and the result of §13.1, the counts, the aliases present. Nothing in it is secret. */
    suspend fun describe(): String = step { core.describe() }

    // ---------------------------------------------------------------------------------------------
    // The pad (F2, F4, F5)

    /**
     * A digit key, 0 to 9. The first digit of an entry takes the charge (lock.md §6.3 C); the sixth
     * is checked once the charge is a second old (§6.3 E), and a right PIN opens the vault. Key
     * presses that arrive while a check runs are dropped.
     */
    suspend fun digit(digit: Int) {
        val epoch = core.inputEpoch
        step { unlock.digit(digit, epoch) }
    }

    /** ⌫: the last digit goes. Deleting back to none gives the entry up and refunds its charge (§6.3 A). */
    suspend fun deleteDigit() {
        val epoch = core.inputEpoch
        step { unlock.deleteDigit(epoch) }
    }

    /** A long press on ⌫: every digit goes, and the charge is refunded. */
    suspend fun clearDigits() {
        val epoch = core.inputEpoch
        step { unlock.clearDigits(epoch) }
    }

    // ---------------------------------------------------------------------------------------------
    // Which way in

    /** "Forgot PIN? Use master password", "Use master password" (F7 step 1): a held charge is refunded, and the master-password screen is shown without a reason. */
    suspend fun useMasterPassword() = step { unlock.useMasterPassword() }

    /** "Use PIN": back to the pad. On a practice screen within its grace it is "Not now: use PIN" (F10 step 3). */
    suspend fun usePin() = step { unlock.usePin() }

    /** "Not now: use fingerprint" on a practice screen within its grace (F10 step 3). */
    suspend fun notNow() = step {
        val screen = (core.state.value as? LockState.Locked)?.screen as? LockScreen.MasterPassword
        if (screen?.practice?.notNow != null) shortcuts.practiceNotNow()
    }

    /**
     * "I can't remember it" on a practice screen (F10 step 4), or "Use my PIN" or "Use my fingerprint"
     * on the "Forgot master password?" sheet (F11 step 1, F17 step 1): F11 from the lock screen, which
     * wants the PIN now, or the fingerprint when there is no PIN. Practice is skipped for this visit,
     * so the pad (or the fingerprint-only screen) is shown; the entry that then opens the app is
     * also the proof of F11 step 3, when it is the one that step accepts. Nothing changes while
     * neither shortcut can open the app.
     */
    suspend fun cantRemember() = step { passwords.cantRemember() }

    // ---------------------------------------------------------------------------------------------
    // Fingerprint (F3)

    /**
     * Opens the fingerprint prompt by itself if this visit asks for it (lock.md §9.2 rule 7): once
     * per visit, on the pad or the fingerprint-only screen, while fingerprint is usable. The caller
     * calls it when the activity is resumed, has window focus and shows the lock screen with no
     * dialog open, one turn of the main thread after the resume. It returns when the prompt has
     * ended and its result is dealt with.
     */
    suspend fun promptIfDue() = unlock.fingerprint(auto = true)

    /** The fingerprint key of the pad, or "Use fingerprint": opens the prompt again. An entry that was begun is given up (§6.3 A). */
    suspend fun useFingerprint() = unlock.fingerprint(auto = false)

    // ---------------------------------------------------------------------------------------------
    // The master password (§6.8)

    /**
     * The master password typed on the lock screen, checked under the guard of lock.md §6.8; a
     * right one opens the vault, returns both PIN counts to zero and counts as practice. It belongs
     * to the visit in which it was submitted (I11). [chars] stays the caller's, which wipes it.
     */
    suspend fun unlockWithMasterPassword(chars: CharArray): MasterPasswordOutcome {
        val at = core.visit?.place
        return step { unlock.masterPasswordOnLockScreen(chars, at) }
    }

    /**
     * The master password typed in a prompt of the open app (a backup, an export, a change, a
     * setting, "Type it now"), checked against the open vault's own slot under the same guard. It
     * belongs to that prompt: a lock or `ON_STOP` while it runs makes it [MasterPasswordOutcome.TooLate].
     */
    suspend fun checkMasterPassword(chars: CharArray): MasterPasswordOutcome {
        val at = core.openPrompt()
        try {
            return step { unlock.masterPasswordInApp(chars, at) }
        } finally {
            core.closePrompt(at)
        }
    }

    /**
     * An answer on "This is an older master password", or on "Open the older set instead?" (F16b).
     * "Open my passwords from before the change" and "Open my passwords from 7 Oct" open the app
     * when they succeed; an answer that does not belong to the screen shown does nothing.
     */
    suspend fun olderPasswordAnswer(answer: OlderPasswordAnswer) = step { olderCopies.answer(answer) }

    // ---------------------------------------------------------------------------------------------
    // Notices (§13.4)

    /** The answer [answer] to the notice [id]; it is taken off the list. An id that is not shown does nothing. */
    suspend fun answerNotice(id: Long, answer: NoticeAnswer) = step { unlock.answerNotice(id, answer) }

    /** F12: the master password was just typed and a backup is due, so "Make a backup now?" is offered on that entry. */
    suspend fun offerBackup() = step { vaults.offerBackup() }

    // ---------------------------------------------------------------------------------------------
    // Vaults: setup, restore, the vaults put aside

    /**
     * Creates the vault (lock.md F1 steps 2 to 5; F13 and F17 step 3 with [restoreFrom]). The two
     * typings are compared, through `MasterPasswordBytes`, before anything is derived; then the
     * vault port writes the vault and proves it from disk with the second typing, `state.rec` is
     * written, and the app is unlocked with the proof of [again].
     *
     * @param created runs after the vault is proven and before the app counts as unlocked: setup
     *   writes its default settings there (lock.md §10.1).
     */
    suspend fun createVault(first: CharArray, again: CharArray, restoreFrom: Vault? = null, created: suspend () -> Unit = {}): CreateOutcome =
        if (restoreFrom == null) unlock.createVault(first, again, created) else vaults.restore(first, again, restoreFrom, created)

    /** "Start with an empty app" confirmed (F17 step 2): the vault is put aside, with its lock records and keys left where they are. */
    suspend fun startEmpty(): Boolean = step { vaults.startEmpty() }

    /** "Open my previous passwords" for the vault [vaultId] put aside (F17 step 4): its own lock screen. */
    suspend fun showAside(vaultId: String) = step { vaults.showAside(vaultId) }

    /** Back on the lock screen of a vault put aside: the current vault's screen again, and nothing changed. */
    suspend fun goBack() = step { vaults.goBack() }

    /** Settings › Old passwords › "Open" (F17 step 5): that vault's master password, under its own guard; its accounts join the current vault. */
    suspend fun mergeAside(vaultId: String, chars: CharArray): AsideOutcome = vaults.mergeAside(vaultId, chars)

    /** Settings › Old passwords › "Delete" (F17 step 5): that vault's master password only; then its files, its lock directory and its keys. */
    suspend fun deleteAside(vaultId: String, chars: CharArray): AsideOutcome = vaults.deleteAside(vaultId, chars)

    // ---------------------------------------------------------------------------------------------
    // The shortcuts (F6, F9)

    /**
     * Sets a PIN with the master password of this flow (F6 "Set", F1 step 6). [PinSetOutcome.NotSet]:
     * what was there stays. [PinSetOutcome.MayNotBeSaved]: the record write reported a failure, and
     * the lock went on with the record it read back (F6 step 8).
     */
    suspend fun setPin(pin: ByteArray, proof: MasterProof): PinSetOutcome = step { shortcuts.setPin(pin, proof) }

    /** "Change PIN": [current] is a counted check in a prompt (§6.3, F6 step 2), then [new]. */
    suspend fun changePin(current: ByteArray, new: ByteArray): PinChangeOutcome = shortcuts.changePin(current, new)

    /** Turns fingerprint unlock on with the master password of this flow (F9). */
    suspend fun fingerprintOn(proof: MasterProof): FingerprintOnOutcome = shortcuts.fingerprintOn(proof)

    /** Turns fingerprint unlock off; no proof (F9 "Off"). */
    suspend fun fingerprintOff() = step { shortcuts.fingerprintOff() }

    // ---------------------------------------------------------------------------------------------
    // The master password: change and replacement (F8, F11, F16b)

    /**
     * Changes the master password (F8 steps 2 to 9). [proof] is the current master password, typed
     * in this flow; [pin] is the PIN typed at step 3 to keep it, or null for "Not now" and when no
     * PIN is usable. After [ChangeOutcome.Changed] every earlier proof is dropped, and
     * [LockState.Unlocked] holds the proof of the new master password, for "Choose a PIN" and for
     * "Back up now" (F8 steps 3 and 9).
     */
    suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeOutcome =
        passwords.changeMasterPassword(proof, first, again, pin)

    /**
     * "Proof now" for a new master password, in the open app (F11 step 3): [pin] is the PIN typed now
     * in its prompt, a counted check (§6.3); null asks without one. Without a PIN it answers
     * [ProofOutcome.Proven] when the entry that opened the app after "I can't remember it" was that
     * proof, [ProofOutcome.NeedsPin] while a PIN exists, and otherwise shows the fingerprint prompt
     * "Confirm with your fingerprint", which is accepted only when there is no PIN. The proof is held
     * until [requestNewMasterPassword], a lock or `ON_STOP`.
     */
    suspend fun proveForNewMasterPassword(pin: ByteArray?): ProofOutcome = passwords.proveNow(pin)

    /**
     * Asks for a new master password while the old one is forgotten (F11 step 5), with the proof of
     * step 3 that this engine holds: from the lock screen after [cantRemember], or from
     * [proveForNewMasterPassword]. Without one: [RequestOutcome.NotAllowed].
     */
    suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestOutcome = passwords.requestNewMasterPassword(first, again)

    /** "Cancel it": the pending new master password is dropped; no proof (F11 step 8). */
    suspend fun cancelPending() = step {
        passwords.cancelPending()
        core.publishSummary()
    }

    /**
     * "Finish now": the new master password is ready and is typed to finish (F11 steps 13 to 19).
     * [MasterPasswordOutcome.Right]: the session goes on in the new vault, and the proof is the new
     * master password's, for that vault; or, when [chars] is the current master password, that
     * password cancelled the request (F11 step 9) and the proof is its own. Before the 24 hours are
     * credited nothing is tried: [MasterPasswordOutcome.NotFinished].
     */
    suspend fun finishPending(chars: CharArray): MasterPasswordOutcome = passwords.finishPending(chars)

    /** "It wasn't me" on the "replaced" notice, or "Open my passwords from before 7 Oct" (F16b): the old vault's own lock screen. */
    suspend fun itWasntMe(oldVaultId: String?) = step { olderCopies.itWasntMe(oldVaultId) }
}
