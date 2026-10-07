package io.github.dilshodbekmx.passwordvault.settings

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.files.FileTask
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's settings outside the vault, needed before unlock and not secret (ux.md §2 "Outside the
 * vault"; architecture.md §3.5, D-7, D-19): `PrefsAppSettings` (build plan 2.9) over the
 * `SharedPreferences` file "pv_settings", written with `commit()`. A value that is missing or cannot
 * be read is the strictest one [V-P9]; setup writes the defaults ([writeDefaults]). The app's
 * language is not here: AppCompat keeps it (i18n.md §1.4).
 */
interface AppSettings {
    /** "Lock the app after leaving it". Missing or unreadable: [LockDelay.IMMEDIATELY]; setup writes [LockDelay.SECONDS_30]. */
    val lockDelay: StateFlow<LockDelay>

    /** "Clear copied text after". Missing or unreadable: [ClipDelay.SECONDS_30]; setup writes [ClipDelay.MINUTE_1] (ux.md §5.18). */
    val clipDelay: StateFlow<ClipDelay>

    /** System, light or dark. */
    val theme: StateFlow<ThemeChoice>

    /** The generator sheet's last choices (ux.md §5.15). */
    val generator: StateFlow<GeneratorOptions>

    /** The backup reminder: on or off, and snoozed until (ux.md §5.11); when a backup is due is computed from the vault (architecture.md §4.3). */
    val backupReminder: StateFlow<BackupReminder>

    /** An unfinished backup, export, import or restore: its kind, step and document; nothing secret (ux.md §4). */
    val fileTask: StateFlow<FileTask?>

    /** The one-time tips and flags already shown or set. */
    val oneTime: StateFlow<Set<OneTime>>

    /** The first step of the two-step purge of Recently deleted (architecture.md D-24); null when not armed. */
    val purgeArm: StateFlow<PurgeArm?>

    /**
     * The token and deadline of a clip of ours still to be cleared (architecture.md D-14): kept here
     * so that an overdue clip is cleared when the app next comes to the front, after a force-stop
     * cancelled its alarm. Null when nothing of ours is pending.
     */
    val pendingClip: StateFlow<PendingClip?>

    /**
     * Writes the setup defaults: a 30-second lock delay and a 1-minute clipboard delay (lock.md
     * §10.1, D-7). Called by `LockManager.createVault` after the vault is proven. False: not written.
     */
    fun writeDefaults(): Boolean

    /**
     * Changes settings in one `commit()`: [change] sets values on the editor, which starts from the
     * current ones. False: not written, and nothing changed. Call it off the main thread
     * (architecture.md §5.3).
     */
    fun update(change: (SettingsEditor) -> Unit): Boolean
}

/** The values [AppSettings.update] can set; each starts as the current value. */
interface SettingsEditor {
    var lockDelay: LockDelay
    var clipDelay: ClipDelay
    var theme: ThemeChoice
    var generator: GeneratorOptions
    var backupReminder: BackupReminder
    var fileTask: FileTask?
    var oneTime: Set<OneTime>
    var purgeArm: PurgeArm?
    var pendingClip: PendingClip?
}
