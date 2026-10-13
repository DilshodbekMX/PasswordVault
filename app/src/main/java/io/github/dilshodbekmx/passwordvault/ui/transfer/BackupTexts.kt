package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.files.Cleanup
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.UiText

/**
 * The texts of the backup, restore and old-passwords screens for what their ViewModels say
 * (ux.md §4 item 3, §5.2, §5.10, §5.19; lock.md §13.3): one function per kind of fact, so that the
 * screens and the JVM tests read the same choice. Dates are [BackupDate] arguments, which the screen
 * formats in its own language ([show]).
 */
object BackupTexts {
    /** What the last master password came to (lock.md §13.3). */
    fun passwordError(error: PasswordError): UiText = when (error) {
        PasswordError.Wrong -> UiText.of(R.string.backup_wrong)
        PasswordError.NoMemory -> UiText.of(R.string.backup_no_memory)
        is PasswordError.Older -> UiText.of(
            if (error.unfinished) R.string.backup_older_unfinished else R.string.backup_older,
            BackupDate(error.changedAtMs, BackupDate.Style.DAY_MONTH),
        )
    }

    /** The line of a step that runs. */
    fun phase(phase: Phase): UiText = UiText.of(
        when (phase) {
            Phase.MAKING -> R.string.backup_making
            Phase.SAVING -> R.string.backup_saving
            Phase.CHECKING -> R.string.backup_checking_file
            Phase.STOPPING -> R.string.backup_stopping
        },
    )

    /** Why a backup was not saved (ux.md §5.19 "Errors"). */
    fun failure(why: Failure): UiText = UiText.of(
        when (why) {
            Failure.NOT_MADE -> R.string.backup_not_made
            Failure.NO_MEMORY -> R.string.backup_no_memory_make
            Failure.PICKER -> R.string.backup_picker_failed
            Failure.WRITE -> R.string.backup_write_failed
            Failure.CHECK -> R.string.backup_check_failed
        },
    )

    /** After a failed write or check: what became of the file (ux.md §4 item 3); null when there is nothing to say. */
    fun failedFile(cleanup: Cleanup?, name: String?): UiText? = when (cleanup) {
        Cleanup.Removed -> UiText.of(R.string.backup_file_removed)
        is Cleanup.Left -> left(cleanup.place, name.orEmpty(), R.string.backup_file_left_phone, R.string.backup_file_left_drive, R.string.backup_file_left_other)
        Cleanup.Kept, Cleanup.NoDocument, null -> null
    }

    /** An unfinished backup was given up (ux.md §4 item 3): "No backup was made. …"; null when there is nothing to say. */
    fun stopped(cleanup: Cleanup, name: String?): UiText? = when (cleanup) {
        Cleanup.Removed -> UiText.of(R.string.backup_stopped_removed)
        is Cleanup.Left -> left(cleanup.place, name.orEmpty(), R.string.backup_stopped_left_phone, R.string.backup_stopped_left_drive, R.string.backup_stopped_left_other)
        Cleanup.Kept, Cleanup.NoDocument -> null
    }

    /**
     * The result (ux.md §5.19 step 5): "Backup saved: … (52 accounts).", then one line about its
     * place: that the copy is on this phone, or, for any place that is not the phone, Google Drive
     * included, that it may upload the file later (the owner's decisions 8 and 20, build-plan.md §12).
     */
    fun saved(saved: BackupStep.Saved): List<UiText> = listOf(
        UiText.plural(R.plurals.backup_saved, saved.accounts, saved.accounts, saved.name),
        when (saved.place) {
            Place.PhoneStorage -> UiText.of(R.string.backup_place_phone)
            Place.GoogleDrive, is Place.Other -> UiText.of(R.string.backup_upload_later)
        },
    )

    /** A restore's message (ux.md §5.2 "States and messages"). */
    fun restoreProblem(problem: RestoreProblem): UiText = UiText.of(
        when (problem) {
            RestoreProblem.PICKER -> R.string.backup_restore_picker_failed
            RestoreProblem.UNREADABLE -> R.string.backup_restore_unreadable
            RestoreProblem.NOT_OURS -> R.string.backup_restore_not_ours
            RestoreProblem.NOT_OURS_LOCKED -> R.string.backup_restore_not_ours_locked
            RestoreProblem.WRONG -> R.string.backup_restore_wrong
            RestoreProblem.WRONG_OR_DAMAGED -> R.string.backup_restore_wrong_or_damaged
            RestoreProblem.DAMAGED -> R.string.backup_restore_damaged
            RestoreProblem.NEWER -> R.string.backup_restore_newer
            RestoreProblem.NO_MEMORY -> R.string.backup_restore_no_memory
            RestoreProblem.NOT_SAVED -> R.string.backup_restore_not_saved
        },
    )

    /** The summary of an opened backup (ux.md §5.2 step 4): "52 accounts, backup made on 3 Oct 2026." */
    fun summary(summary: RestoreStep.Summary): UiText =
        UiText.plural(R.plurals.backup_restore_summary, summary.accounts, summary.accounts, BackupDate(summary.createdMs, BackupDate.Style.DATE))

    /** The line of a vault put aside: "Locked on 6 Oct 2026". */
    fun oldItem(putAsideAtMs: Long): UiText = UiText.of(R.string.backup_old_item, BackupDate(putAsideAtMs, BackupDate.Style.DATE))

    /** The title of the step of [action] for a vault put aside at [putAsideAtMs]. */
    fun oldTitle(action: OldAction, putAsideAtMs: Long): UiText = UiText.of(
        if (action == OldAction.OPEN) R.string.backup_old_open_title else R.string.backup_old_delete_title,
        BackupDate(putAsideAtMs, BackupDate.Style.DATE),
    )

    /** Why the last try of an old-passwords step did nothing. */
    fun oldProblem(problem: OldProblem, action: OldAction): UiText = UiText.of(
        when (problem) {
            OldProblem.WRONG -> R.string.backup_old_wrong
            OldProblem.NO_MEMORY -> R.string.backup_old_no_memory
            OldProblem.NOT_SAVED -> if (action == OldAction.OPEN) R.string.backup_old_not_added else R.string.backup_old_not_deleted
        },
    )

    /** What an old-passwords action did. */
    fun oldDone(done: OldDone): UiText = UiText.of(if (done == OldDone.ADDED) R.string.backup_old_added else R.string.backup_old_deleted)

    private fun left(place: Place, name: String, phone: Int, drive: Int, other: Int): UiText = when (place) {
        Place.PhoneStorage -> UiText.of(phone, name)
        Place.GoogleDrive -> UiText.of(drive, name)
        is Place.Other -> UiText.of(other, name, place.name)
    }
}

/**
 * A date in a text of these screens: the ViewModel keeps the time, and the screen formats it in its
 * own language (`Formatters`, i18n.md §4.6), so that a language switch changes it too.
 */
data class BackupDate(val ms: Long, val style: Style) {
    enum class Style {
        /** A day and a month: "Oct 7" ("You changed your master password on 7 Oct."). */
        DAY_MONTH,

        /** A day, a month and the year: "Oct 6, 2026". */
        DATE,
    }
}

/** [text] with every [BackupDate] argument, also inside a nested [UiText], formatted by [formatters]. */
fun UiText.withDates(formatters: Formatters): UiText = when (this) {
    is UiText.Res -> UiText.Res(id, args.map { it.withDates(formatters) })
    is UiText.Plural -> UiText.Plural(id, count, args.map { it.withDates(formatters) })
}

private fun Any.withDates(formatters: Formatters): Any = when (this) {
    is BackupDate -> when (style) {
        BackupDate.Style.DAY_MONTH -> formatters.dayMonth(ms)
        BackupDate.Style.DATE -> formatters.date(ms)
    }
    is UiText -> withDates(formatters)
    else -> this
}

/**
 * The test tags of these screens, exposed as resource ids under the route's root, so that tests and
 * the emulator scripts find each part whatever the language.
 */
object BackupTags {
    const val BACK = "pv.backup.back"
    const val TEXT = "pv.backup.text"
    const val PAUSED = "pv.backup.paused"
    const val FIELD = "pv.backup.password"
    const val SUBMIT = "pv.backup.submit"
    const val PROGRESS = "pv.backup.progress"
    const val ERROR = "pv.backup.error"
    const val COUNTDOWN = "pv.backup.countdown"
    const val CONTINUE = "pv.backup.continue"
    const val CHOOSE = "pv.backup.choose"
    const val RETRY = "pv.backup.retry"
    const val CANCEL = "pv.backup.cancel"
    const val SEND = "pv.backup.send"
    const val DONE = "pv.backup.done"
    const val RESTORE = "pv.backup.restore"
    const val SUMMARY = "pv.backup.summary"
    const val STAYS = "pv.backup.stays"
    const val OLD_LIST = "pv.backup.old.list"
    const val OLD_NONE = "pv.backup.old.none"
    const val OLD_DONE = "pv.backup.old.done"

    /** A line of a result or a message, from 0. */
    fun line(index: Int): String = "pv.backup.line.$index"

    /** The row of the vault put aside [vaultId]. */
    fun oldItem(vaultId: String): String = "pv.backup.old.item.$vaultId"

    /** "Open" of the vault [vaultId]. */
    fun oldOpen(vaultId: String): String = "pv.backup.old.open.$vaultId"

    /** "Delete" of the vault [vaultId]. */
    fun oldDelete(vaultId: String): String = "pv.backup.old.delete.$vaultId"
}
