package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding
import io.github.dilshodbekmx.passwordvault.files.DocRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the import's screens hand to each other, in memory only (architecture.md §3.6, "Values handed
 * to the next screen"): the user's choices for the chosen file, and which file a screen has just seen
 * picked. Nothing here is secret: an encoding, the meanings of the columns, three switches, a
 * `content://` address. So it survives a lock, and the preview comes back after the unlock as the
 * user left it; a new process starts without it, with the file's defaults.
 *
 * The choices belong to one document ([Choices.doc]); choices for another document start from the
 * defaults again. One per process ([shared]); tests make their own.
 */
internal class ImportMemory {
    /**
     * The user's choices for the file [doc] (a `content://` address).
     *
     * @property encoding "Text looks wrong? Read as …" (formats.md §8.2); null: `TextDecoding` chooses.
     * @property columns the column-mapping screen's answer, one meaning per column; null: the aliases'.
     * @property columnsShown the column-mapping screen was opened for this file, by itself or by the
     *   user, so it does not open by itself again.
     * @property includeWrongLength the rows with the wrong number of values were confirmed (formats-review P4).
     * @property keepSeparate "Keep them separate" (ux.md §5.21, rules (b) and (c)).
     * @property tidyNames "Tidy names"; null: the file's default (the owner's decision 10 of 2026-10-08).
     */
    data class Choices(
        val doc: String,
        val encoding: TextDecoding.Encoding? = null,
        val columns: List<ColumnMeaning>? = null,
        val columnsShown: Boolean = false,
        val includeWrongLength: Boolean = false,
        val keepSeparate: Boolean = false,
        val tidyNames: Boolean? = null,
    )

    private val current = MutableStateFlow<Choices?>(null)

    /** The choices last made, for whichever document. */
    val choices: StateFlow<Choices?> = current.asStateFlow()

    /** The choices for [doc]: the ones made, or the defaults. */
    fun choicesFor(doc: DocRef): Choices = current.value?.takeIf { it.doc == doc.uri } ?: Choices(doc.uri)

    /** Changes the choices for [doc]; the choices for any other document are forgotten. */
    fun update(doc: DocRef, change: (Choices) -> Choices) {
        current.value = change(choicesFor(doc))
    }

    /** The import of [doc] ended (finished or given up): its choices go. */
    fun forget(doc: DocRef?) {
        if (doc == null || current.value?.doc == doc.uri) current.value = null
    }

    @Volatile private var fresh: String? = null

    /**
     * The sources screen opened the picker and received [doc] itself, without a lock in between: the
     * file's screen then does not say "This was paused when the app locked." (ux.md §4).
     */
    fun markFresh(doc: DocRef) {
        fresh = doc.uri
    }

    /** Whether [doc] was just picked ([markFresh]); asked once, by the file's screen when it opens. */
    fun takeFresh(doc: DocRef): Boolean {
        val was = fresh == doc.uri
        fresh = null
        return was
    }

    companion object {
        /** The process's one memory. */
        val shared: ImportMemory = ImportMemory()
    }
}
