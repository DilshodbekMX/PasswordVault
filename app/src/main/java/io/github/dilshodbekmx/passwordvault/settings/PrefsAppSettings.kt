package io.github.dilshodbekmx.passwordvault.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Build
import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Credit
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.FileTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AppSettings] over the `SharedPreferences` file [FILE], written with `commit()` (architecture.md
 * §3.5, D-7, D-19; build plan 2.9).
 *
 * **Missing or unreadable values.** A key that is absent, holds a value of another type, or holds a
 * text this version does not know reads as the strictest value [V-P9]: the lock delay as
 * [LockDelay.WHEN_MISSING] ("Immediately"), the clipboard delay as [ClipDelay.WHEN_MISSING] (30
 * seconds), the backup reminder as on, and a purge arm, a file task or a pending clip that is not
 * complete as none (nothing is purged, resumed or cleared on half a record). The look has no strict
 * value: the theme reads as [ThemeChoice.SYSTEM] and the style as [StyleChoice.DEFAULT], Bank blue,
 * which also stands for a stored "Phone's colours" on a phone that has none ([phoneColours] false:
 * before Android 12), where that style cannot be set either. Every enum is kept as
 * its fixed `stored` text, and the generator's kind and separator as the fixed texts below, so that
 * renaming a constant (or R8) never changes what is read back. Setup writes the defaults
 * ([writeDefaults], lock.md §10.1).
 *
 * **Reading.** The file is read once, when this object is made: `Context.getSharedPreferences` loads
 * it on a thread of its own, and the first read waits for it. It is a few hundred bytes, read once
 * per process, and every value is then known before the first screen is drawn, so no screen starts
 * from a value that is not the stored one (the theme, the one-time flags, a clip left to clear after
 * a force-stop). From then on the [StateFlow]s are the truth, and they change only after a write
 * that `commit()` reported as done.
 *
 * **Writing.** [update] runs under one lock: it starts an editor from the current values, lets the
 * caller change it, writes the keys of the values that changed in one `commit()`, and only then
 * publishes them. `SharedPreferences` changes its in-memory copy before it writes the file, so after
 * a failed `commit()` the old entries of those keys are put back (a second commit, best effort): a
 * later successful write then cannot carry the failed change to disk. A write is a disk write: call
 * [update] and [writeDefaults] off the main thread (architecture.md §5.3), and never call [update]
 * again from inside its `change`.
 *
 * Nothing here is secret: no password, no PIN, no account data. The clip's token is a random number
 * that marks our own clip, and a document is a `content://` address.
 */
class PrefsAppSettings(
    private val prefs: SharedPreferences,
    private val phoneColours: Boolean = StyleChoice.hasPhoneColours(Build.VERSION.SDK_INT),
) : AppSettings {

    /** Held for the whole of every write, so that two writes never interleave. */
    private val writing = Any()

    private val initial = Values.read(prefs.all, phoneColours)

    /** The values last read or written; changed only under [writing]. */
    @Volatile
    private var current: Values = initial

    private val lockDelayFlow = MutableStateFlow(initial.lockDelay)
    private val clipDelayFlow = MutableStateFlow(initial.clipDelay)
    private val themeFlow = MutableStateFlow(initial.theme)
    private val styleFlow = MutableStateFlow(initial.style)
    private val generatorFlow = MutableStateFlow(initial.generator)
    private val backupReminderFlow = MutableStateFlow(initial.backupReminder)
    private val fileTaskFlow = MutableStateFlow(initial.fileTask)
    private val oneTimeFlow = MutableStateFlow(initial.oneTime)
    private val purgeArmFlow = MutableStateFlow(initial.purgeArm)
    private val pendingClipFlow = MutableStateFlow(initial.pendingClip)

    override val lockDelay: StateFlow<LockDelay> = lockDelayFlow.asStateFlow()
    override val clipDelay: StateFlow<ClipDelay> = clipDelayFlow.asStateFlow()
    override val theme: StateFlow<ThemeChoice> = themeFlow.asStateFlow()
    override val style: StateFlow<StyleChoice> = styleFlow.asStateFlow()
    override val generator: StateFlow<GeneratorOptions> = generatorFlow.asStateFlow()
    override val backupReminder: StateFlow<BackupReminder> = backupReminderFlow.asStateFlow()
    override val fileTask: StateFlow<FileTask?> = fileTaskFlow.asStateFlow()
    override val oneTime: StateFlow<Set<OneTime>> = oneTimeFlow.asStateFlow()
    override val purgeArm: StateFlow<PurgeArm?> = purgeArmFlow.asStateFlow()
    override val pendingClip: StateFlow<PendingClip?> = pendingClipFlow.asStateFlow()

    override fun hasSettings(): Boolean = prefs.contains(LOCK_DELAY) || prefs.contains(CLIP_DELAY)

    override fun writeDefaults(): Boolean = update {
        it.lockDelay = LockDelay.SETUP
        it.clipDelay = ClipDelay.SETUP
    }

    // commit(), not the KTX edit {}, which uses apply(): the result decides what is published (D-19).
    @SuppressLint("UseKtx")
    override fun update(change: (SettingsEditor) -> Unit): Boolean = synchronized(writing) {
        val before = current
        val editor = Editor(before)
        change(editor)
        val after = editor.values().let { set ->
            if (set.style in StyleChoice.offered(phoneColours)) set else set.copy(style = StyleChoice.DEFAULT)
        }
        if (after == before) return@synchronized true

        val entriesBefore = prefs.all
        val edit = prefs.edit()
        val touched = Values.write(before, after, edit)
        if (!edit.commit()) {
            restore(touched, entriesBefore)
            return@synchronized false
        }
        current = after
        publish(after)
        true
    }

    /**
     * Puts the in-memory entries of [keys] back as they were in [entries], after a failed commit. A
     * `commit()` like every write here (D-19), whose result does not matter: the file still holds the
     * old entries if this one fails too.
     */
    @SuppressLint("ApplySharedPref", "UseKtx")
    private fun restore(keys: Set<String>, entries: Map<String, *>) {
        val edit = prefs.edit()
        for (key in keys) {
            when (val value = entries[key]) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toHashSet())
                else -> edit.remove(key)
            }
        }
        edit.commit()
    }

    private fun publish(values: Values) {
        lockDelayFlow.value = values.lockDelay
        clipDelayFlow.value = values.clipDelay
        themeFlow.value = values.theme
        styleFlow.value = values.style
        generatorFlow.value = values.generator
        backupReminderFlow.value = values.backupReminder
        fileTaskFlow.value = values.fileTask
        oneTimeFlow.value = values.oneTime
        purgeArmFlow.value = values.purgeArm
        pendingClipFlow.value = values.pendingClip
    }

    private class Editor(start: Values) : SettingsEditor {
        override var lockDelay = start.lockDelay
        override var clipDelay = start.clipDelay
        override var theme = start.theme
        override var style = start.style
        override var generator = start.generator
        override var backupReminder = start.backupReminder
        override var fileTask = start.fileTask
        override var oneTime = start.oneTime
        override var purgeArm = start.purgeArm
        override var pendingClip = start.pendingClip

        fun values() = Values(
            lockDelay, clipDelay, theme, style, generator, backupReminder, fileTask, oneTime.toSet(), purgeArm, pendingClip,
        )
    }

    /** Every value of the file, as read or as about to be written. */
    private data class Values(
        val lockDelay: LockDelay,
        val clipDelay: ClipDelay,
        val theme: ThemeChoice,
        val style: StyleChoice,
        val generator: GeneratorOptions,
        val backupReminder: BackupReminder,
        val fileTask: FileTask?,
        val oneTime: Set<OneTime>,
        val purgeArm: PurgeArm?,
        val pendingClip: PendingClip?,
    ) {
        companion object {
            /**
             * The values of [entries], `SharedPreferences.getAll()`; anything missing or unreadable
             * as above. [phoneColours]: whether this phone has the wallpaper's colours.
             */
            fun read(entries: Map<String, *>, phoneColours: Boolean): Values = Values(
                lockDelay = LockDelay.entries.firstOrNull { it.stored == entries[LOCK_DELAY] } ?: LockDelay.WHEN_MISSING,
                clipDelay = ClipDelay.entries.firstOrNull { it.stored == entries[CLIP_DELAY] } ?: ClipDelay.WHEN_MISSING,
                theme = ThemeChoice.entries.firstOrNull { it.stored == entries[THEME] } ?: ThemeChoice.SYSTEM,
                style = StyleChoice.read(entries[STYLE], phoneColours),
                generator = readGenerator(entries),
                backupReminder = BackupReminder(
                    on = entries[BACKUP_ON] as? Boolean ?: true,
                    snoozedUntilMs = entries[BACKUP_SNOOZED_UNTIL] as? Long,
                    offeredAtMs = entries[BACKUP_OFFERED_AT] as? Long,
                ),
                fileTask = readFileTask(entries),
                oneTime = (entries[ONE_TIME] as? Set<*>).orEmpty()
                    .mapNotNull { stored -> OneTime.entries.firstOrNull { it.stored == stored } }.toSet(),
                purgeArm = readPurgeArm(entries),
                pendingClip = readPendingClip(entries),
            )

            /**
             * Writes into [edit] the keys of every value that differs between [old] and [new], and
             * returns those keys. A value that is null, or an optional part of one, is removed.
             */
            fun write(old: Values, new: Values, edit: SharedPreferences.Editor): Set<String> {
                val keys = mutableSetOf<String>()
                fun put(key: String, value: Any?) {
                    keys += key
                    when (value) {
                        null -> edit.remove(key)
                        is String -> edit.putString(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toHashSet())
                        else -> error("no stored form for ${value.javaClass.name}")
                    }
                }
                if (new.lockDelay != old.lockDelay) put(LOCK_DELAY, new.lockDelay.stored)
                if (new.clipDelay != old.clipDelay) put(CLIP_DELAY, new.clipDelay.stored)
                if (new.theme != old.theme) put(THEME, new.theme.stored)
                if (new.style != old.style) put(STYLE, new.style.stored)
                if (new.generator != old.generator) {
                    val g = new.generator
                    put(GEN_KIND, kindStored(g.kind))
                    put(GEN_LENGTH, g.length)
                    put(GEN_UPPER, g.upper)
                    put(GEN_LOWER, g.lower)
                    put(GEN_DIGITS, g.digits)
                    put(GEN_SYMBOLS, g.symbols)
                    put(GEN_AVOID_LOOK_ALIKE, g.avoidLookAlike)
                    put(GEN_WORDS, g.words)
                    put(GEN_SEPARATOR, g.separator.char.toString())
                    put(GEN_CAPITALIZE, g.capitalize)
                    put(GEN_ADD_NUMBER, g.addNumber)
                }
                if (new.backupReminder != old.backupReminder) {
                    put(BACKUP_ON, new.backupReminder.on)
                    put(BACKUP_SNOOZED_UNTIL, new.backupReminder.snoozedUntilMs)
                    put(BACKUP_OFFERED_AT, new.backupReminder.offeredAtMs)
                }
                if (new.fileTask != old.fileTask) {
                    val task = new.fileTask
                    put(TASK_KIND, task?.kind?.stored)
                    put(TASK_STEP, task?.step)
                    put(TASK_DOC, task?.doc?.uri)
                    put(TASK_WRITTEN, if (task?.written == true) true else null)
                }
                if (new.oneTime != old.oneTime) put(ONE_TIME, new.oneTime.map { it.stored }.toSet())
                if (new.purgeArm != old.purgeArm) {
                    val arm = new.purgeArm
                    put(PURGE_VAULT, arm?.vaultId)
                    put(PURGE_ARMED_AT, arm?.armedAtMs)
                    put(PURGE_CREDITED, arm?.credit?.creditedMs)
                    put(PURGE_SEEN, arm?.credit?.seenElapsedMs)
                }
                if (new.pendingClip != old.pendingClip) {
                    val clip = new.pendingClip
                    put(CLIP_TOKEN, clip?.token)
                    put(CLIP_DEADLINE, clip?.deadlineMs)
                }
                return keys
            }

            /**
             * The generator's options, each from its own key. A length or a number of words outside
             * the sheet's bounds reads as the default, and so do the four character sets when none of
             * them is on, which the sheet never allows (ux.md §5.15).
             */
            private fun readGenerator(entries: Map<String, *>): GeneratorOptions {
                val d = GeneratorOptions()
                val kind = when (entries[GEN_KIND]) {
                    KIND_RANDOM -> GeneratorOptions.Kind.RANDOM
                    KIND_WORDS -> GeneratorOptions.Kind.WORDS
                    else -> d.kind
                }
                val length = (entries[GEN_LENGTH] as? Int)
                    ?.takeIf { it in GeneratorOptions.MIN_LENGTH..GeneratorOptions.MAX_LENGTH } ?: d.length
                val words = (entries[GEN_WORDS] as? Int)
                    ?.takeIf { it in GeneratorOptions.MIN_WORDS..GeneratorOptions.MAX_WORDS } ?: d.words
                val separator = (entries[GEN_SEPARATOR] as? String)
                    ?.let { s -> GeneratorOptions.Separator.entries.firstOrNull { it.char.toString() == s } } ?: d.separator
                var upper = entries[GEN_UPPER] as? Boolean ?: d.upper
                var lower = entries[GEN_LOWER] as? Boolean ?: d.lower
                var digits = entries[GEN_DIGITS] as? Boolean ?: d.digits
                var symbols = entries[GEN_SYMBOLS] as? Boolean ?: d.symbols
                if (!upper && !lower && !digits && !symbols) {
                    upper = d.upper
                    lower = d.lower
                    digits = d.digits
                    symbols = d.symbols
                }
                return GeneratorOptions(
                    kind = kind,
                    length = length,
                    upper = upper,
                    lower = lower,
                    digits = digits,
                    symbols = symbols,
                    avoidLookAlike = entries[GEN_AVOID_LOOK_ALIKE] as? Boolean ?: d.avoidLookAlike,
                    words = words,
                    separator = separator,
                    capitalize = entries[GEN_CAPITALIZE] as? Boolean ?: d.capitalize,
                    addNumber = entries[GEN_ADD_NUMBER] as? Boolean ?: d.addNumber,
                )
            }

            private fun kindStored(kind: GeneratorOptions.Kind): String = when (kind) {
                GeneratorOptions.Kind.RANDOM -> KIND_RANDOM
                GeneratorOptions.Kind.WORDS -> KIND_WORDS
            }

            /**
             * A task needs its kind and a step of 0 or more; the document is optional. That its write
             * began is kept only while it is true, and means something only with a document.
             */
            private fun readFileTask(entries: Map<String, *>): FileTask? {
                val kind = FileTask.Kind.entries.firstOrNull { it.stored == entries[TASK_KIND] } ?: return null
                val step = (entries[TASK_STEP] as? Int)?.takeIf { it >= 0 } ?: return null
                val doc = (entries[TASK_DOC] as? String)?.takeIf { it.isNotEmpty() }?.let(::DocRef)
                return FileTask(kind, step, doc, written = doc != null && entries[TASK_WRITTEN] == true)
            }

            /** All four parts, or not armed: a purge is never carried out on half a record. */
            private fun readPurgeArm(entries: Map<String, *>): PurgeArm? {
                val vaultId = (entries[PURGE_VAULT] as? String)?.takeIf { it.isNotEmpty() } ?: return null
                val armedAt = entries[PURGE_ARMED_AT] as? Long ?: return null
                val credited = entries[PURGE_CREDITED] as? Long ?: return null
                val seen = entries[PURGE_SEEN] as? Long ?: return null
                return PurgeArm(vaultId, armedAt, Credit(credited, seen))
            }

            private fun readPendingClip(entries: Map<String, *>): PendingClip? {
                val token = (entries[CLIP_TOKEN] as? String)?.takeIf { it.isNotEmpty() } ?: return null
                val deadline = entries[CLIP_DEADLINE] as? Long ?: return null
                return PendingClip(token, deadline)
            }
        }
    }

    companion object {
        /** The name of the settings file (architecture.md §3.5). */
        const val FILE = "pv_settings"

        // The keys. They and the stored texts are what earlier versions wrote: never rename them.
        const val LOCK_DELAY = "lock_delay"
        const val CLIP_DELAY = "clip_delay"
        const val THEME = "theme"
        const val STYLE = "style"
        const val GEN_KIND = "generator_kind"
        const val GEN_LENGTH = "generator_length"
        const val GEN_UPPER = "generator_upper"
        const val GEN_LOWER = "generator_lower"
        const val GEN_DIGITS = "generator_digits"
        const val GEN_SYMBOLS = "generator_symbols"
        const val GEN_AVOID_LOOK_ALIKE = "generator_avoid_look_alike"
        const val GEN_WORDS = "generator_words"
        const val GEN_SEPARATOR = "generator_separator"
        const val GEN_CAPITALIZE = "generator_capitalize"
        const val GEN_ADD_NUMBER = "generator_add_number"
        const val BACKUP_ON = "backup_reminder_on"
        const val BACKUP_SNOOZED_UNTIL = "backup_reminder_snoozed_until_ms"
        const val BACKUP_OFFERED_AT = "backup_reminder_offered_at_ms"
        const val TASK_KIND = "file_task_kind"
        const val TASK_STEP = "file_task_step"
        const val TASK_DOC = "file_task_doc"
        const val TASK_WRITTEN = "file_task_written"
        const val ONE_TIME = "one_time"
        const val PURGE_VAULT = "purge_arm_vault_id"
        const val PURGE_ARMED_AT = "purge_arm_armed_at_ms"
        const val PURGE_CREDITED = "purge_arm_credited_ms"
        const val PURGE_SEEN = "purge_arm_seen_elapsed_ms"
        const val CLIP_TOKEN = "pending_clip_token"
        const val CLIP_DEADLINE = "pending_clip_deadline_ms"

        /** The stored texts of `GeneratorOptions.Kind`, which has none of its own (it is :core's). */
        const val KIND_RANDOM = "random"
        const val KIND_WORDS = "words"
    }
}
