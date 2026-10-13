package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.formats.FileKind
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlanner
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickResult
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import io.github.dilshodbekmx.passwordvault.ui.setup.PinSetupStart
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Restore from a backup file" (ux.md §5.2; lock.md F13, F17 step 3; architecture.md §4.12), from
 * Welcome on a phone without passwords, and from the lock screen ("Forgot master password?", "Your
 * passwords can't be opened"), where the passwords on the phone are put aside, never deleted.
 *
 * 1. **The file**, through the process's file task (`FileTask.Kind.RESTORE`, the picker for any
 *    type): it is recognised by its content, not its extension (`FileKind`); a file that is no
 *    backup of this app is refused before any password is typed.
 * 2. **The master password the backup was made with**, without waits: the file is in the user's
 *    hands anyway (lock.md §6.8). [RestoreStep.Password] says why a try failed; "This file may be
 *    damaged, or the password is wrong." allows another try (architecture.md §4.4).
 * 3. **"Opening…"**: `VaultRepository.openBackup` (the header is bounds-checked before any key
 *    derivation, [V-C4]). Nothing on the phone changes before the backup has opened and checked.
 * 4. **The summary**: how many accounts and when the backup was made; "Restore", or, with passwords
 *    on the phone, "Your current passwords stay on this phone, locked." and "Use this backup"
 *    ([overCurrent]).
 * 5. `LockManager.createVault(typed, typed, backup)`: the lock writes the backup as this phone's
 *    vault with a new key and proves it from disk (F13 step 4), puts a vault on the phone aside
 *    (F17 step 3), and unlocks; setup continues at the PIN (F13 step 6), so this screen leaves by
 *    itself.
 *
 * The typed password is kept from "Open backup" to the end of the restore, in a `CharArray` zeroed
 * when it is used or dropped; the backup's vault likewise. Both go when the screen is left or the
 * file is given up. A file recorded by a restore that a new process cut short is read again when
 * this screen opens ([FileTask] with its document); the store opens no screen by itself.
 */
class RestoreViewModel(
    private val lock: LockManager,
    private val repository: VaultRepository,
    private val files: BackupFiles,
) : ViewModel() {

    /** The backup's master-password field (step 2). */
    val password = TextFieldState()

    private val mutableStep = MutableStateFlow<RestoreStep>(RestoreStep.Start())

    /** What the screen shows. */
    val step: StateFlow<RestoreStep> = mutableStep.asStateFlow()

    private val mutableLeave = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The screen closes ([leave] was called and the task is given up). */
    val leave: SharedFlow<Unit> = mutableLeave.asSharedFlow()

    /** Passwords are on this phone (the lock screen's restore): they are put aside, and the summary says so. */
    val overCurrent: Boolean
        get() = when (lock.state.value) {
            is LockUiState.Locked, is LockUiState.Unreadable -> true
            else -> false
        }

    private var doc: DocRef? = null
    private var bytes: ByteArray? = null
    private var typed: CharArray? = null
    private var opened: Vault? = null
    private var work: Job? = null

    init {
        val recorded = files.task.value?.takeIf { it.kind == FileTask.Kind.RESTORE }?.doc
        if (recorded != null) read(recorded)
        viewModelScope.launch { files.results(FileTask.Kind.RESTORE).collect(::picked) }
    }

    /** True while a step runs that Back does not interrupt: reading, opening, restoring. */
    val busy: Boolean
        get() = when (val now = step.value) {
            RestoreStep.Reading -> true
            is RestoreStep.Password -> now.opening
            is RestoreStep.Summary -> now.restoring
            is RestoreStep.Start -> false
        }

    /** "Choose file" or "Choose another file" (step 1). */
    fun choose() {
        if (busy) return
        drop()
        mutableStep.value = RestoreStep.Start(opening = true)
        work = viewModelScope.launch {
            if (!files.openBackupFile(STEP_FILE) && (step.value as? RestoreStep.Start)?.opening == true) {
                mutableStep.value = RestoreStep.Start(problem = RestoreProblem.PICKER)
            }
        }
    }

    /** "Open backup", or the keyboard's Done (steps 2 and 3). */
    fun submit() {
        val now = step.value as? RestoreStep.Password ?: return
        val file = bytes ?: return
        if (now.opening) return
        val chars = password.readSecret()
        password.clearSecret()
        if (chars.isEmpty()) return
        mutableStep.value = RestoreStep.Password(opening = true)
        work = viewModelScope.launch {
            val passwordBytes = MasterPasswordBytes.of(chars)
            val result = try {
                repository.openBackup(file, passwordBytes)
            } finally {
                passwordBytes.fill(0)
            }
            if ((step.value as? RestoreStep.Password)?.opening != true) {
                chars.fill(ZERO)
                return@launch
            }
            when (result) {
                is BackupResult.Opened -> {
                    typed?.fill(ZERO)
                    typed = chars
                    opened = result.vault
                    // The file has done its part: what the restore needs is held here now. Its task ends
                    // before the lock is asked, because a restore that succeeds takes this screen away.
                    files.finish(FileTask.Kind.RESTORE)
                    mutableStep.value = RestoreStep.Summary(result.vault.entries.count { it.deletedAt == null }, result.createdMs, overCurrent)
                }
                else -> {
                    chars.fill(ZERO)
                    when (result) {
                        BackupResult.Wrong -> mutableStep.value = RestoreStep.Password(problem = RestoreProblem.WRONG)
                        BackupResult.WrongOrDamaged -> mutableStep.value = RestoreStep.Password(problem = RestoreProblem.WRONG_OR_DAMAGED)
                        BackupResult.NoMemory -> mutableStep.value = RestoreStep.Password(problem = RestoreProblem.NO_MEMORY)
                        BackupResult.Damaged -> refuse(RestoreProblem.DAMAGED)
                        BackupResult.Newer -> refuse(RestoreProblem.NEWER)
                        BackupResult.NotOurs -> refuse(notOurs())
                        is BackupResult.Opened -> Unit
                    }
                }
            }
        }
    }

    /** "Restore" or "Use this backup" (step 5). */
    fun restore() {
        val now = step.value as? RestoreStep.Summary ?: return
        val chars = typed ?: return
        val vault = opened ?: return
        if (now.restoring) return
        mutableStep.value = now.copy(restoring = true, problem = null)
        work = viewModelScope.launch {
            val first = chars.copyOf()
            val again = chars.copyOf()
            PinSetupStart.newVault() // the PIN steps after it end with "Make backups" (decision 24)
            var made = false
            val result = try {
                lock.createVault(first, again, vault).also { made = it is CreateResult.Created }
            } finally {
                first.fill(ZERO)
                again.fill(ZERO)
                if (!made) PinSetupStart.takeNewVault() // no vault: the mark goes again
            }
            when (result) {
                // The lock has unlocked the restored vault: setup goes on at the PIN, and this screen leaves.
                is CreateResult.Created -> drop()
                CreateResult.NoMemory -> mutableStep.value = now.copy(restoring = false, problem = RestoreProblem.NO_MEMORY)
                // The typings are one typing, and a restore checks no code points: neither comes back here.
                CreateResult.Mismatch, is CreateResult.Unassigned, CreateResult.NotSaved ->
                    mutableStep.value = now.copy(restoring = false, problem = RestoreProblem.NOT_SAVED)
            }
        }
    }

    /** Back: the restore is given up. Ignored while a step runs. */
    fun leave() {
        if (busy) return
        work?.cancel()
        drop()
        viewModelScope.launch {
            if (files.task.value?.kind == FileTask.Kind.RESTORE) files.abandon(FileTask.Kind.RESTORE, written = false)
            mutableLeave.tryEmit(Unit)
        }
    }

    override fun onCleared() {
        drop()
    }

    // ---- Internals ------------------------------------------------------------------------------

    private fun picked(result: PickResult) {
        when (result) {
            is PickResult.Cancelled -> if ((step.value as? RestoreStep.Start)?.opening == true) mutableStep.value = RestoreStep.Start()
            is PickResult.Picked -> if (result.doc != doc) read(result.doc)
        }
    }

    /** Reads the chosen file [target]: a backup of this app goes on to the password; anything else is refused. */
    private fun read(target: DocRef) {
        drop()
        doc = target
        mutableStep.value = RestoreStep.Reading
        work = viewModelScope.launch {
            val content = try {
                files.read(target, ImportPlanner.MAX_FILE_BYTES.toInt())
            } catch (e: IOException) {
                refuse(RestoreProblem.UNREADABLE)
                return@launch
            }
            if (FileKind.of(content) != FileKind.BACKUP) {
                refuse(notOurs())
                return@launch
            }
            bytes = content
            mutableStep.value = RestoreStep.Password()
        }
    }

    /** The file cannot be restored ([problem]): its task ends, and step 1 says why. */
    private suspend fun refuse(problem: RestoreProblem) {
        drop()
        files.finish(FileTask.Kind.RESTORE)
        mutableStep.value = RestoreStep.Start(problem = problem)
    }

    private fun notOurs(): RestoreProblem = if (overCurrent) RestoreProblem.NOT_OURS_LOCKED else RestoreProblem.NOT_OURS

    /** Forgets the file, the typed password and the backup's vault. */
    private fun drop() {
        password.clearSecret()
        typed?.fill(ZERO)
        typed = null
        opened = null
        bytes = null
        doc = null
    }

    companion object {
        /** The step a restore's `FileTask` is recorded at: its file is being chosen or read. */
        const val STEP_FILE = 1

        private const val ZERO = '\u0000'

        /** The restore screen over the app's own parts. */
        fun create(graph: AppGraph): RestoreViewModel = RestoreViewModel(
            lock = graph.lock,
            repository = graph.data.repository,
            files = BackupFiles(FileTaskStore.of(graph), graph.files, graph.dispatchers.io),
        )
    }
}

/** The steps of a restore (ux.md §5.2). */
sealed interface RestoreStep {
    /** Step 1: "Choose file"; [opening] while the picker is open; [problem] about the last file. */
    data class Start(val opening: Boolean = false, val problem: RestoreProblem? = null) : RestoreStep

    /** The chosen file is read. */
    data object Reading : RestoreStep

    /** Step 2 and 3: the backup's master password; [opening] while it is tried; [problem] about the last try. */
    data class Password(val opening: Boolean = false, val problem: RestoreProblem? = null) : RestoreStep

    /**
     * Step 4: [accounts] (outside Recently deleted) and the backup's `created` time ([createdMs]);
     * [overCurrent]: passwords on this phone are put aside ("Use this backup"); [restoring] while
     * the lock writes it; [problem] when that failed.
     */
    data class Summary(
        val accounts: Int,
        val createdMs: Long,
        val overCurrent: Boolean,
        val restoring: Boolean = false,
        val problem: RestoreProblem? = null,
    ) : RestoreStep
}

/** The messages of the restore (ux.md §5.2 "States and messages"). */
enum class RestoreProblem {
    /** "The list of files could not be opened. Try again." */
    PICKER,

    /** "This file can't be read. Try another backup file." */
    UNREADABLE,

    /** "This is not a PasswordVault backup. To bring passwords from Chrome or another app, finish setup first, then use Settings › Import." */
    NOT_OURS,

    /** The same from the lock screen, where setup is not what comes next. */
    NOT_OURS_LOCKED,

    /** "This password does not open this backup. Try the master password you used when you made it." Another try is allowed. */
    WRONG,

    /** "This file may be damaged, or the password is wrong." Another try is allowed. */
    WRONG_OR_DAMAGED,

    /** "This backup file is damaged and cannot be opened. Try another backup file." */
    DAMAGED,

    /** "This backup was made by a newer version of the app. Install the newest version of PasswordVault and try again." */
    NEWER,

    /** "Your phone does not have enough free memory to open the file. Close other apps and try again." */
    NO_MEMORY,

    /** "Couldn't save. Your phone's storage may be full." Nothing changed; "Try again". */
    NOT_SAVED,
}
