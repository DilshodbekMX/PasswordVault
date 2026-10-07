package io.github.dilshodbekmx.passwordvault.ui.list

import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.settings.BackupReminder

/**
 * A banner at the top of the Passwords list (ux.md §5.11). Facts and numbers only: the screen picks
 * the sentence, so that the language can change under it (architecture.md §5.4). The kinds, in
 * their priority order:
 *
 * 1. the lock's banner, from `LockStatus` (lock.md §13.4 items 2, 3 and 6): at most one of
 *    [PendingMasterPassword], [PendingReady], [Replaced] and [PracticeOverdue];
 * 2. [UnprotectedExport], an export without protection not yet deleted (ux.md §5.20);
 * 3. [BackupDue] (ux.md §5.11's rule, [ListBanners.backupDue]);
 * 4. [ChangesPending], password changes waiting for the user's answer (ux.md §5.17).
 *
 * At most [ListBanners.MAX_SHOWN] show at a time; the others wait.
 */
sealed interface ListBanner {
    /** The banner's place in the priority order, from 1. */
    val priority: Int

    /**
     * A new master password is waiting for its 24 credited hours (lock.md §13.4 item 2): asked for
     * at [requestedAtMs] (wall clock) with [by]; about [hoursLeft] hours are left (1 or more).
     * "Cancel it" (`LockIntent.CancelPending`). Never dismissed: it is the owner's defence (lock.md F11).
     */
    data class PendingMasterPassword(val requestedAtMs: Long, val by: Shortcut, val hoursLeft: Int) : ListBanner {
        override val priority: Int get() = 1
    }

    /** The new master password is ready: "Finish now" (`LockIntent.FinishPending`), "Later", "Cancel it". */
    data object PendingReady : ListBanner {
        override val priority: Int get() = 1
    }

    /**
     * The master password was replaced at [atMs] with [by] (lock.md §13.4 item 3), while the lock
     * still shows its notice: "It wasn't me" (`LockIntent.ItWasntMe`) and "OK".
     */
    data class Replaced(val atMs: Long, val by: Shortcut) : ListBanner {
        override val priority: Int get() = 1
    }

    /**
     * Practice is past its grace and no new master password is pending (lock.md §13.4 item 6): the
     * master password was last typed at [sinceMs] (wall clock). "Type it now" and "I forgot it".
     * It stays until a master password has been typed.
     */
    data class PracticeOverdue(val sinceMs: Long) : ListBanner {
        override val priority: Int get() = 1
    }

    /** An export without protection, saved at [savedAtMs] as [name] (the document [uri]): "Delete" and "I deleted it myself". */
    data class UnprotectedExport(val uri: String, val name: String, val savedAtMs: Long) : ListBanner {
        override val priority: Int get() = 2

        override fun toString(): String = "UnprotectedExport(savedAtMs=$savedAtMs)"
    }

    /** "Your newest passwords are not in a backup yet." "Back up now" and "Later" (3 days). */
    data object BackupDue : ListBanner {
        override val priority: Int get() = 3
    }

    /**
     * [count] password changes wait for the user's answer; [firstId] and [firstTitle] are the first
     * of them in the vault's order, which "Answer now" opens.
     */
    data class ChangesPending(val count: Int, val firstId: String, val firstTitle: String) : ListBanner {
        override val priority: Int get() = 4

        override fun toString(): String = "ChangesPending(count=$count, firstId=$firstId)"
    }
}

/** A lock banner the user put off until the next unlock: "Later" on [ListBanner.PendingReady], "OK" on [ListBanner.Replaced]. */
enum class Dismissed { PENDING_READY, REPLACED }

/** Which banners the list shows (ux.md §5.11). Pure, so that it is tested on the JVM. */
object ListBanners {
    /** At most this many banners at a time (ux.md §5.11). */
    const val MAX_SHOWN = 2

    /** "Later" on the backup banner puts it off for this long (ux.md §5.11). */
    const val BACKUP_SNOOZE_MS: Long = 3L * DAY_MS

    /**
     * The banners for [vault], the lock's [status] and the backup [reminder] at the wall-clock time
     * [nowMs], without those in [dismissed]: every one that applies, at most one of the lock's, in
     * priority order, then cut to [MAX_SHOWN].
     */
    fun of(vault: Vault, status: LockStatus, reminder: BackupReminder, nowMs: Long, dismissed: Set<Dismissed> = emptySet()): List<ListBanner> =
        all(vault, status, reminder, nowMs, dismissed).take(MAX_SHOWN)

    /** Every banner that applies, in priority order, before the cut to [MAX_SHOWN]. */
    fun all(vault: Vault, status: LockStatus, reminder: BackupReminder, nowMs: Long, dismissed: Set<Dismissed> = emptySet()): List<ListBanner> =
        buildList {
            lockBanner(status, dismissed)?.let(::add)
            vault.plainExports.firstOrNull()?.let { add(ListBanner.UnprotectedExport(it.uri, it.name, it.savedAt)) }
            if (backupDue(vault, reminder, nowMs)) add(ListBanner.BackupDue)
            changesPending(vault)?.let(::add)
        }

    /**
     * The lock's one banner (ux.md §5.11 item 1): a pending new master password first (lock.md
     * §13.4 item 2), then a replacement whose notice still shows (item 3), then overdue practice
     * (item 6), which `LockStatus` sets only while nothing is pending.
     */
    fun lockBanner(status: LockStatus, dismissed: Set<Dismissed> = emptySet()): ListBanner? {
        val pending = status.pending
        if (pending != null) {
            if (!pending.ready) return ListBanner.PendingMasterPassword(pending.requestedAtMs, pending.by, hoursLeft(pending.remainingMs))
            if (Dismissed.PENDING_READY !in dismissed) return ListBanner.PendingReady
            return null
        }
        val replaced = status.replaced
        if (replaced != null && Dismissed.REPLACED !in dismissed) return ListBanner.Replaced(replaced.atMs, replaced.by)
        return status.practiceOverdueSinceMs?.let(ListBanner::PracticeOverdue)
    }

    /** The whole hours in [remainingMs], rounded up, at least 1: "in about 20 hours" (lock.md §13.4 item 2). */
    fun hoursLeft(remainingMs: Long): Int = ((remainingMs.coerceAtLeast(0L) + HOUR_MS - 1) / HOUR_MS).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * Whether the backup reminder shows (ux.md §5.11): the reminder is on and not snoozed past
     * [nowMs]; some account changed or was deleted after the newest backup this app saved (any
     * account at all when there is none, architecture.md §4.3); and either there is no backup and
     * the first account was added a day or more ago, or the newest backup is 7 or more days old.
     * A wall clock set back makes nothing due.
     */
    fun backupDue(vault: Vault, reminder: BackupReminder, nowMs: Long): Boolean {
        if (!reminder.on) return false
        val snoozedUntil = reminder.snoozedUntilMs
        if (snoozedUntil != null && nowMs < snoozedUntil) return false
        if (vault.entries.isEmpty()) return false
        val newest = vault.backups.maxOfOrNull { it.savedAt }
        if (newest == null) {
            val firstAdded = vault.entries.minOf { it.createdAt }
            return nowMs - firstAdded >= DAY_MS
        }
        val changed = vault.entries.any { it.updatedAt > newest || (it.deletedAt ?: Long.MIN_VALUE) > newest }
        return changed && nowMs - newest >= BACKUP_AGE_MS
    }

    /** The password changes waiting for an answer, outside Recently deleted (ux.md §5.17). */
    fun changesPending(vault: Vault): ListBanner.ChangesPending? {
        var count = 0
        var first: io.github.dilshodbekmx.passwordvault.core.model.Entry? = null
        for (entry in vault.entries) {
            if (!entry.changePending || entry.deletedAt != null) continue
            if (first == null) first = entry
            count++
        }
        return first?.let { ListBanner.ChangesPending(count, it.id, it.title) }
    }

    private const val HOUR_MS: Long = 3_600_000L

    /** "The last backup is 7 or more days old" (ux.md §5.11). */
    private const val BACKUP_AGE_MS: Long = 7L * DAY_MS
}

/** One day in milliseconds. */
internal const val DAY_MS: Long = 86_400_000L
