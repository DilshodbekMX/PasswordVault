package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.BackupReminder
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.settings.OneTime
import io.github.dilshodbekmx.passwordvault.settings.PendingClip
import io.github.dilshodbekmx.passwordvault.settings.PurgeArm
import io.github.dilshodbekmx.passwordvault.settings.SettingsEditor
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [AppSettings] (architecture.md §2.2 `fake`). It starts as a fresh install reads: every
 * value missing, so the strictest ones ([LockDelay.WHEN_MISSING], [ClipDelay.WHEN_MISSING]).
 * [failWrites] makes [update] and [writeDefaults] fail and change nothing; [writes] counts the
 * successful ones. A test may also set any flow's value directly. Single-threaded.
 */
class FakeAppSettings : AppSettings {
    override val lockDelay = MutableStateFlow(LockDelay.WHEN_MISSING)
    override val clipDelay = MutableStateFlow(ClipDelay.WHEN_MISSING)
    override val theme = MutableStateFlow(ThemeChoice.SYSTEM)
    override val generator = MutableStateFlow(GeneratorOptions())
    override val backupReminder = MutableStateFlow(BackupReminder())
    override val fileTask = MutableStateFlow<FileTask?>(null)
    override val oneTime = MutableStateFlow<Set<OneTime>>(emptySet())
    override val purgeArm = MutableStateFlow<PurgeArm?>(null)
    override val pendingClip = MutableStateFlow<PendingClip?>(null)

    /** While true, nothing is written. */
    var failWrites: Boolean = false

    /** How many writes succeeded. */
    var writes: Int = 0
        private set

    override fun writeDefaults(): Boolean = update {
        it.lockDelay = LockDelay.SETUP
        it.clipDelay = ClipDelay.SETUP
    }

    override fun update(change: (SettingsEditor) -> Unit): Boolean {
        if (failWrites) return false
        val editor = Editor()
        change(editor)
        lockDelay.value = editor.lockDelay
        clipDelay.value = editor.clipDelay
        theme.value = editor.theme
        generator.value = editor.generator
        backupReminder.value = editor.backupReminder
        fileTask.value = editor.fileTask
        oneTime.value = editor.oneTime
        purgeArm.value = editor.purgeArm
        pendingClip.value = editor.pendingClip
        writes++
        return true
    }

    private inner class Editor : SettingsEditor {
        override var lockDelay = this@FakeAppSettings.lockDelay.value
        override var clipDelay = this@FakeAppSettings.clipDelay.value
        override var theme = this@FakeAppSettings.theme.value
        override var generator = this@FakeAppSettings.generator.value
        override var backupReminder = this@FakeAppSettings.backupReminder.value
        override var fileTask = this@FakeAppSettings.fileTask.value
        override var oneTime = this@FakeAppSettings.oneTime.value
        override var purgeArm = this@FakeAppSettings.purgeArm.value
        override var pendingClip = this@FakeAppSettings.pendingClip.value
    }
}
