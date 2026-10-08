package io.github.dilshodbekmx.passwordvault.lock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpError
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PasswordOpen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultPort
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultRef
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultScan
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultWrite
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MasterPasswordGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MpAttempt
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MpCheck
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Place
import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordStore
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.lock.gate.GateRig
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The lock of the walking skeleton (build plan 3.2), which `DefaultLockManager` over `LockEngine`
 * replaced in the app (build plan 4.1): a [LockManager] that knows only three states, "no vault"
 * (Welcome), "locked: master password" and "unlocked". It has no PIN, no fingerprint, no lock records
 * and no vault put aside; the flows of those answer that nothing was done.
 *
 * **It is test code now.** Package 4.1 took it out of the app and kept it here, in the test source
 * set, only as the lock that the session's tests run on (`APP-T/session/`: `SessionRig`,
 * `AutoLockTest`, `LockOrderTest`, with [GateRig] and `InMemoryVault`). Those tests are package
 * 3.2's and name this class and its package; they assert the exact order of the calls a lock makes
 * on a vault that logs them, which a lock that does only this much keeps short. It stays in the
 * package `…passwordvault.lock`, where they import it from. The app's own lock is tested in
 * `APP-T/lock/` on `DefaultLockManager`. Once the session's tests run on that manager, this file,
 * `GateRig`, `InMemoryVault` and `MasterPasswordGateTest` can go (and the skeleton's check,
 * `tools/emu/checks/3A.sh`, stops counting that test among 3.2's).
 *
 * - **Start** (lock.md §5.11 step 2): [state] is [LockUiState.Starting] until [VaultPort.vaults]
 *   has answered, off the main thread: [LockUiState.NoVault] for an empty phone (§13.1 row 1),
 *   [LockUiState.Unreadable] when no generation can be read or the vaults cannot be listed (row 2,
 *   never "no vault"), otherwise the master-password screen (row 7, with no reason: no PIN is set).
 * - **Visits** (lock.md §6.3): a visit starts when the master-password screen is shown while the
 *   app is in front (the process-level `ON_START`, or a lock while in front) and ends at the
 *   process-level `ON_STOP` or when the app is unlocked. Nothing here listens to the activity, so
 *   a rotation or a language change ends no visit (§11). A check belongs to the visit in which it
 *   was typed; one that finishes after its visit ended opens nothing (I11): what the vault port
 *   opened is dropped again, and [MpResult.TooLate] is returned. A password submitted while no
 *   visit is open is not checked at all.
 * - **The master password** is checked under [MasterPasswordGuard] (lock.md §6.8): from the sixth
 *   try after five wrong ones in a row each try waits until 30 seconds of uptime have passed since
 *   the last one. The gate keeps no `state.rec`, so the guard counts in memory only, for the life
 *   of the process ([InMemoryOnly]). [VaultPort.openWithPassword] derives (one Argon2id at a time in
 *   the process, `GatedMasterKdf`) and tries every slot; "wrong" only when no slot opened.
 * - **[createVault]** (lock.md F1 steps 2 and 3, F13): the two typings are compared, through
 *   `MasterPasswordBytes`, before anything is derived; a password with an unassigned code point is
 *   refused [V-C9]; the vault port writes the vault and proves it from disk with the second typing;
 *   then [AppSettings.writeDefaults] writes the defaults of lock.md §10.1 (lock delay 30 seconds,
 *   clipboard 1 minute), and only then is the state unlocked, so that the session's first decision
 *   already reads them.
 * - **[lock]** (architecture.md §5.5 steps 3 and 4): the open prompts end and the proofs handed out
 *   are dropped, [VaultPort.lock] drops the session (it waits for a running save first), and the
 *   master-password screen is shown. Only `AutoLock` calls it, after the draft flush of §5.5.
 *
 * Every flow runs under one mutex (the lock mutex of architecture.md §5.2) and, once started, to
 * its end even if its caller is cancelled: a vault the port opened is either shown or dropped, and
 * a vault it made is always published. Everything else runs on the main thread. The arrays passed
 * in belong to the caller; every byte array made here is zeroed.
 *
 * @param scope the app's scope: the start-up scan, "Try again" and the end of a wait run in it.
 * @param io the dispatcher of [VaultPort.vaults] (it reads the vault directories) and of
 *   [AppSettings.writeDefaults] (`commit()`).
 * @param processLifecycle `ProcessLifecycleOwner`'s lifecycle (lock.md §10.2): `ON_START` and
 *   `ON_STOP` start and end the visits and the prompts.
 */
class MasterPasswordGate(
    private val port: VaultPort,
    private val settings: AppSettings,
    private val random: RandomSource,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    processLifecycle: Lifecycle,
) : LockManager {

    private val _state = MutableStateFlow<LockUiState>(LockUiState.Starting)
    override val state: StateFlow<LockUiState> = _state.asStateFlow()

    private val _status = MutableStateFlow(LockStatus.NONE)

    /** [LockStatus.NONE] with the vaults put aside: no PIN, no fingerprint, nothing pending or replaced. */
    override val status: StateFlow<LockStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    /** The app is visible: between the process-level `ON_START` and `ON_STOP`. */
    private var inFront = false

    /** The visit of the lock screen shown now; null while none is shown or the app is not in front. */
    private var visit: Place? = null
    private var visits = 0L

    /** The checks typed in the open app (a backup, an export…) that have not answered yet. */
    private val prompts = ArrayList<Place>()
    private var promptCount = 0L

    /** The proofs handed out since the last lock or `ON_STOP`, which drop them (lock.md §5.8). */
    private val proofs = ArrayList<MasterProof>()

    /** The current vault's id while one is on the phone; null for none or one that cannot be read. */
    private var vaultId: String? = null
    private var aside: List<AsideVault> = emptyList()

    /** One guard per vault for the life of the process (lock.md §6.8). */
    private val guards = HashMap<String, MasterPasswordGuard>()

    private var noticeIds = 0L
    private var waitEnd: Job? = null

    init {
        processLifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = cameToFront()

                override fun onStop(owner: LifecycleOwner) = wentAway()
            },
        )
        scope.launch { rescan(onlyWhen = { it is LockUiState.Starting }) }
    }

    override fun send(intent: LockIntent) {
        when (intent) {
            is LockIntent.NoticeAnswered -> {
                val now = _state.value
                if (now is LockUiState.Unlocked) _state.value = LockUiState.Unlocked(now.notices.filterNot { it.id == intent.id }, now.proof)
            }
            // "Try again" on "Your passwords can't be opened"; back to the field from "an older master password".
            LockIntent.UseMasterPassword, is LockIntent.OlderPasswordAnswer -> when (val now = _state.value) {
                is LockUiState.Unreadable -> scope.launch { rescan(onlyWhen = { it is LockUiState.Unreadable }) }
                is LockUiState.Locked -> if (now.screen is LockScreen.OlderPassword) showField(error = null)
                else -> Unit
            }
            // The PIN, fingerprint, the "Forgot master password?" sheet and the vaults put aside come with
            // DefaultLockManager (build plan 4.1) and the lock screens (4.2).
            else -> Unit
        }
    }

    override suspend fun lock(reason: LockReason) {
        // Before the mutex, so that a check running in the open app answers TooLate.
        endPrompts()
        dropProofs()
        mutex.withLock {
            withContext(NonCancellable) {
                if (_state.value !is LockUiState.Unlocked) return@withContext
                try {
                    port.lock()
                } finally {
                    showLockScreen()
                }
            }
        }
    }

    override suspend fun submitMasterPassword(chars: CharArray, purpose: MpPurpose): MpResult {
        // The place is taken when the password is submitted: a check that waits for the mutex still
        // belongs to the visit or prompt in which it was typed.
        when (_state.value) {
            is LockUiState.Unlocked -> {
                val at = Place.prompt(++promptCount).also { prompts += it }
                try {
                    return mutex.withLock { withContext(NonCancellable) { checkInApp(chars, at) } }
                } finally {
                    prompts.remove(at)
                }
            }
            is LockUiState.Locked -> {
                if (purpose != MpPurpose.UNLOCK) return MpResult.TooLate // a step of the open app while it is locked
                val at = visit ?: return MpResult.TooLate // typed on no visit: nothing is checked, nothing opens
                return mutex.withLock { withContext(NonCancellable) { unlockWith(chars, at) } }
            }
            is LockUiState.Unreadable -> {
                if (purpose == MpPurpose.UNLOCK) rescan(onlyWhen = { it is LockUiState.Unreadable }) // "Try again"
                return MpResult.TooLate
            }
            LockUiState.Starting, is LockUiState.NoVault -> return MpResult.TooLate // no vault to check against
        }
    }

    override suspend fun createVault(first: CharArray, again: CharArray, restoreFrom: Vault?): CreateResult {
        if (!MasterPasswordBytes.same(first, again)) return CreateResult.Mismatch // before anything is derived
        MasterPasswordBytes.firstUnassigned(first)?.let { return CreateResult.Unassigned(it) }
        return mutex.withLock {
            withContext(NonCancellable) {
                if (_state.value !is LockUiState.NoVault) return@withContext CreateResult.NotSaved
                val firstBytes = MasterPasswordBytes.of(first)
                val againBytes = MasterPasswordBytes.of(again)
                val write = try {
                    port.createVault(random.uuid(), firstBytes, againBytes, restoreFrom, putCurrentAside = false)
                } catch (e: KdfUnavailable) {
                    return@withContext CreateResult.NoMemory
                } finally {
                    firstBytes.fill(0)
                    againBytes.fill(0)
                }
                if (write !is VaultWrite.Done) return@withContext CreateResult.NotSaved
                writeDefaults()
                vaultId = write.vaultId
                endVisit()
                _state.value = LockUiState.Unlocked(emptyList(), null)
                CreateResult.Created(write.vaultId)
            }
        }
    }

    override fun pinProblem(pin: ByteArray): PinBlocklist.Reason? = PinBlocklist.reason(pin)

    /** No PIN in the gate (build plan 4.1): nothing is set. */
    override suspend fun setPin(pin: ByteArray, proof: MasterProof): Boolean = false

    /** No PIN to change. */
    override suspend fun changePin(current: ByteArray, new: ByteArray): PinChange = PinChange.CannotCheck

    /** No fingerprint in the gate (build plan 4.1). */
    override suspend fun fingerprintOn(proof: MasterProof): FingerprintOnResult = FingerprintOnResult.Failed

    override suspend fun fingerprintOff() = Unit

    /** A change comes with build plan 5.5; the typings are still compared first. */
    override suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeResult =
        if (!MasterPasswordBytes.same(first, again)) ChangeResult.Mismatch else ChangeResult.NothingChanged

    /** No PIN or fingerprint can confirm a new master password here (lock.md F11 needs one). */
    override suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestResult =
        if (!MasterPasswordBytes.same(first, again)) RequestResult.Mismatch else RequestResult.NotAllowed

    override fun cancelNewMasterPassword() = Unit

    /** The gate opens no vault put aside (lock.md F17 comes with build plan 4.1 and 5.2). */
    override suspend fun openAside(vaultId: String, chars: CharArray): AsideResult = AsideResult.NotSaved

    override suspend fun deleteAside(vaultId: String, chars: CharArray): AsideResult = AsideResult.NotSaved

    // ---- the lock screen -------------------------------------------------------------------------

    /** Checks [chars] on the lock screen during the visit [at]; a right one unlocks. Holds the mutex. */
    private suspend fun unlockWith(chars: CharArray, at: Place): MpResult {
        val id = vaultId
        if (_state.value !is LockUiState.Locked || id == null) return MpResult.TooLate // unlocked or changed meanwhile
        if (at.hasEnded) return MpResult.TooLate // the visit ended while this waited for the mutex: nothing was checked
        val guard = guardOf(id)
        guard.waitUntilElapsedMs()?.let { until ->
            showField(error = currentError())
            return MpResult.Wait(until)
        }
        val bytes = MasterPasswordBytes.of(chars)
        var opened: PasswordOpen? = null
        try {
            showField(error = null, opening = true) // "Opening…" while Argon2id runs
            val attempt = guard.attempt(at) {
                val result = port.openWithPassword(VaultRef.Current, bytes)
                opened = result
                checkOf(result)
            }
            return when (attempt) {
                is MpAttempt.Wait -> MpResult.Wait(attempt.untilElapsedMs)
                is MpAttempt.Checked -> afterCheck(attempt, opened, bytes, id)
            }
        } finally {
            bytes.fill(0)
            // Not unlocked and not another screen: the field again, with the line of this try if any.
            val now = (_state.value as? LockUiState.Locked)?.screen as? LockScreen.MasterPassword
            if (now != null && now.opening) showField(error = null)
        }
    }

    private suspend fun afterCheck(attempt: MpAttempt.Checked, opened: PasswordOpen?, bytes: ByteArray, id: String): MpResult {
        if (attempt.tooLate) {
            // The visit ended while Argon2id ran (the screen went off, the app was left): the counts
            // are kept, nothing opens (lock.md §6.8 step 8, I11), and the next visit starts afresh.
            if (opened is PasswordOpen.Opened) port.lock()
            return when (attempt.check) {
                MpCheck.RIGHT -> MpResult.TooLate
                MpCheck.WRONG -> MpResult.Wrong
                MpCheck.NOT_COUNTED -> MpResult.NoMemory
            }
        }
        return when (opened) {
            is PasswordOpen.Opened -> {
                endVisit()
                val notices = if (opened.olderCopy) listOf(Notice.OlderCopy(++noticeIds, opened.createdMs)) else emptyList()
                _state.value = LockUiState.Unlocked(notices, null)
                MpResult.Right(proofOf(bytes, id))
            }
            PasswordOpen.Wrong -> {
                showField(error = MpError.Wrong(offerForgot = false, replaced = null))
                MpResult.Wrong
            }
            is PasswordOpen.OlderPassword -> {
                // Only after a master-password change (build plan 5.5), which the gate cannot make.
                _state.value = LockUiState.Locked(LockScreen.OlderPassword(opened.changedAtMs, unfinishedChange = false))
                MpResult.OlderPassword(opened.changedAtMs, unfinishedChange = false)
            }
            PasswordOpen.NoMemory -> {
                showField(error = MpError.NoMemory)
                MpResult.NoMemory
            }
            // The password opened a slot, but no generation could be used: "Your passwords can't be opened".
            PasswordOpen.Damaged -> cannotOpen(CurrentVault.Why.DAMAGED)
            PasswordOpen.Newer -> cannotOpen(CurrentVault.Why.NEWER)
            PasswordOpen.Unreadable -> cannotOpen(CurrentVault.Why.IO)
            null -> error("the guard checked without the vault port")
        }
    }

    private fun cannotOpen(why: CurrentVault.Why): MpResult {
        endVisit()
        _state.value = LockUiState.Unreadable(why, aside)
        return MpResult.TooLate
    }

    /** Checks [chars] against the open vault, for a step of the open app (the prompt [at]). Holds the mutex. */
    private suspend fun checkInApp(chars: CharArray, at: Place): MpResult {
        val id = vaultId
        if (_state.value !is LockUiState.Unlocked || id == null || at.hasEnded) return MpResult.TooLate // nothing was checked
        val guard = guardOf(id)
        val bytes = MasterPasswordBytes.of(chars)
        try {
            val attempt = guard.attempt(at) {
                when (port.checkPassword(bytes)) {
                    true -> MpCheck.RIGHT
                    false -> MpCheck.WRONG
                    null -> MpCheck.NOT_COUNTED
                }
            }
            return when (attempt) {
                is MpAttempt.Wait -> MpResult.Wait(attempt.untilElapsedMs)
                is MpAttempt.Checked -> when (attempt.check) {
                    MpCheck.RIGHT -> if (attempt.tooLate) MpResult.TooLate else MpResult.Right(proofOf(bytes, id))
                    MpCheck.WRONG -> MpResult.Wrong
                    MpCheck.NOT_COUNTED -> MpResult.NoMemory
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    // ---- what is shown ------------------------------------------------------------------------------

    /** Reads the vaults on the phone again and shows what it found, if the state is still one [onlyWhen] accepts. */
    private suspend fun rescan(onlyWhen: (LockUiState) -> Boolean) {
        mutex.withLock {
            withContext(NonCancellable) {
                if (onlyWhen(_state.value)) applyScan(scan())
            }
        }
    }

    /** The vaults on the phone, read off the main thread; null when they cannot be listed. */
    private suspend fun scan(): VaultScan? =
        try {
            withContext(io) { port.vaults() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a directory that cannot be read is a storage error, never "no vault" (lock.md F1 step 1)
        }

    private fun applyScan(scan: VaultScan?) {
        aside = scan?.aside.orEmpty()
        _status.value = LockStatus.NONE.copy(aside = aside)
        when (val current = scan?.current) {
            null -> showUnreadable(CurrentVault.Why.IO)
            CurrentVault.NoVault -> {
                vaultId = null
                endVisit()
                _state.value = LockUiState.NoVault(aside)
            }
            is CurrentVault.Unreadable -> showUnreadable(current.why)
            is CurrentVault.Present -> {
                vaultId = current.vaultId
                showLockScreen()
            }
        }
    }

    private fun showUnreadable(why: CurrentVault.Why) {
        vaultId = null
        endVisit()
        _state.value = LockUiState.Unreadable(why, aside)
    }

    /** The master-password screen, afresh: a new visit when the app is in front. */
    private fun showLockScreen() {
        endVisit()
        if (inFront) visit = Place.visit(++visits)
        showField(error = null)
    }

    /** The master-password screen with [error], and the guard's wait. */
    private fun showField(error: MpError?, opening: Boolean = false) {
        val until = vaultId?.let { guards[it]?.waitUntilElapsedMs() }
        _state.value = LockUiState.Locked(
            LockScreen.MasterPassword(
                reason = null, // no PIN is ever set here: lock.md §13.3, "none of the above"
                canUsePin = false,
                practice = null,
                waitUntilElapsedMs = until,
                error = error,
                pendingReadyInMs = null,
                opening = opening,
                aside = null,
            ),
        )
        if (until != null) endWaitAt(until)
    }

    private fun currentError(): MpError? = ((_state.value as? LockUiState.Locked)?.screen as? LockScreen.MasterPassword)?.error

    /** When the wait is over, the field is shown enabled again, with the same line. */
    private fun endWaitAt(untilElapsedMs: Long) {
        waitEnd?.cancel()
        waitEnd = scope.launch {
            clock.delayUntilElapsed(untilElapsedMs)
            val screen = (_state.value as? LockUiState.Locked)?.screen as? LockScreen.MasterPassword ?: return@launch
            if (screen.waitUntilElapsedMs == untilElapsedMs && !screen.opening) showField(error = screen.error)
        }
    }

    // ---- visits, prompts and proofs --------------------------------------------------------------

    private fun cameToFront() {
        inFront = true
        if (_state.value is LockUiState.Locked && visit == null) showLockScreen()
    }

    private fun wentAway() {
        inFront = false
        endVisit()
        endPrompts()
        dropProofs()
    }

    private fun endVisit() {
        val ended = visit ?: return
        visit = null
        ended.end()
        guards.values.forEach { it.placeEnded(ended) } // its wrong tries stay counted (lock.md §6.8)
    }

    private fun endPrompts() {
        prompts.forEach { prompt -> guards.values.forEach { it.placeEnded(prompt) } }
        prompts.forEach { it.end() }
        prompts.clear()
    }

    private fun dropProofs() {
        proofs.forEach { it.drop() }
        proofs.clear()
    }

    /**
     * The proof of a master password that [VaultPort.openWithPassword] or [VaultPort.checkPassword]
     * has just found right ([bytes], copied), for the vault [id]. `MasterProof`'s constructor is
     * `internal` to :core, and only `LockEngine` makes the real proofs; this stand-in makes its own
     * with `MasterProof.forFakes`, as test code may (build check R-15 does not scan the test source
     * sets), but never without a right master password behind them. Each one is dropped at the next
     * lock or `ON_STOP` (lock.md §5.8).
     */
    private fun proofOf(bytes: ByteArray, id: String): MasterProof = MasterProof.forFakes(bytes, id).also { proofs += it }

    private fun guardOf(id: String): MasterPasswordGuard =
        guards.getOrPut(id) { MasterPasswordGuard(id, InMemoryOnly, clock).also { it.load() } }

    private suspend fun writeDefaults() {
        try {
            withContext(io) { settings.writeDefaults() } // false: not written; a missing setting reads as the strictest (§10.1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The same: the vault exists, and a lock delay that cannot be read is "Immediately".
        }
    }

    private fun checkOf(result: PasswordOpen): MpCheck = when (result) {
        is PasswordOpen.Opened -> MpCheck.RIGHT
        PasswordOpen.Wrong, is PasswordOpen.OlderPassword -> MpCheck.WRONG // an older password stays counted (§6.8 step 6)
        PasswordOpen.NoMemory -> MpCheck.NOT_COUNTED
        // A slot opened, so the password is right (§6.8 step 7: "wrong only when no slot opened").
        PasswordOpen.Damaged, PasswordOpen.Newer, PasswordOpen.Unreadable -> MpCheck.RIGHT
    }

    /**
     * The gate keeps no `state.rec` (the lock records come with `LockEngine`, build plan 3.10 and
     * 4.1): the guard reads it as MISSING and so keeps its counts in memory, for this process, and
     * never writes.
     */
    private object InMemoryOnly : RecordStore<StateRecord> {
        override fun read(): RecordRead<StateRecord> = RecordRead.Missing

        override fun write(record: StateRecord) = throw IOException("the gate keeps no lock records")
    }
}
