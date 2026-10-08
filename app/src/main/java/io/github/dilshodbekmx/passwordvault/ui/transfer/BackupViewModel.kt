package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.files.Cleanup
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickResult
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Make a backup" (ux.md §5.19; lock.md F12; architecture.md §4.12), in its steps ([BackupStep]):
 *
 * 1. **Explanation**, the first time only ([OneTime.BACKUP_EXPLAINED]).
 * 2. **The master password, always**, typed now and checked against the vault's own password slot
 *    (`MpPurpose.BACKUP`), which also counts as practice. The one exception is the entry that
 *    "Make a backup now?" offered right after a master-password entry ([BackupOffer]): this
 *    ViewModel is made with it ([offered]) and does not ask. No backup is made while a new master
 *    password is pending (lock.md F11 step 10): [BackupStep.Pending] instead.
 * 3. **The backup is made before the file picker opens** (the owner's decision 5 of 2026-10-08,
 *    lock.md F12 step 4): the picker makes the app stop being visible, and that drops every proof
 *    (lock.md §5.8). So the backup is sealed with the proof (`VaultRepository.sealBackup`), opened
 *    once with the proof's bytes (`openBackup`, the check that the typed password opens it), and
 *    the proof is dropped. What was sealed, and its SHA-256, stay here.
 * 4. **"Save as"** through the process's file task ([BackupFiles.saveAs]); closed without a place,
 *    the screen offers it again ([BackupStep.Choose]).
 * 5. **"Saving…", then "Checking the saved file…"**: the file is written and read back, and what
 *    was read must be what was sealed (SHA-256 and length). Only then "Backup saved", the note in the
 *    vault (`VaultEdits.noteBackup`: "Last backup", and when a backup is due) and the task's end.
 *    A write or a check that fails gives the task up, and the file is removed (ux.md §4 item 3).
 *
 * **An unfinished backup** (ux.md §4): the task recorded with its document, which a lock or a new
 * process cut short, is continued when this screen is opened again ([BackupUi.paused]). What was
 * sealed did not survive (the lock or the process dropped this ViewModel), so the master password is
 * asked again and the new backup goes into that document without the picker. "Cancel" gives the task
 * up and says what became of its file ([BackupStep.Stopped]).
 *
 * **Secrets.** The field is a [TextFieldState] held here, emptied at every submit, when the app
 * locks and when this ViewModel is cleared. A proof is used for one backup and dropped right after
 * it was sealed and opened once. Locked, this ViewModel drops what it holds (the lock may come while
 * the activity is stopped, before Navigation clears it, NavState's comment).
 *
 * Main thread; sealing and hashing run on `default`, the files on `io` (through [files]).
 */
class BackupViewModel(
    private val lock: LockManager,
    private val repository: VaultRepository,
    private val files: BackupFiles,
    private val settings: AppSettings,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    offered: MasterProof?,
) : ViewModel() {

    /** The master-password field (step 2). */
    val password = TextFieldState()

    private val mutableUi = MutableStateFlow(BackupUi(BackupStep.Password()))

    /** What the screen shows. */
    val ui: StateFlow<BackupUi> = mutableUi.asStateFlow()

    private val mutableLeave = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The screen closes: the backup was given up and there is nothing to say. */
    val leave: SharedFlow<Unit> = mutableLeave.asSharedFlow()

    /** The entry of "Make a backup now?", until it is used or dropped. */
    private var proof: MasterProof? = offered

    /** The backup made in step 3, until the screen is left or the app locks. */
    private var backup: Made? = null

    /** The document of this backup's task: the one the picker returned, or the recorded one of an unfinished backup. */
    private var doc: DocRef? = null

    private var work: Job? = null
    private var ticker: Job? = null

    init {
        doc = files.task.value?.takeIf { it.kind == FileTask.Kind.BACKUP }?.doc
        start()
        viewModelScope.launch { files.results(FileTask.Kind.BACKUP).collect(::picked) }
        viewModelScope.launch {
            lock.state.collect { if (it !is LockUiState.Unlocked) locked() }
        }
    }

    private val step: BackupStep get() = mutableUi.value.step

    /** True while a step runs that Back does not interrupt: checking, making, saving, checking the file. */
    val busy: Boolean
        get() = when (val now = step) {
            is BackupStep.Password -> now.checking
            is BackupStep.Working -> true
            else -> false
        }

    // ---- Steps 1 and 2 --------------------------------------------------------------------------

    /** "Continue" under the explanation: it is not shown again. */
    fun explained() {
        if (step != BackupStep.Explain) return
        viewModelScope.launch(dispatchers.io) {
            try {
                settings.update { it.oneTime = it.oneTime + OneTime.BACKUP_EXPLAINED }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // Best effort: the explanation then shows once more next time.
            }
        }
        afterExplanation()
    }

    /** "Make backup", or the keyboard's Done: the master password typed now (lock.md F12 step 2). */
    fun submit() {
        val now = step as? BackupStep.Password ?: return
        if (now.checking || waiting(now)) return
        val chars = password.readSecret()
        password.clearSecret()
        if (chars.isEmpty()) return
        show(BackupStep.Password(checking = true))
        work = viewModelScope.launch {
            val result = try {
                lock.submitMasterPassword(chars, MpPurpose.BACKUP)
            } finally {
                chars.fill(ZERO)
            }
            if ((step as? BackupStep.Password)?.checking != true) {
                (result as? MpResult.Right)?.proof?.drop() // locked meanwhile: nothing is made
                return@launch
            }
            when (result) {
                is MpResult.Right -> make(result.proof)
                MpResult.Wrong -> show(BackupStep.Password(error = PasswordError.Wrong))
                is MpResult.Wait -> waitUntil(result.untilElapsedMs)
                MpResult.NoMemory -> show(BackupStep.Password(error = PasswordError.NoMemory))
                is MpResult.OlderPassword -> show(BackupStep.Password(error = PasswordError.Older(result.changedAtMs, result.unfinishedChange)))
                MpResult.TooLate -> show(BackupStep.Password()) // the app was left or locked while it was checked: nothing was made
            }
        }
    }

    // ---- Steps 3 to 5 ---------------------------------------------------------------------------

    /** "Choose a place" or "Choose another place": "Save as" again for the backup made. */
    fun choose() {
        if (backup == null || busy) return
        when (step) {
            is BackupStep.Choose, is BackupStep.Failed -> openPicker()
            else -> Unit
        }
    }

    /** "Try again" after the backup could not be made: back to the master password. */
    fun retry() {
        val now = step as? BackupStep.Failed ?: return
        if (backup != null && now.retry == Retry.CHOOSE) openPicker() else show(BackupStep.Password())
    }

    /**
     * "Send to another app…" (ux.md §5.19 item 6): the share sheet for a copy of the backup saved,
     * named as the place named it; [title] is the sheet's title, in the screen's language.
     */
    fun send(title: CharSequence) {
        val saved = step as? BackupStep.Saved ?: return
        val made = backup ?: return
        viewModelScope.launch {
            val shown = files.share(made.bytes, saved.name, title)
            if (step === saved) show(saved.copy(sendFailed = !shown))
        }
    }

    /**
     * Back, or "Cancel" on an unfinished backup: one whose place was chosen gives its task up and
     * says what became of the file (ux.md §4 item 3); otherwise the screen closes. Ignored while a
     * step runs (ux.md §4, "Saving…").
     */
    fun cancel() {
        if (busy) return
        val stopping = doc
        if (stopping == null || step is BackupStep.Saved || step is BackupStep.Stopped) {
            mutableLeave.tryEmit(Unit)
            return
        }
        show(BackupStep.Working(Phase.STOPPING))
        work = viewModelScope.launch {
            val cleanup = files.abandon(FileTask.Kind.BACKUP, written = false)
            doc = null
            val name = nameIfLeft(cleanup, stopping)
            if (cleanup is Cleanup.Removed || cleanup is Cleanup.Left) {
                show(BackupStep.Stopped(cleanup, name), paused = false)
            } else {
                mutableLeave.tryEmit(Unit)
            }
        }
    }

    override fun onCleared() {
        wipe()
    }

    // ---- Internals ------------------------------------------------------------------------------

    private fun start() {
        mutableUi.value = BackupUi(BackupStep.Password(), paused = doc != null)
        when {
            lock.status.value.pending != null -> {
                dropProof()
                show(BackupStep.Pending)
            }
            OneTime.BACKUP_EXPLAINED !in settings.oneTime.value -> show(BackupStep.Explain)
            else -> afterExplanation()
        }
    }

    private fun afterExplanation() {
        val entry = proof
        if (entry != null) make(entry) else show(BackupStep.Password())
    }

    /** Step 3 with [entry]: seal, open once with its bytes, drop it; then the picker, or the recorded document. */
    private fun make(entry: MasterProof) {
        proof = null
        show(BackupStep.Working(Phase.MAKING))
        work = viewModelScope.launch {
            val outcome = try {
                seal(entry)
            } finally {
                entry.drop()
            }
            if ((step as? BackupStep.Working)?.phase != Phase.MAKING) return@launch // locked meanwhile
            when (outcome) {
                is Sealing.Made -> {
                    backup = outcome.made
                    val target = doc
                    if (target != null) save(target) else openPicker()
                }
                Sealing.Dropped -> show(BackupStep.Password()) // the entry ended (the app was left): type it now
                Sealing.NoMemory -> show(BackupStep.Failed(Failure.NO_MEMORY, cleanup = null, name = null, retry = Retry.PASSWORD))
                Sealing.Failed -> show(BackupStep.Failed(Failure.NOT_MADE, cleanup = null, name = null, retry = Retry.PASSWORD))
            }
        }
    }

    /**
     * Seals the backup with [entry] and opens it once with [entry]'s bytes: it must open, under the
     * password slot made from those bytes, as the vault it was sealed from. The bytes are copied for
     * the check and wiped after it.
     */
    private suspend fun seal(entry: MasterProof): Sealing {
        if (dropped(entry)) return Sealing.Dropped
        val bytes = try {
            repository.sealBackup(entry)
        } catch (e: KdfUnavailable) {
            return Sealing.NoMemory
        } catch (e: IllegalStateException) {
            return if (dropped(entry)) Sealing.Dropped else Sealing.Failed
        }
        val passwordBytes = try {
            entry.useBytes { it.copyOf() }
        } catch (e: IllegalStateException) {
            return Sealing.Dropped
        }
        val opened = try {
            repository.openBackup(bytes, passwordBytes)
        } finally {
            passwordBytes.fill(0)
        }
        return when (opened) {
            is BackupResult.Opened -> {
                val digest = withContext(dispatchers.default) { sha256(bytes) }
                Sealing.Made(Made(bytes, digest, opened.vault.entries.count { it.deletedAt == null }))
            }
            BackupResult.NoMemory -> Sealing.NoMemory
            else -> Sealing.Failed
        }
    }

    private fun openPicker() {
        show(BackupStep.Choose(opening = true))
        work = viewModelScope.launch {
            val opened = files.saveAs(STEP_SAVE, clock.wallMs())
            if (!opened && (step as? BackupStep.Choose)?.opening == true) {
                show(BackupStep.Failed(Failure.PICKER, cleanup = null, name = null, retry = Retry.CHOOSE))
            }
        }
    }

    /** What the picker returned, or the recorded document handed out again after an unlock. */
    private fun picked(result: PickResult) {
        when (result) {
            is PickResult.Cancelled -> {
                // Closed without a place: nothing was made there, and nothing is said (ux.md §5.19).
                if (step is BackupStep.Choose) show(BackupStep.Choose(opening = false))
            }
            is PickResult.Picked -> {
                if (result.doc == doc) return // known: this backup's own document, handed out again
                doc = result.doc
                val now = step
                if (backup != null && now is BackupStep.Choose) {
                    save(result.doc)
                } else if (now is BackupStep.Password || now == BackupStep.Explain) {
                    mutableUi.value = mutableUi.value.copy(paused = true)
                }
            }
        }
    }

    /** Step 5 into [target]: write, read back, compare with what was sealed; then the note and the end of the task. */
    private fun save(target: DocRef) {
        val made = backup ?: return
        doc = target
        show(BackupStep.Working(Phase.SAVING))
        work = viewModelScope.launch {
            if (!files.write(made.bytes)) {
                failed(Failure.WRITE, target)
                return@launch
            }
            show(BackupStep.Working(Phase.CHECKING))
            val back = files.readBack(target, made.bytes.size)
            val same = back != null && back.size == made.bytes.size &&
                withContext(dispatchers.default) { sha256(back) }.contentEquals(made.digest)
            if (!same) {
                failed(Failure.CHECK, target)
                return@launch
            }
            val name = files.name(target) ?: BackupFiles.suggestedName(clock.wallMs())
            val place = files.place(target)
            // "Last backup" and when a backup is due (architecture.md §4.3). The file is saved and
            // checked whatever this save does; a failure shows again as a backup that is due.
            repository.edit(VaultEdits.noteBackup(target.uri, name))
            files.finish(FileTask.Kind.BACKUP)
            show(BackupStep.Saved(name, made.accounts, place), paused = false)
        }
    }

    /** The write or its check failed: the task is given up, and the file this task wrote into is removed. */
    private suspend fun failed(why: Failure, target: DocRef) {
        val cleanup = files.abandon(FileTask.Kind.BACKUP, written = true)
        doc = null
        show(BackupStep.Failed(why, cleanup, nameIfLeft(cleanup, target), Retry.CHOOSE), paused = false)
    }

    private suspend fun nameIfLeft(cleanup: Cleanup, target: DocRef): String? =
        if (cleanup is Cleanup.Left) files.name(target) ?: BackupFiles.suggestedName(clock.wallMs()) else null

    private fun waitUntil(untilElapsedMs: Long) {
        show(BackupStep.Password(waitUntilElapsedMs = untilElapsedMs))
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val left = untilElapsedMs - clock.elapsedMs()
                if (left <= 0) break
                val seconds = ((left + SECOND_MS - 1) / SECOND_MS).toInt()
                mutableUi.value = mutableUi.value.copy(waitSeconds = seconds)
                clock.delayUntilElapsed(untilElapsedMs - (seconds - 1) * SECOND_MS)
            }
            mutableUi.value = mutableUi.value.copy(waitSeconds = null)
        }
    }

    private fun waiting(now: BackupStep.Password): Boolean {
        val until = now.waitUntilElapsedMs ?: return false
        return clock.elapsedMs() < until
    }

    private fun show(next: BackupStep, paused: Boolean = mutableUi.value.paused) {
        val waits = next is BackupStep.Password && next.waitUntilElapsedMs != null
        if (!waits) {
            ticker?.cancel()
            ticker = null
        }
        mutableUi.value = BackupUi(next, paused = paused && doc != null, waitSeconds = if (waits) mutableUi.value.waitSeconds else null)
    }

    /** The app is locked: what was typed, the entry and the backup go; the screen starts again after the unlock. */
    private fun locked() {
        work?.cancel()
        work = null
        wipe()
        doc = files.task.value?.takeIf { it.kind == FileTask.Kind.BACKUP }?.doc
        show(BackupStep.Password(), paused = doc != null)
    }

    private fun wipe() {
        password.clearSecret()
        dropProof()
        backup = null
        ticker?.cancel()
        ticker = null
    }

    private fun dropProof() {
        proof?.drop()
        proof = null
    }

    private fun dropped(entry: MasterProof): Boolean = try {
        entry.useBytes { }
        false
    } catch (e: IllegalStateException) {
        true
    }

    /** The backup made in step 3: its bytes, their SHA-256, and how many accounts it holds outside Recently deleted. */
    private class Made(val bytes: ByteArray, val digest: ByteArray, val accounts: Int)

    private sealed interface Sealing {
        class Made(val made: BackupViewModel.Made) : Sealing

        /** The entry was dropped before it was used (the app stopped being visible). */
        data object Dropped : Sealing

        data object NoMemory : Sealing

        data object Failed : Sealing
    }

    companion object {
        /** The step a backup's `FileTask` is recorded at: its place is being chosen or written. */
        const val STEP_SAVE = 1

        private const val SECOND_MS = 1_000L
        private const val ZERO = '\u0000'

        /** SHA-256 of [bytes]. */
        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        /** The backup screen of the open app, with [offered], the entry of "Make a backup now?" ([BackupOffer.take]). */
        fun create(graph: AppGraph, offered: MasterProof?): BackupViewModel = BackupViewModel(
            lock = graph.lock,
            repository = graph.data.repository,
            files = BackupFiles(FileTaskStore.of(graph), graph.files, graph.dispatchers.io),
            settings = graph.settings,
            clock = graph.platform.clock,
            dispatchers = graph.dispatchers,
            offered = offered,
        )
    }
}

/**
 * What the backup screen shows: the [step], and whether it continues a backup that a lock or a new
 * process cut short ([paused]: "This was paused when the app locked."). [waitSeconds] counts down
 * a master-password wait.
 */
data class BackupUi(val step: BackupStep, val paused: Boolean = false, val waitSeconds: Int? = null)

/** The steps of a backup (ux.md §5.19). */
sealed interface BackupStep {
    /** Step 1, the first time only. */
    data object Explain : BackupStep

    /** A new master password is pending (lock.md F11 step 10): "You can make a backup when …". */
    data object Pending : BackupStep

    /** Step 2: the field; [checking] while the lock checks it; [error] about the last try; a wait until [waitUntilElapsedMs]. */
    data class Password(val checking: Boolean = false, val error: PasswordError? = null, val waitUntilElapsedMs: Long? = null) : BackupStep

    /** A step that runs: making the backup, saving it, checking it, or giving it up. */
    data class Working(val phase: Phase) : BackupStep

    /** Step 3: the backup is made; "Save as" is open ([opening]) or was closed without a place. */
    data class Choose(val opening: Boolean) : BackupStep

    /** Step 5: saved and checked, as [name] (the name the place shows), with [accounts], in [place]. */
    data class Saved(val name: String, val accounts: Int, val place: Place, val sendFailed: Boolean = false) : BackupStep {
        override fun toString(): String = "Saved(accounts=$accounts, place=${place::class.simpleName}, sendFailed=$sendFailed)"
    }

    /**
     * It failed, [why]; [cleanup] is what became of the file of a failed write or check ([name]: its
     * name when it is left for the user to delete). [retry] says what "Try again" does.
     */
    data class Failed(val why: Failure, val cleanup: Cleanup?, val name: String?, val retry: Retry) : BackupStep {
        override fun toString(): String = "Failed(why=$why, cleanup=${cleanup?.let { it::class.simpleName }}, retry=$retry)"
    }

    /** An unfinished backup was given up: its empty file was removed, or it is left for the user ([name]). */
    data class Stopped(val cleanup: Cleanup, val name: String?) : BackupStep {
        override fun toString(): String = "Stopped(cleanup=${cleanup::class.simpleName})"
    }
}

/** What a [BackupStep.Working] step does. */
enum class Phase {
    /** "Making the backup…": sealed and opened once (step 3). */
    MAKING,

    /** "Saving…" (step 4). */
    SAVING,

    /** "Checking the saved file…" (step 4). */
    CHECKING,

    /** The task is given up and its empty file removed. */
    STOPPING,
}

/** What the last master password came to (lock.md §13.3). */
sealed interface PasswordError {
    data object Wrong : PasswordError

    data object NoMemory : PasswordError

    /** A master password from before the change of [changedAtMs] (lock.md F16b). */
    data class Older(val changedAtMs: Long, val unfinished: Boolean) : PasswordError
}

/** Why a backup was not saved (ux.md §5.19 "Errors"). */
enum class Failure {
    /** The backup could not be made or did not open with the password. */
    NOT_MADE,

    /** Too little memory to make it. */
    NO_MEMORY,

    /** "Save as" could not be opened. */
    PICKER,

    /** The place did not take the file. */
    WRITE,

    /** The file read back differs from the backup. */
    CHECK,
}

/** What "Try again" does after a failure. */
enum class Retry {
    /** The backup is made: "Choose another place". */
    CHOOSE,

    /** It is not: back to the master password. */
    PASSWORD,
}
