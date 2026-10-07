package io.github.dilshodbekmx.passwordvault.files

/**
 * Where a chosen document lives (ux.md §5.19): "Last backup: 3 days ago, Google Drive", and the text
 * that asks the user to delete an empty file themselves where the place does not allow it (ux.md §4).
 */
sealed interface Place {
    /** The phone's own storage (Downloads and the like). */
    data object PhoneStorage : Place

    /** Google Drive. */
    data object GoogleDrive : Place

    /** Another documents provider, by the [name] it shows. */
    data class Other(val name: String) : Place
}
