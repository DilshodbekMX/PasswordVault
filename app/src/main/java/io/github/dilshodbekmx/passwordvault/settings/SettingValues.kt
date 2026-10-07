package io.github.dilshodbekmx.passwordvault.settings

import io.github.dilshodbekmx.passwordvault.core.lock.guard.Credit

// The values of AppSettings (architecture.md §3.5, D-7). Every enum has a fixed `stored` text, the
// value kept in "pv_settings", so that renaming a constant (or R8) never changes what is read back.

/** Settings › App lock › "Lock the app after leaving it" (ux.md §5.18, lock.md §10.1). */
enum class LockDelay(val stored: String, val ms: Long) {
    IMMEDIATELY("immediately", 0L),
    SECONDS_30("30s", 30_000L),
    MINUTE_1("1m", 60_000L),
    MINUTES_5("5m", 300_000L),
    ;

    companion object {
        /** A setting that is missing or cannot be read counts as the strictest one [V-P9]. */
        val WHEN_MISSING = IMMEDIATELY

        /** What setup writes (D-7). */
        val SETUP = SECONDS_30
    }
}

/** Settings › App lock › "Clear copied text after" (ux.md §5.18): there is no "Never". */
enum class ClipDelay(val stored: String, val ms: Long) {
    SECONDS_30("30s", 30_000L),
    MINUTE_1("1m", 60_000L),
    MINUTES_2("2m", 120_000L),
    MINUTES_5("5m", 300_000L),
    ;

    companion object {
        /** A setting that is missing or cannot be read (D-7). */
        val WHEN_MISSING = SECONDS_30

        /** What setup writes (D-7). */
        val SETUP = MINUTE_1
    }
}

/** Settings › Appearance & language › Theme (ux.md §5.18). */
enum class ThemeChoice(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
}

/**
 * The backup reminder (ux.md §5.11, §5.18): the switch "Backup reminders" ([on], on by default) and
 * "Later", which snoozes it for 3 days ([snoozedUntilMs], wall clock).
 */
data class BackupReminder(val on: Boolean = true, val snoozedUntilMs: Long? = null)

/** One-time tips and flags (ux.md §2 "Outside the vault"). */
enum class OneTime(val stored: String) {
    /** Setup's words were shown and no vault exists yet: the next start says "Setup was interrupted." (ux.md §5.5). */
    WORDS_SHOWN("words_shown"),

    /** The first-copy tip was shown (ux.md §6). */
    FIRST_COPY_TIP("first_copy_tip"),

    /** The backup explanation was shown (ux.md §5.19 step 1). */
    BACKUP_EXPLAINED("backup_explained"),

    /** "This phone can't keep a PIN or fingerprint key in protected hardware" was shown (lock.md F18, the first time only). */
    NO_SECURE_HARDWARE_TOLD("no_secure_hardware_told"),
}

/**
 * The purge of Recently deleted, armed (architecture.md D-24): for the vault [vaultId], at
 * [armedAtMs] (wall clock); the entries already due then are removed at a later unlock, once
 * [credit] has 24 hours (lock.md §6.9). A switch of vault disarms it.
 */
data class PurgeArm(val vaultId: String, val armedAtMs: Long, val credit: Credit)

/**
 * A clip of ours still to be cleared (architecture.md D-14): the random [token] in its extras (hex)
 * and the planned clear time [deadlineMs] (wall clock, because an overdue clip may be found after a
 * reboot). The token identifies our copy; it is not a secret of the vault.
 */
data class PendingClip(val token: String, val deadlineMs: Long)
