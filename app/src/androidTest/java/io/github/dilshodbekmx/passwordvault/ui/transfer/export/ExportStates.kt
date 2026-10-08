package io.github.dilshodbekmx.passwordvault.ui.transfer.export

import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportChoice
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportCleanup
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportCsvSummary
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportDeletion
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFailure
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFile
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFormat
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportPasswordError
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportUiState

/**
 * Every step of the export as the screen draws it, for the walks in the pseudo-locales and the
 * pictures for the user: each with a name, in the order a user meets them. Built by hand, so that a
 * step that is hard to reach on a device (a wait of the lock's guard, a file that could not be
 * removed, a pending new master password) is drawn as well.
 */
object ExportStates {
    const val NAME = "passwords-2026-10-06.csv"
    private const val DOC = "content://com.android.providers.downloads.documents/document/41"
    private val CSV = ExportFile(ExportFormat.CSV)

    /** The card of a vault with accounts Google will skip, cut into two files. */
    val CARD = ExportCsvSummary(
        parts = 2,
        skipped = listOf(
            ExportCsvSummary.Skipped(GoogleCsv.Refusal.NO_PASSWORD, listOf("Uy Wi-Fi")),
            ExportCsvSummary.Skipped(GoogleCsv.Refusal.NO_WEBSITE, listOf("Ипотека банк", "Oʻzbekiston pochtasi")),
        ),
        partsTooLarge = emptyList(),
    )

    /** The steps, by name. */
    val all: List<Pair<String, ExportUiState>> = listOf(
        "choose" to ExportUiState.Choose(ExportChoice.CSV),
        "warning-csv" to ExportUiState.Warning(ExportFormat.CSV, understood = false, oldAndroid = true, csv = CARD, showWhich = true),
        "warning-xlsx" to ExportUiState.Warning(ExportFormat.XLSX, understood = true, oldAndroid = false),
        "warning-json" to ExportUiState.Warning(ExportFormat.JSON, understood = false, oldAndroid = false),
        "password" to ExportUiState.Password(CSV),
        "password-wrong" to ExportUiState.Password(CSV, error = ExportPasswordError.Wrong),
        "password-wait" to ExportUiState.Password(CSV, error = ExportPasswordError.Wait, waitSecondsLeft = 27),
        "password-older" to ExportUiState.Password(CSV, error = ExportPasswordError.Older(1_791_244_800_000L)),
        "password-checking" to ExportUiState.Password(CSV, checking = true),
        "paused" to ExportUiState.Password(CSV, paused = true, name = NAME),
        "picking" to ExportUiState.Picking(CSV),
        "saving" to ExportUiState.Saving(CSV),
        "saved-drive" to ExportUiState.Saved(CSV, 1, DocRef(DOC), NAME, Place.GoogleDrive),
        "saved-part" to ExportUiState.Saved(CSV, 2, DocRef(DOC), "passwords-2026-10-06-part1.csv", Place.PhoneStorage),
        "saved-deleted" to ExportUiState.Saved(CSV, 1, DocRef(DOC), NAME, Place.PhoneStorage, deletion = ExportDeletion.DELETED),
        "saved-not-deletable" to ExportUiState.Saved(
            ExportFile(ExportFormat.XLSX), 1, DocRef(DOC), "passwords-2026-10-06.xlsx", Place.Other("USB drive"),
            replaced = 1, truncated = 2, deletion = ExportDeletion.NOT_ALLOWED,
        ),
        "failed-write" to ExportUiState.Failed(CSV, ExportFailure.WRITE, ExportCleanup.Left(NAME, Place.Other("USB drive"))),
        "failed-note" to ExportUiState.Failed(CSV, ExportFailure.NOT_NOTED, ExportCleanup.Removed),
        "failed-too-large" to ExportUiState.Failed(CSV, ExportFailure.TOO_LARGE, ExportCleanup.Left(NAME, Place.GoogleDrive)),
        "failed-picker" to ExportUiState.Failed(CSV, ExportFailure.PICKER),
        "ended" to ExportUiState.Ended(ExportCleanup.Left(NAME, Place.PhoneStorage)),
        "pending" to ExportUiState.Pending(ExportCleanup.Removed),
    )
}
