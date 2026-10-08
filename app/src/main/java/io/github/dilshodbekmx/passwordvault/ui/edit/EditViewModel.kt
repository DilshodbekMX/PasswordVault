package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Draft
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryField
import io.github.dilshodbekmx.passwordvault.core.model.EntryProblem
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.core.strength.Strength
import io.github.dilshodbekmx.passwordvault.core.strength.StrengthMeter
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.ui.common.clearSecret
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add and edit (ux.md §5.14): the form of a new account ([entryId] null) or of the account
 * [entryId], with the password generator sheet (ux.md §5.15, [generator]) and the "Add field" dialog
 * ([addField]).
 *
 * **What is typed** lives in [form] (`TextFieldState`s, never in saved state) and becomes a vault
 * change only at Save, through `VaultEdits.add` or `VaultEdits.update`, which apply `EntryRules`, fill
 * a blank name from the first website's host or with `EditTexts.untitled`, and delete this form's
 * draft in the same save. Nothing compares the password with the master password (D-9b).
 *
 * **Where a new account starts.** Empty, or with the name that the list's 'Add "gmal" as a new
 * account' handed over in memory ([NewAccountName], taken once, when this ViewModel is made). That
 * name is part of what the form started from, so a form with nothing else typed has no change: Back
 * asks nothing and no draft is kept (ux.md §5.11).
 *
 * **The draft** (ux.md §5.14, architecture.md §5.1, §5.5). While the form differs from what it
 * started from, every change is handed to the repository with `holdDraft` (a generated password
 * counts as a change); `AutoLock` writes it into the vault with `flushDraft` when the app is left or
 * locks, so the form survives a lock, a kill and a recreation without touching the entry. A form
 * that opens on a vault holding its own draft (the same entry id, or none for a new account) shows
 * the draft with "Your unsaved changes were kept." When the draft is 30 days old or more, it is
 * offered once instead (Open · Discard) and then dropped from the vault either way. Save and Discard
 * delete it; there is only one draft, so the newest form wins.
 *
 * **A lock** publishes `VaultState.Locked`: every field and its undo history is emptied, the dialogs
 * and the sheet close, and nothing more is held (architecture.md §5.5 step 5, §5.4). The next unlock
 * loads the form again, from the draft that the lock wrote. Leaving for good (onCleared) empties the
 * fields too, but never drops the held draft: when Android destroys the activity in the background,
 * the ViewModels go before `AutoLock`'s flush, which must still find it (lock.md §11).
 *
 * **Leaving**: Close and Back ask "Save changes?" (Save · Discard · Keep editing) while something
 * changed; [EditUiState.done] says where to go after Save, Discard or Close. A new account opens its
 * detail screen, an edited one goes back (ux.md §5.14).
 *
 * **The keyboard after a rotation** (architecture.md §5.4, [V-P7]): the screen tells [focused] which
 * field has focus, and asks [focusToRestore] for it when it is made again.
 *
 * @param measure the strength of a password with the words it should not lean on
 *   (`StrengthMeter.measure`); called on the default dispatcher.
 */
class EditViewModel(
    val entryId: String?,
    private val repository: VaultRepository,
    settings: AppSettings,
    private val random: RandomSource,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val measure: (CharSequence, List<String>) -> Strength = ::sharedMeter,
) : ViewModel() {

    /** The fields of the form. */
    val form = EditForm()

    /** The fields of the "Add field" dialog. */
    val addField = AddFieldForm()

    /** The password generator sheet. */
    val generator = GeneratorModel(viewModelScope, settings, random, dispatchers) { measure(it, emptyList()) }

    private val mutable = MutableStateFlow(EditUiState(isNew = entryId == null))

    /** What the screen shows besides the fields. */
    val ui: StateFlow<EditUiState> = mutable

    /** The open vault as last published, while unlocked. */
    private var vault: Vault? = null

    /** The form as it was loaded, in [EditForm.draft]'s form; null while nothing is loaded. */
    private var baseline: DraftEntry? = null

    /** The stored draft offered as 30 days old or more, until it is answered. */
    private var oldDraft: Draft? = null

    /** Counts the locks, so that a save that finishes after one changes nothing on the screen. */
    private var session = 0

    private var lastFocus: FieldKey? = null
    private var progress: Job? = null

    /**
     * The name a new account starts with: what [NewAccountName] holds when this form is made, as the
     * name field keeps it; empty for none, and always for an existing account. A lock drops it with
     * the rest of the form. Before [init], whose first unlocked state may load the form at once.
     */
    private var startName: String = if (entryId == null) NewAccountName.take()?.let(::nameOf).orEmpty() else ""

    init {
        viewModelScope.launch { repository.state.collect(::onVault) }
        viewModelScope.launch { snapshotFlow { form.draft() }.collect { formChanged() } }
        viewModelScope.launch {
            snapshotFlow { StrengthInput(form.password.text.toString(), form.title.text.toString(), form.username.text.toString()) }
                .collectLatest(::measurePassword)
        }
    }

    // ---- The vault and the draft -------------------------------------------------------------

    private fun onVault(state: VaultState) {
        when (state) {
            is VaultState.Locked -> if (vault != null) locked()
            is VaultState.Unlocked -> {
                vault = state.vault
                val phase = mutable.value.phase
                if (phase == Phase.LOADING) load(state.vault)
                mutable.update { it.copy(storedDraft = ownDraft(state.vault) != null) }
            }
        }
    }

    /** Fills the form from [vault]: the entry (or a new account's start), then this form's draft. */
    private fun load(vault: Vault) {
        val entry = entryId?.let { id -> vault.entries.firstOrNull { it.id == id && it.deletedAt == null } }
        if (entryId != null && entry == null) {
            mutable.update { it.copy(phase = Phase.GONE) }
            return
        }
        form.load(entry?.let(::formOf) ?: DraftEntry(title = startName))
        baseline = form.draft()
        var message: EditMessage? = null
        var dialog: EditDialog? = null
        val stored = ownDraft(vault)
        if (stored != null) {
            if (clock.wallMs() - stored.savedAt >= OLD_DRAFT_MS) {
                oldDraft = stored
                dialog = EditDialog.OldDraft(stored.entry.title.trim().ifEmpty { entry?.title }, stored.savedAt)
            } else {
                form.load(stored.entry)
                message = EditMessage.Kept
            }
        }
        mutable.update { it.copy(phase = Phase.FORM, dialog = dialog, message = message) }
        formChanged()
    }

    /** After a lock: nothing of the form is kept here (architecture.md §5.5 step 5). */
    private fun locked() {
        session++
        progress?.cancel()
        vault = null
        baseline = null
        oldDraft = null
        lastFocus = null
        startName = ""
        generator.close()
        addField.clear()
        form.clear()
        mutable.value = EditUiState(isNew = entryId == null)
    }

    /** Whether the form differs from what it started from; holds the draft or lets it go. */
    private fun formChanged() {
        val start = baseline ?: return
        val now = mutable.value
        if (now.phase != Phase.FORM || now.saving) return
        val typed = form.draft()
        val dirty = typed != start
        repository.holdDraft(if (dirty) Draft(entryId, clock.wallMs(), typed) else null)
        if (dirty != now.dirty) mutable.update { it.copy(dirty = dirty) }
    }

    /** The vault's draft when it belongs to this form. */
    private fun ownDraft(vault: Vault): Draft? = vault.draft?.takeIf { it.entryId == entryId }

    /** "Open" on the offer of a draft 30 days old or more: the form shows it, and the old one leaves the vault. */
    fun openOldDraft() {
        val stored = oldDraft ?: return
        oldDraft = null
        mutable.update { it.copy(dialog = null) }
        form.load(stored.entry)
        formChanged()
        viewModelScope.launch { repository.edit(VaultEdits.clearDraft()) }
    }

    /** "Discard" on that offer: the draft leaves the vault, the form stays as it was loaded. */
    fun discardOldDraft() {
        if (oldDraft == null) return
        oldDraft = null
        mutable.update { it.copy(dialog = null) }
        viewModelScope.launch { repository.edit(VaultEdits.clearDraft()) }
    }

    // ---- Saving ------------------------------------------------------------------------------

    /** Save (ux.md §5.14). [texts] word what Save may store, such as "Untitled" and the time. */
    fun save(texts: EditTexts) = save(texts, sameLoginAsked = false)

    /** "Save anyway" after "You already have Gmail with this login." */
    fun saveAnyway(texts: EditTexts) = save(texts, sameLoginAsked = true)

    private fun save(texts: EditTexts, sameLoginAsked: Boolean) {
        val now = mutable.value
        val open = vault
        if (now.phase != Phase.FORM || now.saving || open == null) return
        val typed = form.typed()
        val problems = EntryRules.problems(typed)
        if (problems.isNotEmpty()) {
            mutable.update { it.copy(dialog = null, problems = problems, focusRequest = focusRequestFor(problems, it.focusRequest)) }
            return
        }
        if (!sameLoginAsked) {
            val ready = EntryRules.prepare(typed)
            val same = EntryRules.sameLogin(open, EntryRules.title(ready, clock.wallMs(), texts), ready.username, entryId)
            if (same != null) {
                mutable.update { it.copy(dialog = EditDialog.SameLogin(same.title), problems = emptyList()) }
                return
            }
        }
        val mine = session
        // Nothing to flush while the save runs: the save itself deletes this form's draft.
        repository.holdDraft(null)
        mutable.update { it.copy(saving = true, dialog = null, problems = emptyList()) }
        progress = viewModelScope.launch {
            delay(PROGRESS_AFTER_MS)
            if (session == mine) mutable.update { it.copy(showProgress = it.saving) }
        }
        val change = if (entryId == null) VaultEdits.add(typed, texts, random) else VaultEdits.update(entryId, typed, texts)
        viewModelScope.launch {
            val result = repository.edit(change)
            progress?.cancel()
            if (session != mine) return@launch
            when (result) {
                is SaveResult.Saved -> when (val value = result.value) {
                    is EntryResult.Done -> {
                        baseline = form.draft()
                        if (entryId == null) SavedNotice.mark(value.entryId)
                        val done = if (entryId == null) EditDone.Added(value.entryId) else EditDone.Updated(value.entryId)
                        mutable.update { it.copy(phase = Phase.DONE, saving = false, showProgress = false, dirty = false, done = done) }
                    }
                    is EntryResult.Refused -> notSaved(problems = value.problems)
                    EntryResult.NotFound -> mutable.update { it.copy(phase = Phase.GONE, saving = false, showProgress = false) }
                    EntryResult.NotPending -> notSaved(message = EditMessage.SaveFailed(SaveProblem.FAILED))
                }
                is SaveResult.NotSaved -> notSaved(message = EditMessage.SaveFailed(result.problem))
            }
        }
    }

    /** The save did not happen: the form stays, and its draft is held again. */
    private fun notSaved(problems: List<EntryProblem> = emptyList(), message: EditMessage? = null) {
        mutable.update {
            it.copy(
                saving = false,
                showProgress = false,
                problems = problems,
                message = message ?: it.message,
                focusRequest = if (problems.isEmpty()) it.focusRequest else focusRequestFor(problems, it.focusRequest),
            )
        }
        formChanged()
    }

    // ---- Leaving -----------------------------------------------------------------------------

    /** Close (X) and Back: "Save changes?" while something changed (ux.md §4), otherwise leave. */
    fun close() {
        val now = mutable.value
        when (now.phase) {
            Phase.LOADING, Phase.GONE -> mutable.update { it.copy(phase = Phase.DONE, done = EditDone.Closed) }
            Phase.FORM -> when {
                now.saving -> Unit
                now.dirty -> mutable.update { it.copy(dialog = EditDialog.SaveChanges) }
                else -> leave()
            }
            Phase.DONE -> Unit
        }
    }

    /** "Discard": the changes and the draft are gone; nothing was saved (ux.md §12 check 4). */
    fun discard() {
        if (mutable.value.phase != Phase.FORM || mutable.value.saving) return
        leave()
    }

    /** "Keep editing", and Cancel on the other questions: the dialog closes. */
    fun dismissDialog() {
        if (mutable.value.dialog == EditDialog.AddField) addField.clear()
        mutable.update { it.copy(dialog = null) }
    }

    /** Leaves without saving: the held draft is let go, and this form's draft leaves the vault. */
    private fun leave() {
        repository.holdDraft(null)
        mutable.update { it.copy(phase = Phase.DONE, dialog = null) }
        val stored = vault?.let(::ownDraft)
        viewModelScope.launch {
            if (stored != null) repository.edit(VaultEdits.clearDraft())
            mutable.update { it.copy(done = EditDone.Closed) }
        }
    }

    // ---- Rows --------------------------------------------------------------------------------

    /** "Add another website" (up to 10); the new row gets the focus. */
    fun addWebsite() {
        val row = form.addWebsite() ?: return
        mutable.update { it.copy(focusRequest = FocusRequest(FieldKey.Website(row.id), (it.focusRequest?.serial ?: 0) + 1)) }
    }

    /** "Remove" next to an extra website. */
    fun removeWebsite(row: Long) {
        form.removeWebsite(row)
        dropProblems()
    }

    /** "Remove" next to an extra field. */
    fun removeField(row: Long) {
        form.removeField(row)
        dropProblems()
    }

    /** "Add field": the dialog opens with no label chosen. */
    fun openAddField() {
        if (mutable.value.phase != Phase.FORM || form.fields.size >= EntryRules.FIELDS_MAX) return
        addField.clear()
        mutable.update { it.copy(dialog = EditDialog.AddField) }
    }

    /** A label chip of the dialog; "Hide this value" follows the label's default. */
    fun chooseLabel(label: FieldLabel) {
        addField.label = label
        addField.secret = label.secretByDefault
        addField.labelMissing = false
    }

    /** The dialog's "Hide this value". */
    fun setFieldSecret(secret: Boolean) {
        addField.secret = secret
    }

    /**
     * "Add" in the dialog: the field joins the form. [chosenLabel] is the chosen chip's text in the
     * screen's language (it is stored as the field's name); for "Other…" the typed label is used.
     */
    fun confirmAddField(chosenLabel: String) {
        val label = addField.label
        val name = if (label == null || label == FieldLabel.OTHER) addField.name.text.toString().trim() else chosenLabel.trim()
        if (name.isEmpty()) {
            addField.labelMissing = true
            return
        }
        val row = form.addField(name, addField.value.text, addField.secret) ?: return
        addField.clear()
        mutable.update {
            it.copy(dialog = null, focusRequest = FocusRequest(FieldKey.FieldValue(row.id), (it.focusRequest?.serial ?: 0) + 1))
        }
        formChanged()
    }

    // ---- The generator -----------------------------------------------------------------------

    /** "Create a strong password": the sheet opens. */
    fun openGenerator() {
        if (mutable.value.phase == Phase.FORM) generator.open()
    }

    /** The sheet is dismissed. */
    fun closeGenerator() = generator.close()

    /** "Use this password": into the field, after "Replace the current password?" when it has one. */
    fun useGenerated() {
        if (generator.password() == null) return
        if (form.password.text.isNotEmpty()) {
            mutable.update { it.copy(dialog = EditDialog.ReplacePassword) }
        } else {
            fillGenerated()
        }
    }

    /** "Replace" in that dialog. */
    fun replacePassword() {
        mutable.update { it.copy(dialog = null) }
        fillGenerated()
    }

    private fun fillGenerated() {
        val password = generator.password() ?: return
        form.password.setTextAndPlaceCursorAtEnd(password)
        generator.close()
        dropProblems()
        // Held now, not at the next snapshot notice: a generated password counts as a change (ux.md §5.14).
        formChanged()
    }

    // ---- The rest of the screen --------------------------------------------------------------

    /** The snackbar of [message] was shown; a newer message stays. */
    fun messageShown(message: EditMessage) = mutable.update { if (it.message == message) it.copy(message = null) else it }

    /** The screen moved on after [EditUiState.done]. */
    fun navigated() = mutable.update { it.copy(done = null) }

    /** The screen asked for the focus of [EditUiState.focusRequest]. */
    fun focusRequested() = mutable.update { it.copy(focusRequest = null) }

    /** The field [key] got the focus. */
    fun focused(key: FieldKey) {
        lastFocus = key
    }

    /** The field [key] lost the focus while the screen was in front (not because the activity went away). */
    fun unfocused(key: FieldKey) {
        if (lastFocus == key) lastFocus = null
    }

    /** The field that had the focus, for the keyboard after a rotation (architecture.md §5.4). */
    fun focusToRestore(): FieldKey? = lastFocus.takeIf { mutable.value.phase == Phase.FORM }

    /** The counter of the field [key] when it is nearly full (ux.md §5.14); null below that or for no such field. */
    fun counter(key: FieldKey): Counter? {
        val (state, max) = stateOf(key) ?: return null
        return Counter.of(state.text, max)
    }

    /** What typing and pasting may put into the field [key] (its `InputTransformation`). */
    fun inputOf(key: FieldKey): FieldInput = when (key) {
        FieldKey.Title -> FieldInput.TITLE
        FieldKey.Username -> FieldInput.USERNAME
        FieldKey.Password -> FieldInput.PASSWORD
        FieldKey.Notes -> FieldInput.NOTES
        is FieldKey.Website -> FieldInput.WEBSITE
        is FieldKey.FieldValue -> if (form.fields.firstOrNull { it.id == key.row }?.secret == false) FieldInput.VALUE else FieldInput.SECRET_VALUE
        FieldKey.AddFieldName -> FieldInput.FIELD_NAME
        FieldKey.AddFieldValue -> if (addField.secret) FieldInput.SECRET_VALUE else FieldInput.VALUE
    }

    /** The text field of [key] and its limit in code points (ux.md §2). */
    fun stateOf(key: FieldKey): Pair<TextFieldState, Int>? = when (key) {
        FieldKey.Title -> form.title to EntryRules.TITLE_MAX
        FieldKey.Username -> form.username to EntryRules.USERNAME_MAX
        FieldKey.Password -> form.password to EntryRules.PASSWORD_MAX
        FieldKey.Notes -> form.notes to EntryRules.NOTES_MAX
        is FieldKey.Website -> form.websites.firstOrNull { it.id == key.row }?.let { it.state to EntryRules.URL_MAX }
        is FieldKey.FieldValue -> form.fields.firstOrNull { it.id == key.row }?.let { it.value to EntryRules.FIELD_VALUE_MAX }
        FieldKey.AddFieldName -> addField.name to EntryRules.FIELD_NAME_MAX
        FieldKey.AddFieldValue -> addField.value to EntryRules.FIELD_VALUE_MAX
    }

    /** Of [problems] (`EditUiState.problems`), those about [key]'s field, to show under it. */
    fun problemsOf(problems: List<EntryProblem>, key: FieldKey): List<EntryProblem> =
        if (problems.isEmpty()) emptyList() else problems.filter { keyOf(it) == key }

    /** The field a problem of `EntryRules` is about, by the row's position as typed. */
    private fun keyOf(problem: EntryProblem): FieldKey? = when (problem.field) {
        EntryField.TITLE -> FieldKey.Title
        EntryField.USERNAME -> FieldKey.Username
        EntryField.PASSWORD -> FieldKey.Password
        EntryField.NOTES -> FieldKey.Notes
        EntryField.WEBSITE -> form.websites.getOrNull(problem.index)?.let { FieldKey.Website(it.id) }
        EntryField.FIELD_NAME, EntryField.FIELD_VALUE -> form.fields.getOrNull(problem.index)?.let { FieldKey.FieldValue(it.id) }
        EntryField.WEBSITES, EntryField.FIELDS -> null
    }

    /** A failed save moves the focus to the first field it names (ux.md §8). */
    private fun focusRequestFor(problems: List<EntryProblem>, last: FocusRequest?): FocusRequest? {
        val key = problems.firstNotNullOfOrNull(::keyOf) ?: return last
        return FocusRequest(key, (last?.serial ?: 0) + 1)
    }

    /** The rows moved: problems that named rows by position no longer fit them. */
    private fun dropProblems() {
        if (mutable.value.problems.isNotEmpty()) mutable.update { it.copy(problems = emptyList()) }
    }

    private suspend fun measurePassword(input: StrengthInput) {
        if (input.password.isEmpty() || baseline == null) {
            mutable.update { if (it.strength == null && it.reuse == 0) it else it.copy(strength = null, reuse = 0) }
            return
        }
        val open = vault
        val inputs = SecurityCheck.userInputs(input.title, input.username)
        val (strength, reuse) = withContext(dispatchers.default) {
            measure(input.password, inputs) to (open?.let { SecurityCheck.otherAccountsWith(it, input.password, entryId) } ?: 0)
        }
        if (baseline != null) mutable.update { it.copy(strength = strength, reuse = reuse) }
    }

    override fun onCleared() {
        // The held draft stays held: see the class comment.
        progress?.cancel()
        baseline = null
        generator.close()
        addField.clear()
        form.clear()
    }

    /** The password and the words around it, for the meter; [toString] hides them. */
    private class StrengthInput(val password: String, val title: String, val username: String) {
        override fun equals(other: Any?): Boolean =
            other is StrengthInput && other.password == password && other.title == title && other.username == username

        override fun hashCode(): Int = password.hashCode() * 31 + title.hashCode()

        override fun toString(): String = "StrengthInput(texts hidden)"
    }

    companion object {
        /** A draft this old or older is offered once instead of opened (ux.md §5.14): 30 days. */
        const val OLD_DRAFT_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** Save shows its small progress after this long (ux.md §5.14). */
        const val PROGRESS_AFTER_MS: Long = 300L

        /** The form of the account [id] (null: a new one) with the app's own parts. */
        fun create(graph: AppGraph, id: String?): EditViewModel = EditViewModel(
            entryId = id,
            repository = graph.data.repository,
            settings = graph.settings,
            random = graph.platform.random,
            clock = graph.platform.clock,
            dispatchers = graph.dispatchers,
        )

        /** The form's fields as an entry holds them. */
        fun formOf(entry: Entry): DraftEntry = DraftEntry(
            title = entry.title,
            username = entry.username,
            password = entry.password,
            notes = entry.notes,
            urls = entry.urls,
            fields = entry.fields.map { CustomField(it.name, it.value, it.secret) },
            favourite = entry.favourite,
        )

        /**
         * What the name field keeps of [text] pasted into it while empty: the rules of
         * [FieldInput.TITLE] (no control characters, at most 200 code points), then without white
         * space at either end, as Save would store it.
         */
        internal fun nameOf(text: String): String {
            val state = TextFieldState()
            state.edit {
                append(text)
                with(FieldInput.TITLE) { transformInput() }
            }
            return state.text.toString().trim()
        }

        private fun sharedMeter(password: CharSequence, inputs: List<String>): Strength = StrengthMeter.shared().measure(password, inputs)
    }
}

/** Where the form is: loading from the vault, shown, gone (the account was deleted), or done. */
enum class Phase { LOADING, FORM, GONE, DONE }

/**
 * What the add and edit screen shows besides the fields (architecture.md §5.4). It holds no text the
 * user typed, except the name of the account a question is about.
 *
 * @property dirty the form differs from what it started from: Back and Close ask first.
 * @property storedDraft the vault holds this form's draft.
 * @property saving Save is running; [showProgress] once it has run for 300 ms.
 * @property problems what Save found wrong (`EntryRules.problems`), shown under the fields.
 * @property strength the typed password's strength; null while it is empty.
 * @property reuse how many other accounts have the typed password.
 * @property done where to go now; the screen calls `navigated()` once it has gone.
 * @property focusRequest a field that should get the focus (a new row, or the first invalid field).
 */
data class EditUiState(
    val isNew: Boolean,
    val phase: Phase = Phase.LOADING,
    val dirty: Boolean = false,
    val storedDraft: Boolean = false,
    val saving: Boolean = false,
    val showProgress: Boolean = false,
    val problems: List<EntryProblem> = emptyList(),
    val strength: Strength? = null,
    val reuse: Int = 0,
    val dialog: EditDialog? = null,
    val message: EditMessage? = null,
    val done: EditDone? = null,
    val focusRequest: FocusRequest? = null,
) {
    /**
     * Whether Back is the form's own (ux.md §4: only while something would be lost, so that the
     * predictive back is not shown for a back that will be stopped): while the form changed, or while
     * the vault holds a draft of it that leaving must delete.
     */
    val backHandled: Boolean get() = phase == Phase.FORM && (dirty || storedDraft)
}

/** The dialogs of the form. */
sealed interface EditDialog {
    /** "Save changes?" — Save · Discard · Keep editing. */
    data object SaveChanges : EditDialog

    /** "You already have [title] with this login. Save anyway?" */
    class SameLogin(val title: String) : EditDialog {
        override fun toString(): String = "SameLogin(title hidden)"
    }

    /** "Replace the current password?" */
    data object ReplacePassword : EditDialog

    /** "You have unsaved changes for [title] from [savedAt]. Open them?"; [title] null: a new account without a name. */
    class OldDraft(val title: String?, val savedAt: Long) : EditDialog {
        override fun toString(): String = "OldDraft(savedAt=$savedAt, title hidden)"
    }

    /** "Add field". */
    data object AddField : EditDialog
}

/** The short messages at the bottom of the form. */
sealed interface EditMessage {
    /** "Your unsaved changes were kept." */
    data object Kept : EditMessage

    /** Save failed: "Couldn't save. …" with "Try again", or for TOO_LARGE the sentence of architecture.md §3.5. */
    data class SaveFailed(val problem: SaveProblem) : EditMessage
}

/** Where the screen goes after the form. */
sealed interface EditDone {
    /** Saved a new account [entryId]: its detail screen opens in place of the form. */
    data class Added(val entryId: String) : EditDone

    /** Saved the account [entryId]: back to where the form was opened from (its detail screen). */
    data class Updated(val entryId: String) : EditDone

    /** Closed without saving: back. */
    data object Closed : EditDone
}

/** A field of the form or of the "Add field" dialog, for the focus. Holds row ids, never text. */
sealed interface FieldKey {
    data object Title : FieldKey
    data object Username : FieldKey
    data object Password : FieldKey
    data object Notes : FieldKey
    data class Website(val row: Long) : FieldKey
    data class FieldValue(val row: Long) : FieldKey
    data object AddFieldName : FieldKey
    data object AddFieldValue : FieldKey
}

/** Give [key] the focus; [serial] makes a new request of the same field a new value. */
data class FocusRequest(val key: FieldKey, val serial: Int)

/** The label chips of the "Add field" dialog (ux.md §5.14) and whether each hides its value by default. */
enum class FieldLabel(@StringRes val text: Int, val secretByDefault: Boolean) {
    PIN(R.string.edit_field_pin, true),
    PHONE(R.string.edit_field_phone, false),
    RECOVERY_EMAIL(R.string.edit_field_recovery_email, false),
    QUESTION(R.string.edit_field_question, true),
    ANSWER(R.string.edit_field_answer, true),
    CODE_WORD(R.string.edit_field_code_word, true),
    OTHER(R.string.edit_field_other, true),
}

/** The fields of the "Add field" dialog, held like the form's (architecture.md §5.6). Main thread only. */
class AddFieldForm {
    /** The label typed for "Other…". */
    val name = TextFieldState()

    /** The value. */
    val value = TextFieldState()

    /** The chosen chip; null before one is chosen. */
    var label: FieldLabel? by mutableStateOf(null)

    /** "Hide this value". */
    var secret: Boolean by mutableStateOf(true)

    /** "Add" was tapped without a label: "Give this field a label." */
    var labelMissing: Boolean by mutableStateOf(false)

    /** Empties both fields and their undo history, and forgets the choices. */
    fun clear() {
        name.clearSecret()
        value.clearSecret()
        label = null
        secret = true
        labelMissing = false
    }
}
