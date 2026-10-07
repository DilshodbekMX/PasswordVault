package io.github.dilshodbekmx.passwordvault.ui.list

import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.lock.ReplacedNotice
import io.github.dilshodbekmx.passwordvault.settings.BackupReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 3.5, [JVM]: which banners the Passwords list shows (ux.md §5.11) and in which order:
 * at most two, the lock's first (lock.md §13.4 items 2, 3 and 6), then an unprotected export, a
 * backup that is due, and password changes waiting for an answer; each computed from its source
 * even while that source is empty.
 */
class ListBannersTest {
    private val now = 1_791_244_800_000L // 2026-10-06 00:00 UTC
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun entry(
        id: String,
        createdAt: Long = now - 30 * day,
        updatedAt: Long = createdAt,
        deletedAt: Long? = null,
        changePending: Boolean = false,
    ) = Entry(
        id = id,
        title = "Title $id",
        password = "pw",
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        changePending = changePending,
        pendingOldPassword = if (changePending) "old" else null,
    )

    private val pending = PendingMasterPassword(requestedAtMs = now - 4 * hour, by = Shortcut.PIN, creditedMs = 4 * hour, remainingMs = 20 * hour, ready = false)
    private val ready = pending.copy(creditedMs = 24 * hour, remainingMs = 0, ready = true)
    private val replaced = ReplacedNotice(atMs = now - day, by = Shortcut.FINGERPRINT, oldVaultId = "old-vault")
    private val export = FileRef("content://docs/17", "passwords-2026-10-05.csv", now - 2 * hour)
    private val oldBackup = FileRef("content://docs/18", "PasswordVault-backup-2026-09-01.pvault", now - 35 * day)

    /** A vault where every non-lock banner applies: an export, a backup due, two changes pending. */
    private val busy = Vault(
        entries = listOf(entry("a", changePending = true), entry("b", updatedAt = now - day), entry("c", changePending = true)),
        plainExports = listOf(export),
        backups = listOf(oldBackup),
    )

    @Test
    fun allSourcesEmptyGiveNoBanner() {
        assertEquals(emptyList<ListBanner>(), ListBanners.of(Vault(), LockStatus.NONE, BackupReminder(), now))
        assertEquals(emptyList<ListBanner>(), ListBanners.all(Vault(), LockStatus.NONE, BackupReminder(), now))
    }

    @Test
    fun atMostTwoInPriorityOrder() {
        val practice = LockStatus.NONE.copy(practiceOverdueSinceMs = now - 40 * day)
        val all = ListBanners.all(busy, practice, BackupReminder(), now)
        assertEquals(
            listOf(
                ListBanner.PracticeOverdue(now - 40 * day),
                ListBanner.UnprotectedExport(export.uri, export.name, export.savedAt),
                ListBanner.BackupDue,
                ListBanner.ChangesPending(2, "a", "Title a"),
            ),
            all,
        )
        assertEquals(listOf(1, 2, 3, 4), all.map { it.priority })
        assertEquals(all.take(2), ListBanners.of(busy, practice, BackupReminder(), now))

        // With the lock's banner gone, the backup reminder is no longer hidden by the export (ux.md §5.11).
        assertEquals(
            listOf(ListBanner.UnprotectedExport(export.uri, export.name, export.savedAt), ListBanner.BackupDue),
            ListBanners.of(busy, LockStatus.NONE, BackupReminder(), now),
        )
        // Without the export, the backup and the pending changes.
        assertEquals(
            listOf(ListBanner.BackupDue, ListBanner.ChangesPending(2, "a", "Title a")),
            ListBanners.of(busy.copy(plainExports = emptyList()), LockStatus.NONE, BackupReminder(), now),
        )
    }

    @Test
    fun theLocksBannerIsOneOfFourAndNeverTwo() {
        // A pending request comes first, and says the hours left, rounded up.
        val withAll = LockStatus.NONE.copy(pending = pending, replaced = replaced, practiceOverdueSinceMs = now - day)
        assertEquals(ListBanner.PendingMasterPassword(pending.requestedAtMs, Shortcut.PIN, 20), ListBanners.lockBanner(withAll))
        assertEquals(1, ListBanners.of(Vault(), withAll, BackupReminder(), now).size)

        // Ready: "Finish now", "Later", "Cancel it"; "Later" puts it off until the next unlock, and
        // nothing of the lock takes its place.
        val readyStatus = LockStatus.NONE.copy(pending = ready)
        assertEquals(ListBanner.PendingReady, ListBanners.lockBanner(readyStatus))
        assertNull(ListBanners.lockBanner(readyStatus, setOf(Dismissed.PENDING_READY)))

        // A pending request that is not ready is never put off: it is the owner's defence (lock.md F11).
        assertEquals(
            ListBanner.PendingMasterPassword(pending.requestedAtMs, Shortcut.PIN, 20),
            ListBanners.lockBanner(LockStatus.NONE.copy(pending = pending), setOf(Dismissed.PENDING_READY, Dismissed.REPLACED)),
        )

        // A replacement whose notice still shows, until "OK".
        val replacedStatus = LockStatus.NONE.copy(replaced = replaced, practiceOverdueSinceMs = now - day)
        assertEquals(ListBanner.Replaced(replaced.atMs, Shortcut.FINGERPRINT), ListBanners.lockBanner(replacedStatus))
        assertEquals(ListBanner.PracticeOverdue(now - day), ListBanners.lockBanner(replacedStatus, setOf(Dismissed.REPLACED)))

        // Practice alone.
        assertEquals(ListBanner.PracticeOverdue(now - day), ListBanners.lockBanner(LockStatus.NONE.copy(practiceOverdueSinceMs = now - day)))
        assertNull(ListBanners.lockBanner(LockStatus.NONE))
    }

    @Test
    fun hoursLeftAreRoundedUpAndAtLeastOne() {
        assertEquals(20, ListBanners.hoursLeft(20 * hour))
        assertEquals(20, ListBanners.hoursLeft(19 * hour + 1))
        assertEquals(1, ListBanners.hoursLeft(1))
        assertEquals(1, ListBanners.hoursLeft(0))
        assertEquals(1, ListBanners.hoursLeft(-5 * hour))
        assertEquals(24, ListBanners.hoursLeft(24 * hour))
    }

    @Test
    fun backupIsDueByTheRuleOfUx511() {
        val on = BackupReminder()
        val fresh = Vault(entries = listOf(entry("a", createdAt = now - 23 * hour)))
        val dayOld = Vault(entries = listOf(entry("a", createdAt = now - day)))

        // No backup yet: due once the first account was added a day or more ago.
        assertFalse(ListBanners.backupDue(fresh, on, now))
        assertTrue(ListBanners.backupDue(dayOld, on, now))
        assertFalse("an empty vault needs no backup", ListBanners.backupDue(Vault(), on, now))

        // The switch, and "Later" for 3 days.
        assertFalse(ListBanners.backupDue(dayOld, BackupReminder(on = false), now))
        assertFalse(ListBanners.backupDue(dayOld, BackupReminder(snoozedUntilMs = now + 1), now))
        assertTrue(ListBanners.backupDue(dayOld, BackupReminder(snoozedUntilMs = now), now))
        assertEquals(3 * day, ListBanners.BACKUP_SNOOZE_MS)

        // A backup exists: due when it is 7 or more days old and something changed after it.
        val backup = FileRef("content://b", "b.pvault", now - 7 * day)
        val changedAfter = Vault(entries = listOf(entry("a", createdAt = now - 30 * day, updatedAt = now - 2 * day)), backups = listOf(backup))
        assertTrue(ListBanners.backupDue(changedAfter, on, now))
        assertFalse("6 days is not yet 7", ListBanners.backupDue(changedAfter, on, now - day))
        val unchanged = Vault(entries = listOf(entry("a", createdAt = now - 30 * day, updatedAt = now - 8 * day)), backups = listOf(backup))
        assertFalse("nothing changed since the backup", ListBanners.backupDue(unchanged, on, now))
        val deletedAfter = Vault(
            entries = listOf(entry("a", createdAt = now - 30 * day, updatedAt = now - 8 * day, deletedAt = now - 3 * day)),
            backups = listOf(backup),
        )
        assertTrue("a deletion after the backup is a change", ListBanners.backupDue(deletedAfter, on, now))

        // The newest of several backups counts, and a clock set back makes nothing due.
        val twoBackups = changedAfter.copy(backups = listOf(backup, FileRef("content://c", "c.pvault", now - day)))
        assertFalse(ListBanners.backupDue(twoBackups, on, now))
        assertFalse(ListBanners.backupDue(changedAfter, on, now - 30 * day))
    }

    @Test
    fun changesPendingCountOnlyAccountsOutsideRecentlyDeleted() {
        val vault = Vault(
            entries = listOf(
                entry("gone", changePending = true, deletedAt = now - day),
                entry("first", changePending = true),
                entry("plain"),
                entry("second", changePending = true),
            ),
        )
        assertEquals(ListBanner.ChangesPending(2, "first", "Title first"), ListBanners.changesPending(vault))
        assertNull(ListBanners.changesPending(Vault(entries = listOf(entry("plain")))))
    }

    @Test
    fun theOldestUnprotectedExportIsShownFirst() {
        val newer = FileRef("content://docs/20", "passwords-2026-10-06.json", now - hour)
        val banners = ListBanners.all(Vault(plainExports = listOf(export, newer)), LockStatus.NONE, BackupReminder(on = false), now)
        assertEquals(listOf(ListBanner.UnprotectedExport(export.uri, export.name, export.savedAt)), banners)
    }

    @Test
    fun noBannerTextOrFileNameInToString() {
        val banners = ListBanners.all(busy, LockStatus.NONE, BackupReminder(), now)
        val text = banners.toString()
        assertFalse(text, "Title" in text)
        assertFalse(text, "passwords-" in text)
        assertFalse(text, "content://" in text)
    }
}
