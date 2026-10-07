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
     * right and was counted as usual (§6.8 step 8, I11); or there was nothing to check against;
     * or the password was right and no generation could be used ([LockEngine.state] then says so).
     */
    data object TooLate : MasterPasswordOutcome
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

    /** The PIN or fingerprint confirmation was closed. */
    data object Cancelled : RequestOutcome

    /** No proof is accepted now (F11 step 3, lock.md T16). */
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

/** The buttons of "This is an older master password" (lock.md F16b, [LockScreen.OlderPassword]). */
enum class OlderPasswordAnswer {
    /** The change did not finish: "Try again", with the new master password. */
    TRY_AGAIN,

    /** The change did not finish: "Open my passwords from before the change", after its question was answered with "Open". */
    OPEN_FROM_BEFORE,

    /** The change was proven: "OK", back to the field. */
    OK,
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
 * F11: 4.4) and `OlderCopies` (F16b: 4.4), whose functions throw `NotImplementedError` until those
 * packages fill them.
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
    suspend fun start(noSecureHardwareTold: Boolean = false) = core.locked {
        if (core.state.value !is LockState.Starting) return@locked
        core.noSecureHardwareTold = noSecureHardwareTold
        startUp.run()
    }

    /**
     * The process-level `ON_START` (lock.md §10.2): the app is visible. While it is locked, a visit
     * of the lock screen starts (§6.3). The credits of §6.9 are observed.
     */
    suspend fun appStarted() {
        core.visible = true
        core.locked {
            core.observeCredits()
            if (core.state.value is LockState.Locked && core.visit == null) {
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
     * zeroed, the credits observed. It does not lock: `AutoLock` decides that (§10.2).
     */
    suspend fun appStopped() {
        core.visible = false
        core.endVisitNow()
        val prompts = core.endPromptsNow()
        core.dropProofs()
        core.locked {
            core.closeVisit()
            core.promptsEnded(prompts)
            core.observeCredits()
            val now = core.state.value
            if (now is LockState.Unlocked && now.proof != null) core.state.value = now.copy(proof = null)
            if (now is LockState.Locked) core.showLockScreen() // as the next visit will find it
            core.publishSummary()
        }
    }

    /**
     * Locks (lock.md §10.4; architecture.md §5.5 steps 3 and 4), called only by `AutoLock` after
     * its draft flush. At once: the prompts end, the proofs are dropped, a fingerprint prompt of the
     * open app is cancelled. Under the mutex: the vault port drops its session (it waits for a save
     * that is running), the vaults are listed again, the lock screen is chosen (§13.1), and a new
     * visit starts if the app is visible. It never writes vault data. While the app is not unlocked
     * it does nothing more.
     */
    suspend fun lock() {
        val wasOpen = core.state.value is LockState.Unlocked
        val prompts = core.endPromptsNow()
        core.dropProofs()
        if (wasOpen) core.endLaunchNow()
        core.locked {
            core.promptsEnded(prompts)
            if (core.state.value !is LockState.Unlocked) return@locked
            try {
                core.vault.lock()
            } finally {
                unlock.relock()
            }
        }
    }

    /**
     * Brings what depends on time and on the phone up to date. Locked: the lock screen is decided
     * again (a wait that is over, a new master password that became ready). "Your passwords can't be
     * opened" or Welcome: the vaults are listed again ("Try again"). Unlocked: `canAuthenticate` is
     * asked again and the summary published, for Settings › App lock (§9.4) and the banners
     * (§13.4). The credits of §6.9 are observed in every case.
     */
    suspend fun refresh() = core.locked { unlock.refresh() }

    /**
     * The text of F18, "This phone can't keep a PIN or fingerprint key in protected hardware …",
     * has been shown: it is not given as a reason again ("first time only", lock.md §13.3). The
     * caller keeps that across processes and passes it to [start].
     */
    suspend fun noSecureHardwareShown() = core.locked {
        if (core.noSecureHardwareTold) return@locked
        core.noSecureHardwareTold = true
        if (core.state.value is LockState.Locked) core.showLockScreen()
    }

    /** The debug status of lock.md §15.3: the inputs and the result of §13.1, the counts, the aliases present. Nothing in it is secret. */
    suspend fun describe(): String = core.locked { core.describe() }

    // ---------------------------------------------------------------------------------------------
    // The pad (F2, F4, F5)

    /**
     * A digit key, 0 to 9. The first digit of an entry takes the charge (lock.md §6.3 C); the sixth
     * is checked once the charge is a second old (§6.3 E), and a right PIN opens the vault. Key
     * presses that arrive while a check runs are dropped.
     */
    suspend fun digit(digit: Int) {
        val epoch = core.inputEpoch
        core.locked { unlock.digit(digit, epoch) }
    }

    /** ⌫: the last digit goes. Deleting back to none gives the entry up and refunds its charge (§6.3 A). */
    suspend fun deleteDigit() {
        val epoch = core.inputEpoch
        core.locked { unlock.deleteDigit(epoch) }
    }

    /** A long press on ⌫: every digit goes, and the charge is refunded. */
    suspend fun clearDigits() {
        val epoch = core.inputEpoch
        core.locked { unlock.clearDigits(epoch) }
    }

    // ---------------------------------------------------------------------------------------------
    // Which way in

    /** "Forgot PIN? Use master password", "Use master password" (F7 step 1): a held charge is refunded, and the master-password screen is shown without a reason. */
    suspend fun useMasterPassword() = core.locked { unlock.useMasterPassword() }

    /** "Use PIN": back to the pad. On a practice screen within its grace it is "Not now: use PIN" (F10 step 3). */
    suspend fun usePin() = core.locked { unlock.usePin() }

    /** "Not now: use fingerprint" on a practice screen within its grace (F10 step 3). */
    suspend fun notNow() = core.locked {
        val screen = (core.state.value as? LockState.Locked)?.screen as? LockScreen.MasterPassword
        if (screen?.practice?.notNow != null) shortcuts.practiceNotNow()
    }

    /** "I can't remember it" on a practice screen (F10 step 4): F11, which wants the PIN or the fingerprint now. */
    suspend fun cantRemember() = core.locked { passwords.cantRemember() }

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
        return core.locked { unlock.masterPasswordOnLockScreen(chars, at) }
    }

    /**
     * The master password typed in a prompt of the open app (a backup, an export, a change, a
     * setting, "Type it now"), checked against the open vault's own slot under the same guard. It
     * belongs to that prompt: a lock or `ON_STOP` while it runs makes it [MasterPasswordOutcome.TooLate].
     */
    suspend fun checkMasterPassword(chars: CharArray): MasterPasswordOutcome {
        val at = core.openPrompt()
        try {
            return core.locked { unlock.masterPasswordInApp(chars, at) }
        } finally {
            core.closePrompt(at)
        }
    }

    /** An answer on "This is an older master password" (F16b). */
    suspend fun olderPasswordAnswer(answer: OlderPasswordAnswer) = core.locked { olderCopies.answer(answer) }

    // ---------------------------------------------------------------------------------------------
    // Notices (§13.4)

    /** The answer [answer] to the notice [id]; it is taken off the list. An id that is not shown does nothing. */
    suspend fun answerNotice(id: Long, answer: NoticeAnswer) = core.locked { unlock.answerNotice(id, answer) }

    /** F12: the master password was just typed and a backup is due, so "Make a backup now?" is offered on that entry. */
    suspend fun offerBackup() = core.locked { vaults.offerBackup() }

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
    suspend fun startEmpty(): Boolean = core.locked { vaults.startEmpty() }

    /** "Open my previous passwords" for the vault [vaultId] put aside (F17 step 4): its own lock screen. */
    suspend fun showAside(vaultId: String) = core.locked { vaults.showAside(vaultId) }

    /** Back on the lock screen of a vault put aside: the current vault's screen again, and nothing changed. */
    suspend fun goBack() = core.locked { vaults.goBack() }

    /** Settings › Old passwords › "Open" (F17 step 5): that vault's master password, under its own guard; its accounts join the current vault. */
    suspend fun mergeAside(vaultId: String, chars: CharArray): AsideOutcome = vaults.mergeAside(vaultId, chars)

    /** Settings › Old passwords › "Delete" (F17 step 5): that vault's master password only; then its files, its lock directory and its keys. */
    suspend fun deleteAside(vaultId: String, chars: CharArray): AsideOutcome = vaults.deleteAside(vaultId, chars)

    // ---------------------------------------------------------------------------------------------
    // The shortcuts (F6, F9)

    /** Sets a PIN with the master password of this flow (F6 "Set", F1 step 6). False: not set; what was there stays. */
    suspend fun setPin(pin: ByteArray, proof: MasterProof): Boolean = core.locked { shortcuts.setPin(pin, proof) }

    /** "Change PIN": [current] is a counted check in a prompt (§6.3, F6 step 2), then [new]. */
    suspend fun changePin(current: ByteArray, new: ByteArray): PinChangeOutcome = shortcuts.changePin(current, new)

    /** Turns fingerprint unlock on with the master password of this flow (F9). */
    suspend fun fingerprintOn(proof: MasterProof): FingerprintOnOutcome = shortcuts.fingerprintOn(proof)

    /** Turns fingerprint unlock off; no proof (F9 "Off"). */
    suspend fun fingerprintOff() = core.locked { shortcuts.fingerprintOff() }

    // ---------------------------------------------------------------------------------------------
    // The master password: change and replacement (F8, F11, F16b)

    /** Changes the master password (F8); [pin] is the PIN typed at step 3 to keep it, or null. */
    suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeOutcome =
        passwords.changeMasterPassword(proof, first, again, pin)

    /** Asks for a new master password while the old one is forgotten (F11 steps 3 to 5). */
    suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestOutcome = passwords.requestNewMasterPassword(first, again)

    /** "Cancel it": the pending new master password is dropped; no proof (F11 step 8). */
    suspend fun cancelPending() = core.locked {
        passwords.cancelPending()
        core.publishSummary()
    }

    /** "Finish now": the new master password is ready and is typed to finish (F11 steps 13 to 19). */
    suspend fun finishPending(chars: CharArray): MasterPasswordOutcome = passwords.finishPending(chars)

    /** "It wasn't me" on the "replaced" notice, or "Open my passwords from before 7 Oct" (F16b): the old vault's own lock screen. */
    suspend fun itWasntMe(oldVaultId: String?) = core.locked { olderCopies.itWasntMe(oldVaultId) }
}
