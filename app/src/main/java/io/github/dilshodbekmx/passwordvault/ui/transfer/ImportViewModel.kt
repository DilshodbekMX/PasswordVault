package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.formats.FileKind
import io.github.dilshodbekmx.passwordvault.core.formats.ImportFile
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlan
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlanner
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Imported
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import io.github.dilshodbekmx.passwordvault.ui.common.readSecret
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The import of the chosen file (`Route.Import(ImportStep.FILE)`; ux.md §5.21, formats.md §8):
 * read the file, open the column mapping when no column is a password column, show the preview,
 * import it in one save, offer "Undo this import" and ask whether to delete the file. A backup file
 * asks for its master password and adds its accounts instead ("Backup file into an existing vault").
 *
 * **The file is read again whenever this screen opens** (ux.md §4 item 2): what was read holds
 * passwords, so it lives only in this ViewModel. It is dropped when the vault locks (the activity may
 * be stopped then, and Navigation 3 clears this ViewModel only when it starts again, `NavState`), and
 * after the unlock a new ViewModel reads the file again, the task's document being recorded outside
 * the vault. Nothing secret reaches the saved state, a log or a `toString` (architecture.md §5.6).
 *
 * **The task** ([ImportTasks]) stays recorded from the picker until the import is finished: after the
 * file was deleted or kept, after an undo, or after a backup's accounts were added. Back before the
 * import gives it up; nothing has been saved then. After a lock between the import and the answer
 * about the file, the file reads again as "every account of this file is already here", and the
 * question is asked then.
 *
 * The screen hands in the texts the import stores ([start]), in its own language.
 */
internal class ImportViewModel(
    private val tasks: ImportTasks,
    private val documents: DocumentIo,
    private val repository: VaultRepository,
    private val random: RandomSource,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val memory: ImportMemory,
) : ViewModel() {

    private val state = MutableStateFlow(ImportUi())
    val ui: StateFlow<ImportUi> = state.asStateFlow()

    /** The backup's master password (ux.md §5.2's field, VaultSecureField); emptied after each try and at a lock. */
    val backupPassword = TextFieldState()

    private var texts: EditTexts? = null
    private var started = false
    private var doc: DocRef? = null

    // What was read from the file and what it would become: secrets, dropped at a lock ([drop]).
    private var file: ImportFile? = null
    private var tidyByDefault = true
    private var plan: ImportPlan? = null
    private var plannedVault: Vault? = null
    private var plannedChoices: ImportPlanner.Choices? = null
    private var before: Vault? = null
    private var backupBytes: ByteArray? = null
    private var opened: Vault? = null

    /** The importer's choices the file was last read with: a change of them reads it again. */
    private var readWith: Importer.Choices? = null
    private var reading: Job? = null
    private var planning: Job? = null

    /**
     * Starts with [texts] the first time; afterwards only takes the newer [texts] (the language may
     * have changed under the screen).
     */
    fun start(texts: EditTexts) {
        this.texts = texts
        if (started) return
        started = true
        viewModelScope.launch {
            repository.state.collect { vault ->
                when {
                    vault !is VaultState.Unlocked -> drop()
                    state.value.screen == ImportScreen.Locked -> begin()
                    else -> vaultChanged(vault.vault)
                }
            }
        }
        viewModelScope.launch {
            memory.choices.collect { choices ->
                val d = doc ?: return@collect
                val read = readWith ?: return@collect
                if (choices == null || choices.doc != d.uri) return@collect
                if (choices.encoding != read.encoding || choices.columns != read.columns) read(d)
            }
        }
        begin()
    }

    private fun begin() {
        if (repository.state.value !is VaultState.Unlocked) {
            drop()
            return
        }
        val d = tasks.document
        if (d == null) {
            set(ImportScreen.Gone)
            move(ImportMove.LEAVE)
            return
        }
        doc = d
        state.update { it.copy(paused = !memory.takeFresh(d)) }
        viewModelScope.launch {
            val name = withContext(dispatchers.io) {
                try {
                    documents.name(d)
                } catch (e: RuntimeException) {
                    null
                }
            }
            state.update { it.copy(fileName = name) }
        }
        read(d)
    }

    // ------------------------------------------------------------------ reading

    private fun read(d: DocRef) {
        val texts = texts ?: return
        reading?.cancel()
        planning?.cancel()
        file = null
        plan = null
        set(ImportScreen.Reading)
        val choices = memory.choicesFor(d)
        val with = Importer.Choices(choices.encoding, choices.columns)
        readWith = with
        reading = viewModelScope.launch {
            val bytes = try {
                documents.read(d, Importer.MAX_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                problem(ImportProblem.CANNOT_OPEN)
                return@launch
            } catch (e: RuntimeException) { // the permission is gone (SecurityException), or the provider failed
                problem(ImportProblem.CANNOT_OPEN)
                return@launch
            }
            if (bytes.size > Importer.MAX_BYTES) {
                Bytes.wipe(bytes)
                problem(ImportProblem.TOO_BIG)
                return@launch
            }
            val kind = withContext(dispatchers.default) { FileKind.of(bytes) }
            if (kind == FileKind.BACKUP) {
                backupBytes = bytes // locked with the backup's own key
                set(ImportScreen.BackupPassword())
                return@launch
            }
            val (ours, result) = withContext(dispatchers.default) {
                try {
                    ImportDefaults.hasOwnBlock(bytes) to Importer.read(bytes, clock.wallMs(), texts, random, with)
                } catch (e: OutOfMemoryError) {
                    false to null
                } finally {
                    Bytes.wipe(bytes)
                }
            }
            readDone(d, kind, ours, result)
        }
    }

    private fun readDone(d: DocRef, kind: FileKind, ours: Boolean, result: Importer.Result?) {
        when (result) {
            null -> problem(ImportProblem.NO_MEMORY)
            is Importer.Result.NotForImport -> problem(
                when (result.kind) {
                    FileKind.SPREADSHEET -> ImportProblem.EXCEL
                    FileKind.KEEPASS -> ImportProblem.KEEPASS
                    FileKind.EMPTY -> ImportProblem.EMPTY
                    else -> ImportProblem.UNREADABLE
                },
            )
            Importer.Result.Empty -> problem(ImportProblem.EMPTY)
            is Importer.Result.TooBig -> problem(ImportProblem.TOO_BIG)
            is Importer.Result.TooManyRows -> problem(ImportProblem.TOO_MANY)
            Importer.Result.NotText -> problem(ImportProblem.UNREADABLE)
            is Importer.Result.Unreadable -> problem(
                when {
                    result.error.reason == ImportDefaults.REASON_OUT_OF_MEMORY -> ImportProblem.NO_MEMORY
                    kind == FileKind.JSON -> ImportProblem.BAD_JSON
                    else -> ImportProblem.UNREADABLE
                },
            )
            is Importer.Result.Encrypted -> problem(ImportProblem.ENCRYPTED)
            Importer.Result.NotAnExport -> problem(ImportProblem.NOT_AN_EXPORT)
            is Importer.Result.ChooseColumns -> {
                // formats-review P6: no password column, so the user chooses (ux.md §5.21).
                set(ImportScreen.NeedsColumns)
                if (!memory.choicesFor(d).columnsShown) openColumns()
            }
            is Importer.Result.Read -> {
                file = result.file
                tidyByDefault = ImportDefaults.tidyNames(result.file.format, ours)
                val columns = result.file.columns
                // A column whose meaning another one took is an ambiguity the user should see (formats.md §8.3).
                if (columns != null && columns.ambiguous.isNotEmpty() && !columns.fromUser && !memory.choicesFor(d).columnsShown) {
                    openColumns()
                }
                replan()
            }
        }
    }

    private fun problem(problem: ImportProblem) = set(ImportScreen.Problem(problem))

    // ------------------------------------------------------------------ the preview

    private fun choices(): ImportPlanner.Choices? {
        val d = doc ?: return null
        val c = memory.choicesFor(d)
        return ImportPlanner.Choices(c.includeWrongLength, c.keepSeparate, c.tidyNames ?: tidyByDefault)
    }

    /** Plans [file] against the open vault with the user's choices, and shows the preview. */
    private fun replan() {
        val f = file ?: return
        val texts = texts ?: return
        val vault = (repository.state.value as? VaultState.Unlocked)?.vault ?: return
        val choices = choices() ?: return
        planning?.cancel()
        when (val now = state.value.screen) {
            is ImportScreen.Preview -> if (!now.importing) set(now.copy(planning = true)) else return
            else -> set(ImportScreen.Reading)
        }
        planning = viewModelScope.launch {
            val (p, preview) = withContext(dispatchers.default) {
                val p = ImportPlanner.plan(vault, f, texts::fromFile, choices)
                p to ImportPreview.of(f, p, choices.tidyNames, choices.includeWrongLength, choices.keepSeparate)
            }
            plan = p
            plannedVault = vault
            plannedChoices = choices
            if (ImportDefaults.nothingNew(preview)) {
                // Every account is here already: after an import that a lock cut off from its question,
                // or a file imported before. The file still holds them unprotected (ux.md §5.21).
                set(ImportScreen.Done(ImportDone(added = 0, alreadyAll = preview.found, canUndo = false, askFile = true)))
            } else {
                set(ImportScreen.Preview(preview))
            }
        }
    }

    private fun vaultChanged(vault: Vault) {
        val now = state.value.screen
        if (file != null && plannedVault !== vault && now is ImportScreen.Preview && !now.importing) replan()
    }

    /** "Text looks wrong? Read as …" (formats.md §8.2). */
    fun readAs(encoding: TextDecoding.Encoding) {
        val d = doc ?: return
        memory.update(d) { it.copy(encoding = encoding) }
    }

    /** The rows with the wrong number of values are imported as they are ([on]), or left out (formats-review P4). */
    fun includeWrongRows(on: Boolean) = choose { it.copy(includeWrongLength = on) }

    /** "Keep them separate" ([on]) or keep them together (ux.md §5.21, rules (b) and (c)). */
    fun keepSeparate(on: Boolean) = choose { it.copy(keepSeparate = on) }

    /** "Tidy names". */
    fun tidyNames(on: Boolean) = choose { it.copy(tidyNames = on) }

    private fun choose(change: (ImportMemory.Choices) -> ImportMemory.Choices) {
        val d = doc ?: return
        memory.update(d, change)
        replan()
    }

    /** "Choose columns" or "Check columns": the column-mapping screen. */
    fun openColumns() {
        val d = doc ?: return
        memory.update(d) { it.copy(columnsShown = true) }
        move(ImportMove.COLUMNS)
    }

    /** "Import 45 accounts": one save (architecture.md §3.8), refused before anything is saved when it would not fit (§3.5). */
    fun importNow() {
        val now = state.value.screen as? ImportScreen.Preview ?: return
        val f = file ?: return
        val p = plan ?: return
        val choices = plannedChoices ?: return
        val texts = texts ?: return
        if (now.importing || now.planning) return
        if (!p.fits) {
            say(ImportMessage.TOO_LARGE)
            return
        }
        if (p.accounts.isEmpty()) return
        val planned = plannedVault
        set(now.copy(importing = true))
        viewModelScope.launch {
            val saved = repository.edit { vault, nowMs ->
                // Planned against another vault (a change came in between): plan again, inside the one save.
                val use = if (vault === planned) p else ImportPlanner.plan(vault, f, texts::fromFile, choices)
                if (!use.fits || use.accounts.isEmpty()) {
                    Edited(vault, Imported(emptyList(), 0, 0, vault))
                } else {
                    VaultEdits.addImported(use.entries, random, texts::fromFile)(vault, nowMs)
                }
            }
            when (saved) {
                is SaveResult.Saved -> imported(saved.value)
                is SaveResult.NotSaved -> {
                    set(now.copy(importing = false))
                    say(if (saved.problem == SaveProblem.TOO_LARGE) ImportMessage.TOO_LARGE else ImportMessage.SAVE_FAILED)
                }
            }
        }
    }

    private suspend fun imported(imported: Imported) {
        val now = state.value.screen as? ImportScreen.Preview
        if (imported.addedIds.isEmpty()) {
            // Planned again inside the save, and then it did not fit, or nothing was new.
            if (now != null) set(now.copy(importing = false))
            replan()
            return
        }
        before = imported.before
        file = null
        plan = null
        val vault = (repository.state.value as? VaultState.Unlocked)?.vault
        val shared = if (vault == null) 0 else withContext(dispatchers.default) { ImportDefaults.sharing(vault, imported.addedIds) }
        set(
            ImportScreen.Done(
                ImportDone(
                    added = imported.addedIds.size,
                    canUndo = true,
                    askFile = true,
                    manyShared = shared >= ImportDefaults.MANY_SHARED,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ after the import

    /** "Undo this import": the vault exactly as it was before it (architecture.md D-15). The file stays. */
    fun undo() {
        val done = (state.value.screen as? ImportScreen.Done)?.done ?: return
        val b = before ?: return
        if (done.busy || !done.canUndo) return
        set(ImportScreen.Done(done.copy(busy = true)))
        viewModelScope.launch {
            val result = repository.edit(VaultEdits.replaceVault(b))
            if (result is SaveResult.Saved && result.value) {
                before = null
                set(ImportScreen.Done(done.copy(busy = false, canUndo = false, undone = true, askFile = false, manyShared = false)))
                end() // the passwords are no longer here: the file keeps them
            } else {
                set(ImportScreen.Done(done.copy(busy = false)))
                say(ImportMessage.UNDO_FAILED)
            }
        }
    }

    /** "Delete file" (ux.md §5.21): deletes the imported file where the place allows it, then ends the task. */
    fun deleteFile() {
        val done = (state.value.screen as? ImportScreen.Done)?.done ?: return
        val d = doc ?: return
        if (done.busy || !done.askFile) return
        set(ImportScreen.Done(done.copy(busy = true)))
        viewModelScope.launch {
            val deleted = withContext(dispatchers.io) {
                try {
                    documents.delete(d)
                } catch (e: RuntimeException) {
                    false
                }
            }
            if (deleted) before = null // undoing now would lose these passwords: they are nowhere else
            set(
                ImportScreen.Done(
                    done.copy(
                        busy = false,
                        askFile = false,
                        file = if (deleted) FileOutcome.DELETED else FileOutcome.NOT_DELETED,
                        canUndo = done.canUndo && !deleted,
                    ),
                ),
            )
            end()
        }
    }

    /** "Keep": the file stays where it is; the task ends. */
    fun keepFile() {
        val done = (state.value.screen as? ImportScreen.Done)?.done ?: return
        if (done.busy || !done.askFile) return
        set(ImportScreen.Done(done.copy(askFile = false, file = FileOutcome.KEPT)))
        viewModelScope.launch { end() }
    }

    /** "Open Security": the reused passwords. An unanswered question about the file counts as "Keep". */
    fun openSecurity() {
        leaveThen(ImportMove.SECURITY)
    }

    /** "Done", or Back after the import: an unanswered question about the file counts as "Keep". */
    fun done() {
        leaveThen(ImportMove.LEAVE)
    }

    private fun leaveThen(next: ImportMove) {
        viewModelScope.launch {
            val now = state.value.screen
            if (now is ImportScreen.Done && now.done.askFile) {
                set(ImportScreen.Done(now.done.copy(askFile = false, file = FileOutcome.KEPT)))
            }
            end()
            move(next)
        }
    }

    // ------------------------------------------------------------------ leaving

    /**
     * Back. Before anything was saved, it gives the import up (the file is never touched); while a
     * save runs it is ignored (ux.md §4); after the import it is [done].
     */
    fun back() {
        when (val now = state.value.screen) {
            is ImportScreen.Preview -> if (now.importing) return
            is ImportScreen.BackupPassword -> if (now.opening) return
            is ImportScreen.BackupSummary -> if (now.adding) return
            is ImportScreen.Done, is ImportScreen.BackupAdded -> {
                done()
                return
            }
            ImportScreen.Gone, ImportScreen.Locked -> {
                move(ImportMove.LEAVE)
                return
            }
            else -> Unit
        }
        giveUpThen(ImportMove.LEAVE)
    }

    /** "Choose another file": this one is given up, and the sources open in place of this screen. */
    fun chooseAnother() = giveUpThen(ImportMove.SOURCES)

    private fun giveUpThen(next: ImportMove) {
        reading?.cancel()
        planning?.cancel()
        viewModelScope.launch {
            tasks.abandon()
            memory.forget(doc)
            drop(ImportScreen.Gone)
            move(next)
        }
    }

    /** The import is over: its task and its choices go. Its document was deleted or kept before. */
    private suspend fun end() {
        tasks.finish()
        memory.forget(doc)
    }

    // ------------------------------------------------------------------ a backup file

    /** "Open backup" with the master password typed (ux.md §5.2; lock.md §6.8: no waits for a file from outside). */
    fun openBackup() {
        val now = state.value.screen as? ImportScreen.BackupPassword ?: return
        val bytes = backupBytes ?: return
        if (now.opening) return
        val chars = backupPassword.readSecret()
        if (chars.isEmpty()) return
        backupPassword.clearSecret()
        set(ImportScreen.BackupPassword(opening = true))
        viewModelScope.launch {
            val password = try {
                withContext(dispatchers.default) { MasterPasswordBytes.of(chars) }
            } finally {
                Bytes.wipe(chars)
            }
            val result = try {
                repository.openBackup(bytes, password)
            } finally {
                Bytes.wipe(password)
            }
            when (result) {
                is BackupResult.Opened -> {
                    opened = result.vault
                    set(ImportScreen.BackupSummary(result.vault.entries.count { it.deletedAt == null }, result.createdMs))
                }
                BackupResult.Wrong -> set(ImportScreen.BackupPassword(BackupError.WRONG))
                BackupResult.WrongOrDamaged -> set(ImportScreen.BackupPassword(BackupError.WRONG_OR_DAMAGED))
                BackupResult.NoMemory -> set(ImportScreen.BackupPassword(BackupError.NO_MEMORY))
                BackupResult.Damaged -> problem(ImportProblem.BACKUP_DAMAGED)
                BackupResult.Newer -> problem(ImportProblem.BACKUP_NEWER)
                BackupResult.NotOurs -> problem(ImportProblem.BACKUP_NOT_OURS)
            }
        }
    }

    /**
     * "Add to my passwords": the backup's accounts by the rules of "Backup file into an existing vault"
     * (ux.md §5.21, architecture.md §4.11): new ones added, identical ones skipped, one whose id an
     * account here holds with other content added as "Gmail (from backup)". Nothing is overwritten.
     */
    fun addBackup() {
        val now = state.value.screen as? ImportScreen.BackupSummary ?: return
        val v = opened ?: return
        val texts = texts ?: return
        if (now.adding) return
        set(now.copy(adding = true))
        viewModelScope.launch {
            when (val saved = repository.edit(VaultEdits.mergeEntries(v.entries, texts, random))) {
                is SaveResult.Saved -> {
                    opened = null
                    backupBytes = null
                    set(ImportScreen.BackupAdded(saved.value.addedIds.size, saved.value.skipped, saved.value.renamed))
                    end()
                }
                is SaveResult.NotSaved -> {
                    set(now.copy(adding = false))
                    say(if (saved.problem == SaveProblem.TOO_LARGE) ImportMessage.TOO_LARGE else ImportMessage.SAVE_FAILED)
                }
            }
        }
    }

    // ------------------------------------------------------------------ state

    /** The vault locked, or the screen gave up: everything read from the file goes. */
    private fun drop(then: ImportScreen = ImportScreen.Locked) {
        reading?.cancel()
        planning?.cancel()
        file = null
        plan = null
        plannedVault = null
        plannedChoices = null
        before = null
        opened = null
        backupBytes = null
        readWith = null
        backupPassword.clearSecret()
        set(then)
    }

    private fun set(screen: ImportScreen) = state.update { it.copy(screen = screen) }

    private fun say(message: ImportMessage) = state.update { it.copy(message = message) }

    private fun move(next: ImportMove) = state.update { it.copy(move = next) }

    /** The message was shown. */
    fun messageShown() = state.update { it.copy(message = null) }

    /** The move was made. */
    fun moved() = state.update { it.copy(move = null) }

    override fun onCleared() {
        drop()
    }

    companion object {
        /** The file screen with the app's own parts. */
        fun create(graph: AppGraph): ImportViewModel = ImportViewModel(
            tasks = StoreImportTasks(FileTaskStore.of(graph)),
            documents = graph.files,
            repository = graph.data.repository,
            random = graph.platform.random,
            clock = graph.platform.clock,
            dispatchers = graph.dispatchers,
            memory = ImportMemory.shared,
        )
    }
}

/** The import's rules that are not the importer's (ux.md §5.21; the owner's decision 10 of 2026-10-08). */
internal object ImportDefaults {
    /**
     * The reason of `Importer.Result.Unreadable` when the phone ran out of memory reading the file:
     * "This file is too large to read on this phone." (`Importer.REASON_OUT_OF_MEMORY`, asked for in
     * the review of 3.15; this is its value until that constant is there).
     */
    const val REASON_OUT_OF_MEMORY: String = "out-of-memory"

    /** "Many of these accounts share a password": at least this many of the imported accounts do. */
    const val MANY_SHARED: Int = 3

    /**
     * The start of the marker of our CSV's fields block (`FieldsBlock`, formats.md §4.4), which stays
     * in English in every language.
     */
    private const val OWN_BLOCK_MARKER = "--- PasswordVault ("

    /**
     * "Tidy names" starts switched off for our own files, our JSON and our CSV with its fields block,
     * and on for other apps' files (the owner's decision 10 of 2026-10-08): our names are the user's.
     */
    fun tidyNames(format: Importer.Format, ownBlock: Boolean): Boolean = when (format) {
        Importer.Format.OWN_JSON -> false
        Importer.Format.GOOGLE_CSV -> !ownBlock
        else -> true
    }

    /** Whether [bytes], a CSV file as UTF-8, UTF-16 or Windows-1251, hold the marker of our fields block. */
    fun hasOwnBlock(bytes: ByteArray): Boolean {
        val ascii = OWN_BLOCK_MARKER.toByteArray(Charsets.US_ASCII)
        if (indexOf(bytes, ascii) >= 0) return true
        return indexOf(bytes, OWN_BLOCK_MARKER.toByteArray(Charsets.UTF_16LE)) >= 0 ||
            indexOf(bytes, OWN_BLOCK_MARKER.toByteArray(Charsets.UTF_16BE)) >= 0
    }

    private fun indexOf(bytes: ByteArray, part: ByteArray): Int {
        if (part.isEmpty() || bytes.size < part.size) return -1
        val first = part[0]
        var i = 0
        val last = bytes.size - part.size
        while (i <= last) {
            if (bytes[i] == first) {
                var k = 1
                while (k < part.size && bytes[i + k] == part[k]) k++
                if (k == part.size) return i
            }
            i++
        }
        return -1
    }

    /**
     * Every account the file holds is already here, and nothing of it was left out: the preview then
     * has nothing to import, and asks about the file at once.
     */
    fun nothingNew(preview: ImportPreview): Boolean =
        preview.toAdd == 0 && preview.found > 0 && preview.leftOut.isEmpty() &&
            (preview.wrongRows.isEmpty() || preview.wrongRowsIncluded)

    /**
     * How many of the accounts [ids] share their password with another account of [vault] outside
     * Recently deleted and the check's leave-outs (the security check's reuse, ux.md §5.16).
     */
    fun sharing(vault: Vault, ids: Collection<String>): Int {
        val counts = HashMap<String, Int>()
        for (e in vault.entries) {
            if (e.deletedAt != null || e.checkIgnored) continue
            val p = SecurityCheck.websitePassword(e)
            if (p.isNotEmpty()) counts[p] = (counts[p] ?: 0) + 1
        }
        val wanted = ids.toHashSet()
        return vault.entries.count { e ->
            e.id in wanted && e.deletedAt == null && !e.checkIgnored && (counts[SecurityCheck.websitePassword(e)] ?: 0) >= 2
        }
    }
}
