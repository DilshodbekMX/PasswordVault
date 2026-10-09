package io.github.dilshodbekmx.passwordvault.ui.security.change

import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeRules
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.ADDED_MS
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.BANK
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.DAY_MS
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.FACEBOOK
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.SHARED
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.YANDEX
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.account
import io.github.dilshodbekmx.passwordvault.ui.security.change.ChangeKit.Companion.gmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 7.2, [JVM]: what the guided change reads from the vault besides the account (ux.md
 * §5.17): "Last one!", the account "Next" offers, whether a change is in no backup, and the website
 * "Open website" opens.
 */
class GuidedChangeRulesTest {
    private val former = listOf(HistoryItem(SHARED, ADDED_MS))

    // ---- "Last one!" -----------------------------------------------------------------------------

    @Test
    fun lastOneWhenEveryOtherAccountOfTheGroupHasChanged() {
        val gmail = gmail()
        val vault = Vault(entries = listOf(gmail, account(FACEBOOK, "Facebook", password = "own-1", history = former)))
        assertTrue(GuidedChangeRules.isLastOfGroup(vault, gmail))
    }

    @Test
    fun notLastWhileAnotherAccountHasThePassword() {
        val gmail = gmail()
        val others = listOf(account(FACEBOOK, "Facebook", password = "own-1", history = former))
        // Still in use: by an account that takes part, by one left out of the check, or by one whose
        // change waits for its answer (its website may still have it).
        for (user in listOf(
            account(BANK, "Kapitalbank"),
            account(BANK, "Kapitalbank", checkIgnored = true),
            account(BANK, "Kapitalbank", password = "new-1", changePending = true, pendingOldPassword = SHARED),
        )) {
            assertFalse(GuidedChangeRules.isLastOfGroup(Vault(entries = listOf(gmail) + others + user), gmail))
        }
        // An account in Recently deleted does not count, either way.
        val deleted = account(BANK, "Kapitalbank", deletedAt = ADDED_MS + DAY_MS)
        assertTrue(GuidedChangeRules.isLastOfGroup(Vault(entries = listOf(gmail) + others + deleted), gmail))
        val deletedFormer = account(FACEBOOK, "Facebook", password = "own-1", history = former, deletedAt = ADDED_MS + DAY_MS)
        assertFalse(GuidedChangeRules.isLastOfGroup(Vault(entries = listOf(gmail, deletedFormer)), gmail))
    }

    @Test
    fun notLastWithoutAGroup() {
        // Only this account ever had the password: it was never in a group (a weak password, say).
        val gmail = gmail()
        assertFalse(GuidedChangeRules.isLastOfGroup(Vault(entries = listOf(gmail, account(FACEBOOK, "Facebook", password = "own-1"))), gmail))
        // An empty password is never the last of anything.
        val empty = account(GMAIL, "Gmail", password = "")
        val emptyFormer = account(FACEBOOK, "Facebook", password = "own-1", history = listOf(HistoryItem("", ADDED_MS)))
        assertFalse(GuidedChangeRules.isLastOfGroup(Vault(entries = listOf(empty, emptyFormer)), empty))
    }

    @Test
    fun whileAChangeWaitsTheWebsitesPasswordIsTheOneThatCounts() {
        val gmail = account(GMAIL, "Gmail", password = "new-1", changePending = true, pendingOldPassword = SHARED)
        val vault = Vault(entries = listOf(gmail, account(FACEBOOK, "Facebook", password = "own-1", history = former)))
        assertTrue(GuidedChangeRules.isLastOfGroup(vault, gmail))
        assertFalse(GuidedChangeRules.isLastOfGroup(Vault(entries = vault.entries + account(BANK, "Kapitalbank")), gmail))
    }

    // ---- "Next: Facebook" --------------------------------------------------------------------------

    /** Gmail after "Yes, it's changed": a new password, the old one first among the previous. */
    private val changed = account(GMAIL, "Gmail", password = "new-1", history = listOf(HistoryItem(SHARED, ADDED_MS + DAY_MS)))

    @Test
    fun nextIsTheFirstOtherAccountWithTheOldPasswordInTheGroupListsOrder() {
        // Email first, then favourites, then A to Z (ux.md §5.16, "Group list").
        val zebra = account(BANK, "Zebra bank")
        val favourite = account(FACEBOOK, "Facebook", favourite = true)
        val yandex = account(YANDEX, "Yandex pochta")
        val vault = Vault(entries = listOf(changed, zebra, favourite, yandex))
        assertEquals("an email account first", YANDEX, GuidedChangeRules.nextAfter(vault, changed)?.id)
        val noEmail = Vault(entries = listOf(changed, zebra, favourite))
        assertEquals("then a favourite", FACEBOOK, GuidedChangeRules.nextAfter(noEmail, changed)?.id)
        val plain = Vault(entries = listOf(changed, zebra, account(FACEBOOK, "Anor market")))
        assertEquals("then A to Z", FACEBOOK, GuidedChangeRules.nextAfter(plain, changed)?.id)
        // The order of the language: a title order that puts Z first wins over code points.
        val zFirst = Comparator<String> { a, b -> -a.compareTo(b) }
        assertEquals(BANK, GuidedChangeRules.nextAfter(plain, changed, zFirst)?.id)
    }

    @Test
    fun nextLeavesOutWhatTakesNoPartInTheGroup() {
        val vault = Vault(
            entries = listOf(
                changed,
                account(FACEBOOK, "Facebook", checkIgnored = true),
                account(BANK, "Kapitalbank", deletedAt = ADDED_MS + 2 * DAY_MS),
                account(YANDEX, "Yandex", password = "other"),
            ),
        )
        assertNull(GuidedChangeRules.nextAfter(vault, changed))
        // An account whose own change waits still has the old password on its website: it is next.
        val waiting = account(BANK, "Kapitalbank", password = "new-2", changePending = true, pendingOldPassword = SHARED)
        assertEquals(BANK, GuidedChangeRules.nextAfter(Vault(entries = listOf(changed, waiting)), changed)?.id)
    }

    @Test
    fun noNextWhileTheChangeWaitsOrWhenThePasswordDidNotChange() {
        val others = listOf(account(FACEBOOK, "Facebook"))
        val waiting = account(GMAIL, "Gmail", password = "new-1", changePending = true, pendingOldPassword = SHARED, history = listOf(HistoryItem(SHARED, ADDED_MS)))
        assertNull(GuidedChangeRules.nextAfter(Vault(entries = listOf(waiting) + others), waiting))
        val same = account(GMAIL, "Gmail", history = listOf(HistoryItem(SHARED, ADDED_MS)))
        assertNull(GuidedChangeRules.nextAfter(Vault(entries = listOf(same) + others), same))
        val none = account(GMAIL, "Gmail", password = "new-1")
        assertNull(GuidedChangeRules.nextAfter(Vault(entries = listOf(none) + others), none))
    }

    // ---- The backup line and the website -------------------------------------------------------------

    @Test
    fun aChangeIsInNoBackupUntilABackupIsNewerThanIt() {
        val entry = account(GMAIL, "Gmail") // updatedAt = ADDED_MS + DAY_MS
        assertTrue("no backup at all", GuidedChangeRules.notInBackup(Vault(entries = listOf(entry)), entry))
        val older = FileRef("content://b/1", "old.pvault", ADDED_MS)
        assertTrue(GuidedChangeRules.notInBackup(Vault(entries = listOf(entry), backups = listOf(older)), entry))
        val newer = FileRef("content://b/2", "new.pvault", ADDED_MS + 2 * DAY_MS)
        assertFalse(GuidedChangeRules.notInBackup(Vault(entries = listOf(entry), backups = listOf(older, newer)), entry))
    }

    @Test
    fun openWebsiteOpensTheFirstWebAddressOnly() {
        assertEquals("https://accounts.google.com/", GuidedChangeRules.websiteOf(gmail()))
        assertEquals("http://old.example.uz", GuidedChangeRules.websiteOf(account(GMAIL, "x", urls = listOf("  http://old.example.uz  "))))
        assertEquals("HTTPS://A.UZ", GuidedChangeRules.websiteOf(account(GMAIL, "x", urls = listOf("HTTPS://A.UZ"))))
        assertNull(GuidedChangeRules.websiteOf(account(GMAIL, "x", urls = listOf("android://hash@com.bank.app/", "example.uz", "ftp://a"))))
        assertNull(GuidedChangeRules.websiteOf(account(GMAIL, "x")))
    }
}
