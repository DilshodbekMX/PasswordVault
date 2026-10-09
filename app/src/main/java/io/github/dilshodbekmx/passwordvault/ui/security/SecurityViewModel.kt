package io.github.dilshodbekmx.passwordvault.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityReport
import io.github.dilshodbekmx.passwordvault.data.CheckProgress
import io.github.dilshodbekmx.passwordvault.data.SecurityCheckRunner
import io.github.dilshodbekmx.passwordvault.data.SecurityReports
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Security tab (ux.md §5.16): the cards of the check's report in their order, the progress of
 * the first check after unlock, "all clear" and "no accounts yet", and the "Why this matters" card.
 *
 * - **The cards** come from [SecurityReports.report] and the open vault, which gives the names in a
 *   card's text. A report is made in the background and may be a moment older than the vault after a
 *   save, so only accounts the vault still has outside Recently deleted are counted, and a group of
 *   reused passwords with fewer than two of them left is not shown.
 * - **"Why this matters"** is open on the first visit and folded afterwards: open while the vault's
 *   `securitySeenAt` was null when this screen was made, that is until the tab has once been seen
 *   with results; the user folds and unfolds it. Decided once, so that the card does not fold under
 *   the user's eyes when the visit writes `securitySeenAt`.
 * - **The dot** ([whileShown]): while the screen is shown, with no notice of the lock over it, the
 *   results count as seen; `SecurityCheckRunner.markSeen` then writes `securitySeenAt` when that
 *   clears the dot. The notices come first after an unlock, the older-copy notice among them, so the
 *   write never comes before that notice is answered (architecture.md §5.5, "Automatic writes").
 * - **Dropped with the vault.** [state] is null while the vault is locked; it is shared eagerly, so it
 *   drops the account names at once, also while the screen is in the background (§5.5 step 5).
 *
 * @param markSeen `SecurityCheckRunner.markSeen`: writes `securitySeenAt` when that clears the dot.
 */
class SecurityViewModel(
    private val reports: SecurityReports,
    progress: StateFlow<CheckProgress?>,
    private val markSeen: suspend () -> Boolean,
    vaults: StateFlow<VaultState>,
    private val lockState: StateFlow<LockUiState>,
    dispatchers: AppDispatchers,
) : ViewModel() {

    /** Whether "Why this matters" is open; null until the first open vault decides it. */
    private val whyOpen = MutableStateFlow<Boolean?>(null)

    /** What the tab shows; null while the vault is locked. */
    val state: StateFlow<SecurityUi?> = combine(vaults, reports.report, progress, whyOpen) { vault, report, checking, why ->
        if (vault !is VaultState.Unlocked) return@combine null
        SecurityUi(
            whyOpen = why ?: (vault.vault.securitySeenAt == null),
            checking = checking,
            results = report?.let { resultsOf(it, vault.vault) },
        )
    }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val first = vaults.first { it is VaultState.Unlocked } as VaultState.Unlocked
            whyOpen.compareAndSet(null, first.vault.securitySeenAt == null)
        }
    }

    /** "Why this matters" is folded or unfolded. */
    fun toggleWhy() {
        val shown = state.value ?: return
        whyOpen.value = !shown.whyOpen
    }

    /**
     * Runs while the screen is shown (the screen calls it while it is resumed): whenever the dot is
     * on and no notice of the lock is over the screen, the results count as seen and the dot is
     * cleared. Returns only when cancelled.
     */
    suspend fun whileShown() {
        combine(reports.unseen, lockState.map { it is LockUiState.Unlocked && it.notices.isEmpty() }) { unseen, inView -> unseen && inView }
            .distinctUntilChanged()
            .collect { due -> if (due) markSeen() }
    }

    companion object {
        /** The ViewModel of the app's Security tab, over the app's check ([SecurityCheckRunner], `SecurityModule`). */
        fun from(graph: AppGraph): SecurityViewModel {
            val reports = graph.security
            val runner = reports as? SecurityCheckRunner
            return SecurityViewModel(
                reports = reports,
                progress = runner?.progress ?: MutableStateFlow(null),
                markSeen = { runner?.markSeen() ?: false },
                vaults = graph.data.repository.state,
                lockState = graph.lock.state,
                dispatchers = graph.dispatchers,
            )
        }

        /** How many account names a card's text gives before "…" (ux.md §5.16: "(Gmail, Facebook…)"). */
        const val NAMES_SHOWN: Int = 2

        /**
         * The results of [report] for [vault] (ux.md §5.16): [SecurityResults.NoAccounts] when the
         * vault has no account outside Recently deleted, otherwise the cards in their order: the
         * master password used elsewhere, the same password on several accounts, easy to guess,
         * changes not finished, left out. Each card is there only when it counts something.
         */
        fun resultsOf(report: SecurityReport, vault: Vault): SecurityResults {
            val live = HashMap<String, Entry>()
            for (entry in vault.entries) if (entry.deletedAt == null) live[entry.id] = entry
            if (live.isEmpty()) return SecurityResults.NoAccounts
            fun present(ids: List<String>) = ids.filter { it in live }
            fun namesOf(ids: List<String>) = ids.take(NAMES_SHOWN).map { live.getValue(it).title }

            val cards = ArrayList<SecurityCard>(5)
            val master = present(report.sameAsMasterPassword)
            if (master.isNotEmpty()) cards += SecurityCard.MasterPassword(master.size, namesOf(master))
            val groups = report.reused.mapNotNull { group ->
                val members = present(group.entryIds)
                if (members.size < 2) null else GroupLine(group.id, members.size, group.changed)
            }
            if (groups.isNotEmpty()) cards += SecurityCard.Reused(groups.first(), groups.drop(1))
            val weak = present(report.weak)
            if (weak.isNotEmpty()) cards += SecurityCard.Weak(weak.size)
            val pending = present(report.pending)
            if (pending.isNotEmpty()) cards += SecurityCard.Pending(pending.size, namesOf(pending), pending.singleOrNull())
            val leftOut = present(report.leftOut)
            if (leftOut.isNotEmpty()) cards += SecurityCard.LeftOut(leftOut.size)
            return SecurityResults.Cards(cards)
        }
    }
}

/**
 * What the Security tab shows (ux.md §5.16).
 *
 * @property whyOpen "Why this matters" is open.
 * @property checking a check is running that shows its progress ("Checking 120 of 480…"): the first
 *   after unlock, or one with many new accounts; null otherwise.
 * @property results the results; null until the first check of this unlock has ended.
 */
data class SecurityUi(val whyOpen: Boolean, val checking: CheckProgress?, val results: SecurityResults?)

/** The check's results as the tab shows them. */
sealed interface SecurityResults {
    /** No account outside Recently deleted: "Add some accounts first. The check runs on this phone only." */
    data object NoAccounts : SecurityResults

    /**
     * The [cards] in the order of ux.md §5.16. With none of cards 1 to 4 ([allClear]) the tab says
     * "All your passwords are different and hard to guess. Well done.", and still shows "Left out"
     * when accounts are left out (architecture.md D-9a: the count is always shown).
     */
    data class Cards(val cards: List<SecurityCard>) : SecurityResults {
        val allClear: Boolean get() = cards.all { it is SecurityCard.LeftOut }
    }
}

/** One card of the Security tab (ux.md §5.16), with what its text counts. Its [toString] names no account. */
sealed interface SecurityCard {
    /**
     * 1. "Master password used elsewhere": [count] accounts have the master password; [names] are the
     * first of them (at most [SecurityViewModel.NAMES_SHOWN]). Its button opens Change master password.
     */
    data class MasterPassword(val count: Int, val names: List<String>) : SecurityCard {
        override fun toString(): String = "MasterPassword(count=$count)"
    }

    /**
     * 2. "Same password on several accounts": the [biggest] group, with its progress, and the [others],
     * the biggest first. Each opens its group list (`Route.Group(id)`).
     */
    data class Reused(val biggest: GroupLine, val others: List<GroupLine>) : SecurityCard

    /** 3. "Easy to guess": [count] passwords; the button opens their list. */
    data class Weak(val count: Int) : SecurityCard

    /**
     * 4. "Changes not finished": [count] changes, [names] of the first accounts. The button opens the
     * account when only one waits ([onlyEntryId]), otherwise the list of them.
     */
    data class Pending(val count: Int, val names: List<String>, val onlyEntryId: String?) : SecurityCard {
        override fun toString(): String = "Pending(count=$count, only=${onlyEntryId != null})"
    }

    /** 5. "Left out": [count] accounts; the button opens their list, with "Check it again". */
    data class LeftOut(val count: Int) : SecurityCard
}

/**
 * One group of reused passwords on card 2: its [id] (`ReuseGroup.id`, the route's), the [accounts]
 * that still share the password and how many [changed] it ("3 changed, 14 to go").
 */
data class GroupLine(val id: String, val accounts: Int, val changed: Int)
