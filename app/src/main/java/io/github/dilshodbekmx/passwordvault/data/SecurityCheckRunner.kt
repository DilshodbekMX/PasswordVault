package io.github.dilshodbekmx.passwordvault.data

import android.icu.text.CollationKey
import io.github.dilshodbekmx.passwordvault.core.model.Edited
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityReport
import io.github.dilshodbekmx.passwordvault.core.strength.StrengthMeter
import io.github.dilshodbekmx.passwordvault.i18n.Collation
import io.github.dilshodbekmx.passwordvault.i18n.SortKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.IdentityHashMap
import java.util.Locale

/**
 * The security check of the open vault (ux.md §5.16; architecture.md §3.5 `SecurityReports`, §5.1;
 * build plan 7.1): `SecurityCheck.run` in the background after unlock and after each save, the
 * [report] it makes, its [progress] ("Checking 120 of 480…"), and the Security tab's dot ([unseen]).
 *
 * - **When it runs.** It follows [repository]'s vault and [locales], the language the screens are
 *   shown in, whose A–Z order the report's lists end with. A check runs when the vault is opened,
 *   and again when its accounts, its master-password matches (D-9b) or the language change. A vault
 *   whose accounts and matches are those the report was made from (a save of vault-level data, such
 *   as `securitySeenAt` or a backup's note) is not checked again; only [unseen] is worked out again.
 * - **In the background, on one core.** The check runs on [dispatcher]: the app passes one thread's
 *   worth of `Dispatchers.Default` (architecture.md §5.3, zxcvbn), so that the list, which is built
 *   on the same pool, never waits for it. The newest vault wins: a check for an older one is
 *   cancelled and stops after the account it is scoring, and a check already running for the same
 *   accounts, matches and language is not started again.
 * - **Cached for the session** (ux.md §5.16). The verdict "easy to guess" of each account is kept for
 *   the open vault, by entry object: an entry is immutable, so the same object has the same password,
 *   name and username, and a check after a save scores only the accounts that changed. Only the
 *   current vault's entries are kept; a lock drops them all.
 * - **Progress.** [progress] counts the accounts scored, while a check runs that has accounts to
 *   score and either no report yet (the first check after unlock) or at least [PROGRESS_FROM] of
 *   them (an import). A check after an ordinary save takes milliseconds and shows none, so the tab
 *   does not flicker.
 * - **The dot** ([unseen], architecture.md §4.3 `securitySeenAt`): the report flags an account (cards
 *   1 to 4: the master password, a shared password, easy to guess, a change not finished; not "left
 *   out", which the user chose) whose `updatedAt` is later than the vault's `securitySeenAt`, or that
 *   has never been seen. [markSeen] clears it, and writes `securitySeenAt` only when that clears it.
 * - **Dropped with the vault.** While the vault is locked [report] and [progress] are null and there
 *   is no dot; the change is made in the thread that publishes `VaultState.Locked`, before that
 *   publication returns, and a check still running for the vault that went is never published.
 *
 * Call [start] once. No password leaves this object: the report holds entry ids.
 *
 * @param collationOf the A–Z order of a language (`SortKeys` in the app), made once per language.
 * @param easyToGuess card 3's verdict for one account; by default [SecurityCheck.isEasyToGuess] with
 *   the shared meter, loaded on [dispatcher] at the first check.
 */
class SecurityCheckRunner(
    private val repository: VaultRepository,
    private val locales: StateFlow<Locale>,
    private val collationOf: (Locale) -> Collation,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    easyToGuess: ((Entry) -> Boolean)? = null,
) : SecurityReports {
    private val score: (Entry) -> Boolean = easyToGuess ?: { entry -> SecurityCheck.isEasyToGuess(entry, StrengthMeter.shared()) }

    private val lock = Any()
    private val reportState = MutableStateFlow<SecurityReport?>(null)
    private val unseenState = MutableStateFlow(false)
    private val progressState = MutableStateFlow<CheckProgress?>(null)

    override val report: StateFlow<SecurityReport?> = reportState.asStateFlow()
    override val unseen: StateFlow<Boolean> = unseenState.asStateFlow()

    /**
     * "Checking 120 of 480…" (ux.md §5.16): how many of the accounts that are scored have been, while
     * a check that shows its progress runs (above); null otherwise.
     */
    val progress: StateFlow<CheckProgress?> = progressState.asStateFlow()

    private var running: Job? = null // guarded by lock
    private var runningFor: Inputs? = null // guarded by lock: what the running check was started for
    private var builtFrom: Inputs? = null // guarded by lock: what [report] was made from

    /** The verdicts of the open vault's entries, by entry object. Replaced, never changed, so a running check may read it. */
    private var verdicts: Map<Entry, Boolean> = emptyMap() // guarded by lock

    private var collation: Pair<Locale, Collation>? = null // guarded by lock: the last one made

    /**
     * Follows the vault and [locales] from now on, in [scope]. Both are collected unconfined, so that
     * a lock drops the report at once, in the thread that locked.
     */
    fun start() {
        scope.launch(Dispatchers.Unconfined) { repository.state.collect { refresh() } }
        scope.launch(Dispatchers.Unconfined) { locales.collect { refresh() } }
    }

    /**
     * The Security tab is shown (ux.md §5.16, "Badge"): writes `securitySeenAt` when that clears the
     * dot, that is when an account the report flags has changed since the tab was last seen, judged
     * on the vault the write is made to. The time written is now, or the latest such change when the
     * clock stands before it, so that the dot always goes. True when it wrote; false when there was
     * nothing to clear, no report, or the save failed (the dot then stays).
     *
     * Writing it is an automatic write (architecture.md §5.5): the caller makes it only while the
     * Security tab is in view with no notice over it, so never before the older-copy notice is
     * answered.
     */
    suspend fun markSeen(): Boolean {
        val shown = reportState.value ?: return false
        if (!unseenState.value) return false
        val flagged = flaggedIds(shown)
        val saved = repository.edit { vault, nowMs ->
            val latest = latestUnseenChange(flagged, vault)
            if (latest == null) {
                Edited(vault, false)
            } else {
                Edited(VaultEdits.markSecuritySeen()(vault, maxOf(nowMs, latest)).vault, true)
            }
        }
        return saved is SaveResult.Saved && saved.value
    }

    private fun refresh() {
        synchronized(lock) {
            val state = repository.state.value
            if (state !is VaultState.Unlocked) {
                running?.cancel()
                running = null
                runningFor = null
                builtFrom = null
                verdicts = emptyMap()
                reportState.value = null
                unseenState.value = false
                progressState.value = null
                return
            }
            val vault = state.vault
            val inputs = Inputs(vault.entries, vault.masterPasswordMatches, locales.value)
            val shown = reportState.value
            if (shown != null && builtFrom?.sameAs(inputs) == true) {
                unseenState.value = unseenIn(shown, vault)
                return
            }
            if (running?.isActive == true && runningFor?.sameAs(inputs) == true) return // already on its way
            running?.cancel()
            runningFor = inputs
            running = check(vault, inputs)
        }
    }

    /** One check of [vault], started under [lock]; it publishes only while the vault is still the one it was made for. */
    private fun check(vault: Vault, inputs: Inputs): Job {
        val known = verdicts
        val hadReport = reportState.value != null
        return scope.launch(dispatcher) {
            val job = coroutineContext.job
            val made = collationFor(inputs.locale)
            ensureActive()
            val toScore = vault.entries.count { it.deletedAt == null && !it.checkIgnored && SecurityCheck.websitePassword(it).isNotEmpty() && it !in known }
            val shows = toScore > 0 && (!hadReport || toScore >= PROGRESS_FROM)
            if (!shows) progressState.value = null // what a check cancelled for this one showed
            val fresh = IdentityHashMap<Entry, Boolean>()
            val report = SecurityCheck.run(
                vault = vault,
                easyToGuess = { entry -> (known[entry] ?: scoreSafely(entry)).also { fresh[entry] = it } },
                titleOrder = titleOrderOf(made),
                progress = { checked, total ->
                    job.ensureActive()
                    if (shows) progressState.value = CheckProgress(checked, total)
                },
            )
            ensureActive()
            synchronized(lock) {
                val now = repository.state.value
                if (running === job && now is VaultState.Unlocked && Inputs(now.vault.entries, now.vault.masterPasswordMatches, locales.value).sameAs(inputs)) {
                    verdicts = fresh
                    builtFrom = inputs
                    running = null
                    runningFor = null
                    reportState.value = report
                    unseenState.value = unseenIn(report, now.vault)
                    progressState.value = null
                }
            }
        }
    }

    /**
     * The A–Z of [collation] for one check. With `SortKeys` each title's collation key is made once and
     * the keys are compared, as the list does, instead of collating the texts at every comparison.
     */
    private fun titleOrderOf(collation: Collation): Comparator<String> {
        if (collation !is SortKeys) return Comparator { a, b -> collation.compare(a, b) }
        val keys = HashMap<String, CollationKey>()
        return Comparator { a, b -> keys.getOrPut(a) { collation.key(a) }.compareTo(keys.getOrPut(b) { collation.key(b) }) }
    }

    /**
     * Card 3's verdict for [entry]. A meter that fails on its password counts it as not easy to guess,
     * so that one odd password never stops the check, or the app at every unlock.
     */
    private fun scoreSafely(entry: Entry): Boolean = try {
        score(entry)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        false
    }

    private fun collationFor(locale: Locale): Collation {
        synchronized(lock) { collation?.let { (made, c) -> if (made == locale) return c } }
        val made = collationOf(locale)
        synchronized(lock) { collation = locale to made }
        return made
    }

    /**
     * What a report is made from: the vault's accounts (the list itself: every save that changes an
     * account makes a new one), its master-password matches and the language.
     */
    private class Inputs(val entries: List<Entry>, val matches: List<String>, val locale: Locale) {
        fun sameAs(other: Inputs): Boolean = entries === other.entries && matches == other.matches && locale == other.locale
    }

    companion object {
        /** After the first report, a check shows its progress only when it scores at least this many accounts. */
        const val PROGRESS_FROM: Int = 50

        /**
         * The accounts [report] flags with something to do (cards 1 to 4 of ux.md §5.16): the master
         * password's twins, every account of a group of reused passwords, those easy to guess and those
         * whose change is not finished. "Left out" is the user's own choice and flags nothing.
         */
        fun flaggedIds(report: SecurityReport): Set<String> {
            val ids = HashSet<String>()
            ids.addAll(report.sameAsMasterPassword)
            for (group in report.reused) ids.addAll(group.entryIds)
            ids.addAll(report.weak)
            ids.addAll(report.pending)
            return ids
        }

        /**
         * The dot (architecture.md §4.3): an account [report] flags, outside Recently deleted, has an
         * `updatedAt` later than [vault]'s `securitySeenAt`, or the tab has never been seen.
         */
        fun unseenIn(report: SecurityReport, vault: Vault): Boolean = latestUnseenChange(flaggedIds(report), vault) != null

        /** The latest `updatedAt` of a [flagged] account of [vault] that the tab has not seen; null when there is none. */
        private fun latestUnseenChange(flagged: Set<String>, vault: Vault): Long? {
            if (flagged.isEmpty()) return null
            val seenAt = vault.securitySeenAt
            var latest: Long? = null
            for (entry in vault.entries) {
                if (entry.deletedAt != null || entry.id !in flagged) continue
                if (seenAt == null || entry.updatedAt > seenAt) latest = maxOf(latest ?: entry.updatedAt, entry.updatedAt)
            }
            return latest
        }
    }
}

/** How far a check is: [checked] of the [total] accounts it scores ("Checking 120 of 480…"). */
data class CheckProgress(val checked: Int, val total: Int)
