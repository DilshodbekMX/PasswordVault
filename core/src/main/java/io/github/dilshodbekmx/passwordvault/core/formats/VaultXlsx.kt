package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.TimeZone

/**
 * The Excel export: the vault as one sheet, for reading and printing (formats.md §6, ux.md §5.20.1).
 * The workbook itself is `Xlsx`'s: every cell a shared string in text format, so nothing is
 * evaluated or converted, the same bytes on every phone, and no author or time in the file.
 *
 * - **Columns**, with their widths in characters: Name 24 · Username 26 · Password 22 · Website 30 ·
 *   Other websites 24 (one per line) · Notes 40 · Extra fields 30 (`Label (hidden): value`, one per
 *   line) · Favourite 10 ("Yes") · Added 16 · Last changed 16 · Password changed 16. The labels come
 *   from the app, in the user's language ([Labels]).
 * - **One row per account**, in the order given; deleted accounts have no row (D-10e).
 * - **Dates** are text such as `2026-10-06 14:30` in the time zone given (the phone's), which sorts
 *   correctly; ASCII digits whatever the language.
 * - **Android app links** are shown as `app: com.example`.
 * - **Hidden fields are shown in clear** (D-17): the file exists to be read and printed, and
 *   "(hidden)" after the label still says which ones are secret.
 * - **A pending change** (D-10d): the Password column holds the new password, and the Extra fields
 *   cell ends with the line `Old password (hidden): <old password>`.
 * - Previous passwords, "left out of the security check" and the ids are not in the file, and the
 *   file is not meant to be imported back (formats.md §6.3).
 */
object VaultXlsx {
    /** The sheet's name when [Labels.sheet] is not one Excel accepts. */
    const val DEFAULT_SHEET = "Passwords"

    /**
     * The texts of the sheet. The defaults are the English ones; the app passes its own from its
     * string resources (the export screens, build plan 5.3).
     */
    class Labels(
        /** The sheet's name: 1 to 31 characters, none of `[ ] : * ? / \` (`Xlsx.isValidSheetName`). */
        val sheet: String = DEFAULT_SHEET,
        val name: String = "Name",
        val username: String = "Username",
        val password: String = "Password",
        val website: String = "Website",
        val otherWebsites: String = "Other websites",
        val notes: String = "Notes",
        val fields: String = "Extra fields",
        val favourite: String = "Favourite",
        val added: String = "Added",
        val lastChanged: String = "Last changed",
        val passwordChanged: String = "Password changed",
        /** The Favourite cell of a favourite; other accounts leave it empty. */
        val yes: String = "Yes",
        /** The word in "PIN code (hidden): 1234". */
        val hidden: String = "hidden",
        /** The label of the old password's line while a change is pending. */
        val oldPassword: String = "Old password",
        /** The word in "app: com.example". */
        val app: String = "app",
        /** Printed at the top left of every page: the app's name. */
        val pageHeader: String = "PasswordVault",
        /** The note at the end of a cell that was cut at Excel's 32,767 characters, given how many were cut. */
        val truncationNote: (Int) -> String = Xlsx.DEFAULT_TRUNCATION_NOTE,
    )

    /** A workbook as bytes, and what the writer counted. */
    class Written(val bytes: ByteArray, val stats: Xlsx.Stats) {
        override fun toString(): String = "Written(bytes=${bytes.size}, rows=${stats.rows})"
    }

    /** The eleven columns with their widths (formats.md §6.2). */
    fun columns(labels: Labels = Labels()): List<Xlsx.Column> = listOf(
        Xlsx.Column(labels.name, 24.0),
        Xlsx.Column(labels.username, 26.0),
        Xlsx.Column(labels.password, 22.0),
        Xlsx.Column(labels.website, 30.0),
        Xlsx.Column(labels.otherWebsites, 24.0),
        Xlsx.Column(labels.notes, 40.0),
        Xlsx.Column(labels.fields, 30.0),
        Xlsx.Column(labels.favourite, 10.0),
        Xlsx.Column(labels.added, 16.0),
        Xlsx.Column(labels.lastChanged, 16.0),
        Xlsx.Column(labels.passwordChanged, 16.0),
    )

    /**
     * A website as the sheet shows it: Chrome's `android://<hash>@<package>/` and Bitwarden's
     * `androidapp://<package>` as `app: <package>`, anything else as it is.
     */
    fun displayUrl(url: String, app: String = "app"): String = when {
        url.startsWith("android://", ignoreCase = true) && url.indexOf('@') >= 0 ->
            "$app: " + url.substring(url.lastIndexOf('@') + 1).trimEnd('/')
        url.startsWith("androidapp://", ignoreCase = true) ->
            "$app: " + url.substring("androidapp://".length).trimEnd('/')
        else -> url
    }

    /** [ms] as `2026-10-06 14:30` in [zone], without `java.time` (API 26). */
    fun localTime(ms: Long, zone: TimeZone): String {
        val local = ms + zone.getOffset(ms)
        val date = IsoTime.civilFromDays(local.floorDiv(IsoTime.MS_PER_DAY))
        val minutes = local.mod(IsoTime.MS_PER_DAY) / 60_000
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d", date[0], date[1], date[2], minutes / 60, minutes % 60)
    }

    /** The cells of [entry]'s row, in the order of [columns]; null leaves a cell empty. */
    fun row(entry: Entry, zone: TimeZone, labels: Labels = Labels()): List<String?> {
        val websites = entry.urls.filter { it.isNotBlank() }.map { displayUrl(it, labels.app) }
        val hidden = " (" + labels.hidden + ")"
        val fields = ArrayList<String>(entry.fields.size + 1)
        for (field in entry.fields) fields.add(field.name + (if (field.secret) hidden else "") + ": " + field.value)
        val oldPassword = if (entry.changePending) entry.pendingOldPassword else null
        if (oldPassword != null) fields.add(labels.oldPassword + hidden + ": " + oldPassword)
        return listOf(
            entry.title,
            entry.username,
            entry.password,
            websites.firstOrNull(),
            if (websites.size > 1) websites.subList(1, websites.size).joinToString("\n") else null,
            entry.notes,
            if (fields.isEmpty()) null else fields.joinToString("\n"),
            if (entry.favourite) labels.yes else null,
            localTime(entry.createdAt, zone),
            localTime(entry.updatedAt, zone),
            entry.passwordChangedAt?.let { localTime(it, zone) },
        )
    }

    /** The rows of every account of [entries] outside Recently deleted, in order. */
    fun rows(entries: List<Entry>, zone: TimeZone, labels: Labels = Labels()): List<List<String?>> =
        entries.filter { it.deletedAt == null }.map { row(it, zone, labels) }

    /**
     * Writes the workbook of the accounts of [entries] outside Recently deleted to [out], with the
     * dates in [zone]. Does not close [out].
     */
    fun write(out: OutputStream, entries: List<Entry>, zone: TimeZone, labels: Labels = Labels()): Xlsx.Stats {
        // A label Excel would refuse as a sheet name must not stop the export.
        val sheet = if (Xlsx.isValidSheetName(labels.sheet)) labels.sheet else DEFAULT_SHEET
        return Xlsx.write(
            out = out,
            sheetName = sheet,
            columns = columns(labels),
            rows = rows(entries, zone, labels),
            pageHeader = labels.pageHeader,
            truncationNote = labels.truncationNote,
        )
    }

    /** The workbook as bytes, for `DocumentIo.write`. */
    fun export(entries: List<Entry>, zone: TimeZone, labels: Labels = Labels()): Written {
        val buffer = ByteArrayOutputStream(64 * 1024)
        val stats = write(buffer, entries, zone, labels)
        return Written(buffer.toByteArray(), stats)
    }
}
