package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordCheck
import io.github.dilshodbekmx.passwordvault.core.strength.MasterPasswordRules
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.ChangeResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.ui.setup.MasterPasswordAsk
import io.github.dilshodbekmx.passwordvault.ui.setup.PinDigits
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupFiles
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupNotes
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupOffer
import io.github.dilshodbekmx.passwordvault.ui.transfer.NoteDeleted
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings › App lock › "Change master password" (ux.md §5.18; lock.md F8, in its order), one route
 * with its steps ([ChangeStage]):
 *
 * 1. **The current master password** ([current], `MpPurpose.CHANGE_MASTER_PASSWORD`), under the lock's
 *    guard (§6.8): a right one is this flow's proof, held here until the flow ends and then dropped.
 *    "I don't remember it" opens `Route.NewMasterPassword` (ux.md §5.24).
 * 2. **The new one**, chosen, written down and typed again ([choice], setup's three steps; the
 *    screen is kept on for the last two, lock.md §10.1).
 * 3. **"Enter your app PIN to keep using it."** only while a PIN is usable: the six digits go to the
 *    lock with the change, which makes the counted check (§6.3). "Not now" changes without it. A
 *    wrong PIN changes nothing yet and asks again with the tries left.
 * 4. **"Saving…"**: `LockManager.changeMasterPassword`, in [work], the app's scope, so that leaving the
 *    screen cannot cut the change in half. Step 5, the fingerprint prompt "Keep fingerprint unlock",
 *    belongs to that call; while fingerprint unlock is on the screen says "Touch the sensor to keep
 *    fingerprint unlock on." A change that could not be made, or not be confirmed, says so.
 * 5. **After the change**: "Choose a PIN" (4.3's steps, which take the new master password's proof
 *    from `LockUiState.Unlocked.proof`) when a PIN worked before and was not kept (F8 step 3); then
 *    "Make a new backup now. …" with "Back up now", which hands that proof to the backup screen
 *    (`BackupOffer`, so it is not asked again), and "Later". When the old master password is also an
 *    account's password (`Vault.masterPasswordMatches`, architecture.md D-9b: others may know it,
 *    ux.md §5.16 card 1) the step lists the backups this app saved, with "Delete", and "Also delete
 *    copies you sent to Telegram or by email."
 *
 * A lock ends the flow: nothing typed, chosen or proved stays (lock.md §10.1: "a lock discards the
 * flow, and nothing was changed"); after the unlock the route starts again at step 1. A rotation or
 * a language change keeps everything (the route's ViewModel; lock.md §11).
 *
 * @param offerBackup `BackupOffer.offer`: the backup screen opened next uses this proof.
 * @param deleteBackup `BackupNotes.delete`: deletes a backup this app saved and forgets it.
 */
class ChangeMasterPasswordViewModel(
    private val lock: LockManager,
    private val repository: VaultRepository,
    clock: Clock,
    random: RandomSource,
    dispatchers: AppDispatchers,
    private val work: CoroutineScope,
    private val offerBackup: (MasterProof) -> Unit,
    private val deleteBackup: suspend (FileRef) -> NoteDeleted,
    rules: (CharArray) -> MasterPasswordCheck = { MasterPasswordRules.check(it) },
) : ViewModel() {

    /** Step 1: the current master password. */
    val current = MasterPasswordAsk(lock, clock, MpPurpose.CHANGE_MASTER_PASSWORD, work, viewModelScope)

    /** Step 2: the new master password, chosen, written down and typed again. */
    val choice = NewMasterPasswordChoice(viewModelScope, random, dispatchers, rules)

    private val state = MutableStateFlow(ChangeMasterPasswordUi())

    /** What the route shows. */
    val ui: StateFlow<ChangeMasterPasswordUi> = state.asStateFlow()

    /** The proof of step 1, until the flow ends. */
    private var proof: MasterProof? = null

    /** A PIN worked when the change was asked for: one that was not kept is chosen again afterwards (F8 step 3). */
    private var hadPin = false

    private val pinDigits = PinDigits()

    /** The six digits of step 3, kept while the change runs. */
    private var pin: ByteArray? = null

    /**
     * The route has left the back stack ([onCleared]): its flow has ended, so a check of step 1 that
     * returns now proves nothing for it, and its proof goes at once (lock.md §5.8).
     */
    private var cleared = false

    init {
        // A lock (or the vault put aside) ends the flow: nothing typed or proved stays behind.
        viewModelScope.launch {
            lock.state.collect { if (it !is LockUiState.Unlocked) lockedNow() }
        }
    }

    // ---- step 1 ------------------------------------------------------------------------------------

    /**
     * "Next" on step 1: the current master password goes to the lock. A right one is the proof of
     * this flow, and the new one is chosen next. Whether the old one is also an account's password
     * is read now: the lock has compared them before it hands the proof over (D-9b).
     */
    fun submitCurrent() {
        if (state.value.stage != ChangeStage.CURRENT) return
        current.submit { right ->
            if (cleared || state.value.stage != ChangeStage.CURRENT) {
                right.drop()
                return@submit
            }
            proof?.drop()
            proof = right
            if (choice.typed) {
                // Asked again because the first proof went (the app was left meanwhile): the new one stands.
                state.update { it.copy(line = null) }
                toPinOrSave()
                return@submit
            }
            val vault = (repository.state.value as? VaultState.Unlocked)?.vault
            val usedElsewhere = vault?.masterPasswordMatches?.isNotEmpty() == true
            state.update { it.copy(stage = ChangeStage.CHOOSE, usedElsewhere = usedElsewhere, line = null) }
            choice.showWords()
        }
    }

    /**
     * "I don't remember it": what was typed goes, and the screen opens `Route.NewMasterPassword` over
     * this one; Back from there finds step 1 again, empty.
     */
    fun dontRemember() {
        if (state.value.stage != ChangeStage.CURRENT) return
        dropAll()
        state.update { it.copy(leave = Leave.NEW_MASTER_PASSWORD) }
    }

    // ---- step 2 ------------------------------------------------------------------------------------

    /** "Use these words" or "No, it's new": step 2 goes on to "Write it down on paper". */
    fun chosen() {
        val stage = state.value.stage
        val choosing = stage == ChangeStage.CHOOSE || stage == ChangeStage.WRITE_DOWN || stage == ChangeStage.TYPE_AGAIN
        if (choosing && choice.state.value.chosen != null) state.update { it.copy(stage = ChangeStage.WRITE_DOWN) }
    }

    /** "Next" on "Write it down on paper". */
    fun wroteItDown() {
        if (state.value.stage == ChangeStage.WRITE_DOWN) state.update { it.copy(stage = ChangeStage.TYPE_AGAIN) }
    }

    /** "Show my password again" on "Type your master password again": back to "Write it down on paper". */
    fun showAgain() {
        if (state.value.stage != ChangeStage.TYPE_AGAIN) return
        choice.leftTypeAgain()
        state.update { it.copy(stage = ChangeStage.WRITE_DOWN) }
    }

    /**
     * "Next" on "Type your master password again": a matching typing goes on to the PIN while one is
     * usable (step 3), otherwise straight to the change.
     */
    fun submitAgain() {
        if (state.value.stage != ChangeStage.TYPE_AGAIN) return
        if (choice.submitAgain()) toPinOrSave()
    }

    /** The new master password is typed twice: the PIN while one is usable (step 3), otherwise the change. */
    private fun toPinOrSave() {
        val pinNow = lock.status.value.pin
        hadPin = pinNow == PinStatus.On || pinNow == PinStatus.CannotCheck
        if (pinNow == PinStatus.On) {
            pinDigits.clear()
            state.update { it.copy(stage = ChangeStage.PIN, entered = 0, line = null) }
        } else {
            save(withPin = null)
        }
    }

    // ---- step 3: the PIN ---------------------------------------------------------------------------

    /** A digit of the pad on step 3; the sixth starts the change with the PIN. */
    fun digit(digit: Int) {
        if (state.value.stage != ChangeStage.PIN) return
        if (!pinDigits.add(digit)) {
            state.update { it.copy(entered = pinDigits.count) }
            return
        }
        wipePin()
        pin = pinDigits.take()
        state.update { it.copy(entered = 0) }
        save(withPin = pin)
    }

    /** ⌫ on the pad of step 3. */
    fun deleteDigit() {
        if (state.value.stage != ChangeStage.PIN) return
        pinDigits.delete()
        state.update { it.copy(entered = pinDigits.count) }
    }

    /** A long press on ⌫: the digits typed so far go. */
    fun clearDigits() {
        if (state.value.stage != ChangeStage.PIN) return
        pinDigits.clear()
        state.update { it.copy(entered = 0) }
    }

    /** "Not now" on step 3: the change goes on without the PIN, which is chosen again afterwards. */
    fun notNow() {
        if (state.value.stage != ChangeStage.PIN) return
        pinDigits.clear()
        save(withPin = null)
    }

    // ---- step 4: the change ------------------------------------------------------------------------

    /** "Try again" after the phone had too little memory: the same typings, with the PIN typed again when one is usable. */
    fun retry() {
        if (state.value.stage != ChangeStage.NO_MEMORY) return
        if (lock.status.value.pin == PinStatus.On) {
            pinDigits.clear()
            state.update { it.copy(stage = ChangeStage.PIN, entered = 0, line = null) }
        } else {
            save(withPin = null)
        }
    }

    /**
     * The change with the proof of step 1, the two typings of step 2 and [withPin] (step 3). A proof
     * that went meanwhile (the app stopped being visible: lock.md §5.8) cannot change anything: the
     * current master password is asked again, and the new one chosen is kept.
     */
    private fun save(withPin: ByteArray?) {
        val proved = proof
        if (proved != null && !alive(proved)) {
            proof = null
            wipePin()
            pinDigits.clear()
            current.clear()
            state.update { it.copy(stage = ChangeStage.CURRENT, entered = 0, line = UiText.of(R.string.master_current_again)) }
            return
        }
        val typings = choice.typings()
        if (proved == null || typings == null) {
            // Nothing to change with (should not happen while the app is open): back to step 1.
            dropAll()
            state.update { ChangeMasterPasswordUi() }
            return
        }
        val pinCopy = withPin?.copyOf()
        wipePin()
        state.update {
            it.copy(stage = ChangeStage.SAVING, line = null, entered = 0, fingerprintOn = lock.status.value.fingerprint == FingerprintStatus.On)
        }
        val (first, again) = typings
        val change = work.async {
            try {
                lock.changeMasterPassword(proved, first, again, pinCopy).also { result ->
                    // Said here, in the app's scope, so that Settings says it also when a lock has ended
                    // this flow meanwhile (the screen went off while "Saving…" or the prompt of step 7
                    // waited): from step 6 on the change runs to its end (lock.md F8).
                    if (result is ChangeResult.Changed) SettingsNotice.post(UiText.of(R.string.master_changed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChangeResult.NothingChanged // the lock failed in a way it does not report: say that nothing changed
            } finally {
                first.fill('\u0000')
                again.fill('\u0000')
                pinCopy?.fill(0)
            }
        }
        viewModelScope.launch { afterSave(change.await()) }
    }

    private fun afterSave(result: ChangeResult) {
        if (state.value.stage != ChangeStage.SAVING) return
        when (result) {
            is ChangeResult.Changed -> changed(pinKept = result.pinKept)
            ChangeResult.Mismatch -> {
                choice.mismatchFromLock()
                state.update { it.copy(stage = ChangeStage.TYPE_AGAIN) }
            }
            is ChangeResult.Unassigned -> {
                choice.refuseUnassigned()
                state.update { it.copy(stage = ChangeStage.CHOOSE) }
            }
            is ChangeResult.PinWrong -> state.update {
                it.copy(stage = ChangeStage.PIN, entered = 0, line = UiText.plural(R.plurals.master_pin_wrong, result.left), shakes = it.shakes + 1)
            }
            ChangeResult.NoMemory -> state.update { it.copy(stage = ChangeStage.NO_MEMORY) }
            ChangeResult.NothingChanged -> {
                dropAll()
                state.update { it.copy(stage = ChangeStage.NOT_CHANGED) }
            }
            ChangeResult.Unclear -> {
                dropAll()
                state.update { it.copy(stage = ChangeStage.UNCLEAR) }
            }
        }
    }

    /**
     * Changed: what was typed goes, and so does the proof of step 1, which holds the old master
     * password (the lock has dropped it already). The unlocked state holds the new one's proof for
     * the steps that follow. "Choose a PIN" first when a PIN worked before and was not kept; this
     * phone must be able to keep a PIN key. ([save] has left "Your master password was changed." for
     * Settings.)
     */
    private fun changed(pinKept: Boolean) {
        dropAll()
        val choosePin = hadPin && !pinKept && lock.status.value.pin != PinStatus.NoSecureHardware
        val places = if (state.value.usedElsewhere) savedBackups().map { BackupPlace(it, PlaceState.LISTED) } else emptyList()
        state.update { it.copy(stage = ChangeStage.BACKUP, choosePin = choosePin, places = places) }
    }

    /** The screen opened "Choose a PIN" (or could not): it is not opened again. */
    fun pinStepsOpened() = state.update { it.copy(choosePin = false) }

    // ---- step 5: the new backup --------------------------------------------------------------------

    /**
     * "Back up now": the backup screen that opens next ([Leave.BACKUP]) uses the new master password's
     * proof, which the unlocked state holds, so that it does not ask for it again. Without that
     * proof (the app was left meanwhile) the backup screen asks for the master password.
     */
    fun backUpNow() {
        if (state.value.stage != ChangeStage.BACKUP) return
        (lock.state.value as? LockUiState.Unlocked)?.proof?.let(offerBackup)
        state.update { it.copy(leave = Leave.BACKUP) }
    }

    /** "Later", "OK", or Back at the end: the route closes. */
    fun close() {
        if (state.value.stage == ChangeStage.SAVING) return
        dropAll()
        state.update { it.copy(leave = Leave.BACK) }
    }

    /** "Delete" for the backup [ref] in the list of step 5: the file is deleted where its place allows it, and the vault forgets it. */
    fun deletePlace(ref: FileRef) {
        val now = state.value.places.firstOrNull { it.ref == ref } ?: return
        if (now.state == PlaceState.DELETING || now.state == PlaceState.DELETED) return
        setPlace(ref, PlaceState.DELETING)
        val deleting = work.async {
            try {
                deleteBackup(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NoteDeleted.NOT_DELETABLE
            }
        }
        viewModelScope.launch {
            setPlace(
                ref,
                when (deleting.await()) {
                    NoteDeleted.DELETED -> PlaceState.DELETED
                    NoteDeleted.NOT_DELETABLE -> PlaceState.NOT_DELETABLE
                    NoteDeleted.NOT_FORGOTTEN -> PlaceState.NOT_NOTED
                },
            )
        }
    }

    // ---- Back --------------------------------------------------------------------------------------

    /**
     * Back: from the first two steps the route closes; from "Write it down on paper" back to the
     * choice (the password goes, the words stay); from "Type your master password again" back to
     * "Write it down on paper"; from the PIN back to "Type your master password again". Ignored
     * while the change runs.
     */
    fun back() {
        when (state.value.stage) {
            ChangeStage.WRITE_DOWN -> {
                choice.leftWriteDown()
                state.update { it.copy(stage = ChangeStage.CHOOSE) }
            }
            ChangeStage.TYPE_AGAIN -> {
                choice.leftTypeAgain()
                state.update { it.copy(stage = ChangeStage.WRITE_DOWN) }
            }
            ChangeStage.PIN -> {
                pinDigits.clear()
                choice.leftTypeAgain()
                state.update { it.copy(stage = ChangeStage.TYPE_AGAIN, entered = 0, line = null) }
            }
            ChangeStage.SAVING -> Unit
            else -> close()
        }
    }

    /** The screen closed the route, or opened the next one, as [ChangeMasterPasswordUi.leave] asked. */
    fun left() = state.update { it.copy(leave = null) }

    override fun onCleared() {
        cleared = true
        dropAll()
    }

    // ---- inside ------------------------------------------------------------------------------------

    /** Whether [proof] still holds its bytes: the lock drops every proof at a lock and when the app stops being visible. */
    private fun alive(proof: MasterProof): Boolean = try {
        proof.useBytes { true }
    } catch (e: IllegalStateException) {
        false
    }

    private fun savedBackups(): List<FileRef> =
        ((repository.state.value as? VaultState.Unlocked)?.vault?.backups.orEmpty()).sortedByDescending { it.savedAt }

    private fun setPlace(ref: FileRef, to: PlaceState) =
        state.update { now -> now.copy(places = now.places.map { if (it.ref == ref) it.copy(state = to) else it }) }

    /** The app locked: the flow is over; after the unlock the route starts again at step 1. */
    private fun lockedNow() {
        dropAll()
        state.value = ChangeMasterPasswordUi()
    }

    private fun wipePin() {
        pin?.fill(0)
        pin = null
    }

    private fun dropAll() {
        current.clear()
        choice.wipe()
        pinDigits.clear()
        wipePin()
        proof?.drop()
        proof = null
    }

    companion object {
        /** The ViewModel over the app's graph. */
        fun from(graph: AppGraph): ChangeMasterPasswordViewModel {
            val notes = BackupNotes(graph.data.repository, BackupFiles(FileTaskStore.of(graph), graph.files, graph.dispatchers.io))
            val offers = BackupOffer.of(graph)
            return ChangeMasterPasswordViewModel(
                lock = graph.lock,
                repository = graph.data.repository,
                clock = graph.platform.clock,
                random = graph.platform.random,
                dispatchers = graph.dispatchers,
                work = graph.appScope,
                offerBackup = offers::offer,
                deleteBackup = notes::delete,
            )
        }
    }
}

/** The steps of "Change master password" (lock.md F8). */
enum class ChangeStage {
    /** "Type your current master password". */
    CURRENT,

    /** "Choose your master password" (setup's screen). */
    CHOOSE,

    /** "Write it down on paper" (setup's screen; the screen kept on). */
    WRITE_DOWN,

    /** "Type your master password again" (setup's screen; the screen kept on). */
    TYPE_AGAIN,

    /** "Enter your app PIN to keep using it" (F8 step 3). */
    PIN,

    /** "Saving…" (F8 steps 4 to 8), with the fingerprint prompt of step 7. */
    SAVING,

    /** Too little memory: "Try again". */
    NO_MEMORY,

    /** "Couldn't change your master password. Nothing was changed. …" */
    NOT_CHANGED,

    /** "Saving may not have finished. …" */
    UNCLEAR,

    /** Changed: "Choose a PIN" when it follows, then "Make a new backup now." (F8 step 9). */
    BACKUP,
}

/** Where the route goes when it ends. */
enum class Leave {
    /** Back to where it was opened from. */
    BACK,

    /** `Route.NewMasterPassword`, over this route ("I don't remember it"). */
    NEW_MASTER_PASSWORD,

    /** `Route.Backup`, in its place ("Back up now"). */
    BACKUP,
}

/** A backup this app saved, in the list of the last step, and what "Delete" did to it. */
data class BackupPlace(val ref: FileRef, val state: PlaceState) {
    override fun toString(): String = "BackupPlace(savedAt=${ref.savedAt}, state=$state)"
}

/** What became of a backup in the list of the last step. */
enum class PlaceState { LISTED, DELETING, DELETED, NOT_DELETABLE, NOT_NOTED }

/**
 * What "Change master password" shows. Never a digit or a password.
 *
 * @property entered how many digits of the PIN are typed (step 3).
 * @property line the line under the dots: a wrong PIN with the tries left; on step 1, that the
 *   current master password is asked again with the new one kept.
 * @property shakes grows with each wrong PIN: the dots shake.
 * @property fingerprintOn fingerprint unlock was on when the change started: "Touch the sensor …".
 * @property usedElsewhere the old master password is also the password of some account (D-9b).
 * @property choosePin "Choose a PIN" opens once, before the backup step.
 * @property places the backups this app saved, when [usedElsewhere].
 * @property leave the route ends, and where it goes.
 */
data class ChangeMasterPasswordUi(
    val stage: ChangeStage = ChangeStage.CURRENT,
    val entered: Int = 0,
    val line: UiText? = null,
    val shakes: Int = 0,
    val fingerprintOn: Boolean = false,
    val usedElsewhere: Boolean = false,
    val choosePin: Boolean = false,
    val places: List<BackupPlace> = emptyList(),
    val leave: Leave? = null,
)
