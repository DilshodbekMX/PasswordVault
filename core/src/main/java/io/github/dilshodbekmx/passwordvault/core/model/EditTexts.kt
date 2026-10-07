package io.github.dilshodbekmx.passwordvault.core.model

/**
 * The few texts that an edit stores in the vault (architecture.md §3.7, §5.4). `:app` resolves them
 * from its string resources and passes them in, so that a stored title is in the user's language;
 * `:core` has no resources.
 */
interface EditTexts {
    /** The title of an entry saved with a blank name and no website: "Untitled" and the time [nowMs] (ux.md §2). */
    fun untitled(nowMs: Long): String

    /**
     * The title of an entry brought in from a backup or a vault put aside whose id is taken by an
     * entry with other content: [title] with "(from backup)" (architecture.md §4.11, ux.md §5.21).
     */
    fun fromBackup(title: String): String

    /**
     * The title of an imported entry whose id is held by an entry with other content: [title] with
     * "(from file)" (formats.md §5.2, ux.md §5.21). `VaultEdits.addImported` takes it as `texts::fromFile`.
     */
    fun fromFile(title: String): String
}
