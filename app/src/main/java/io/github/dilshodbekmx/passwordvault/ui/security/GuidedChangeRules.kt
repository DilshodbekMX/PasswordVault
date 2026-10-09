package io.github.dilshodbekmx.passwordvault.ui.security

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck

/**
 * What the guided change (ux.md §5.17) reads from the vault besides the account itself, as pure
 * functions, so that they are tested on the JVM. Passwords are compared exactly, as websites compare
 * them, and are named from the website's point of view: while a change waits for its answer the
 * website may still have the old password, so the account's "website password" is
 * `pendingOldPassword` then ([SecurityCheck.websitePassword], as the security check judges it).
 * Accounts in Recently deleted take part in nothing (ux.md §2). Nothing here returns a password.
 */
internal object GuidedChangeRules {
    /**
     * "Last one! After this, your old password is not used anywhere." (ux.md §5.17): the website
     * password of [entry] is not the website password of any other account outside Recently deleted
     * (left-out accounts count: they still use it), and at least one other such account had it among
     * its previous passwords, that is, left the group of reused passwords by a change. A password
     * that only this account ever had is not the last of a group, and an empty one is never.
     */
    fun isLastOfGroup(vault: Vault, entry: Entry): Boolean {
        val password = SecurityCheck.websitePassword(entry)
        if (password.isEmpty()) return false
        var former = false
        for (other in vault.entries) {
            if (other.id == entry.id || other.deletedAt != null) continue
            if (SecurityCheck.websitePassword(other) == password) return false
            if (!former && other.passwordHistory.any { it.password == password }) former = true
        }
        return former
    }

    /**
     * "Next: Facebook" after "Yes, it's changed" (ux.md §5.17): the first account, in the order of
     * the group list ([SecurityCheck.listOrder]: email first, then favourites, then A to Z by
     * [titleOrder]), whose website password is still the one the website of [entry] had before the
     * change. That password is the front of the previous passwords of [entry], where "Yes, it's
     * changed" put it. Only accounts that take part in the group of reused passwords are offered:
     * outside Recently deleted and not left out of the check (D-9a). Null when there is none, when
     * [entry] still waits for an answer, or when its password did not change.
     */
    fun nextAfter(vault: Vault, entry: Entry, titleOrder: Comparator<String>? = null): Entry? {
        if (entry.changePending) return null
        val former = entry.passwordHistory.firstOrNull()?.password
        if (former.isNullOrEmpty() || former == entry.password) return null
        val sharing = vault.entries.filter {
            it.id != entry.id && it.deletedAt == null && !it.checkIgnored && SecurityCheck.websitePassword(it) == former
        }
        if (sharing.isEmpty()) return null
        return sharing.minWithOrNull(SecurityCheck.listOrder(sharing, titleOrder))
    }

    /**
     * Whether [entry] has changes that no backup holds (ux.md §5.17's backup line): it was saved
     * after the newest backup this app made of [vault], or there is no backup at all.
     */
    fun notInBackup(vault: Vault, entry: Entry): Boolean {
        val newest = vault.backups.maxOfOrNull { it.savedAt } ?: return true
        return entry.updatedAt > newest
    }

    /**
     * The website "Open website" opens (ux.md §5.17 step 1): the first of the account's addresses
     * that starts with `https://` or `http://`, as the account's screen opens them (ux.md §5.12);
     * null when there is none. An app's address (`android://`) is never opened.
     */
    fun websiteOf(entry: Entry): String? =
        entry.urls.asSequence().map { it.trim() }.firstOrNull { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}
