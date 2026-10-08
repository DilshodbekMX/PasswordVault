package io.github.dilshodbekmx.passwordvault.files

import androidx.annotation.VisibleForTesting
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The unfinished file task and the system screens it opens: the file picker for a backup, an export,
 * an import or a restore, and the share sheet for a backup (ux.md §4 "System screens the app opens"
 * and "Unfinished file tasks"; lock.md §10.3; architecture.md §3.5 `FileTask`,
 * `AppSettings.fileTask`). One per process ([of]); its state is on the main thread.
 *
 * A task goes like this:
 * 1. Its screen calls [create] (backup, export) or [open] (import, restore) with the task: its kind
 *    and the step its route resumes at. The task is **recorded before the picker opens**, with no
 *    document, so that a result that reaches a new process (this one died while the picker was
 *    open) knows what it is for. Then `Session.beginExternal` (the 10-minute grace of lock.md
 *    §10.3), and the picker, through the launchers the activity registered ([DocumentLaunchers]).
 * 2. The picker's result reaches the activity, in this process or in a new, locked one. With a
 *    document, its persistable permission is taken and the task is recorded with it **first**, before
 *    anyone handles it (ux.md §4 items 1 and 4). Without one, the picker was closed and nothing was
 *    created: the record goes. Either way the grace ends.
 * 3. [results] hands it to the task's screen as soon as that may handle it. A backup's, an export's
 *    or an import's only while the app is unlocked: one that came while the app was locked, or in a
 *    new process, waits for the next unlock. A restore's in any state but `Starting` (its screens
 *    are shown in setup and while locked). A recorded document is handed out again after every
 *    unlock until the screen calls [finish]: the task continues where it was (ux.md §4 item 2).
 * 4. The screen calls [finish] when the task is done, or [abandon] when it is given up (the user
 *    cancelled after the picker, or the write failed): that deletes the document "Save as" created
 *    when nothing of the user's is in it, and says what it did, for the screen's sentence (ux.md §4
 *    item 3).
 *
 * A new task replaces the one recorded: a screen finishes or abandons its own task first. Nothing
 * secret is ever recorded: a kind, a step and a `content://` address.
 *
 * @param lockState the lock's state (`LockManager.state`), which decides when a result is handed out.
 * @param scope the app's scope: recording a result, and cleaning up, run to their end in it.
 * @param sharing the copies for the share sheet; those of an earlier process are deleted when this
 *   store is made.
 */
class FileTaskStore(
    private val settings: AppSettings,
    private val session: Session,
    private val documents: DocumentIo,
    private val lockState: StateFlow<LockUiState>,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val sharing: BackupSharing,
) {
    private val recorded = MutableStateFlow(settings.fileTask.value)

    /**
     * The task as recorded: its kind, its step and, once the picker returned one, its document; null
     * when there is none. It is what the settings hold, as this process last read or wrote them: when
     * a write fails, the process goes on with the new value.
     */
    val task: StateFlow<FileTask?> = recorded.asStateFlow()

    /** Pickers closed without a document, for the screens that are there to see it. */
    private val cancels = MutableSharedFlow<FileTask>(extraBufferCapacity = 8)

    /** The launchers of the activity that is there now; main thread. */
    private var launcher: PickerLauncher? = null

    /** The share sheet was opened, and the app has not been in front since: its grace has not ended. Main thread. */
    private var shareSheetOpen = false

    init {
        scope.launch(dispatchers.io) { sharing.clear() } // kept until the next app start (ux.md §5.19)
    }

    /**
     * Records [task] (a backup or an export) with no document, then opens "Save as" with the
     * suggested [name] and its [mime] type (`DocumentTypes`). False, with nothing opened: no activity
     * is there, the task could not be recorded, or no app on the phone saves documents.
     */
    suspend fun create(task: FileTask, name: String, mime: String): Boolean {
        require(task.kind.action == Action.CREATE) { "${task.kind} opens a document" }
        return launch(task) { it.create(name, mime) }
    }

    /**
     * Records [task] (an import or a restore) with no document, then opens the picker for documents
     * of [mimeTypes]. False as for [create].
     */
    suspend fun open(task: FileTask, mimeTypes: List<String> = DocumentTypes.ANY): Boolean {
        require(task.kind.action == Action.OPEN) { "${task.kind} saves a document" }
        require(mimeTypes.isNotEmpty()) { "at least one type" }
        return launch(task) { it.open(mimeTypes) }
    }

    /**
     * What the picker returned for the task of [kind], as soon as it may be handled (see the class
     * comment): [PickResult.Picked] for the recorded document, again after every unlock until
     * [finish]; [PickResult.Cancelled] when the picker was closed without one, to collectors that are
     * there at that moment.
     */
    fun results(kind: FileTask.Kind): Flow<PickResult> = merge(
        cancels.filter { it.kind == kind }.map { PickResult.Cancelled(it) },
        combine(recorded, lockState) { task, state -> task?.takeIf { it.kind == kind && it.doc != null && mayHandle(kind, state) } }
            .distinctUntilChanged()
            .filterNotNull()
            .map { PickResult.Picked(it) },
    )

    /** Forgets the task of [kind]: it is done. Nothing happens to its document. */
    suspend fun finish(kind: FileTask.Kind) {
        withContext(dispatchers.main) {
            if (recorded.value?.kind == kind) record(null)
        }
    }

    /**
     * Gives up the task of [kind] and cleans up after it (ux.md §4 item 3: the user cancelled after
     * the picker, or the write failed), then forgets it. The document that "Save as" created for it is
     * deleted when nothing of the user's is in it: it is still empty, or [written] says that this task
     * wrote into it and the write or its check failed. A document that holds something else (an
     * existing file the user chose to replace), or that cannot be read, is left as it was; one that
     * an import or a restore opened is never touched. Says what was done, for the screen's sentence.
     */
    suspend fun abandon(kind: FileTask.Kind, written: Boolean = false): Cleanup = withContext(dispatchers.main) {
        val task = recorded.value?.takeIf { it.kind == kind } ?: return@withContext Cleanup.NoDocument
        val doc = task.doc
        val cleanup = if (doc == null || kind.action != Action.CREATE) Cleanup.NoDocument else withContext(NonCancellable) { remove(doc, written) }
        record(null)
        cleanup
    }

    /**
     * Shares [bytes], an encrypted backup, as [name] through the share sheet titled [title] (ux.md
     * §5.19 item 6), after `Session.beginExternal`; the grace ends when the app is in front again
     * ([inFront]), because the share sheet returns no result. [name] may be the name a place shows
     * for the backup: a "/" in it becomes "_" in the copy's file name ([BackupSharing.fileName]).
     * False, with nothing opened: no activity is there, the copy could not be written (also: [name]
     * is no name, or too long for a file), or no app on the phone shows a share sheet.
     */
    suspend fun share(bytes: ByteArray, name: String, title: CharSequence): Boolean = withContext(dispatchers.main) {
        if (launcher == null) return@withContext false
        val file = try {
            withContext(NonCancellable + dispatchers.io) { sharing.write(bytes, BackupSharing.fileName(name)) }
        } catch (e: IOException) {
            return@withContext false
        } catch (e: IllegalArgumentException) {
            return@withContext false // nothing of a name ("", ".", ".."): no copy was written
        }
        val now = launcher ?: return@withContext false
        session.beginExternal()
        try {
            now.share(file, title)
            shareSheetOpen = true
            true
        } catch (e: RuntimeException) {
            session.endExternal()
            false
        }
    }

    /**
     * The activity is in front again: it was resumed (main thread). That ends the grace of a share
     * sheet (lock.md §10.3 item 3). The session cannot see it by itself: the share sheet covers the
     * activity without stopping it, so when it is closed without a choice the process has never
     * left, and without this the lock delay would not apply for the rest of the 10 minutes. While
     * the user is in the app chosen in the sheet, the activity is stopped and is not resumed, so
     * the grace holds as lock.md §10.3 means it to. A picker's grace ends with its result
     * ([returned]) and is not touched here.
     */
    internal fun inFront() {
        if (!shareSheetOpen) return
        shareSheetOpen = false
        session.endExternal()
    }

    /** The activity [now] is there to open the system screens (main thread). */
    internal fun attach(now: PickerLauncher) {
        launcher = now
    }

    /** The activity of [gone] is destroyed (main thread). */
    internal fun detach(gone: PickerLauncher) {
        if (launcher === gone) launcher = null
    }

    /**
     * The picker of [action] returned [uri], or null when it was closed without a document. Called by
     * the launchers' callbacks on the main thread, also in a new process, before the lock has decided.
     */
    internal fun returned(action: Action, uri: String?) {
        session.endExternal()
        val waiting = recorded.value?.takeIf { it.doc == null && it.kind.action == action }
        if (uri == null) {
            if (waiting != null) {
                scope.launch {
                    record(null)
                    cancels.emit(waiting)
                }
            }
            return
        }
        val doc = DocRef(uri)
        if (waiting == null) {
            // No task waits for it. A document the picker created for us is removed while still empty;
            // one the user chose to open is never touched.
            if (action == Action.CREATE) scope.launch { removeIfEmpty(doc) }
            return
        }
        scope.launch {
            withContext(NonCancellable + dispatchers.io) { documents.keep(doc) }
            record(waiting.copy(doc = doc))
        }
    }

    private suspend fun launch(task: FileTask, start: (PickerLauncher) -> Unit): Boolean = withContext(dispatchers.main) {
        if (launcher == null) return@withContext false
        val before = recorded.value
        if (!record(task.copy(doc = null))) {
            recorded.value = before // not recorded, so the picker does not open
            return@withContext false
        }
        val now = launcher
        if (now == null) { // the activity went while the task was written
            record(before)
            return@withContext false
        }
        session.beginExternal()
        try {
            start(now)
            true
        } catch (e: RuntimeException) { // ActivityNotFoundException, or a launcher already unregistered
            session.endExternal()
            record(before)
            false
        }
    }

    /**
     * Writes [next] as the recorded task, to its end even if the caller is cancelled, and publishes
     * it. False: the settings were not written; the process goes on with [next] all the same.
     */
    private suspend fun record(next: FileTask?): Boolean = withContext(NonCancellable) {
        val written = withContext(dispatchers.io) {
            try {
                settings.update { it.fileTask = next }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                false
            }
        }
        recorded.value = next
        written
    }

    private suspend fun removeIfEmpty(doc: DocRef) {
        withContext(NonCancellable) { remove(doc, written = false) }
    }

    /** Deletes [doc], a document "Save as" created, when it is empty or [written] by this task (see [abandon]). */
    private suspend fun remove(doc: DocRef, written: Boolean): Cleanup {
        if (!written) {
            val empty = try {
                documents.read(doc, 0).isEmpty()
            } catch (e: IOException) {
                false // it cannot be read: nothing is known of it
            }
            if (!empty) return Cleanup.Kept
        }
        return withContext(dispatchers.io) {
            if (documents.delete(doc)) Cleanup.Removed else Cleanup.Left(doc, documents.place(doc))
        }
    }

    private fun mayHandle(kind: FileTask.Kind, state: LockUiState): Boolean = when (kind) {
        FileTask.Kind.RESTORE -> state !is LockUiState.Starting
        else -> state is LockUiState.Unlocked
    }

    /** The two pickers: "Save as" makes a new document, the other opens one that exists. */
    enum class Action { CREATE, OPEN }

    companion object {
        @Volatile
        private var instance: FileTaskStore? = null

        /** The process's one store, made when the first activity is (`DocumentLaunchers.onCreate`). */
        fun of(graph: AppGraph): FileTaskStore = instance ?: synchronized(this) {
            instance ?: FileTaskStore(
                settings = graph.settings,
                session = graph.session,
                documents = graph.files,
                lockState = graph.lock.state,
                scope = graph.appScope,
                dispatchers = graph.dispatchers,
                sharing = BackupSharing(File(graph.app.cacheDir, BackupSharing.DIR)),
            ).also { instance = it }
        }

        /**
         * Forgets the process's store, as a new process has none: the next [of] reads the task from the
         * settings again. For the device tests that stand in for a new process.
         */
        @VisibleForTesting
        internal fun forgetForTests() {
            instance = null
        }
    }
}

/** The picker a task opens: a backup and an export save a new document, an import and a restore open one. */
internal val FileTask.Kind.action: FileTaskStore.Action
    get() = when (this) {
        FileTask.Kind.BACKUP, FileTask.Kind.EXPORT -> FileTaskStore.Action.CREATE
        FileTask.Kind.IMPORT, FileTask.Kind.RESTORE -> FileTaskStore.Action.OPEN
    }
