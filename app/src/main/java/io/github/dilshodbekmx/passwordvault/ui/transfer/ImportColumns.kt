package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.formats.ImportColumns
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.FileTaskStore
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
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
 * One column of the CSV file on the column-mapping screen: its header as in the file, its first value
 * that is not blank, and what it is read as. [sample] may be a password (the screen shows it only as
 * dots once the column is the password column), so `toString` shows neither text.
 */
internal data class MappingColumn(val header: String, val sample: String, val meaning: ColumnMeaning, val options: List<ColumnMeaning>) {
    override fun toString(): String = "MappingColumn($meaning, texts hidden)"
}

/** Why the column-mapping screen is shown, for its first sentence. */
internal enum class MappingReason {
    /** No column is a password column: "We couldn't find columns for website, username and password. Choose them yourself." */
    NO_PASSWORD,

    /** A column's meaning was taken by another column. */
    AMBIGUOUS,

    /** The user opened it from the preview. */
    CHECK,
}

/** What the column-mapping screen shows. */
internal sealed interface ColumnsScreen {
    data object Reading : ColumnsScreen

    data class Shown(val columns: List<MappingColumn>, val reason: MappingReason) : ColumnsScreen {
        /** A column is the password column; without one the accounts come without passwords. */
        val hasPassword: Boolean get() = columns.any { it.meaning == ColumnMeaning.PASSWORD }
    }

    /** Nothing to map (no import task, or not a CSV file): the screen goes back. */
    data object Gone : ColumnsScreen

    /** The vault locked: the columns were dropped. */
    data object Locked : ColumnsScreen
}

/** The column-mapping screen's state; [leave] once: go back to the file's screen. */
internal data class ColumnsUi(val screen: ColumnsScreen = ColumnsScreen.Reading, val leave: Boolean = false)

/**
 * The column-mapping screen (`Route.ColumnMapping`; ux.md §5.21, formats.md §8.3, formats-review P6):
 * each column of the CSV file with its first value and what it holds, Name · Website · Username ·
 * Password · Notes · Skip, pre-filled from the header aliases or from the user's earlier answer.
 * "Continue" hands the answer to the file's screen through [ImportMemory], which reads the file
 * again with it.
 *
 * It reads the chosen file itself (in the encoding the user chose), so that it comes back after a
 * lock as the file's screen does; the first values may be secrets and are dropped at a lock.
 */
internal class ImportColumnsViewModel(
    private val tasks: ImportTasks,
    private val documents: DocumentIo,
    private val repository: VaultRepository,
    private val random: RandomSource,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val memory: ImportMemory,
) : ViewModel() {
    private val state = MutableStateFlow(ColumnsUi())
    val ui: StateFlow<ColumnsUi> = state.asStateFlow()

    private var texts: EditTexts? = null
    private var started = false
    private var doc: DocRef? = null
    private var reading: Job? = null

    /** Starts with [texts] the first time (the importer needs them to read the file); later only takes them. */
    fun start(texts: EditTexts) {
        this.texts = texts
        if (started) return
        started = true
        viewModelScope.launch {
            repository.state.collect { vault ->
                if (vault !is VaultState.Unlocked) {
                    reading?.cancel()
                    set(ColumnsScreen.Locked)
                } else if (state.value.screen == ColumnsScreen.Locked) {
                    begin()
                }
            }
        }
        begin()
    }

    private fun begin() {
        val texts = texts ?: return
        if (repository.state.value !is VaultState.Unlocked) {
            set(ColumnsScreen.Locked)
            return
        }
        val d = tasks.document
        if (d == null) {
            gone()
            return
        }
        doc = d
        set(ColumnsScreen.Reading)
        val choices = memory.choicesFor(d)
        reading?.cancel()
        reading = viewModelScope.launch {
            val bytes = try {
                documents.read(d, Importer.MAX_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            if (bytes == null) {
                gone()
                return@launch
            }
            val result = withContext(dispatchers.default) {
                try {
                    Importer.read(bytes, clock.wallMs(), texts, random, Importer.Choices(encoding = choices.encoding))
                } catch (e: OutOfMemoryError) {
                    null
                } finally {
                    Bytes.wipe(bytes)
                }
            }
            when (result) {
                is Importer.Result.ChooseColumns -> show(result.columns, MappingReason.NO_PASSWORD, choices.columns)
                is Importer.Result.Read -> {
                    val columns = result.file.columns
                    if (columns == null) {
                        gone()
                    } else {
                        show(columns, if (columns.ambiguous.isEmpty()) MappingReason.CHECK else MappingReason.AMBIGUOUS, choices.columns)
                    }
                }
                else -> gone() // the file's screen says what is wrong with it
            }
        }
    }

    private fun show(columns: ImportColumns, reason: MappingReason, earlier: List<ColumnMeaning>?) {
        val shown = columns.headers.indices.map { i ->
            val meaning = earlier?.getOrNull(i) ?: columns.meanings[i]
            MappingColumn(columns.headers[i].replace("\uFEFF", "").trim(), columns.samples.getOrElse(i) { "" }.trim(), meaning, optionsFor(meaning))
        }
        set(ColumnsScreen.Shown(shown, reason))
    }

    /** Column [index] holds [meaning]. A meaning that one column only can have leaves the column that had it, which becomes an extra field. */
    fun choose(index: Int, meaning: ColumnMeaning) {
        val now = state.value.screen as? ColumnsScreen.Shown ?: return
        if (index !in now.columns.indices) return
        val columns = now.columns.mapIndexed { i, c ->
            when {
                i == index -> c.copy(meaning = meaning, options = optionsFor(meaning, c.options))
                meaning !in MANY && c.meaning == meaning -> c.copy(meaning = ColumnMeaning.FIELD)
                else -> c
            }
        }
        set(now.copy(columns = columns))
    }

    /** "Continue": the file's screen reads the file with these meanings. */
    fun confirm() {
        val now = state.value.screen as? ColumnsScreen.Shown ?: return
        val d = doc ?: return
        val meanings = now.columns.map { it.meaning }
        memory.update(d) { it.copy(columns = meanings, columnsShown = true) }
        state.update { it.copy(leave = true) }
    }

    private fun gone() {
        set(ColumnsScreen.Gone)
        state.update { it.copy(leave = true) }
    }

    /** The screen went back. */
    fun left() = state.update { it.copy(leave = false) }

    private fun set(screen: ColumnsScreen) = state.update { it.copy(screen = screen) }

    override fun onCleared() {
        reading?.cancel()
        set(ColumnsScreen.Locked)
    }

    companion object {
        /** The meanings the screen offers for every column (ux.md §5.21), and "Extra field", what a column without a meaning becomes. */
        val OFFERED: List<ColumnMeaning> = listOf(
            ColumnMeaning.TITLE,
            ColumnMeaning.WEBSITE,
            ColumnMeaning.USERNAME,
            ColumnMeaning.PASSWORD,
            ColumnMeaning.NOTES,
            ColumnMeaning.FIELD,
            ColumnMeaning.SKIP,
        )

        /** The meanings several columns may have at once. */
        private val MANY: Set<ColumnMeaning> = setOf(ColumnMeaning.FIELD, ColumnMeaning.SKIP)

        /** The options of a column that is read as [meaning]: [OFFERED], and the column's own meaning (a folder, a date …) when it is another one. */
        fun optionsFor(meaning: ColumnMeaning, before: List<ColumnMeaning> = emptyList()): List<ColumnMeaning> {
            val extra = (before + meaning).filter { it !in OFFERED }.distinct()
            return OFFERED + extra
        }

        /** The column mapping with the app's own parts. */
        fun create(graph: AppGraph): ImportColumnsViewModel = ImportColumnsViewModel(
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
