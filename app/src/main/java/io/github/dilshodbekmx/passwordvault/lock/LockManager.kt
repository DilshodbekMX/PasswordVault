package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import kotlinx.coroutines.flow.StateFlow

/**
 * The lock as the app sees it (architecture.md §3.5; lock.md): `DefaultLockManager` (build plan 4.1)
 * over `LockEngine`, and `FakeLockManager` for the shell's previews and the screens' tests. It owns the PIN digits, the charge, the visit, the outstanding fingerprint launch,
 * `MasterProof` and the in-memory lock records, behind the lock mutex (§5.1, §5.2). Nothing of it
 * lives in the activity, so a rotation or a language change neither locks nor loses digits (lock.md
 * §11).
 *
 * Arrays passed in belong to the caller, which wipes them after the call.
 */
interface LockManager {
    /** The top-level state: `NavState` shows its back stack, the lock route shows its screen. */
    val state: StateFlow<LockUiState>

    /** The lock's facts for Settings, the list's banners, Welcome and the sheet. */
    val status: StateFlow<LockStatus>

    /** An intent of the lock screens, Welcome or a notice. Main thread; never blocks. */
    fun send(intent: LockIntent)

    /**
     * Locks: drops the proof, cancels an open prompt, and asks the vault to drop its session
     * (architecture.md §5.5 steps 3 and 4). Called only by `AutoLock`, after the draft flush of §5.5;
     * never writes vault data. While the app is locked already, `AutoLock` still passes a screen-off
     * ([LockReason.SCREEN_OFF], with no flush): it ends the visit of the lock screen shown, so that a
     * check still running opens nothing (lock.md §6.3, decision 29).
     */
    suspend fun lock(reason: LockReason)

    /**
     * Checks a typed master password under the guard of lock.md §6.8, for the lock screen and every
     * prompt of the open app ([purpose]). A right one counts as practice (§8.3) and, on the lock
     * screen, unlocks.
     */
    suspend fun submitMasterPassword(chars: CharArray, purpose: MpPurpose): MpResult

    // The flows started from setup and Settings. Each suspends until its flow ends; the prompts and
    // pads it needs on the way appear through `state`.

    /**
     * Creates the vault (lock.md F1; F13 and F17 step 3 with [restoreFrom]): the two typings are
     * compared before anything is derived ([CreateResult.Mismatch]), then the vault is written and
     * proven with [again]. On a phone that has no settings yet (`AppSettings.hasSettings`: a new
     * install, or after "Clear storage") `AppSettings.writeDefaults` runs then (lock.md §10.1). A
     * phone that has them keeps them, a restore included: the lock delay, the clipboard delay, the
     * theme, the colours and the language stay as they are (build-plan.md §12, decision 13).
     */
    suspend fun createVault(first: CharArray, again: CharArray, restoreFrom: Vault?): CreateResult

    /** Why [pin] (six ASCII digits) is refused, or null (lock.md §7.2): `PinBlocklist.reason`. */
    fun pinProblem(pin: ByteArray): PinBlocklist.Reason?

    /**
     * Sets a PIN where none is usable, with the master password of this flow (lock.md F6): [PinSetResult.Set];
     * [PinSetResult.NotSet] leaves the old state; [PinSetResult.MayNotBeSaved] when the record write
     * reported a failure and the lock cannot say that the old record is the one on disk (F6 step 8).
     */
    suspend fun setPin(pin: ByteArray, proof: MasterProof): PinSetResult

    /** "Change PIN": [current] is a counted check in a prompt (lock.md §6.3, F6 step 2), then [new]. */
    suspend fun changePin(current: ByteArray, new: ByteArray): PinChange

    /** Turns fingerprint unlock on with the master password of this flow (lock.md F9): makes the key, shows the prompt. */
    suspend fun fingerprintOn(proof: MasterProof): FingerprintOnResult

    /** Turns fingerprint unlock off; no proof (lock.md F9 "Off"). */
    suspend fun fingerprintOff()

    /**
     * Changes the master password (lock.md F8): the typings compared first ([ChangeResult.Mismatch]
     * before anything is derived); [pin] is the PIN typed at step 3 to keep it, or null for "Not now"
     * or when no PIN is usable.
     */
    suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeResult

    /**
     * "Proof now" for a new master password in the open app (lock.md F11 step 3: "An app that is
     * already open is not enough"; T16). [pin] is the PIN typed now in the screen's own PIN prompt,
     * six ASCII digits: a counted check in a prompt (lock.md §6.3), which must yield the open vault's
     * key. Null asks without one: [ProofResult.Proven] when the PIN (or, where no PIN exists, the
     * fingerprint) that opened the app after [LockIntent.CantRemember] still stands as this flow's
     * proof, [ProofResult.NeedsPin] while a PIN exists, and otherwise the fingerprint prompt "Confirm
     * with your fingerprint", which appears through `state` like every prompt and is accepted only
     * where there is no PIN. The proof is held by the lock, never handed out: it is used up by
     * [requestNewMasterPassword], and dropped by [leaveNewMasterPassword], at a lock and when the app
     * stops being visible (lock.md §5.8).
     */
    suspend fun proveForNewMasterPassword(pin: ByteArray?): ProofResult

    /**
     * Asks for a new master password while the old one is forgotten (lock.md F11 steps 4 and 5),
     * with the proof of step 3 that the lock holds: from [proveForNewMasterPassword], or from the PIN
     * (or fingerprint) that opened the app after [LockIntent.CantRemember]. The two typings are
     * compared before anything is derived. Without such a proof: [RequestResult.NotAllowed].
     */
    suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestResult

    /**
     * The flow of a new master password ended without [RequestResult.Requested] (lock.md F11 step 3,
     * §5.8): "Cancel" on its explanation, Back from choosing, or its screens closed any other way, in
     * the open app or after the lock-screen route ([LockIntent.CantRemember]). The proof of step 3
     * goes with that flow, so a request later in this session needs the PIN typed again (or the
     * fingerprint where there is no PIN). The screens of F11 call it whenever they close without a
     * request; after a request, a lock or `ON_STOP` it changes nothing. Main thread; never blocks.
     */
    fun leaveNewMasterPassword()

    /** "Cancel it": the pending new master password is dropped (lock.md F11 step 9). */
    fun cancelNewMasterPassword()

    /** Settings › Old passwords › Open (lock.md F17 step 5): that vault's master password, under its own guard; its accounts join the current vault. */
    suspend fun openAside(vaultId: String, chars: CharArray): AsideResult

    /** Settings › Old passwords › Delete (lock.md F17 step 5): that vault's master password only; then its files, its lock directory and its keys. */
    suspend fun deleteAside(vaultId: String, chars: CharArray): AsideResult
}

/** Why [LockManager.lock] is called (lock.md §10; architecture.md §5.5 step 1). */
enum class LockReason {
    /** The lock delay passed since the app was left, or "Immediately" at `ON_STOP`. */
    LEFT,

    /** The screen turned off. */
    SCREEN_OFF,

    /** 5 minutes without a touch while the app was in front. */
    NO_TOUCH,

    /** More than 10 minutes in a file picker or share sheet that the app opened (lock.md §10.3). */
    EXTERNAL_TIMEOUT,

    /** Settings › "Lock now" (`Session.lockNow`). */
    LOCK_NOW,
}

/** What a typed master password is for (lock.md §8.2): it picks what a right one leads to. */
enum class MpPurpose {
    /** The lock screen, practice screens included: a right one unlocks. */
    UNLOCK,

    /** In the open app: "Type it now" on the practice banner, "Type master password" on the wrong-tries notice (lock.md §13.4). */
    PRACTICE,

    /** Before an encrypted backup (lock.md F12). */
    BACKUP,

    /** Before a plain export, every time (lock.md F12). */
    EXPORT,

    /** The current master password before a change (lock.md F8 step 1). */
    CHANGE_MASTER_PASSWORD,

    /** "Set a PIN" when none exists, and "Choose a new PIN" after a shortcut unlock (lock.md F6). */
    SET_PIN,

    /** "Change PIN" › "Use master password instead" (lock.md F6 step 2). */
    CHANGE_PIN,

    /** Turning fingerprint on (lock.md F9 step 2). */
    FINGERPRINT_ON,
}

/** What [LockManager.submitMasterPassword] came to (architecture.md §3.5; lock.md §6.8, §13.3). */
sealed interface MpResult {
    /** Right: [proof] is the verified password for the next step of the same flow (lock.md §5.8). */
    class Right(val proof: MasterProof) : MpResult {
        override fun toString(): String = "MpResult.Right"
    }

    /** No slot opened (lock.md §6.8 step 7). */
    data object Wrong : MpResult

    /** The field waits until `elapsedRealtime()` reaches [untilElapsedMs] (lock.md §6.8 step 1). */
    data class Wait(val untilElapsedMs: Long) : MpResult

    /** Argon2id could not run: not counted, not wrong (lock.md §6.8 step 3). */
    data object NoMemory : MpResult

    /**
     * It opens only a copy from before a change (lock.md F16b): [changedAtMs] is the date of the
     * change; [unfinishedChange] when `mp_change` is set ("Try again" and "Open my passwords from
     * before the change"), otherwise "OK" only.
     */
    data class OlderPassword(val changedAtMs: Long, val unfinishedChange: Boolean) : MpResult

    /** Right, but the visit that asked has ended: nothing opens (lock.md §6.8 step 8, I11). */
    data object TooLate : MpResult

    /**
     * The new master password was ready (lock.md F11 step 13) and was typed right, but the
     * replacement could not be finished (F11 steps 15 and 16: the mark or the new vault could not be
     * written): "Couldn't finish now. Nothing was changed. Try again later." The pending password
     * stays ready, PIN and fingerprint work as before, and the try is not counted. Only the lock
     * screen's field answers it today ([MpPurpose.UNLOCK]); a check of the current master password
     * in the open app never does.
     */
    data object NotFinished : MpResult
}

/** What [LockManager.createVault] came to (lock.md F1 steps 2 and 3, F13, F17 step 3). */
sealed interface CreateResult {
    /** The vault [vaultId] was written, proven and opened; the state is unlocked and setup continues at the PIN (F1 step 5). */
    data class Created(val vaultId: String) : CreateResult

    /** The two typings differ: "This doesn't match. …" Nothing was derived. */
    data object Mismatch : CreateResult

    /** The password holds [codePoint], which Unicode has not assigned or is an unpaired surrogate (lock.md §8.1, [V-C9]). */
    data class Unassigned(val codePoint: Int) : CreateResult

    /** Argon2id could not run. Nothing was written. */
    data object NoMemory : CreateResult

    /** Writing or proving failed: "Couldn't save. Your phone's storage may be full." with "Try again". Nothing exists yet. */
    data object NotSaved : CreateResult
}

/** What [LockManager.setPin] came to (lock.md F6 "Set", steps 4 to 8). */
sealed interface PinSetResult {
    /** The new record is on disk: "Your PIN is set." */
    data object Set : PinSetResult

    /**
     * Nothing changed, and a PIN that worked before still works: "Couldn't save the new PIN. Your old
     * PIN still works." or "The PIN can't be set up right now. Try again later."
     */
    data object NotSet : PinSetResult

    /**
     * The record write reported a failure, and the record read back is the new one (the lock goes on
     * with it) or could not be read (lock.md F6 step 8; build-plan.md §12, decision 4). The screen
     * promises nothing: "The new PIN may not have been saved. Next time try the new PIN first. If it
     * is not accepted, use the old PIN or your master password." (without the old PIN where none
     * worked before), with "OK" only.
     */
    data object MayNotBeSaved : PinSetResult
}

/** What [LockManager.changePin] came to (lock.md F6). */
sealed interface PinChange {
    /** "Your PIN was changed." */
    data object Changed : PinChange

    /** The current PIN was wrong and counted: "Wrong PIN. [left] tries left." */
    data class Wrong(val left: Int) : PinChange

    /** The same wrong PIN as before: not counted again (lock.md §6.6). */
    data object SameWrongAgain : PinChange

    /** A limit was reached and the PIN was turned off (lock.md §6.5): "Type master password" or "Later". */
    data object Limit : PinChange

    /** The PIN cannot be checked right now; the try was refunded. */
    data object CannotCheck : PinChange

    /** The new PIN equals the current one: "Choose a PIN different from your current one." */
    data object SameAsCurrent : PinChange

    /** The new key or record could not be made (lock.md F6 step 8): "Couldn't save the new PIN. Your old PIN still works." */
    data object NotSaved : PinChange

    /** As [PinSetResult.MayNotBeSaved]: the current PIN was right, and the new record may or may not be on disk (lock.md F6 step 8). */
    data object MayNotBeSaved : PinChange
}

/** What [LockManager.fingerprintOn] came to (lock.md F9 steps 4 to 9, ux.md §5.7). */
sealed interface FingerprintOnResult {
    /** The key, the prompt and `fingerprint.rec` all succeeded. */
    data object On : FingerprintOnResult

    /** The prompt was closed (codes 10, 13, 5): stay on the screen. */
    data object Cancelled : FingerprintOnResult

    /** No finger is enrolled: "Add a fingerprint in your phone's settings first." */
    data object NoneEnrolled : FingerprintOnResult

    /** "This phone can't keep a fingerprint key in protected hardware." */
    data object NoSecureHardware : FingerprintOnResult

    /** Anything else: "Fingerprint unlock could not be turned on. You can try again later in Settings." */
    data object Failed : FingerprintOnResult

    /** The prompt could not be shown now (the activity is not resumed, lock.md §9.2 rule 3). */
    data object NotShown : FingerprintOnResult

    /**
     * The record write reported a failure, and the record read back is the new one (the lock goes on
     * with it) or could not be read (lock.md F9 step 9; build-plan.md §12, decision 4): "Fingerprint
     * unlock may not have been turned on. If your fingerprint is not accepted, turn it on again in
     * Settings."
     */
    data object MayNotBeOn : FingerprintOnResult
}

/** What [LockManager.changeMasterPassword] came to (lock.md F8). */
sealed interface ChangeResult {
    /** Changed and proven. [pinKept]: the PIN works with the new key; otherwise "Choose a PIN" follows. [fingerprintKept]: likewise. */
    data class Changed(val pinKept: Boolean, val fingerprintKept: Boolean) : ChangeResult

    /** The two typings differ. Nothing was derived. */
    data object Mismatch : ChangeResult

    /** As [CreateResult.Unassigned]. */
    data class Unassigned(val codePoint: Int) : ChangeResult

    /** The PIN typed to keep it was wrong and counted; nothing changed yet. */
    data class PinWrong(val left: Int) : ChangeResult

    /** Argon2id could not run. Nothing changed. */
    data object NoMemory : ChangeResult

    /** "Couldn't change your master password. Nothing was changed. Your old master password, PIN and fingerprint still work." */
    data object NothingChanged : ChangeResult

    /** "Saving may not have finished. Next time, try your new master password first, then your old one." */
    data object Unclear : ChangeResult
}

/**
 * What [LockManager.proveForNewMasterPassword] came to (lock.md F11 step 3, ux.md §5.24 step 2): the
 * PIN typed now whenever there is one, a fingerprint only where there is none, and never one in
 * place of the other.
 */
sealed interface ProofResult {
    /** The proof is held, given with [by]: the next step is choosing the new master password (F11 step 4). */
    data class Proven(val by: Shortcut) : ProofResult

    /** A PIN exists, so it must be typed now: "Enter your app PIN". Nothing was checked; a fingerprint is not accepted in its place. */
    data object NeedsPin : ProofResult

    /** The PIN was wrong and counted, as always: "Wrong PIN. [left] tries left." */
    data class PinWrong(val left: Int) : ProofResult

    /** The same wrong PIN as before: not counted again (lock.md §6.6). */
    data object SamePinAgain : ProofResult

    /**
     * The PIN can no longer be the proof: a limit turned it off (lock.md §6.5), its key is gone, or
     * there is none any more (`LockStatus.pin` says which). Where fingerprint is usable the flow goes
     * on with it: [LockManager.proveForNewMasterPassword] with null.
     */
    data object PinGone : ProofResult

    /** The PIN cannot be checked right now: "Your PIN can't be checked right now. Try again later." The flow stops; it never falls back to the fingerprint. */
    data object CannotCheck : ProofResult

    /** The fingerprint prompt was closed (codes 10, 13, 5), or the app locked or stopped being visible meanwhile: stay on the screen. */
    data object Cancelled : ProofResult

    /** The fingerprint did not confirm, and nothing was removed: [line] is its line of lock.md §13.3. */
    data class FingerprintFailed(val line: FingerprintLine) : ProofResult

    /** The fingerprint prompt could not be shown now (lock.md §9.2 rules 3 and 4). */
    data object NotShown : ProofResult

    /** Neither a PIN nor a usable fingerprint: the route is not offered (F11 step 3, "Neither"). */
    data object NotOffered : ProofResult
}

/** What [LockManager.requestNewMasterPassword] came to (lock.md F11 steps 3 to 5). */
sealed interface RequestResult {
    /** Asked for: it starts working once [remainingMs] more are credited (24 hours in all, lock.md §6.9). */
    data class Requested(val remainingMs: Long) : RequestResult

    /** The two typings differ. Nothing was derived. */
    data object Mismatch : RequestResult

    /** As [CreateResult.Unassigned]. */
    data class Unassigned(val codePoint: Int) : RequestResult

    /** The proof of step 3 went while the request was made: the app locked or stopped being visible. Nothing is pending. */
    data object Cancelled : RequestResult

    /**
     * No proof of step 3 is held (lock.md T16): the PIN was not typed now
     * ([LockManager.proveForNewMasterPassword], or the lock screen after [LockIntent.CantRemember]),
     * the flow that had one was left ([LockManager.leaveNewMasterPassword]), or a fingerprint was the
     * proof and a PIN exists. Nothing was derived.
     */
    data object NotAllowed : RequestResult

    /** Argon2id could not run. */
    data object NoMemory : RequestResult

    /** The record could not be written. Nothing is pending. */
    data object NotSaved : RequestResult
}

/** What [LockManager.openAside] and [LockManager.deleteAside] came to (lock.md F17 step 5). */
sealed interface AsideResult {
    /** Opened and its accounts added, or deleted with its lock directory and keys. */
    data object Done : AsideResult

    /** Not that vault's master password. */
    data object Wrong : AsideResult

    /** That vault's guard makes the field wait until [untilElapsedMs] (lock.md §6.8). */
    data class Wait(val untilElapsedMs: Long) : AsideResult

    /** Argon2id could not run. */
    data object NoMemory : AsideResult

    /** The merge or the delete failed; nothing changed. */
    data object NotSaved : AsideResult
}
