package io.github.dilshodbekmx.passwordvault.files

/**
 * The MIME types the app gives the file picker (formats.md §2 rule 4, §3, §8.1).
 *
 * A saved file's type must match its extension: a provider appends the type's own extension when
 * the name's extension maps to another type, and `application/octet-stream` is exempt. As measured
 * on the emulators (formats.md §2 rule 4): Android 17 maps `text/csv` to `.csv` (and `.csv` to
 * `text/comma-separated-values`), and `json` and `xlsx` to the types below both ways; Android 7.0
 * knows neither `text/csv` nor `application/json` and calls CSV `text/comma-separated-values`. A
 * provider keeps a name when the type's own extension is the name's or the type has none, so the
 * names of [DocumentNames] come out right on both (formats-review.md P17.3); `DocumentNamesTest`
 * checks it on every test device.
 */
object DocumentTypes {
    /** An encrypted backup (`.pvault`): no extension is ever appended, and import knows it by its first bytes. */
    const val BACKUP = "application/octet-stream"

    /** CSV in Google's layout (`.csv`). */
    const val CSV = "text/csv"

    /** JSON in Bitwarden's layout with our extension (`.json`). */
    const val JSON = "application/json"

    /** Excel (`.xlsx`). */
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /**
     * What the import and restore pickers offer: any type. Providers label CSV as `text/csv`,
     * `text/comma-separated-values` or `application/vnd.ms-excel`, and some change a backup's
     * extension, so the content decides (formats.md §8.1, ux.md §5.2).
     */
    val ANY: List<String> = listOf("*/*")
}
