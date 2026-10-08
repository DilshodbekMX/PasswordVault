package io.github.dilshodbekmx.passwordvault.ui.transfer

import android.os.Build
import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.files.Cleanup
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickResult
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The export (ux.md §5.20; formats.md §2 rule 1; lock.md F12), in the order formats.md gives:
 * choose a format → for a file that is not protected, the full-screen warning with "I understand"
 * → the **master password, typed every time** → the system's "Save as" → the file written and read
 * back → the result, with "Done" as the main button and "Delete it now".
 *
 * - **The master password** goes to `LockManager.submitMasterPassword` for [MpPurpose.EXPORT],
 *   under the lock's guard; a right one counts as practice there. Nothing of the export depends on
 *   its proof, which is dropped at once: the master password was typed now, and the file picker's
 *   `ON_STOP` drops every proof anyway (lock.md §5.8). What a right password allows is held here
 *   only, for this screen, until the export ends: one "Save as", and the next files of a CSV export
 *   cut into parts. A lock (any state but unlocked), a closed picker or leaving the screen ends it,
 *   and nothing else ever stands in for it: not a PIN, not a fingerprint, not the proof of an unlock
 *   (`LockUiState.Unlocked.proof`), not an earlier export.
 * - **The file task** (architecture.md §3.5, ux.md §4): "Save as" goes through the process's
 *   [FileTaskStore], which records the task before the picker opens and its document when the
 *   picker returns one. When the export is cut short by a lock or a new process, this screen is
 *   opened again (its route survives) or later from Settings, finds the task in
 *   [FileTaskStore.task], says "This was paused when the app locked." and asks for the master
 *   password again before it writes, because it is never kept (ux.md §4 item 2). "Cancel" gives the
 *   task up and says what became of the empty file (§4 item 3).
 * - **The note in the vault** is made just before the write (`VaultEdits.notePlainExport`, with the
 *   name the place shows), so that the list's banner "You have an unprotected file with your
 *   passwords" covers the file from the moment it may hold anything. It stays until "Delete it now"
 *   here, or "Delete" or "I deleted it myself" on that banner, removes it; a write that fails takes
 *   it back only when the file is gone.
 * - **The writes** run in [work], the app's scope, and to their end: leaving the screen or a lock
 *   never cuts a file or the vault's note in half.
 *
 * Neither an export nor a backup can be made while a new master password is pending (lock.md F11
 * step 10, F12 step 2): the screen says so instead.
 *
 * @param work the app's scope (`AppGraph.appScope`), for the checks and writes that must finish.
 * @param generator the JSON file's `generator`, `BuildInfo.generator`.
 * @param oldAndroid Android 7 to 10, where the warning adds that other apps can read Downloads.
 * @param zone the phone's time zone, for the Excel file's dates and the files' names.
 */
class ExportViewModel(
    private val repository: VaultRepository,
    private val lock: LockManager,
    private val store: FileTaskStore,
    private val documents: DocumentIo,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val work: CoroutineScope,
    private val generator: String,
    private val oldAndroid: Boolean,
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
) : ViewModel() {

    private val ui = MutableStateFlow<ExportUiState>(ExportUiState.Choose())

    /** What the screen shows. Nothing in it is secret: no password, no file content. */
    val state: StateFlow<ExportUiState> = ui.asStateFlow()

    /**
     * The master-password field: held here and never in saved state (lock.md §5.8, architecture.md
     * §5.6). Emptied with its undo history as soon as it is read, and at every lock.
     */
    val password = TextFieldState()

    private val eventChannel = Channel<ExportEvent>(Channel.BUFFERED)

    /** What the screen does once: open the backup's screen. */
    val events: Flow<ExportEvent> = eventChannel.receiveAsFlow()

    /** The master password was typed right in this export, since the last lock (see the class comment). */
    private var authorized = false

    /** A file is being written: the picker's answers that come meanwhile are this file's. */
    private var writing = false

    private var countdown: Job? = null

    /** The warning as it was left, for Back from the master password. */
    private var warning: ExportUiState.Warning? = null

    /** The Excel file's texts in the language of the screen ([useLabels]). */
    @Volatile
    private var labels: VaultXlsx.Labels = VaultXlsx.Labels()

    init {
        // A task that a lock or a new process cut short (ux.md §4 "Unfinished file tasks").
        val recorded = store.task.value?.takeIf { it.kind == FileTask.Kind.EXPORT }
        if (recorded != null) {
            val file = ExportFile.of(recorded.step)
            val doc = recorded.doc
            if (file != null && doc != null) {
                ui.value = ExportUiState.Password(file, paused = true)
                showNameOf(doc, file)
            } else {
                // The picker's answer never came (the app was swiped away while it was open), or the
                // step is not one of this version's: there is nothing to continue.
                work.launch { store.abandon(FileTask.Kind.EXPORT) }
            }
        }
        viewModelScope.launch {
            lock.state.collect { if (it !is LockUiState.Unlocked) locked() }
        }
        viewModelScope.launch {
            lock.status.map { it.pending != null }.distinctUntilChanged().collect { pending -> if (pending) pendingNow() }
        }
        viewModelScope.launch {
            store.results(FileTask.Kind.EXPORT).collect(::onResult)
        }
    }

    // ---- Choose a format ----

    /** A row of "Choose a format". */
    fun choose(choice: ExportChoice) {
        if (ui.value is ExportUiState.Choose) ui.value = ExportUiState.Choose(choice)
    }

    /** "Continue": the backup's own screen, or the warning of a format that is not protected. */
    fun continueFromChoice() {
        val now = ui.value as? ExportUiState.Choose ?: return
        if (lock.status.value.pending != null) {
            pendingNow()
            return
        }
        val format = now.choice.format
        if (format == null) {
            eventChannel.trySend(ExportEvent.OpenBackup)
            return
        }
        showWarning(ExportUiState.Warning(format, oldAndroid = oldAndroid))
    }

    // ---- The warning ----

    /** The "I understand" box. */
    fun setUnderstood(understood: Boolean) {
        val now = ui.value as? ExportUiState.Warning ?: return
        ui.value = now.copy(understood = understood)
    }

    /** "See which" and "Hide the list" on the CSV card. */
    fun toggleWhich() {
        val now = ui.value as? ExportUiState.Warning ?: return
        ui.value = now.copy(showWhich = !now.showWhich)
    }

    /** "Continue" on the warning: only once "I understand" is ticked (ux.md §5.20). */
    fun continueFromWarning() {
        val now = ui.value as? ExportUiState.Warning ?: return
        if (!now.understood) return
        if (lock.status.value.pending != null) {
            pendingNow()
            return
        }
        warning = now
        password.clearSecret()
        ui.value = ExportUiState.Password(ExportFile(now.format))
    }

    // ---- The master password ----

    /**
     * "Continue" (or the keyboard's Done) on the master password: the typed characters go to the
     * lock for [MpPurpose.EXPORT], and the field is emptied at once. A right one opens "Save as", or,
     * for a task that was paused, writes into the document it already has.
     */
    fun submitPassword() {
        val now = ui.value as? ExportUiState.Password ?: return
        if (!now.enabled || password.text.isEmpty()) return
        if (lock.status.value.pending != null) {
            pendingNow()
            return
        }
        val chars = password.readSecret()
        password.clearSecret()
        countdown?.cancel()
        ui.value = now.copy(checking = true, error = null, waitSecondsLeft = null)
        work.launch {
            val result = try {
                lock.submitMasterPassword(chars, MpPurpose.EXPORT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // the lock failed in a way it does not report: nothing was proved
            } finally {
                chars.fill('\u0000')
            }
            // Nothing here needs the proof (see the class comment).
            (result as? MpResult.Right)?.proof?.drop()
            val still = ui.value as? ExportUiState.Password
            // A lock while the lock checked: the screen starts again after the unlock.
            if (still == null || !still.checking || still.file != now.file) return@launch
            when (result) {
                is MpResult.Right -> passwordRight(still)
                MpResult.Wrong -> ui.value = still.copy(checking = false, error = ExportPasswordError.Wrong)
                is MpResult.Wait -> startWait(still.copy(checking = false), result.untilElapsedMs)
                MpResult.NoMemory -> ui.value = still.copy(checking = false, error = ExportPasswordError.NoMemory)
                is MpResult.OlderPassword -> ui.value = still.copy(checking = false, error = ExportPasswordError.Older(result.changedAtMs))
                MpResult.TooLate, MpResult.NotFinished, null -> ui.value = still.copy(checking = false) // NotFinished: the lock screen's answer only
            }
        }
    }

    /** "Cancel" on a task that was paused: it is given up, and the screen says what became of its file (ux.md §4 item 3). */
    fun cancelPaused() {
        val now = ui.value as? ExportUiState.Password ?: return
        if (!now.paused || now.checking) return
        forgetPassword()
        ui.value = ExportUiState.Ended(null)
        work.launch { ui.value = ExportUiState.Ended(giveUp()) }
    }

    // ---- The result ----

    /** "Save file 2" after a file of a CSV export cut into parts. */
    fun nextPart() {
        val now = ui.value as? ExportUiState.Saved ?: return
        if (now.last || now.deletion == ExportDeletion.DELETING) return
        val next = ExportFile(ExportFormat.CSV, now.file.part + 1)
        if (authorized) openPicker(next) else ui.value = ExportUiState.Password(next)
    }

    /**
     * "Delete it now" (ux.md §5.20): `DocumentIo.delete`; when the place allows it, the vault's note
     * goes and with it the list's banner. A place that does not allow it is said so, and the note
     * stays, so the banner keeps reminding.
     */
    fun deleteNow() {
        val now = ui.value as? ExportUiState.Saved ?: return
        if (now.secondary != ExportAction.DELETE_NOW || now.deletion == ExportDeletion.DELETING) return
        ui.value = now.copy(deletion = ExportDeletion.DELETING)
        work.launch {
            val deleted = withContext(dispatchers.io) {
                try {
                    documents.delete(now.doc)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            if (deleted) {
                withContext(NonCancellable) { forgetNote(now.doc) }
                release(now.doc)
            }
            val after = ui.value as? ExportUiState.Saved
            if (after != null && after.doc == now.doc) {
                ui.value = after.copy(deletion = if (deleted) ExportDeletion.DELETED else ExportDeletion.NOT_ALLOWED)
            }
        }
    }

    /** "Try again" after a file that was not saved: "Save as" again, or the master password after a lock. */
    fun tryAgain() {
        val now = ui.value as? ExportUiState.Failed ?: return
        if (!now.canRetry) return
        if (authorized) openPicker(now.file) else ui.value = ExportUiState.Password(now.file)
    }

    // ---- Back, texts ----

    /**
     * Back, from the top bar or the system. True when the export took it: a step back within the
     * export, "Cancel" on a paused task, no more waiting for a picker that is not in front, or nothing
     * while a file is saved (ux.md §4: Back is ignored while "Saving…" runs). False: the screen is left.
     */
    fun back(): Boolean = when (val now = ui.value) {
        is ExportUiState.Warning -> {
            ui.value = ExportUiState.Choose(now.format.choice)
            true
        }
        is ExportUiState.Password -> when {
            now.checking -> true
            now.paused -> {
                cancelPaused()
                true
            }
            now.file.part > 1 -> false
            else -> {
                forgetPassword()
                val kept = warning?.takeIf { it.format == now.file.format }
                showWarning(kept ?: ExportUiState.Warning(now.file.format, oldAndroid = oldAndroid))
                true
            }
        }
        is ExportUiState.Picking -> {
            stopPicking(now.file)
            true
        }
        is ExportUiState.Saving -> true
        is ExportUiState.Ended -> now.cleanup == null
        else -> false
    }

    /** Whether [back] takes Back in [state], for the system's Back gesture: false lets the screen go. */
    fun takesBack(state: ExportUiState): Boolean = when (state) {
        is ExportUiState.Warning, is ExportUiState.Picking, is ExportUiState.Saving -> true
        is ExportUiState.Password -> state.checking || state.paused || state.file.part == 1
        is ExportUiState.Ended -> state.cleanup == null
        else -> false
    }

    /** The Excel file's texts in the language the screen shows (formats.md §6.1: the sheet's name and the headings are localised). */
    fun useLabels(labels: VaultXlsx.Labels) {
        this.labels = labels
    }

    override fun onCleared() {
        forgetPassword()
    }

    // ---- Inside ----

    private fun showWarning(next: ExportUiState.Warning) {
        ui.value = next
        if (next.format != ExportFormat.CSV || next.csv != null) return
        val vault = openVault() ?: return
        viewModelScope.launch {
            val summary = withContext(dispatchers.default) { ExportFiles.csvSummary(vault) }
            val now = ui.value as? ExportUiState.Warning
            if (now != null && now.format == ExportFormat.CSV && now.csv == null) ui.value = now.copy(csv = summary)
        }
    }

    /** A right master password: "Save as", or the document a paused task already has. */
    private fun passwordRight(now: ExportUiState.Password) {
        authorized = true
        val task = store.task.value
        val doc = task?.doc
        if (now.paused && task?.kind == FileTask.Kind.EXPORT && doc != null && ExportFile.of(task.step) == now.file) {
            save(doc, now.file)
        } else {
            openPicker(now.file)
        }
    }

    /** "Save as" for [file], with the name formats.md §2 rule 5 gives and the MIME type that keeps its extension. */
    private fun openPicker(file: ExportFile) {
        val vault = openVault()
        if (vault == null) {
            // Locked meanwhile: nothing opens, and the next try needs the master password again.
            authorized = false
            ui.value = ExportUiState.Password(file)
            return
        }
        ui.value = ExportUiState.Picking(file)
        work.launch {
            val parts = withContext(dispatchers.default) { ExportFiles.parts(file.format, vault) }
            val name = file.suggestedName(parts, clock.wallMs(), zone())
            // A new task replaces the recorded one, so a task of the export left from before is given
            // up first, with its empty file (architecture.md §3.5).
            if (store.task.value?.kind == FileTask.Kind.EXPORT) giveUp()
            val opened = store.create(FileTask(FileTask.Kind.EXPORT, file.step, null), name, file.format.mime)
            if (!opened && ui.value == ExportUiState.Picking(file)) {
                authorized = false
                ui.value = ExportUiState.Failed(file, ExportFailure.PICKER)
            }
        }
    }

    /**
     * The screen shows while "Save as" should be open, and the user goes back: the picker is gone
     * without an answer (it was closed in a way that sent none). The task is given up; an answer that
     * still comes finds no task, and the store removes the empty file it made. The next "Save as"
     * needs the master password again, as after a closed picker.
     */
    private fun stopPicking(file: ExportFile) {
        authorized = false
        ui.value = ExportUiState.Password(file)
        work.launch {
            val task = store.task.value
            if (task?.kind == FileTask.Kind.EXPORT && task.doc == null) store.abandon(FileTask.Kind.EXPORT)
        }
    }

    /** What the file picker returned for the export (`FileTaskStore.results`). */
    private fun onResult(result: PickResult) {
        val now = ui.value
        when (result) {
            is PickResult.Cancelled -> if (now is ExportUiState.Picking) {
                // No message: nothing was created (ux.md §5.19). The next "Save as" needs the master password again.
                authorized = false
                ui.value = ExportUiState.Password(now.file)
            }
            is PickResult.Picked -> {
                if (writing) return
                val file = ExportFile.of(result.task.step)
                when {
                    file == null -> work.launch { store.abandon(FileTask.Kind.EXPORT) }
                    now is ExportUiState.Picking && authorized -> save(result.doc, file)
                    now is ExportUiState.Pending -> Unit // pendingNow gives the task up
                    now is ExportUiState.Password && now.paused && now.file == file -> Unit
                    else -> {
                        // The answer came after a lock, or to a screen opened again: the master
                        // password is asked again before anything is written (ux.md §4 item 2).
                        forgetPassword()
                        ui.value = ExportUiState.Password(file, paused = true)
                        showNameOf(result.doc, file)
                    }
                }
            }
        }
    }

    /** Writes [file] into [doc] and shows how it went; runs to its end in [work]. */
    private fun save(doc: DocRef, file: ExportFile) {
        writing = true
        ui.value = ExportUiState.Saving(file)
        work.launch {
            val next = try {
                withContext(NonCancellable) { saveNow(doc, file) }
            } finally {
                writing = false
            }
            ui.value = next
        }
    }

    private suspend fun saveNow(doc: DocRef, file: ExportFile): ExportUiState {
        // Locked meanwhile: the task waits, recorded, for the next unlock and the master password.
        val vault = openVault() ?: return ExportUiState.Password(file, paused = true)
        val nowMs = clock.wallMs()
        val zone = zone()
        val labels = labels
        val built = withContext(dispatchers.default) { ExportFiles.build(file, vault, nowMs, zone, generator, labels) }
        val name = nameOf(doc) ?: file.suggestedName(built?.parts ?: 1, nowMs, zone)
        if (built == null) {
            // The vault became smaller since its files were counted: this one is no longer there.
            return ExportUiState.Failed(file, ExportFailure.WRITE, ExportCleanup.of(store.abandon(FileTask.Kind.EXPORT), name))
        }
        try {
            // The note first: from now on the list's banner covers the file (see the class comment).
            val noted = repository.edit(VaultEdits.notePlainExport(doc.uri, name))
            if (noted is SaveResult.NotSaved) {
                val why = if (noted.problem == SaveProblem.TOO_LARGE) ExportFailure.TOO_LARGE else ExportFailure.NOT_NOTED
                return ExportUiState.Failed(file, why, ExportCleanup.of(store.abandon(FileTask.Kind.EXPORT), name))
            }
            if (writeFile(built.bytes)) {
                store.finish(FileTask.Kind.EXPORT)
                return ExportUiState.Saved(file, built.parts, doc, name, placeOf(doc), built.replaced, built.truncated)
            }
            // "Couldn't save to this place": the file is deleted when the place allows it (formats.md
            // §2 rule 3). One that stays may hold part of the export, so its note stays as well.
            val cleanup = store.abandon(FileTask.Kind.EXPORT, written = true)
            if (cleanup == Cleanup.Removed || cleanup == Cleanup.NoDocument) forgetNote(doc)
            return ExportUiState.Failed(file, ExportFailure.WRITE, ExportCleanup.of(cleanup, name))
        } finally {
            built.wipe()
        }
    }

    /**
     * Writes [bytes] into the export task's document through the store (`FileTaskStore.write`), which
     * first records that the task's write began, so that a write cut off by a lock or a new process is
     * removed when the task is given up; then the bytes are read back and compared
     * (`DocumentIo.write`, [V-S6]). The one place where the export writes a file.
     */
    private suspend fun writeFile(bytes: ByteArray): Boolean = try {
        store.write(FileTask.Kind.EXPORT, bytes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Gives up the persisted permission on [doc] once the vault no longer notes it
     * (`DocumentIo.release`, architecture.md §3.5): "Delete it now" here. The task's own end keeps it,
     * because the vault notes the file (`FileTaskStore.finish`); giving the task up releases it there.
     */
    private suspend fun release(doc: DocRef) {
        withContext(NonCancellable + dispatchers.io) { documents.release(doc) }
    }

    /** Gives up the recorded task of the export and says what became of its document. */
    private suspend fun giveUp(): ExportCleanup {
        val task = store.task.value?.takeIf { it.kind == FileTask.Kind.EXPORT }
        val doc = task?.doc
        val file = task?.let { ExportFile.of(it.step) }
        val name = doc?.let { nameOf(it) } ?: file?.suggestedName(file.part, clock.wallMs(), zone())
        val cleanup = withContext(NonCancellable) {
            store.abandon(FileTask.Kind.EXPORT).also { if (doc != null && it == Cleanup.Removed) forgetNote(doc) }
        }
        return ExportCleanup.of(cleanup, name.orEmpty())
    }

    /** The vault no longer notes [doc] (the file is gone): the list's banner for it goes. */
    private suspend fun forgetNote(doc: DocRef) {
        repository.edit(VaultEdits.forgetPlainExport(doc.uri))
    }

    /** The name the place shows for [doc]; null when it cannot be read. */
    private suspend fun nameOf(doc: DocRef): String? = withContext(dispatchers.io) {
        try {
            documents.name(doc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun placeOf(doc: DocRef): Place = withContext(dispatchers.io) {
        try {
            documents.place(doc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Place.Other("") // unknown: "may upload the file later" is the careful line
        }
    }

    /** Shows the name of a paused task's file when the place gives it. */
    private fun showNameOf(doc: DocRef, file: ExportFile) {
        viewModelScope.launch {
            val name = nameOf(doc) ?: return@launch
            val now = ui.value as? ExportUiState.Password ?: return@launch
            if (now.paused && now.file == file) ui.value = now.copy(name = name)
        }
    }

    private fun openVault(): Vault? = (repository.state.value as? VaultState.Unlocked)?.vault

    /** The app locked: what the master password allowed ends, and the field is emptied. */
    private fun locked() {
        forgetPassword()
        val now = ui.value
        if (now is ExportUiState.Password && (now.checking || now.error != null)) {
            ui.value = now.copy(checking = false, error = null, waitSecondsLeft = null)
        }
    }

    private fun forgetPassword() {
        authorized = false
        password.clearSecret()
        countdown?.cancel()
        countdown = null
    }

    /**
     * A new master password is pending (lock.md F11 step 10): no export. Before a file is chosen the
     * screen says so; a task that was paused is given up. A file being chosen or written ends as it is.
     */
    private fun pendingNow() {
        if (writing) return
        when (ui.value) {
            is ExportUiState.Choose, is ExportUiState.Warning, is ExportUiState.Password -> Unit
            else -> return
        }
        forgetPassword()
        ui.value = ExportUiState.Pending()
        if (store.task.value?.kind == FileTask.Kind.EXPORT) {
            work.launch {
                val cleanup = giveUp()
                if (ui.value is ExportUiState.Pending) ui.value = ExportUiState.Pending(cleanup)
            }
        }
    }

    /** "Wait 30 seconds, then try again." with the seconds left; the field is off until then (lock.md §6.8). */
    private fun startWait(base: ExportUiState.Password, untilElapsedMs: Long) {
        countdown?.cancel()
        val first = untilElapsedMs - clock.elapsedMs()
        if (first <= 0) {
            ui.value = base
            return
        }
        ui.value = base.copy(error = ExportPasswordError.Wait, waitSecondsLeft = secondsLeft(first))
        countdown = viewModelScope.launch {
            while (true) {
                val left = untilElapsedMs - clock.elapsedMs()
                if (left <= 0) break
                clock.delayUntilElapsed(untilElapsedMs - (secondsLeft(left) - 1) * SECOND_MS)
                val rest = untilElapsedMs - clock.elapsedMs()
                if (rest <= 0) break
                val now = ui.value as? ExportUiState.Password ?: return@launch
                ui.value = now.copy(waitSecondsLeft = secondsLeft(rest))
            }
            val now = ui.value as? ExportUiState.Password ?: return@launch
            if (now.error == ExportPasswordError.Wait) ui.value = now.copy(error = null, waitSecondsLeft = null)
        }
    }

    companion object {
        private const val SECOND_MS = 1_000L

        /** Whole seconds left, rounded up: 30 at the start of a wait, 1 in its last second. */
        private fun secondsLeft(ms: Long): Int = ((ms + SECOND_MS - 1) / SECOND_MS).toInt()

        /** The ViewModel of the app's export screen, over [graph]'s parts. */
        fun create(graph: AppGraph): ExportViewModel = ExportViewModel(
            repository = graph.data.repository,
            lock = graph.lock,
            store = FileTaskStore.of(graph),
            documents = graph.files,
            clock = graph.platform.clock,
            dispatchers = graph.dispatchers,
            work = graph.appScope,
            generator = graph.build.generator,
            oldAndroid = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q,
        )
    }
}

/** What the export screen shows (ux.md §5.20). Nothing in it is secret: names of files and accounts at most. */
sealed interface ExportUiState {
    /** "You can export your passwords when your new master password has started working." */
    data class Pending(
        /** What became of the file of a task that was paused, given up because of it; null when there was none. */
        val cleanup: ExportCleanup? = null,
    ) : ExportUiState

    /** "Choose a format", with [choice] selected. */
    data class Choose(val choice: ExportChoice = ExportChoice.BACKUP) : ExportUiState

    /** The full-screen warning of a [format] that is not protected (formats.md §2, "Warning text"). */
    data class Warning(
        val format: ExportFormat,
        /** "I understand" is ticked: "Continue" works only then. */
        val understood: Boolean = false,
        /** Android 7 to 10: "On this phone other apps with storage access can read files in Downloads." */
        val oldAndroid: Boolean,
        /** The card about Google Password Manager, for CSV; null while it is counted, and for the other formats. */
        val csv: ExportCsvSummary? = null,
        /** "See which" shows the names. */
        val showWhich: Boolean = false,
    ) : ExportUiState

    /** The master password before "Save as" of [file]. */
    data class Password(
        val file: ExportFile,
        /** The export was cut short by a lock and continues: "This was paused when the app locked." */
        val paused: Boolean = false,
        /** The name of a paused task's file, once the place gave it. */
        val name: String? = null,
        /** The lock checks what was typed. */
        val checking: Boolean = false,
        val error: ExportPasswordError? = null,
        /** During a wait of the lock's guard, the seconds left. */
        val waitSecondsLeft: Int? = null,
    ) : ExportUiState {
        /** The field and its button take input: nothing is checked and nothing waits. */
        val enabled: Boolean get() = !checking && waitSecondsLeft == null
    }

    /** The system's "Save as" is open for [file]. */
    data class Picking(val file: ExportFile) : ExportUiState

    /** [file] is written and read back: "Saving and checking the file…". */
    data class Saving(val file: ExportFile) : ExportUiState

    /** [file] was saved and checked, as [name] in [doc] at [place]; the export has [parts] files. */
    data class Saved(
        val file: ExportFile,
        val parts: Int,
        val doc: DocRef,
        val name: String,
        val place: Place,
        /** Unpaired surrogates written as U+FFFD (formats.md §2 rule 8). */
        val replaced: Int = 0,
        /** Excel cells cut at 32,767 characters (formats.md §6.2). */
        val truncated: Int = 0,
        /** What "Delete it now" did; null before it was tapped. */
        val deletion: ExportDeletion? = null,
    ) : ExportUiState {
        /** The last file of the export. */
        val last: Boolean get() = file.part >= parts

        /** The main button (a filled `Button`): "Done", the user just asked for this file (ux.md §5.20); "Save file 2" between parts. */
        val primary: ExportAction get() = if (last) ExportAction.DONE else ExportAction.NEXT_PART

        /** The second button (an `OutlinedButton`): "Delete it now", until it was tapped. */
        val secondary: ExportAction?
            get() = if (deletion == null || deletion == ExportDeletion.DELETING) ExportAction.DELETE_NOW else null

        /** Google Drive or another app's place, which may upload the file later (owner's decision 8 of 2026-10-08). */
        val mayUploadLater: Boolean get() = place !is Place.PhoneStorage
    }

    /** No file was saved: [why], and what became of the file the picker made. */
    data class Failed(val file: ExportFile, val why: ExportFailure, val cleanup: ExportCleanup? = null) : ExportUiState {
        /** "Try again" can help: not when the vault is too large to note the file. */
        val canRetry: Boolean get() = why != ExportFailure.TOO_LARGE
    }

    /** A paused task was given up: what became of its file; null while that is done. */
    data class Ended(val cleanup: ExportCleanup?) : ExportUiState
}

/** What the master password came to, under the field (lock.md §13.3). */
sealed interface ExportPasswordError {
    data object Wrong : ExportPasswordError

    /** The lock's guard makes the field wait; the seconds are in [ExportUiState.Password.waitSecondsLeft]. */
    data object Wait : ExportPasswordError

    data object NoMemory : ExportPasswordError

    /** The master password from before the change of [changedAtMs] (lock.md F16b). */
    data class Older(val changedAtMs: Long) : ExportPasswordError
}

/** Why no file was saved. */
enum class ExportFailure {
    /** The file picker could not be opened. */
    PICKER,

    /** Writing failed, or the file read back was not what was written. */
    WRITE,

    /** The vault's note of the file could not be saved, so nothing was written. */
    NOT_NOTED,

    /** The same because the vault no longer fits in one file (architecture.md §3.5). */
    TOO_LARGE,
}

/** What became of the document "Save as" made, for the sentence of ux.md §4 item 3. */
sealed interface ExportCleanup {
    /** It was removed. */
    data object Removed : ExportCleanup

    /** It could not be removed: the user deletes [name] at [place] themselves. */
    data class Left(val name: String, val place: Place) : ExportCleanup

    /** Nothing to say: there was no document, or it is the user's own file, left as it was. */
    data object NoneLeft : ExportCleanup

    companion object {
        /** The sentence's case for what `FileTaskStore.abandon` did with the file named [name]. */
        fun of(cleanup: Cleanup, name: String): ExportCleanup = when (cleanup) {
            Cleanup.Removed -> Removed
            is Cleanup.Left -> Left(name, cleanup.place)
            Cleanup.Kept, Cleanup.NoDocument -> NoneLeft
        }
    }
}

/** What "Delete it now" did. */
enum class ExportDeletion { DELETING, DELETED, NOT_ALLOWED }

/** The buttons of the result. */
enum class ExportAction { DONE, NEXT_PART, DELETE_NOW }

/** What the screen does once. */
sealed interface ExportEvent {
    /** "Backup file (recommended)": the backup's own screen (ux.md §5.19). */
    data object OpenBackup : ExportEvent
}
