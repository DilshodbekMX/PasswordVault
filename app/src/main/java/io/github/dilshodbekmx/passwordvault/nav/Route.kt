package io.github.dilshodbekmx.passwordvault.nav

import kotlinx.serialization.Serializable

/**
 * Every route key of the app (architecture.md §3.6, ux.md §4). A key holds ids only: an enum, a
 * number or a UUID, never a secret or a text the user typed, because the back stacks are kept in
 * saved state and survive the process (architecture.md §5.6). Each key is registered by the route
 * file named in its comment; a screen that needs a new key asks for it as an interface request,
 * and the integrator adds it here.
 *
 * The keys are `@Serializable` (the saved back stacks, [NavState.encode]); `rules.keep` keeps their
 * names and serializers in staging and release builds.
 */
@Serializable
sealed interface Route {
    /** The key's name, such as "Welcome" or "Entry": the test tag `pv.route.<name>` of its screen. */
    val name: String get() = toString().substringBefore('(')

    /** Shown while the lock reads its records at process start (`LockUiState.Starting`); the shell registers it. */
    @Serializable
    data object Starting : Route

    // ui/setup/SetupNav.kt: setup screens (build plan 3.3)

    @Serializable
    data object Welcome : Route

    @Serializable
    data object CreateMasterPassword : Route

    @Serializable
    data object WriteDown : Route

    @Serializable
    data object TypeAgain : Route

    // ui/setup/PinSetupNav.kt: PIN and fingerprint setup (4.3); after the vault exists

    @Serializable
    data object CreatePin : Route

    @Serializable
    data object ConfirmPin : Route

    @Serializable
    data object FingerprintOffer : Route

    @Serializable
    data object BackupsExplained : Route

    // ui/lock/LockNav.kt: the lock screens (3.4, then 4.2)

    /** The one locked route: `LockUiState` decides what it shows. */
    @Serializable
    data object Locked : Route

    // ui/list/ListNav.kt: list, search and Recently deleted (3.5)

    @Serializable
    data object Passwords : Route

    @Serializable
    data object RecentlyDeleted : Route

    // ui/entry/EntryNav.kt: entry detail and big letters (3.7)

    /** The entry [id] (a UUID). A route to a deleted entry falls back to Passwords (ux.md §4). */
    @Serializable
    data class Entry(val id: String) : Route

    /**
     * "Show in big letters" (ux.md §5.13) for one value of the entry [id]. [field] names the value:
     * "username", "password", "old-password" (while a change is pending), "previous-N" (the Nth
     * previous password, from 0) or "field-N" (the Nth extra field). Never kept across a lock or a
     * new process (architecture.md D-9d).
     */
    @Serializable
    data class BigLetters(val id: String, val field: String) : Route

    // ui/edit/EditNav.kt: add and edit (3.6)

    /** "New account" when [id] is null, otherwise "Edit account" for the entry [id]. */
    @Serializable
    data class Edit(val id: String? = null) : Route

    // ui/settings/SettingsNav.kt: Settings (4.3)

    @Serializable
    data object Settings : Route

    /** Settings › App lock: the PIN, fingerprint, lock delay and clipboard delay rows (ux.md §5.18). */
    @Serializable
    data object AppLock : Route

    /** "Change PIN", or "Set a PIN" when none is usable (lock.md F6). */
    @Serializable
    data object ChangePin : Route

    /**
     * The dialog "Appearance": the colours and light or dark (docs/design/looks/README.md). Not kept
     * across a lock: dialogs are closed after unlock (ux.md §4).
     */
    @Serializable
    data object Theme : Route

    // ui/settings/MasterPasswordNav.kt: change and replace the master password (5.5)

    @Serializable
    data object ChangeMasterPassword : Route

    @Serializable
    data object NewMasterPassword : Route

    // ui/settings/LanguageNav.kt: the language switch (6.1)

    /** The language dialog, from Welcome's globe button or from Settings. Not kept across a lock. */
    @Serializable
    data object Language : Route

    // ui/settings/HelpNav.kt: help and licences (7.3)

    @Serializable
    data class Help(val page: HelpPage) : Route

    @Serializable
    data object Licences : Route

    // ui/transfer/BackupNav.kt: backup, restore and old passwords (5.2)

    @Serializable
    data object Backup : Route

    /** Restore from a backup file: from Welcome, and from the "Forgot master password?" sheet. */
    @Serializable
    data object Restore : Route

    @Serializable
    data object OldPasswords : Route

    // ui/transfer/ExportNav.kt: export (5.3)

    /**
     * The export (5.3). Settings opens `Export()`. An export cut short continues from the recorded
     * `FileTask` (`FileTaskStore.task`), not from [step], so that no route can skip the warning or the
     * master password (ExportNav.kt says why).
     */
    @Serializable
    data class Export(val step: Int = 0) : Route

    // ui/transfer/ImportNav.kt: import (5.4)

    /** The import at its [step] (the step of its `FileTask`). */
    @Serializable
    data class Import(val step: Int = 0) : Route

    @Serializable
    data object ColumnMapping : Route

    // ui/security/SecurityNav.kt: the Security tab (7.1)

    @Serializable
    data object Security : Route

    /**
     * The list behind a card of the Security tab (ux.md §5.16): a group of reused passwords by its
     * [id] (`ReuseGroup.id`, an entry's UUID), or one of the fixed ids "weak", "pending" and
     * "left-out" (`GroupKind`): the passwords easy to guess, the changes not finished, the accounts
     * left out of the check.
     */
    @Serializable
    data class Group(val id: String) : Route

    // ui/security/ChangeNav.kt: the guided change (7.2)

    @Serializable
    data class GuidedChange(val entryId: String) : Route
}

/** The help pages of Settings › Help (ux.md §5.22); "Open-source licences" is [Route.Licences]. */
@Serializable
enum class HelpPage {
    PROTECTION,
    LOST_PHONE,
    FORGOT_MASTER_PASSWORD,
    FORGOT_PIN,
    VERSION,
}
