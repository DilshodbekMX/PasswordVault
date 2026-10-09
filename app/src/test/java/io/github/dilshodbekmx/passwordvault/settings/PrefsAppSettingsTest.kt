package io.github.dilshodbekmx.passwordvault.settings

import io.github.dilshodbekmx.passwordvault.core.generator.GeneratorOptions
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Credit
import io.github.dilshodbekmx.passwordvault.files.DocRef
import io.github.dilshodbekmx.passwordvault.files.FileTask
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.BACKUP_OFFERED_AT
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.BACKUP_ON
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.BACKUP_SNOOZED_UNTIL
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.CLIP_DEADLINE
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.CLIP_DELAY
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.CLIP_TOKEN
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.GEN_KIND
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.GEN_LENGTH
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.GEN_LOWER
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.GEN_SEPARATOR
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.GEN_SYMBOLS
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.GEN_UPPER
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.GEN_DIGITS
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.GEN_WORDS
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.LOCK_DELAY
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.ONE_TIME
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.PURGE_ARMED_AT
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.PURGE_CREDITED
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.PURGE_SEEN
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.PURGE_VAULT
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.TASK_DOC
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.TASK_KIND
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.TASK_STEP
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.TASK_WRITTEN
import io.github.dilshodbekmx.passwordvault.settings.PrefsAppSettings.Companion.THEME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Build plan 2.9, [JVM]: [PrefsAppSettings] over an in-memory `SharedPreferences` whose "file"
 * outlives it, so that making a new one is a restart of the process (architecture.md §3.5, D-7,
 * D-19, D-24; lock.md §10.1).
 */
class PrefsAppSettingsTest {
    private val file = FakePrefsFile()

    /** A new process: a new `SharedPreferences` that loads the file, and new settings over it. */
    private fun start() = PrefsAppSettings(FakeSharedPreferences(file))

    private val arm = PurgeArm("6f9619ff-8b86-4011-b42d-00c04fc964ff", 1_790_000_000_000L, Credit(3_600_000L, 12_345L))

    @Test
    fun aMissingLockDelayReadsAsImmediatelyAndAMissingClipboardDelayAs30Seconds() {
        val settings = start()
        assertEquals(LockDelay.IMMEDIATELY, settings.lockDelay.value)
        assertEquals(LockDelay.WHEN_MISSING, settings.lockDelay.value)
        assertEquals(ClipDelay.SECONDS_30, settings.clipDelay.value)
        assertEquals(ClipDelay.WHEN_MISSING, settings.clipDelay.value)
        // The rest of a fresh install.
        assertEquals(ThemeChoice.SYSTEM, settings.theme.value)
        assertEquals(GeneratorOptions(), settings.generator.value)
        assertEquals(BackupReminder(on = true, snoozedUntilMs = null), settings.backupReminder.value)
        assertNull(settings.fileTask.value)
        assertEquals(emptySet<OneTime>(), settings.oneTime.value)
        assertNull(settings.purgeArm.value)
        assertNull(settings.pendingClip.value)
    }

    @Test
    fun anUnreadableDelayReadsAsTheStrictestOne() {
        // Another type, an unknown text, an empty text, a constant's name instead of its stored text.
        for (bad in listOf<Any>(30, 30_000L, true, "10m", "", "SECONDS_30", "MINUTE_1", setOf("30s"))) {
            file.stored = mapOf(LOCK_DELAY to bad, CLIP_DELAY to bad)
            val settings = start()
            assertEquals("lock delay stored as $bad", LockDelay.IMMEDIATELY, settings.lockDelay.value)
            assertEquals("clipboard delay stored as $bad", ClipDelay.SECONDS_30, settings.clipDelay.value)
        }
    }

    @Test
    fun writeDefaultsWrites30SecondsAnd1Minute() {
        val settings = start()
        assertTrue(settings.writeDefaults())
        assertEquals(LockDelay.SECONDS_30, settings.lockDelay.value)
        assertEquals(ClipDelay.MINUTE_1, settings.clipDelay.value)
        assertEquals(LockDelay.SETUP, settings.lockDelay.value)
        assertEquals(ClipDelay.SETUP, settings.clipDelay.value)
        // Written explicitly, in one commit, as the fixed stored texts.
        assertEquals(1, file.commits)
        assertEquals(mapOf(LOCK_DELAY to "30s", CLIP_DELAY to "1m"), file.stored)
        // And read back by the next process.
        val again = start()
        assertEquals(LockDelay.SECONDS_30, again.lockDelay.value)
        assertEquals(ClipDelay.MINUTE_1, again.clipDelay.value)
    }

    @Test
    fun hasSettings_isFalseOnANewInstall_andTrueOnceADelayIsStored_inTheNextProcessToo() {
        // build-plan.md §12, decision 13: the lock writes the defaults only while this is false.
        val settings = start()
        assertFalse(settings.hasSettings())
        assertTrue(settings.update { it.oneTime = setOf(OneTime.WORDS_SHOWN) }) // setup's own flag is no setting of the owner's
        assertFalse(settings.hasSettings())
        assertTrue(settings.writeDefaults())
        assertTrue(settings.hasSettings())
        assertTrue(start().hasSettings())
    }

    @Test
    fun theTimeOfTheLastBackupOfferSurvivesARestart_andAnUnreadableOneReadsAsNone() {
        // build-plan.md §12, decision 15: "Make a backup now?" comes at most once a day, in every process.
        val settings = start()
        assertTrue(settings.update { it.backupReminder = it.backupReminder.copy(offeredAtMs = 1_790_000_000_000L) })
        assertEquals(BackupReminder(on = true, snoozedUntilMs = null, offeredAtMs = 1_790_000_000_000L), start().backupReminder.value)
        assertEquals(1_790_000_000_000L, file.stored[BACKUP_OFFERED_AT])
        file.stored = mapOf(BACKUP_OFFERED_AT to "yesterday")
        assertNull(start().backupReminder.value.offeredAtMs)
    }

    @Test
    fun writeDefaultsReplacesEarlierChoices() {
        // writeDefaults itself writes over earlier choices. Since decision 13 (build-plan.md §12) the lock
        // calls it only on a phone that has no settings (hasSettings), so a restore keeps them.
        val settings = start()
        assertTrue(settings.update { it.lockDelay = LockDelay.MINUTES_5; it.clipDelay = ClipDelay.MINUTES_2 })
        assertTrue(settings.writeDefaults())
        assertEquals(mapOf(LOCK_DELAY to "30s", CLIP_DELAY to "1m"), file.stored)
        assertEquals(LockDelay.SECONDS_30, start().lockDelay.value)
    }

    @Test
    fun aFailedWriteDefaultsLeavesTheStrictestValues() {
        file.failWrites = true
        val settings = start()
        assertFalse(settings.writeDefaults())
        assertEquals(LockDelay.IMMEDIATELY, settings.lockDelay.value)
        assertEquals(ClipDelay.SECONDS_30, settings.clipDelay.value)
        file.failWrites = false
        assertEquals(LockDelay.IMMEDIATELY, start().lockDelay.value)
    }

    @Test
    fun theOneTimeFlagsSurviveARestart() {
        assertTrue(start().update { it.oneTime = it.oneTime + OneTime.WORDS_SHOWN })
        assertEquals(setOf(OneTime.WORDS_SHOWN), start().oneTime.value)

        val second = start()
        assertTrue(second.update { it.oneTime = it.oneTime + OneTime.FIRST_COPY_TIP + OneTime.BACKUP_EXPLAINED })
        assertTrue(second.update { it.oneTime = it.oneTime + OneTime.NO_SECURE_HARDWARE_TOLD })
        assertEquals(OneTime.entries.toSet(), start().oneTime.value)

        // Setup ends: "words shown" is cleared, the others stay.
        assertTrue(start().update { it.oneTime = it.oneTime - OneTime.WORDS_SHOWN })
        assertEquals(OneTime.entries.toSet() - OneTime.WORDS_SHOWN, start().oneTime.value)
        assertEquals(
            setOf("first_copy_tip", "backup_explained", "no_secure_hardware_told"),
            file.stored[ONE_TIME],
        )
    }

    @Test
    fun unknownOneTimeFlagsAreIgnored() {
        file.stored = mapOf(ONE_TIME to setOf("words_shown", "a_flag_of_a_newer_version", "WORDS_SHOWN"))
        assertEquals(setOf(OneTime.WORDS_SHOWN), start().oneTime.value)
        file.stored = mapOf(ONE_TIME to "words_shown") // not a set
        assertEquals(emptySet<OneTime>(), start().oneTime.value)
    }

    @Test
    fun thePurgeArmSurvivesARestart() {
        assertTrue(start().update { it.purgeArm = arm })
        assertEquals(arm, start().purgeArm.value)
        assertEquals(arm.vaultId, file.stored[PURGE_VAULT])
        assertEquals(arm.armedAtMs, file.stored[PURGE_ARMED_AT])
        assertEquals(arm.credit.creditedMs, file.stored[PURGE_CREDITED])
        assertEquals(arm.credit.seenElapsedMs, file.stored[PURGE_SEEN])

        // A later unlock credits more time (D-24): the new credit survives too.
        val later = arm.copy(credit = arm.credit.observe(arm.credit.seenElapsedMs + 7_200_000L))
        assertTrue(start().update { it.purgeArm = later })
        assertEquals(later, start().purgeArm.value)
        assertEquals(10_800_000L, start().purgeArm.value?.credit?.creditedMs)

        // Disarmed: gone from the file, and still gone after a restart.
        assertTrue(start().update { it.purgeArm = null })
        assertNull(start().purgeArm.value)
        assertTrue(file.stored.keys.none { it.startsWith("purge_arm") })
    }

    @Test
    fun aPurgeArmMissingAPartIsNotArmed() {
        val whole = mapOf(PURGE_VAULT to arm.vaultId, PURGE_ARMED_AT to arm.armedAtMs, PURGE_CREDITED to 1L, PURGE_SEEN to 2L)
        assertEquals(PurgeArm(arm.vaultId, arm.armedAtMs, Credit(1L, 2L)), PrefsAppSettings(FakeSharedPreferences(FakePrefsFile(whole))).purgeArm.value)
        for (key in whole.keys) {
            file.stored = whole - key
            assertNull("without $key", start().purgeArm.value)
            file.stored = whole + (key to true)
            assertNull("with $key unreadable", start().purgeArm.value)
        }
        file.stored = whole + (PURGE_VAULT to "")
        assertNull("an empty vault id", start().purgeArm.value)
    }

    @Test
    fun everyValueSurvivesARestart() {
        val generator = GeneratorOptions(
            kind = GeneratorOptions.Kind.WORDS, length = 31, upper = false, lower = true, digits = false, symbols = true,
            avoidLookAlike = false, words = 9, separator = GeneratorOptions.Separator.SPACE, capitalize = false, addNumber = false,
        )
        val task = FileTask(FileTask.Kind.EXPORT, 3, DocRef("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fp.csv"))
        val clip = PendingClip("9f86d081884c7d659a2feaa0c55ad015", 1_790_000_030_000L)
        val settings = start()
        assertTrue(
            settings.update {
                it.lockDelay = LockDelay.MINUTES_5
                it.clipDelay = ClipDelay.MINUTES_2
                it.theme = ThemeChoice.DARK
                it.generator = generator
                it.backupReminder = BackupReminder(on = false, snoozedUntilMs = 1_790_259_200_000L)
                it.fileTask = task
                it.oneTime = setOf(OneTime.BACKUP_EXPLAINED)
                it.purgeArm = arm
                it.pendingClip = clip
            },
        )
        for (s in listOf(settings, start())) {
            assertEquals(LockDelay.MINUTES_5, s.lockDelay.value)
            assertEquals(ClipDelay.MINUTES_2, s.clipDelay.value)
            assertEquals(ThemeChoice.DARK, s.theme.value)
            assertEquals(generator, s.generator.value)
            assertEquals(BackupReminder(on = false, snoozedUntilMs = 1_790_259_200_000L), s.backupReminder.value)
            assertEquals(task, s.fileTask.value)
            assertEquals(setOf(OneTime.BACKUP_EXPLAINED), s.oneTime.value)
            assertEquals(arm, s.purgeArm.value)
            assertEquals(clip, s.pendingClip.value)
        }
        // A task without a document yet, a reminder no longer snoozed, no clip: the optional keys go.
        assertTrue(
            settings.update {
                it.fileTask = FileTask(FileTask.Kind.RESTORE, 0, null)
                it.backupReminder = BackupReminder()
                it.pendingClip = null
            },
        )
        val again = start()
        assertEquals(FileTask(FileTask.Kind.RESTORE, 0, null), again.fileTask.value)
        assertEquals(BackupReminder(), again.backupReminder.value)
        assertNull(again.pendingClip.value)
        assertFalse(TASK_DOC in file.stored)
        assertFalse(BACKUP_SNOOZED_UNTIL in file.stored)
        assertFalse(CLIP_TOKEN in file.stored || CLIP_DEADLINE in file.stored)
        assertTrue(settings.update { it.fileTask = null })
        assertNull(start().fileTask.value)
        assertTrue(file.stored.keys.none { it.startsWith("file_task") })
    }

    @Test
    fun thatATasksWriteBeganSurvivesARestart() {
        // FileTaskStore.write records it before it writes, so that a new process that gives the task up
        // removes what was written (architecture.md §3.5).
        val doc = DocRef("content://com.android.providers.downloads.documents/document/41")
        val settings = start()
        assertTrue(settings.update { it.fileTask = FileTask(FileTask.Kind.EXPORT, 2, doc) })
        assertFalse("kept only while true", TASK_WRITTEN in file.stored)
        assertTrue(settings.update { it.fileTask = FileTask(FileTask.Kind.EXPORT, 2, doc, written = true) })
        assertEquals("file_task_written", TASK_WRITTEN)
        assertEquals(true, file.stored[TASK_WRITTEN])
        assertEquals(FileTask(FileTask.Kind.EXPORT, 2, doc, written = true), start().fileTask.value)

        assertTrue(settings.update { it.fileTask = null })
        assertTrue(file.stored.keys.none { it.startsWith("file_task") })

        // Without a document there is nothing it could have written into; anything but true is false.
        file.stored = mapOf(TASK_KIND to "backup", TASK_STEP to 3, TASK_WRITTEN to true)
        assertEquals(FileTask(FileTask.Kind.BACKUP, 3, null), start().fileTask.value)
        file.stored = mapOf(TASK_KIND to "backup", TASK_STEP to 3, TASK_DOC to doc.uri, TASK_WRITTEN to "true")
        assertEquals(FileTask(FileTask.Kind.BACKUP, 3, doc), start().fileTask.value)
    }

    @Test
    fun theStoredTextsAreFixed() {
        // What earlier versions wrote must read back the same: these texts never change.
        assertEquals(listOf("immediately", "30s", "1m", "5m"), LockDelay.entries.map { it.stored })
        assertEquals(listOf("30s", "1m", "2m", "5m"), ClipDelay.entries.map { it.stored })
        assertEquals(listOf("system", "light", "dark"), ThemeChoice.entries.map { it.stored })
        assertEquals(listOf("words_shown", "first_copy_tip", "backup_explained", "no_secure_hardware_told"), OneTime.entries.map { it.stored })
        assertEquals(listOf("backup", "export", "import", "restore"), FileTask.Kind.entries.map { it.stored })

        val settings = start()
        for (kind in GeneratorOptions.Kind.entries) {
            for (separator in GeneratorOptions.Separator.entries) {
                val options = GeneratorOptions(kind = kind, separator = separator, length = 20)
                assertTrue(settings.update { it.generator = options })
                assertEquals(if (kind == GeneratorOptions.Kind.RANDOM) "random" else "words", file.stored[GEN_KIND])
                assertEquals(separator.char.toString(), file.stored[GEN_SEPARATOR])
                assertEquals(20, file.stored[GEN_LENGTH])
                assertEquals(options, start().generator.value)
            }
        }
        assertTrue(settings.update { it.theme = ThemeChoice.LIGHT })
        assertEquals("light", file.stored[THEME])
    }

    @Test
    fun generatorValuesThatTheSheetNeverWritesReadAsTheDefaults() {
        val d = GeneratorOptions()
        file.stored = mapOf(GEN_LENGTH to 3, GEN_WORDS to 13, GEN_SEPARATOR to "+", GEN_KIND to "RANDOM")
        assertEquals(d, start().generator.value)
        file.stored = mapOf(GEN_LENGTH to 65, GEN_WORDS to 3)
        assertEquals(d, start().generator.value)
        file.stored = mapOf(GEN_LENGTH to 4, GEN_WORDS to 12)
        assertEquals(d.copy(length = 4, words = 12), start().generator.value)
        // No character set at all: the sheet refuses that, so the four come back as the defaults.
        file.stored = mapOf(GEN_UPPER to false, GEN_LOWER to false, GEN_DIGITS to false, GEN_SYMBOLS to false, GEN_LENGTH to 20)
        assertEquals(d.copy(length = 20), start().generator.value)
    }

    @Test
    fun otherUnreadableValuesReadAsTheirSafeDefaults() {
        file.stored = mapOf(
            THEME to "DARK", BACKUP_ON to "false", BACKUP_SNOOZED_UNTIL to 5,
            TASK_KIND to "EXPORT", TASK_STEP to 1, CLIP_TOKEN to "abc", CLIP_DEADLINE to 7,
        )
        val settings = start()
        assertEquals(ThemeChoice.SYSTEM, settings.theme.value)
        assertEquals("the reminder stays on", BackupReminder(on = true, snoozedUntilMs = null), settings.backupReminder.value)
        assertNull("a task of no known kind", settings.fileTask.value)
        assertNull("a clip without a deadline", settings.pendingClip.value)
        file.stored = mapOf(TASK_KIND to "import", TASK_STEP to -1)
        assertNull("a negative step", start().fileTask.value)
        file.stored = mapOf(TASK_KIND to "import")
        assertNull("no step", start().fileTask.value)
    }

    @Test
    fun aFailedWriteChangesNothing() {
        val settings = start()
        assertTrue(settings.update { it.theme = ThemeChoice.DARK; it.purgeArm = arm })
        val commits = file.commits

        file.failWrites = true
        assertFalse(settings.update { it.theme = ThemeChoice.LIGHT; it.purgeArm = null; it.lockDelay = LockDelay.MINUTE_1 })
        assertEquals(ThemeChoice.DARK, settings.theme.value)
        assertEquals(arm, settings.purgeArm.value)
        assertEquals(LockDelay.IMMEDIATELY, settings.lockDelay.value)
        assertEquals(commits, file.commits)

        // The disk works again; an unrelated change must not carry the failed one to the file.
        file.failWrites = false
        assertTrue(settings.update { it.oneTime = setOf(OneTime.FIRST_COPY_TIP) })
        for (s in listOf(settings, start())) {
            assertEquals(ThemeChoice.DARK, s.theme.value)
            assertEquals(arm, s.purgeArm.value)
            assertEquals(LockDelay.IMMEDIATELY, s.lockDelay.value)
            assertEquals(setOf(OneTime.FIRST_COPY_TIP), s.oneTime.value)
        }
        assertFalse("a key that was missing stays missing", LOCK_DELAY in file.stored)
    }

    @Test
    fun aChangeThatChangesNothingWritesNothing() {
        val settings = start()
        assertTrue(settings.update { })
        assertTrue(settings.update { it.lockDelay = LockDelay.IMMEDIATELY; it.oneTime = emptySet() })
        assertEquals(0, file.commits)
        assertEquals(emptyMap<String, Any>(), file.stored)
    }

    @Test
    fun anExceptionInTheChangeWritesNothing() {
        val settings = start()
        val thrown = runCatching { settings.update { it.theme = ThemeChoice.DARK; error("the caller failed") } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(ThemeChoice.SYSTEM, settings.theme.value)
        assertEquals(0, file.commits)
    }

    @Test
    fun theEditorStartsFromTheCurrentValues() {
        val settings = start()
        assertTrue(settings.update { it.lockDelay = LockDelay.MINUTE_1; it.purgeArm = arm })
        var seen: Pair<LockDelay, PurgeArm?>? = null
        assertTrue(settings.update { seen = it.lockDelay to it.purgeArm; it.theme = ThemeChoice.DARK })
        assertEquals(LockDelay.MINUTE_1 to arm, seen)
        assertEquals(LockDelay.MINUTE_1, start().lockDelay.value)
        assertEquals(ThemeChoice.DARK, start().theme.value)
    }

    @Test
    fun updatesFromTwoThreadsAreAppliedOneAfterTheOther() {
        val settings = start()
        val rounds = 300
        val go = CountDownLatch(1)
        val a = thread {
            go.await()
            repeat(rounds) {
                assertTrue(settings.update { s -> s.pendingClip = PendingClip("a", (s.pendingClip?.deadlineMs ?: 0L) + 1L) })
            }
        }
        val b = thread {
            go.await()
            repeat(rounds) {
                assertTrue(settings.update { s -> s.backupReminder = BackupReminder(snoozedUntilMs = (s.backupReminder.snoozedUntilMs ?: 0L) + 1L) })
            }
        }
        go.countDown()
        a.join()
        b.join()
        for (s in listOf(settings, start())) {
            assertEquals(rounds.toLong(), s.pendingClip.value?.deadlineMs)
            assertEquals(rounds.toLong(), s.backupReminder.value.snoozedUntilMs)
        }
        assertEquals(2 * rounds, file.commits)
    }
}
