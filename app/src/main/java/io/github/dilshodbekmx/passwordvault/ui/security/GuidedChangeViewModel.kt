package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.core.generator.PasswordGenerator
import io.github.dilshodbekmx.passwordvault.core.generator.isValid
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultChange
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.core.strength.Strength
import io.github.dilshodbekmx.passwordvault.core.strength.StrengthMeter
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.data.SaveProblem
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.ui.edit.GeneratorModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The guided change of one password (ux.md §5.17): five numbered steps that the person comes back
 * to several times while they change the password on the website, then the end of the change.
 *
 * **Where the change is.** In the account, never here: [VaultEdits.startChange] saves the new
 * password at once as the account's password and keeps the website's in `pendingOldPassword`;
 * [VaultEdits.retryChange], [VaultEdits.confirmChange] and [VaultEdits.revertChange] go on from
 * there. Each is one `VaultRepository.edit` (architecture.md §5.4), so the change survives a lock and
 * the end of the process at every step, and whatever opens this screen again (the route after a
 * lock, the group list after a new process) shows the step the account is at. The account is read
 * from the open vault by its id whenever the vault changes. The only thing kept outside it is the end
 * of a change, so that it shows again after a lock ([GuidedChangeMemory]).
 *
 * **The passwords** are named from the website's point of view: the old one is the one the website
 * has now (`pendingOldPassword` while a change waits for its answer, the account's password before),
 * the new one the one the app made. Neither is ever in [state]: a copy reads its text from the vault
 * at the moment of the tap and hands it to [SecretClipboard], with the sensitive flag (ux.md §6).
 *
 * **A new password** ("Create new password", "Create another password") is made by
 * `PasswordGenerator` with the generator's options as last chosen (`AppSettings.generator`, which
 * start as ux.md §5.15's defaults), so that rules the person set in "Password rules" for a website
 * that refused a password hold for the next try too. "Password rules" opens the generator sheet
 * ([generator]); its "Use this password" saves the sheet's password in the same way.
 *
 * **A lock** publishes `VaultState.Locked`: everything of the account is dropped here at once, and
 * a save that ends after it shows nothing (the account shows what was saved after the unlock).
 *
 * Every function is called on the main thread.
 *
 * @param titleOrder the A to Z of the app's language (`SortKeys`), for the order in which "Next"
 *   offers the other accounts of the group (that of the group list, `SecurityCheck.listOrder`).
 * @param measure the strength of a password in the generator sheet (`StrengthMeter`); called on the
 *   default dispatcher.
 */
class GuidedChangeViewModel(
    val entryId: String,
    private val repository: VaultRepository,
    private val clipboard: SecretClipboard,
    private val settings: AppSettings,
    random: RandomSource,
    private val dispatchers: AppDispatchers,
    private val memory: GuidedChangeMemory,
    private val titleOrder: Comparator<String>? = null,
    measure: (CharSequence) -> Strength = ::sharedMeter,
) : ViewModel() {

    private val passwords = PasswordGenerator(random)

    /** The password generator sheet of "Password rules" (ux.md §5.15). */
    val generator = GeneratorModel(viewModelScope, settings, random, dispatchers, measure)

    private val mutable = MutableStateFlow<GuidedChangeUiState>(GuidedChangeUiState.Loading)

    /** What the screen shows. */
    val state: StateFlow<GuidedChangeUiState> = mutable

    private val eventChannel = Channel<GuidedChangeEvent>(Channel.BUFFERED)

    /** One-off messages for the screen's snackbar. */
    val events: Flow<GuidedChangeEvent> = eventChannel.receiveAsFlow()

    /** The open vault as last seen; null while locked. */
    private var vault: Vault? = null

    /** A save of this screen runs: its buttons wait, so that one tap makes one change. */
    private var busy = false

    /** Counts the locks, so that a save that ends after one changes nothing on the screen. */
    private var session = 0

    /** "Next" or "Done for now" was used: the screen is going. */
    private var left = false

    init {
        viewModelScope.launch { repository.state.collect(::onVault) }
    }

    // ---- The vault ---------------------------------------------------------------------------

    private fun onVault(now: VaultState) {
        when (now) {
            VaultState.Locked -> forgetAll()
            is VaultState.Unlocked -> {
                vault = now.vault
                render()
            }
        }
    }

    /** The vault as the repository publishes it now: a save's result is there before its collector runs. */
    private fun currentVault(): Vault? = (repository.state.value as? VaultState.Unlocked)?.vault.also { if (it != null) vault = it }

    /** The account outside Recently deleted; null when locked, deleted or gone. */
    private fun liveEntry(): Entry? = currentVault()?.entries?.firstOrNull { it.id == entryId && it.deletedAt == null }

    private fun render() {
        val open = vault
        if (open == null) {
            mutable.value = GuidedChangeUiState.Loading
            return
        }
        val entry = open.entries.firstOrNull { it.id == entryId }
        mutable.value = when {
            entry == null -> GuidedChangeUiState.Gone(inRecentlyDeleted = false)
            entry.deletedAt != null -> GuidedChangeUiState.Gone(inRecentlyDeleted = true)
            ended(entry) -> {
                val next = GuidedChangeRules.nextAfter(open, entry, titleOrder)
                GuidedChangeUiState.Done(
                    title = entry.title,
                    nextId = next?.id,
                    nextTitle = next?.title,
                    // Until a backup holds the change: "Back up now" leads there and back here.
                    backupLine = memory.backupLine(entryId) && GuidedChangeRules.notInBackup(open, entry),
                )
            }
            else -> GuidedChangeUiState.Steps(
                title = entry.title,
                username = entry.username,
                website = GuidedChangeRules.websiteOf(entry),
                started = entry.changePending,
                canCopyOld = SecurityCheck.websitePassword(entry).isNotEmpty(),
                canCopyNew = entry.changePending && entry.password.isNotEmpty(),
                lastOne = GuidedChangeRules.isLastOfGroup(open, entry),
                busy = busy,
            )
        }
    }

    /**
     * Whether the screen shows the end of the change of [entry]: "Yes, it's changed" was answered
     * here and the account is as it left it. A mark the account no longer matches is dropped.
     */
    private fun ended(entry: Entry): Boolean {
        val at = memory.doneAt(entryId) ?: return false
        if (!entry.changePending && entry.updatedAt == at) return true
        memory.forget(entryId)
        return false
    }

    /** The vault locked: nothing of the account stays here (architecture.md §5.5 step 5). */
    private fun forgetAll() {
        vault = null
        session++
        busy = false
        generator.close()
        mutable.value = GuidedChangeUiState.Loading
    }

    // ---- Copying (ux.md §6): true when the clip was handed over ------------------------------

    /**
     * "Copy old password" (steps 1 and 4): the password the website has now, `pendingOldPassword`
     * while a change waits for its answer, before it the account's password.
     */
    fun copyOld(): Boolean {
        val entry = liveEntry() ?: return false
        val text = SecurityCheck.websitePassword(entry)
        if (text.isEmpty()) return false
        return copy(text, UiText.of(R.string.change_copied_old, entry.title))
    }

    /** "Copy new password" (step 4): the password the app made; nothing before one was made. */
    fun copyNew(): Boolean {
        val entry = liveEntry() ?: return false
        if (!entry.changePending || entry.password.isEmpty()) return false
        return copy(entry.password, UiText.of(R.string.change_copied_new, entry.title))
    }

    private fun copy(text: String, what: UiText): Boolean = try {
        clipboard.copy(text, what, sensitive = true)
        true
    } catch (e: RuntimeException) {
        // Some vendor builds throw here (ux.md §6, "Failures"); nothing of the value is kept.
        eventChannel.trySend(GuidedChangeEvent.CopyBlocked)
        false
    }

    // ---- Step 3: the new password --------------------------------------------------------------

    /**
     * "Create new password", and "Create another password" once one was made: a new password from
     * the generator's options, saved at once ([VaultEdits.startChange], [VaultEdits.retryChange]).
     * The website's password stays kept however many tries it takes (ux.md §12 check 5).
     */
    fun create() {
        if (busy || liveEntry() == null) return
        val options = settings.generator.value.takeIf { it.isValid() } ?: GeneratorOptions()
        val mine = start()
        viewModelScope.launch {
            val made = withContext(dispatchers.default) {
                val chars = passwords.generate(options)
                try {
                    String(chars)
                } finally {
                    chars.fill('\u0000')
                }
            }
            saveNew(made, mine, again = ::create)
        }
    }

    /** "Password rules": the generator sheet opens. */
    fun openRules() {
        if (liveEntry() != null) generator.open()
    }

    /** The sheet is closed without its password. */
    fun closeRules() = generator.close()

    /** The sheet's "Use this password": that password is saved as "Create new password" saves one. */
    fun useRules() {
        if (busy || liveEntry() == null) return
        val password = generator.password() ?: return
        generator.close()
        val mine = start()
        viewModelScope.launch { saveNew(password, mine, again = { saveAgain(password) }) }
    }

    /** "Try again" after the sheet's password was not saved: the same password once more. */
    private fun saveAgain(password: String) {
        if (busy || liveEntry() == null) return
        val mine = start()
        viewModelScope.launch { saveNew(password, mine, again = { saveAgain(password) }) }
    }

    private suspend fun saveNew(password: String, mine: Int, again: () -> Unit) {
        // Locked while the password was being made: nothing is saved, and the screen is gone.
        if (!stillOpen(mine)) return
        val retry = liveEntry()?.changePending ?: false
        val change = if (retry) VaultEdits.retryChange(entryId, password) else VaultEdits.startChange(entryId, password)
        val result = repository.edit(change)
        if (!end(mine)) return
        when (result) {
            is SaveResult.Saved -> if (result.value is EntryResult.Done) eventChannel.send(GuidedChangeEvent.Created(again = retry))
            is SaveResult.NotSaved -> notSaved(result.problem, again)
        }
    }

    // ---- Step 5: the answer ----------------------------------------------------------------------

    /**
     * "Yes, it's changed": the website's old password joins the previous passwords and the change
     * ends ([VaultEdits.confirmChange]); the screen shows the end ("Gmail now has its own password.").
     */
    fun confirm() {
        if (busy || liveEntry()?.changePending != true) return
        val mine = start()
        viewModelScope.launch {
            val result = repository.edit(VaultEdits.confirmChange(entryId))
            if (result is SaveResult.Saved && result.value is EntryResult.Done) {
                val open = currentVault()
                val entry = open?.entries?.firstOrNull { it.id == entryId }
                if (open != null && entry != null && !entry.changePending && stillOpen(mine)) {
                    memory.markDone(entryId, entry.updatedAt, GuidedChangeRules.notInBackup(open, entry))
                }
            }
            if (!end(mine)) return@launch
            if (result is SaveResult.NotSaved) notSaved(result.problem, ::confirm)
        }
    }

    /**
     * "No, put back the old password": the website's password is the account's password again, the
     * rejected new one joins the previous passwords, and the change ends ([VaultEdits.revertChange]).
     * The steps start again from the top.
     */
    fun revert() {
        if (busy || liveEntry()?.changePending != true) return
        val mine = start()
        viewModelScope.launch {
            val result = repository.edit(VaultEdits.revertChange(entryId))
            if (!end(mine)) return@launch
            when (result) {
                is SaveResult.Saved -> if (result.value is EntryResult.Done) eventChannel.send(GuidedChangeEvent.Reverted)
                is SaveResult.NotSaved -> notSaved(result.problem, ::revert)
            }
        }
    }

    // ---- The end of the change -------------------------------------------------------------------

    /**
     * "Next: Facebook": the id of the account to change next, whose screen takes this one's place;
     * null when there is none, and after the end was left once. The end stays drawn while the screen
     * goes.
     */
    fun next(): String? {
        val next = (mutable.value as? GuidedChangeUiState.Done)?.nextId?.takeIf { !left }
        leave()
        return next
    }

    /** "Done for now": the end of this change is not shown again. */
    fun finish() = leave()

    private fun leave() {
        left = true
        memory.forget(entryId)
    }

    // ---- Inside ------------------------------------------------------------------------------------

    /** A save starts: the buttons wait. Returns the session it belongs to. */
    private fun start(): Int {
        busy = true
        render()
        return session
    }

    /**
     * Whether the screen still shows session [mine]: no lock came since, and the vault is open. The
     * lock's state reaches this ViewModel's collector a moment after the repository has it, so the
     * repository is asked too.
     */
    private fun stillOpen(mine: Int): Boolean = mine == session && repository.state.value is VaultState.Unlocked

    /** A save of session [mine] ended: true when the screen still shows that session, which is then shown again. */
    private fun end(mine: Int): Boolean {
        if (!stillOpen(mine)) return false
        busy = false
        currentVault()
        render()
        return true
    }

    private suspend fun notSaved(problem: SaveProblem, again: () -> Unit) {
        // Another try cannot make a vault that is too large fit.
        eventChannel.send(GuidedChangeEvent.NotSaved(problem, again.takeIf { problem != SaveProblem.TOO_LARGE }))
    }

    override fun onCleared() {
        // Left while the app is unlocked (Back, "Next", "Done for now"): the end of the change is not
        // shown again. After a lock the route comes back with the unlock, and so does the end.
        if (repository.state.value is VaultState.Unlocked) memory.forget(entryId)
        vault = null
    }

    companion object {
        /** The steps of the guided change (ux.md §5.17). */
        const val STEPS: Int = 5

        /** The guided change of the account [id] over the app's own parts. */
        fun create(graph: AppGraph, id: String, memory: GuidedChangeMemory, titleOrder: Comparator<String>?): GuidedChangeViewModel =
            GuidedChangeViewModel(
                entryId = id,
                repository = graph.data.repository,
                clipboard = graph.clipboard,
                settings = graph.settings,
                random = graph.platform.random,
                dispatchers = graph.dispatchers,
                memory = memory,
                titleOrder = titleOrder,
            )

        private fun sharedMeter(password: CharSequence): Strength = StrengthMeter.shared().measure(password, emptyList())
    }
}

/** What the guided change shows. Its [toString]s show no text of the account (architecture.md §5.6). */
sealed interface GuidedChangeUiState {
    /** The vault is locked, or has not been seen yet: nothing of the account is held. */
    data object Loading : GuidedChangeUiState

    /** The account is not there: it waits in Recently deleted ([inRecentlyDeleted]), or it no longer exists. */
    data class Gone(val inRecentlyDeleted: Boolean) : GuidedChangeUiState

    /**
     * The five steps (ux.md §5.17).
     *
     * @property title the account's name ("Open Gmail and sign in.").
     * @property username the account's username, shown under its name; empty for none.
     * @property website the address "Open website" opens; null for none (the button is not shown).
     * @property started a new password was made and the change waits for its answer (`changePending`):
     *   "Create another password", "Saved. Your old password is kept until you finish.", and the
     *   buttons of steps 4 and 5.
     * @property canCopyOld the website's password is not empty: "Copy old password" works.
     * @property canCopyNew a new password was made: "Copy new password" works.
     * @property lastOne "Last one! After this, your old password is not used anywhere."
     * @property busy a save of this screen runs: the buttons that save wait.
     */
    data class Steps(
        val title: String,
        val username: String,
        val website: String?,
        val started: Boolean,
        val canCopyOld: Boolean,
        val canCopyNew: Boolean,
        val lastOne: Boolean,
        val busy: Boolean,
    ) : GuidedChangeUiState {
        override fun toString(): String =
            "Steps(started=$started, canCopyOld=$canCopyOld, canCopyNew=$canCopyNew, lastOne=$lastOne, busy=$busy, " +
                "website=${website != null}, texts hidden)"
    }

    /**
     * The end of the change, after "Yes, it's changed": "[title] now has its own password." with
     * "Next: [nextTitle]" when another account still has the old password ([nextId]), otherwise
     * "Done for now"; and, once per session while changes are in no backup, [backupLine].
     */
    data class Done(val title: String, val nextId: String?, val nextTitle: String?, val backupLine: Boolean) : GuidedChangeUiState {
        override fun toString(): String = "Done(next=${nextId != null}, backupLine=$backupLine, texts hidden)"
    }
}

/** One-off messages of the guided change, shown in its snackbar. */
sealed interface GuidedChangeEvent {
    /**
     * A new password was saved; [again]: in place of one made before, where the screen says "New
     * password saved." for a moment, since nothing else on it changes.
     */
    data class Created(val again: Boolean) : GuidedChangeEvent

    /** "No, put back the old password" was saved: "The old password is back." */
    data object Reverted : GuidedChangeEvent

    /** The phone refused to copy (ux.md §6, "Failures"). */
    data object CopyBlocked : GuidedChangeEvent

    /**
     * A change was not saved, for [problem]: "Couldn't save. Your phone's storage may be full." with
     * "Try again" ([retry]), or the sentence of [SaveProblem.TOO_LARGE], which trying again cannot
     * help ([retry] null).
     */
    class NotSaved(val problem: SaveProblem, val retry: (() -> Unit)?) : GuidedChangeEvent {
        override fun toString(): String = "NotSaved($problem, retry=${retry != null})"
    }
}
