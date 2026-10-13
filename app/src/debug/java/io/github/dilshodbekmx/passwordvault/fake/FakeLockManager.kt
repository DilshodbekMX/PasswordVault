package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.core.lock.flow.ChoosePinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.lock.AsideResult
import io.github.dilshodbekmx.passwordvault.lock.ChangeResult
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintOnResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.FinishResult
import io.github.dilshodbekmx.passwordvault.lock.LockIntent
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.lock.PinChange
import io.github.dilshodbekmx.passwordvault.lock.PinSetResult
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.lock.ProofResult
import io.github.dilshodbekmx.passwordvault.lock.RequestResult
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A scriptable [LockManager] for the shell, previews and ViewModel tests (architecture.md §2.2
 * `fake`). A test sets [state] and [status] directly, queues the answers of the flows (each queue
 * is used first; when it is empty the default below applies), and reads what was asked from
 * [sent], [lockReasons] and [purposes]. It never keeps what was typed. Single-threaded.
 *
 * The defaults behave like a lock that knows the master password "anything", so that the debug app
 * walks its placeholder routes:
 * - [createVault] compares the typings, then unlocks with [noticesAfterCreate] (lock.md F1 step 6:
 *   "Choose a PIN" for a new vault);
 * - [submitMasterPassword] is right; for [MpPurpose.UNLOCK] on a lock screen it unlocks;
 * - [lock] shows [lockScreen];
 * - [finishNewMasterPassword] finishes, and nothing is pending any more;
 * - [send] records the intent, and `NoticeAnswered` removes that notice.
 *
 * A [vault], when given, is opened at every unlock and closed at every lock, as `VaultPort` is.
 */
class FakeLockManager(
    initial: LockUiState = LockUiState.NoVault(emptyList()),
    private val vault: FakeVaultRepository? = null,
) : LockManager {

    override val state = MutableStateFlow(initial)
    override val status = MutableStateFlow(LockStatus.NONE)

    /** Every intent sent, in order. */
    val sent = mutableListOf<LockIntent>()

    /** The reason of every [lock]. */
    val lockReasons = mutableListOf<LockReason>()

    /** The purpose of every [submitMasterPassword]; what was typed is never kept. */
    val purposes = mutableListOf<MpPurpose>()

    /** Whether each [proveForNewMasterPassword] was given a PIN; the digits are never kept. */
    val proofPins = mutableListOf<Boolean>()

    /** How many times [leaveNewMasterPassword] was called. */
    var newMasterPasswordLeft = 0
        private set

    /** How many times [finishNewMasterPassword] was called; what was typed is never kept. */
    var finishes = 0
        private set

    val mpResults = ArrayDeque<MpResult>()
    val createResults = ArrayDeque<CreateResult>()
    val setPinResults = ArrayDeque<PinSetResult>()
    val pinChanges = ArrayDeque<PinChange>()
    val fingerprintResults = ArrayDeque<FingerprintOnResult>()
    val changeResults = ArrayDeque<ChangeResult>()
    val proofResults = ArrayDeque<ProofResult>()
    val requestResults = ArrayDeque<RequestResult>()
    val finishResults = ArrayDeque<FinishResult>()
    val asideResults = ArrayDeque<AsideResult>()

    /** The vault id of the proofs this fake makes. */
    var vaultId: String = "00000000-0000-4000-8000-000000000001"

    /** The screen [lock] shows: the master-password screen without a reason. */
    var lockScreen: LockScreen = LockScreen.MasterPassword(
        reason = null,
        canUsePin = false,
        practice = null,
        waitUntilElapsedMs = null,
        error = null,
        pendingReadyInMs = null,
        opening = false,
        aside = null,
    )

    /** The notices after [createVault]: "Choose a PIN" for a new vault, with the proof of setup (lock.md F1 step 6). */
    var noticesAfterCreate: List<Notice> = listOf(Notice.ChoosePin(1L, ChoosePinReason.NONE_HERE, needsMasterPassword = false))

    /** The notices after an unlock by [submitMasterPassword]. */
    var noticesAfterUnlock: List<Notice> = emptyList()

    override fun send(intent: LockIntent) {
        sent += intent
        val now = state.value
        if (intent is LockIntent.NoticeAnswered && now is LockUiState.Unlocked) {
            state.value = LockUiState.Unlocked(now.notices.filterNot { it.id == intent.id }, now.proof)
        }
    }

    override suspend fun lock(reason: LockReason) {
        lockReasons += reason
        (state.value as? LockUiState.Unlocked)?.proof?.drop()
        vault?.close()
        state.value = LockUiState.Locked(lockScreen)
    }

    override suspend fun submitMasterPassword(chars: CharArray, purpose: MpPurpose): MpResult {
        purposes += purpose
        val result = mpResults.removeFirstOrNull() ?: MpResult.Right(proof())
        val now = state.value
        if (result is MpResult.Right && purpose == MpPurpose.UNLOCK && (now is LockUiState.Locked || now is LockUiState.Unreadable)) {
            unlock(noticesAfterUnlock, result.proof, null)
        }
        return result
    }

    override suspend fun createVault(first: CharArray, again: CharArray, restoreFrom: Vault?): CreateResult {
        val result = createResults.removeFirstOrNull()
            ?: if (first.contentEquals(again)) CreateResult.Created(vaultId) else CreateResult.Mismatch
        if (result is CreateResult.Created) unlock(noticesAfterCreate, proof(), restoreFrom ?: Vault())
        return result
    }

    override fun pinProblem(pin: ByteArray): PinBlocklist.Reason? = PinBlocklist.reason(pin)

    override suspend fun setPin(pin: ByteArray, proof: MasterProof): PinSetResult {
        val result = setPinResults.removeFirstOrNull() ?: PinSetResult.Set
        if (result == PinSetResult.Set) status.value = status.value.copy(pin = PinStatus.On)
        return result
    }

    override suspend fun changePin(current: ByteArray, new: ByteArray): PinChange = pinChanges.removeFirstOrNull() ?: PinChange.Changed

    override suspend fun fingerprintOn(proof: MasterProof): FingerprintOnResult {
        val result = fingerprintResults.removeFirstOrNull() ?: FingerprintOnResult.On
        if (result == FingerprintOnResult.On) status.value = status.value.copy(fingerprint = FingerprintStatus.On)
        return result
    }

    override suspend fun fingerprintOff() {
        status.value = status.value.copy(fingerprint = FingerprintStatus.Off)
    }

    override suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeResult =
        changeResults.removeFirstOrNull()
            ?: if (!first.contentEquals(again)) {
                ChangeResult.Mismatch
            } else {
                ChangeResult.Changed(pinKept = pin != null, fingerprintKept = status.value.fingerprint == FingerprintStatus.On)
            }

    /** The PIN given is recorded as typed or not ([proofPins]); never the digits. */
    override suspend fun proveForNewMasterPassword(pin: ByteArray?): ProofResult {
        proofPins += pin != null
        return proofResults.removeFirstOrNull() ?: ProofResult.Proven(Shortcut.PIN)
    }

    override suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestResult =
        requestResults.removeFirstOrNull()
            ?: if (first.contentEquals(again)) RequestResult.Requested(remainingMs = 24L * 60 * 60 * 1000) else RequestResult.Mismatch

    override fun leaveNewMasterPassword() {
        newMasterPasswordLeft++
    }

    /** Finished or cancelled by the current master password: nothing is pending any more. */
    override suspend fun finishNewMasterPassword(chars: CharArray): FinishResult {
        finishes++
        val result = finishResults.removeFirstOrNull() ?: FinishResult.Finished
        if (result == FinishResult.Finished || result == FinishResult.RequestCancelled) status.value = status.value.copy(pending = null)
        return result
    }

    override fun cancelNewMasterPassword() {
        status.value = status.value.copy(pending = null)
    }

    override suspend fun openAside(vaultId: String, chars: CharArray): AsideResult = asideResults.removeFirstOrNull() ?: AsideResult.Done

    override suspend fun deleteAside(vaultId: String, chars: CharArray): AsideResult = asideResults.removeFirstOrNull() ?: AsideResult.Done

    /** A proof that no master password stands behind (architecture.md §3.4, R-15). */
    fun proof(): MasterProof = MasterProof.forFakes(ByteArray(0), vaultId)

    private fun unlock(notices: List<Notice>, proof: MasterProof, opened: Vault?) {
        if (opened != null) vault?.open(opened) else vault?.open()
        state.value = LockUiState.Unlocked(notices, proof)
    }
}
