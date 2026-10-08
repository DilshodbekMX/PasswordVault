package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.files.PickResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the passwords come from (ux.md §5.21, "Sources screen"); the content of the file decides the reader. */
internal enum class ImportSource {
    /** "Backup file from this app". */
    BACKUP,

    /** "Google Password Manager or Chrome (CSV)": the help is shown before the picker. */
    GOOGLE,

    /** "Another password app or a spreadsheet saved as CSV". */
    CSV,

    /** "JSON (this app or Bitwarden)". */
    JSON,
}

/**
 * What the sources screen shows.
 *
 * @property help the Google help is shown, before the picker (ux.md §5.21, "Google Password Manager help").
 * @property moreHelp "I can't find it" was tapped: the way through the phone's Settings app.
 * @property opening the picker is being opened, or is open: the buttons wait.
 * @property noPicker no app on this phone opens files: the picker could not be opened.
 * @property toFile one-time: a file was chosen (now, or before a lock or in another process); the
 *   file's step opens in place of this one.
 */
internal data class ImportSourcesUi(
    val help: Boolean = false,
    val moreHelp: Boolean = false,
    val opening: Boolean = false,
    val noPicker: Boolean = false,
    val toFile: Boolean = false,
)

/**
 * The first step of the import (`Route.Import(ImportStep.SOURCES)`): the sources, the Google help,
 * and the system file picker, opened through the process's `FileTaskStore` ([ImportTasks]). Nothing
 * secret is here.
 *
 * The picker's answer reaches this screen through [watchResults], which the screen runs while it is
 * shown: a chosen document moves to the file's step. A document that was recorded before this
 * ViewModel opened a picker (the app locked while the picker was open, the process ended, or the user
 * left an unfinished import and opened Import again) moves there too, and that screen then says "This
 * was paused when the app locked." (ux.md §4).
 */
internal class ImportSourcesViewModel(
    private val tasks: ImportTasks,
    private val memory: ImportMemory,
) : ViewModel() {
    private val state = MutableStateFlow(ImportSourcesUi())
    val ui: StateFlow<ImportSourcesUi> = state.asStateFlow()

    /** This ViewModel opened the picker and has not yet had its answer. */
    private var pickerOpenedHere = false

    /** The picker opened and its task is recorded: when the record goes without an answer here, the picker was closed. */
    private var pickerOpen = false

    /** A source was chosen: the Google help first, the picker for the others. */
    fun choose(source: ImportSource) {
        if (state.value.opening) return
        if (source == ImportSource.GOOGLE) {
            state.update { it.copy(help = true, moreHelp = false, noPicker = false) }
        } else {
            openPicker()
        }
    }

    /** "I can't find it" (the Google help). */
    fun showMoreHelp() = state.update { it.copy(moreHelp = true) }

    /** Back from the Google help to the sources; true when that was what Back did. */
    fun closeHelp(): Boolean {
        if (!state.value.help) return false
        state.update { it.copy(help = false, moreHelp = false) }
        return true
    }

    /** "Choose file": the system picker for a file of any type (ux.md §5.21; time in it counts as time in the app, §4). */
    fun openPicker() {
        if (state.value.opening) return
        state.update { it.copy(opening = true, noPicker = false) }
        viewModelScope.launch {
            pickerOpenedHere = true
            val opened = tasks.open()
            if (opened) {
                pickerOpen = true
            } else {
                pickerOpenedHere = false
                state.update { it.copy(opening = false, noPicker = true) }
            }
        }
    }

    /**
     * Hands the picker's answers to [onResult] while the screen is shown; the screen runs it in its
     * own scope. A closed picker is also seen in the task's record, which goes: its answer may have
     * come while the screen was not there to receive it (the activity was being recreated).
     */
    suspend fun watchResults() = coroutineScope {
        launch {
            tasks.task.collect { task ->
                if (pickerOpen && task?.kind != FileTask.Kind.IMPORT) {
                    pickerOpen = false
                    pickerOpenedHere = false
                    state.update { it.copy(opening = false) }
                }
            }
        }
        tasks.results().collect(::onResult)
    }

    /** The picker answered: [result]. */
    fun onResult(result: PickResult) {
        when (result) {
            is PickResult.Picked -> {
                if (pickerOpenedHere) memory.markFresh(result.doc)
                pickerOpenedHere = false
                pickerOpen = false
                state.update { it.copy(opening = false, toFile = true) }
            }
            is PickResult.Cancelled -> {
                pickerOpenedHere = false
                pickerOpen = false
                state.update { it.copy(opening = false) }
            }
        }
    }

    /** The file's step was opened. */
    fun moved() = state.update { it.copy(toFile = false) }

    companion object {
        /** The sources with the app's own parts. */
        fun create(graph: AppGraph): ImportSourcesViewModel =
            ImportSourcesViewModel(StoreImportTasks(FileTaskStore.of(graph)), ImportMemory.shared)
    }
}
