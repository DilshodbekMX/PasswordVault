package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.fake.FakeDocumentIo
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.fake.FakeSecretClipboard
import io.github.dilshodbekmx.passwordvault.fake.FakeSession
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.lock.FingerprintOnResult
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.LockReason
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build plan 4.3, [JVM]: the view model of Settings (ux.md §5.18) against `FakeLockManager`,
 * `FakeAppSettings` and the other fakes: the defaults shown are those `writeDefaults` wrote (and the
 * strictest values before), the fingerprint switch by the availability table of lock.md §9.4, the PIN
 * row by the lock's status, turning fingerprint on asks for the master password first (lock.md §8.2)
 * while turning it off and the delays need nothing, "Lock now", and the last backup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** The app's scope in these tests: on the test dispatcher, and not a background scope, so that `advanceUntilIdle` runs its work. */
    private val work = CoroutineScope(SupervisorJob() + dispatcher)
    private val store = ViewModelStore()
    private val settings = FakeAppSettings()
    private val clock = FakeClock(elapsedMs = 5_000_000L, autoAdvance = false)
    private val lock = FakeLockManager()
    private val repository = FakeVaultRepository(Vault())
    private val files = FakeDocumentIo()
    private var models = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lock.state.value = LockUiState.Unlocked(emptyList(), proof = null)
        SettingsNotice.take()
    }

    @After
    fun tearDown() {
        store.clear()
        work.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.model(session: FakeSession = FakeSession(work, lock, FakeSecretClipboard(), repository)): SettingsViewModel {
        val factory = viewModelFactory {
            initializer { SettingsViewModel(lock, settings, session, repository, files, clock, AppDispatchers.single(dispatcher), work) }
        }
        val vm = ViewModelProvider(store, factory)["settings-${models++}", SettingsViewModel::class.java]
        runCurrent()
        return vm
    }

    // ---- the defaults ----------------------------------------------------------------------------------

    @Test
    fun theDefaultsShownAreThoseWriteDefaultsWrote() = runTest {
        val vm = model()
        // Nothing written yet (a fresh install): the strictest values (D-7, [V-P9]).
        assertEquals(LockDelay.IMMEDIATELY, vm.ui.value.lockDelay)
        assertEquals(UiText.of(R.string.settings_lock_delay_immediately), vm.ui.value.lockDelayText)
        assertEquals(ClipDelay.SECONDS_30, vm.ui.value.clipDelay)
        assertEquals(UiText.plural(R.plurals.settings_clip_delay_seconds, 30), vm.ui.value.clipDelayText)
        // What setup writes (LockManager.createVault calls it).
        assertTrue(settings.writeDefaults())
        runCurrent()
        val ui = vm.ui.value
        assertEquals(LockDelay.SETUP, ui.lockDelay)
        assertEquals("After 30 seconds", UiText.plural(R.plurals.settings_lock_delay_seconds, 30), ui.lockDelayText)
        assertEquals(ClipDelay.SETUP, ui.clipDelay)
        assertEquals("1 minute", UiText.plural(R.plurals.settings_clip_delay_minutes, 1), ui.clipDelayText)
        assertEquals(ThemeChoice.SYSTEM, ui.theme)
        assertEquals(UiText.of(R.string.settings_theme_system), ui.themeText)
        assertTrue("backup reminders on by default", ui.backupReminder)
    }

    @Test
    fun eachDelayHasItsText() {
        assertEquals(
            listOf(
                UiText.of(R.string.settings_lock_delay_immediately),
                UiText.plural(R.plurals.settings_lock_delay_seconds, 30),
                UiText.plural(R.plurals.settings_lock_delay_minutes, 1),
                UiText.plural(R.plurals.settings_lock_delay_minutes, 5),
            ),
            LockDelay.entries.map(SettingsViewModel::lockDelayText),
        )
        assertEquals(
            listOf(
                UiText.plural(R.plurals.settings_clip_delay_seconds, 30),
                UiText.plural(R.plurals.settings_clip_delay_minutes, 1),
                UiText.plural(R.plurals.settings_clip_delay_minutes, 2),
                UiText.plural(R.plurals.settings_clip_delay_minutes, 5),
            ),
            ClipDelay.entries.map(SettingsViewModel::clipDelayText),
        )
    }

    @Test
    fun aDelayIsChosenInItsDialogAndWrittenAtOnce() = runTest {
        val vm = model()
        vm.openLockDelay()
        runCurrent()
        assertEquals(SettingsDialog.LOCK_DELAY, vm.ui.value.dialog)
        vm.setLockDelay(LockDelay.MINUTES_5)
        advanceUntilIdle()
        assertNull(vm.ui.value.dialog)
        assertEquals(LockDelay.MINUTES_5, settings.lockDelay.value)
        assertEquals(UiText.plural(R.plurals.settings_lock_delay_minutes, 5), vm.ui.value.lockDelayText)
        vm.openClipDelay()
        runCurrent()
        assertEquals(SettingsDialog.CLIP_DELAY, vm.ui.value.dialog)
        vm.setClipDelay(ClipDelay.MINUTES_2)
        advanceUntilIdle()
        assertEquals(ClipDelay.MINUTES_2, settings.clipDelay.value)
        assertTrue("no proof for a delay (lock.md §8.2)", lock.purposes.isEmpty())
    }

    @Test
    fun aWriteThatFailsChangesNothingAndSaysSo() = runTest {
        val vm = model()
        settings.failWrites = true
        vm.setTheme(ThemeChoice.DARK)
        advanceUntilIdle()
        assertEquals(ThemeChoice.SYSTEM, vm.ui.value.theme)
        assertEquals(UiText.of(R.string.settings_not_saved), vm.ui.value.message)
        vm.messageShown()
        runCurrent()
        assertNull(vm.ui.value.message)
        settings.failWrites = false
        vm.setTheme(ThemeChoice.DARK)
        vm.setBackupReminder(false)
        advanceUntilIdle()
        assertEquals(ThemeChoice.DARK, vm.ui.value.theme)
        assertEquals(UiText.of(R.string.settings_theme_dark), vm.ui.value.themeText)
        assertFalse(vm.ui.value.backupReminder)
        assertFalse(settings.backupReminder.value.on)
    }

    // ---- the App lock rows -------------------------------------------------------------------------------

    /** lock.md §9.4, the Settings column, through `LockStatus.fingerprint` (architecture.md §3.5). */
    @Test
    fun theFingerprintSwitchFollowsTheAvailabilityTable() = runTest {
        val anyone = UiText.of(R.string.pin_fingerprint_anyone)
        val table = listOf(
            FingerprintStatus.Off to Triple(true, false, anyone), // 0, and −1 ("unknown"): turning it on decides
            FingerprintStatus.Unavailable(0) to Triple(true, false, anyone),
            FingerprintStatus.Unavailable(-1) to Triple(true, false, anyone),
            FingerprintStatus.Unavailable(11) to Triple(false, false, UiText.of(R.string.settings_fingerprint_none_enrolled)),
            FingerprintStatus.Unavailable(12) to Triple(false, false, UiText.of(R.string.settings_fingerprint_no_sensor)),
            FingerprintStatus.Unavailable(-2) to Triple(false, false, UiText.of(R.string.settings_fingerprint_no_sensor)),
            FingerprintStatus.Unavailable(1) to Triple(false, false, UiText.of(R.string.settings_fingerprint_not_for_apps)),
            FingerprintStatus.Unavailable(15) to Triple(false, false, UiText.of(R.string.settings_fingerprint_update_needed)),
            FingerprintStatus.NoSecureHardware to Triple(false, false, UiText.of(R.string.pin_fingerprint_no_secure_hardware)),
            FingerprintStatus.On to Triple(true, true, anyone),
        )
        val vm = model()
        for ((status, expected) in table) {
            lock.status.value = lock.status.value.copy(fingerprint = status)
            runCurrent()
            val row = vm.ui.value.fingerprint
            val (enabled, checked, supporting) = expected
            assertEquals("$status: enabled", enabled, row.enabled)
            assertEquals("$status: on", checked, row.checked)
            assertEquals("$status: the line under it", supporting, row.supporting)
            assertEquals("$status", UiText.of(R.string.settings_fingerprint), row.title)
        }
        lock.status.value = lock.status.value.copy(fingerprint = FingerprintStatus.TurnOnAgain)
        runCurrent()
        assertEquals(UiText.of(R.string.settings_fingerprint_turn_on_again), vm.ui.value.fingerprint.title)
        assertTrue(vm.ui.value.fingerprint.enabled)
        assertFalse(vm.ui.value.fingerprint.checked)
    }

    @Test
    fun thePinRowFollowsTheLockStatus() = runTest {
        val cases = listOf(
            PinStatus.On to PinRow(UiText.of(R.string.settings_change_pin), null, true),
            PinStatus.NotSet to PinRow(UiText.of(R.string.settings_set_pin), null, true),
            PinStatus.Off(PinReason.LIMIT) to PinRow(UiText.of(R.string.settings_set_pin), UiText.of(R.string.settings_pin_off_limit), true),
            PinStatus.Off(PinReason.STOPPED_WORKING) to PinRow(UiText.of(R.string.settings_set_pin), UiText.of(R.string.settings_pin_stopped_working), true),
            PinStatus.Off(PinReason.NEWER_APP) to PinRow(UiText.of(R.string.settings_set_pin), UiText.of(R.string.settings_pin_newer_app), true),
            PinStatus.Off(PinReason.STORAGE_FULL) to PinRow(UiText.of(R.string.settings_set_pin), UiText.of(R.string.settings_pin_storage_full), true),
            PinStatus.CannotCheck to PinRow(UiText.of(R.string.settings_change_pin), UiText.of(R.string.settings_pin_cannot_check), true),
            PinStatus.NoSecureHardware to PinRow(UiText.of(R.string.settings_set_pin), UiText.of(R.string.settings_no_secure_hardware), false),
        )
        val vm = model()
        for ((status, row) in cases) {
            lock.status.value = lock.status.value.copy(pin = status)
            runCurrent()
            assertEquals("$status", row, vm.ui.value.pin)
        }
    }

    @Test
    fun turningFingerprintOnAsksForTheMasterPasswordFirst() = runTest {
        val vm = model()
        vm.setFingerprint(true)
        runCurrent()
        assertEquals(SettingsDialog.FINGERPRINT_ON, vm.ui.value.dialog)
        assertEquals("nothing is turned on before the master password", FingerprintStatus.Off, lock.status.value.fingerprint)
        vm.fingerprintPassword.field.type("vowel-shiny-shell-crisp-shaft-cadet")
        vm.submitFingerprintPassword()
        runCurrent()
        assertEquals(listOf(MpPurpose.FINGERPRINT_ON), lock.purposes)
        assertEquals("", vm.fingerprintPassword.field.text.toString())
        advanceUntilIdle()
        val ui = vm.ui.value
        assertNull(ui.dialog)
        assertFalse(ui.fingerprintBusy)
        assertEquals(FingerprintStatus.On, lock.status.value.fingerprint)
        assertTrue(ui.fingerprint.checked)
        assertEquals(UiText.of(R.string.settings_fingerprint_is_on), ui.message)
    }

    @Test
    fun whatTurningFingerprintOnCameToIsSaid() = runTest {
        val cases = listOf(
            FingerprintOnResult.NoneEnrolled to UiText.of(R.string.pin_fingerprint_add_first),
            FingerprintOnResult.NoSecureHardware to UiText.of(R.string.pin_fingerprint_no_secure_hardware),
            FingerprintOnResult.Failed to UiText.of(R.string.settings_fingerprint_failed),
            FingerprintOnResult.Cancelled to null,
            FingerprintOnResult.NotShown to null,
        )
        val vm = model()
        for ((result, message) in cases) {
            lock.fingerprintResults += result
            vm.setFingerprint(true)
            runCurrent()
            vm.fingerprintPassword.field.type("vowel-shiny-shell-crisp-shaft-cadet")
            vm.submitFingerprintPassword()
            advanceUntilIdle()
            assertEquals("$result", message, vm.ui.value.message)
            assertFalse("$result", vm.ui.value.fingerprint.checked)
            vm.messageShown()
            runCurrent()
        }
    }

    @Test
    fun aWrongMasterPasswordKeepsTheDialogOpen() = runTest {
        lock.mpResults += io.github.dilshodbekmx.passwordvault.lock.MpResult.Wrong
        val vm = model()
        vm.setFingerprint(true)
        runCurrent()
        vm.fingerprintPassword.field.type("wrong")
        vm.submitFingerprintPassword()
        advanceUntilIdle()
        assertEquals(SettingsDialog.FINGERPRINT_ON, vm.ui.value.dialog)
        assertEquals(FingerprintStatus.Off, lock.status.value.fingerprint)
        vm.dismissDialog()
        runCurrent()
        assertNull(vm.ui.value.dialog)
    }

    @Test
    fun turningFingerprintOffNeedsNothing() = runTest {
        lock.status.value = lock.status.value.copy(fingerprint = FingerprintStatus.On)
        val vm = model()
        vm.setFingerprint(false)
        advanceUntilIdle()
        assertEquals(FingerprintStatus.Off, lock.status.value.fingerprint)
        assertTrue(lock.purposes.isEmpty())
        assertNull(vm.ui.value.dialog)
    }

    @Test
    fun aSwitchThatCannotBeUsedDoesNothing() = runTest {
        lock.status.value = lock.status.value.copy(fingerprint = FingerprintStatus.Unavailable(11))
        val vm = model()
        vm.setFingerprint(true)
        runCurrent()
        assertNull(vm.ui.value.dialog)
    }

    @Test
    fun lockNowLocksThroughTheSession() = runTest {
        val session = FakeSession(work, lock, FakeSecretClipboard(), repository)
        val vm = model(session)
        vm.lockNow()
        advanceUntilIdle()
        assertEquals(1, session.lockNows)
        assertEquals(listOf(LockReason.LOCK_NOW), lock.lockReasons)
    }

    @Test
    fun aLockClosesTheDialogAndEmptiesTheField() = runTest {
        val vm = model()
        vm.setFingerprint(true)
        runCurrent()
        vm.fingerprintPassword.field.type("half typed")
        lock.lock(LockReason.SCREEN_OFF)
        runCurrent()
        assertNull(vm.ui.value.dialog)
        assertEquals("", vm.fingerprintPassword.field.text.toString())
    }

    // ---- backup and old passwords ------------------------------------------------------------------------

    @Test
    fun theLastBackupIsTheNewestWithItsPlace() = runTest {
        val vm = model()
        assertNull("no backup yet", vm.ui.value.lastBackup)
        files.places["content://drive/2"] = Place.GoogleDrive
        repository.state.value = io.github.dilshodbekmx.passwordvault.data.VaultState.Unlocked(
            Vault(backups = listOf(FileRef("content://phone/1", "PasswordVault-backup-2026-10-01.pvault", 1_000L), FileRef("content://drive/2", "PasswordVault-backup-2026-10-05.pvault", 2_000L))),
            2L,
            null,
            false,
        )
        advanceUntilIdle()
        assertEquals(LastBackup(2_000L, Place.GoogleDrive), vm.ui.value.lastBackup)
    }

    @Test
    fun oneOldPasswordsRowPerVaultPutAside() = runTest {
        val vm = model()
        lock.status.value = lock.status.value.copy(aside = listOf(AsideVault("a", 100L, null), AsideVault("b", 200L, null)))
        runCurrent()
        assertEquals(listOf(100L, 200L), vm.ui.value.oldPasswords)
    }

    @Test
    fun theThemeDialogsBackdropStartsWhereTheListWasOnce() {
        ThemeBackdrop.take()
        ThemeBackdrop.mark(12, 34)
        assertEquals(12 to 34, ThemeBackdrop.take())
        assertEquals("used once: the top of the list next time", 0 to 0, ThemeBackdrop.take())
    }

    @Test
    fun theMessageOfChangePinIsShownOnce() = runTest {
        val vm = model()
        SettingsNotice.post(UiText.of(R.string.pin_changed))
        vm.screenShown()
        runCurrent()
        assertEquals(UiText.of(R.string.pin_changed), vm.ui.value.message)
        vm.messageShown()
        vm.screenShown()
        runCurrent()
        assertNull(vm.ui.value.message)
    }
}
