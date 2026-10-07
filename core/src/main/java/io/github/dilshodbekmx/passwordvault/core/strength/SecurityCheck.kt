package io.github.dilshodbekmx.passwordvault.core.strength

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.search.searchKey
import io.github.dilshodbekmx.passwordvault.core.text.UzbekScript
import io.github.dilshodbekmx.passwordvault.core.util.Bytes

/**
 * The security check (ux.md §5.16), as pure functions of the vault. `SecurityCheckRunner` (build
 * plan 7.1) runs [run] in the background after unlock and after each save; the vault repository
 * (3.1) runs [matchesMasterPassword] after each correct master-password entry (D-9b); the edit form
 * (3.6) shows [otherAccountsWith] under a typed password (ux.md §5.14).
 *
 * **Which accounts take part.** An account in Recently deleted takes part in nothing (ux.md §2). An
 * account left out of the check ([Entry.checkIgnored], D-9a) is listed under "Left out" and takes no
 * part in the two checks of its own password, "Same password on several accounts" and "Easy to
 * guess", which would report a PIN or a password the user cannot change for ever. It still shows in
 * "Master password used elsewhere", whose cure is a new master password, and in "Changes not
 * finished", which the user started; the detail screen shows both banners whatever the flag says
 * (ux.md §5.12).
 *
 * **The password a website has.** While a guided change waits for its answer ([Entry.changePending],
 * ux.md §5.17), the website may still have the old password, [Entry.pendingOldPassword]: the checks
 * use that one ([websitePassword]), and the new one counts from "Yes, it's changed" on. So an account
 * leaves its group of reused passwords, and the progress line moves ("3 changed, 14 to go"), when
 * the website has taken the new password, not when the app made it.
 *
 * Passwords are compared exactly, as websites compare them (ux.md §2); an empty password is never
 * reused and never easy to guess. No password leaves these functions: the report holds entry ids.
 */
object SecurityCheck {

    /**
     * The report of the five cards of ux.md §5.16 for [vault]. Every list is in the order of the
     * group list ([listOrder]): accounts that look like email first, then favourites, then A–Z.
     *
     * - [easyToGuess] decides card 3 for one account; by default [isEasyToGuess] with the shared
     *   meter, taken once for the whole run. A runner may pass its own, for example one that
     *   remembers the answer for an entry's id and `updatedAt` for the session, so that a check
     *   after a save scores only what changed.
     * - [titleOrder] is the A–Z of the app's language (`Collation.compare`); by default the titles'
     *   search keys in code point order.
     * - [progress] is told how many of the accounts that are scored have been ("Checking 120 of
     *   480…"): once with 0 before the first, then after each. It may throw to stop the check (a
     *   coroutine's `CancellationException`); the exception passes through and nothing is kept.
     *
     * Scoring runs zxcvbn: call this from a background thread.
     */
    fun run(
        vault: Vault,
        easyToGuess: (Entry) -> Boolean = StrengthMeter.shared().let { meter -> { entry: Entry -> isEasyToGuess(entry, meter) } },
        titleOrder: Comparator<String>? = null,
        progress: (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): SecurityReport {
        val live = vault.entries.filter { it.deletedAt == null }
        val order = listOrder(live, titleOrder)
        val byId = HashMap<String, Entry>(live.size * 2)
        for (e in live) byId[e.id] = e

        val sameAsMaster = vault.masterPasswordMatches.mapNotNull { byId[it] }.distinct().sortedWith(order).map { it.id }
        val checked = live.filter { !it.checkIgnored }
        val reused = reuseGroups(checked, order)

        val scored = checked.filter { websitePassword(it).isNotEmpty() }
        val weakIds = HashSet<String>()
        progress(0, scored.size)
        for ((index, entry) in scored.withIndex()) {
            if (easyToGuess(entry)) weakIds.add(entry.id)
            progress(index + 1, scored.size)
        }
        return SecurityReport(
            sameAsMasterPassword = sameAsMaster,
            reused = reused,
            weak = scored.filter { it.id in weakIds }.sortedWith(order).map { it.id },
            pending = live.filter { it.changePending }.sortedWith(order).map { it.id },
            leftOut = live.filter { it.checkIgnored }.sortedWith(order).map { it.id },
        )
    }

    /**
     * Card 3 for one account: its [websitePassword], with the parts of its name and username as user
     * inputs ([userInputs], as Bitwarden's weak-password report does), scores
     * [StrengthMeter.EASY_TO_GUESS] or less. An empty password is not easy to guess.
     */
    fun isEasyToGuess(entry: Entry, meter: StrengthMeter = StrengthMeter.shared()): Boolean {
        val password = websitePassword(entry)
        return password.isNotEmpty() && meter.isEasyToGuess(password, userInputs(entry))
    }

    /**
     * The words of [entry] that its password should not lean on: the runs of letters, digits and
     * apostrophes of three characters or more in its username (before an `@`, so not the mail
     * provider) and in its name, in lower case, in that order.
     */
    fun userInputs(entry: Entry): List<String> = userInputs(entry.title, entry.username)

    /**
     * [userInputs] of an account being typed (ux.md §5.14): its name [title] and its [username] as
     * they are in the form, so that the meter under the password judges it as the check will.
     */
    fun userInputs(title: String, username: String): List<String> {
        val parts = LinkedHashSet<String>()
        val at = username.indexOf('@')
        addParts(if (at > 0) username.substring(0, at) else username, parts)
        addParts(title, parts)
        return parts.toList()
    }

    /** The password the website has, as far as the app knows: the old one while a change waits for its answer. */
    fun websitePassword(entry: Entry): String =
        if (entry.changePending && entry.pendingOldPassword != null) entry.pendingOldPassword else entry.password

    /**
     * D-9b: the accounts outside Recently deleted whose password is the master password, compared
     * after a correct master-password entry with [masterPasswordBytes], the verified bytes of the
     * `MasterProof`. Each password goes through [MasterPasswordBytes] (folded as the master password
     * is) and is compared in constant time; while a change waits for its answer, the old password is
     * compared too, because the website may still have it. Every array made here is wiped. The
     * repository stores the result with `VaultEdits.setMasterPasswordMatches`.
     */
    fun matchesMasterPassword(vault: Vault, masterPasswordBytes: ByteArray): Set<String> {
        val out = LinkedHashSet<String>()
        for (e in vault.entries) {
            if (e.deletedAt != null) continue
            val old = if (e.changePending) e.pendingOldPassword else null
            if (isMasterPassword(e.password, masterPasswordBytes) || (old != null && isMasterPassword(old, masterPasswordBytes))) {
                out.add(e.id)
            }
        }
        return out
    }

    private fun isMasterPassword(password: String, master: ByteArray): Boolean {
        if (password.isEmpty() || master.isEmpty()) return false
        val chars = password.toCharArray()
        val bytes = MasterPasswordBytes.of(chars)
        try {
            return Bytes.equal(bytes, master)
        } finally {
            Bytes.wipe(chars)
            Bytes.wipe(bytes)
        }
    }

    /**
     * The reuse note of the edit form (ux.md §5.14, "You use this password for 5 other accounts"):
     * how many accounts outside Recently deleted, other than [entryId] (null for a new account), have
     * [password] as their [websitePassword]. Left-out accounts count: the note states a fact and
     * never blocks. 0 for an empty password.
     */
    fun otherAccountsWith(vault: Vault, password: String, entryId: String?): Int {
        if (password.isEmpty()) return 0
        return vault.entries.count { it.id != entryId && it.deletedAt == null && websitePassword(it) == password }
    }

    /**
     * Whether [entry] looks like an email account (ux.md §5.16, the group list's order): its name or
     * one of its websites contains gmail, google, mail, yandex, outlook, hotmail, icloud, yahoo, pochta
     * or почта, compared through their search keys.
     */
    fun isEmailLike(entry: Entry): Boolean {
        if (looksLikeEmail(entry.title)) return true
        for (url in entry.urls) if (looksLikeEmail(url)) return true
        return false
    }

    private fun looksLikeEmail(text: String): Boolean {
        val key = searchKey(text)
        return EMAIL_WORDS.any { key.contains(it) }
    }

    /**
     * The order of every list of the report (ux.md §5.16, "Group list"): accounts that look like email
     * ([isEmailLike]) first, then favourites, then A–Z by [titleOrder] (by default the titles' search
     * keys, then the titles, in code point order: the same on every phone), then by id.
     */
    fun listOrder(entries: Collection<Entry>, titleOrder: Comparator<String>? = null): Comparator<Entry> {
        val email = HashMap<String, Boolean>(entries.size * 2)
        for (e in entries) email[e.id] = isEmailLike(e)
        val byTitle: Comparator<Entry> = if (titleOrder != null) {
            Comparator { a, b -> titleOrder.compare(a.title, b.title) }
        } else {
            val keys = HashMap<String, String>(entries.size * 2)
            for (e in entries) keys[e.id] = searchKey(e.title)
            compareBy<Entry> { keys[it.id] ?: searchKey(it.title) }.thenBy { it.title }
        }
        return compareByDescending<Entry> { email[it.id] ?: isEmailLike(it) }
            .thenByDescending { it.favourite }
            .then(byTitle)
            .thenBy { it.id }
    }

    /**
     * Card 2: the groups of [checked] accounts whose [websitePassword] is the same, the biggest first.
     *
     * - **Changed** ([ReuseGroup.changed]): the accounts that left the group by a change, that is
     *   the [checked] accounts whose password history holds the group's password and whose website
     *   password is another one.
     * - **Id** ([ReuseGroup.id]): the id of the oldest account (`createdAt`, then id) among the
     *   group's accounts and those that left it by a change; when an earlier group of the report has
     *   that id already, the next oldest. It never derives from the password, and it stays the same
     *   while accounts leave the group one by one in guided changes (the route `Group(id)` keeps
     *   finding its group); an older account joining the group, an import for example, may change it.
     */
    private fun reuseGroups(checked: List<Entry>, order: Comparator<Entry>): List<ReuseGroup> {
        val members = LinkedHashMap<String, MutableList<Entry>>()
        for (e in checked) {
            val p = websitePassword(e)
            if (p.isNotEmpty()) members.getOrPut(p) { ArrayList(2) }.add(e)
        }
        val shared = members.filterValues { it.size >= 2 }
        if (shared.isEmpty()) return emptyList()
        val formers = HashMap<String, MutableList<Entry>>()
        for (e in checked) {
            val current = websitePassword(e)
            var counted: HashSet<String>? = null
            for (item in e.passwordHistory) {
                val p = item.password
                if (p == current || p !in shared) continue
                val seen = counted ?: HashSet<String>().also { counted = it }
                if (seen.add(p)) formers.getOrPut(p) { ArrayList() }.add(e)
            }
        }
        val groups = shared.map { (p, list) -> Shared(list, formers[p].orEmpty()) }
            .sortedWith(compareByDescending<Shared> { it.sharing.size }.thenComparator { a, b -> OLDEST_FIRST.compare(a.candidates[0], b.candidates[0]) })
        val used = HashSet<String>()
        return groups.map { group ->
            val id = group.candidates.firstOrNull { it.id !in used }?.id ?: (group.candidates[0].id + "#" + (used.size + 1))
            used.add(id)
            ReuseGroup(id = id, entryIds = group.sharing.sortedWith(order).map { it.id }, changed = group.left.size)
        }
    }

    /** One group of reused passwords: the accounts [sharing] it and those that [left] it by a change. */
    private class Shared(val sharing: List<Entry>, val left: List<Entry>) {
        /** Who may give the group its id, the oldest first. */
        val candidates: List<Entry> = (sharing + left).sortedWith(OLDEST_FIRST)
    }

    /** The oldest account first: by `createdAt`, then by id. */
    private val OLDEST_FIRST: Comparator<Entry> = compareBy<Entry> { it.createdAt }.thenBy { it.id }

    /** The words of ux.md §5.16 that mark an email account, as search keys ("почта" is "pochta"). */
    private val EMAIL_WORDS = listOf("gmail", "google", "mail", "yandex", "outlook", "hotmail", "icloud", "yahoo", "pochta")

    /** The fewest code points a part of a name or username must have to be a user input (as Bitwarden). */
    private const val MIN_INPUT = 3

    /** Adds the runs of letters, digits and apostrophes of [text], in lower case, that are long enough. */
    private fun addParts(text: String, into: MutableSet<String>) {
        var start = -1
        var i = 0
        while (i <= text.length) {
            val c = if (i < text.length) text.codePointAt(i) else -1
            val inWord = c >= 0 && (Character.isLetterOrDigit(c) || (c < 0x10000 && UzbekScript.isApostrophe(c.toChar())))
            if (inWord && start < 0) start = i
            if (!inWord && start >= 0) {
                val part = text.substring(start, i)
                if (part.codePointCount(0, part.length) >= MIN_INPUT) into.add(WordList.lowerCase(part))
                start = -1
            }
            i += if (c >= 0) Character.charCount(c) else 1
        }
    }
}
