package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.Formatters
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.lock.AsideResult
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldPasswordsScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupScreensRig.Companion.ASIDE_NEWER
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupScreensRig.Companion.MADE_MS
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupScreensRig.Companion.PASSWORD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.2 [device]: the three screens drawn over their ViewModels and the fakes, as a person
 * uses them: the backup from the master password to "Backup saved" and its place, the restore with
 * its messages and its summary, and old passwords with "Delete" and its progress. The flows'
 * rules are the JVM tests'; here, what is on the screen and what the buttons do.
 */
@RunWith(AndroidJUnit4::class)
class BackupScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any): String = compose.activity.resources.getString(id, *args)

    private fun plural(id: Int, count: Int, vararg args: Any): String = compose.activity.resources.getQuantityString(id, count, *args)

    private fun date(ms: Long): String =
        Formatters(AppLanguages.screenLocale(AppLanguages.localesOf(compose.activity.resources.configuration)), hour24 = true).date(ms)

    @Test
    fun aBackupFromTheMasterPasswordToSavedOnThePhone() {
        BackupScreensRig().use { rig ->
            rig.opens()
            rig.documents.names[BackupScreensRig.DOC] = NAME
            val vm = rig.backup()
            compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { BackupScreen(vm, rig.nav) } }
            compose.onNodeWithText(text(R.string.backup_password_text)).assertIsDisplayed()
            compose.onNodeWithTag(BackupTags.SUBMIT).assertIsNotEnabled()
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
            compose.onNodeWithTag(BackupTags.SUBMIT).assertIsEnabled().performClick()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
            compose.onNodeWithText(text(R.string.backup_choose_text)).assertIsDisplayed()

            rig.answerSaveAs()
            compose.waitUntil(TIMEOUT_MS) { vm.ui.value.step is BackupStep.Saved }
            compose.onNodeWithText(plural(R.plurals.backup_saved, 4, 4, NAME)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.backup_place_phone)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.backup_upload_later)).assertDoesNotExist()
            compose.onNodeWithTag(BackupTags.SEND).performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.any { it.startsWith("share") } }
            assertEquals("share $NAME", rig.launcher.calls.last())
            compose.onNodeWithTag(BackupTags.DONE).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf("back"), rig.nav.calls)
        }
    }

    @Test
    fun aBackupInGoogleDriveSaysSoAndThatItMayBeUploadedLater_andOffersNoSending() {
        BackupScreensRig().use { rig ->
            rig.opens()
            val drive = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D9"
            rig.documents.places[drive] = Place.GoogleDrive
            rig.documents.names[drive] = NAME
            val vm = rig.backup()
            compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { BackupScreen(vm, rig.nav) } }
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
            compose.onNodeWithTag(BackupTags.SUBMIT).performClick()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
            rig.answerSaveAs(drive)
            compose.waitUntil(TIMEOUT_MS) { vm.ui.value.step is BackupStep.Saved }
            // Decision 20 as the owner corrected it: "Saved to Google Drive.", then "This place may upload the file later. …".
            compose.onNodeWithTag(BackupTags.line(1)).assertTextEquals(text(R.string.backup_place_drive))
            compose.onNodeWithTag(BackupTags.line(2)).assertTextEquals(text(R.string.backup_upload_later))
            compose.onNodeWithTag(BackupTags.line(3)).assertDoesNotExist()
            compose.onNodeWithTag(BackupTags.SEND).assertDoesNotExist()
        }
    }

    @Test
    fun aBackupOnAnotherPlaceSaysItsNameAndThatItMayBeUploadedLater_andOffersSending() {
        BackupScreensRig().use { rig ->
            rig.opens()
            val card = "content://com.android.externalstorage.documents/document/1A2B-3C4D%3APasswordVault-backup.pvault"
            rig.documents.places[card] = Place.Other("SD card")
            rig.documents.names[card] = NAME
            val vm = rig.backup()
            compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { BackupScreen(vm, rig.nav) } }
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
            compose.onNodeWithTag(BackupTags.SUBMIT).performClick()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
            rig.answerSaveAs(card)
            compose.waitUntil(TIMEOUT_MS) { vm.ui.value.step is BackupStep.Saved }
            // Decision 20 as the owner corrected it: "Saved to SD card." stays, then "This place may upload the file later. …".
            compose.onNodeWithTag(BackupTags.line(1)).assertTextEquals(text(R.string.backup_place_other, "SD card"))
            compose.onNodeWithTag(BackupTags.line(2)).assertTextEquals(text(R.string.backup_upload_later))
            compose.onNodeWithTag(BackupTags.line(3)).assertDoesNotExist()
            compose.onNodeWithTag(BackupTags.SEND).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aWrongMasterPasswordSaysSoAndTheFieldIsEmptyForTheNextTry() {
        BackupScreensRig().use { rig ->
            rig.lock.mpResults.add(io.github.dilshodbekmx.passwordvault.lock.MpResult.Wrong)
            val vm = rig.backup()
            compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { BackupScreen(vm, rig.nav) } }
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput("wrong one")
            compose.onNodeWithTag(BackupTags.SUBMIT).performClick()
            compose.onNodeWithText(text(R.string.backup_wrong)).assertIsDisplayed()
            assertEquals("", vm.password.text.toString())
            assertTrue(rig.launcher.calls.isEmpty())
        }
    }

    @Test
    fun noBackupWhileANewMasterPasswordIsPending() {
        BackupScreensRig().use { rig ->
            rig.lock.status.value = rig.lock.status.value.copy(
                pending = io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword(
                    requestedAtMs = BackupScreensRig.NOW,
                    by = io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut.PIN,
                    creditedMs = 0,
                    remainingMs = 3_600_000L,
                    ready = false,
                ),
            )
            val vm = rig.backup()
            compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { BackupScreen(vm, rig.nav) } }
            compose.onNodeWithText(text(R.string.backup_pending)).assertIsDisplayed()
            compose.onNodeWithTag(BackupTags.FIELD).assertDoesNotExist()
        }
    }

    @Test
    fun aRestoreShowsItsMessagesThenTheSummaryAndRestores() {
        BackupScreensRig(vault = io.github.dilshodbekmx.passwordvault.core.model.Vault(), state = LockUiState.NoVault(emptyList())).use { rig ->
            val vm = rig.restore()
            compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { RestoreScreen(vm, rig.nav) } }
            compose.onNodeWithText(text(R.string.backup_restore_intro)).assertIsDisplayed()
            compose.onNodeWithTag(BackupTags.CHOOSE).performClick()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
            rig.answerOpen()
            compose.waitUntil(TIMEOUT_MS) { vm.step.value is RestoreStep.Password }
            compose.onNodeWithText(text(R.string.backup_restore_password_text)).assertIsDisplayed()

            rig.vault.backupResults.add(BackupResult.WrongOrDamaged)
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput("not it")
            compose.onNodeWithTag(BackupTags.SUBMIT).performClick()
            compose.waitUntil(TIMEOUT_MS) { (vm.step.value as? RestoreStep.Password)?.problem != null }
            compose.onNodeWithText(text(R.string.backup_restore_wrong_or_damaged)).assertIsDisplayed()

            rig.opens()
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
            compose.onNodeWithTag(BackupTags.SUBMIT).performClick()
            compose.waitUntil(TIMEOUT_MS) { vm.step.value is RestoreStep.Summary }
            compose.onNodeWithText(plural(R.plurals.backup_restore_summary, 4, 4, date(MADE_MS))).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.backup_restore_restore)).assertIsDisplayed()
            compose.onNodeWithTag(BackupTags.STAYS).assertDoesNotExist()
            compose.onNodeWithTag(BackupTags.RESTORE).performClick()
            compose.waitUntil(TIMEOUT_MS) { rig.lock.state.value is LockUiState.Unlocked }
        }
    }

    @Test
    fun aRestoreOverTheLockScreenSaysTheCurrentPasswordsStay() {
        BackupScreensRig(state = LockUiState.Locked(io.github.dilshodbekmx.passwordvault.fake.FakeLockManager().lockScreen)).use { rig ->
            val vm = rig.restore()
            compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { RestoreScreen(vm, rig.nav) } }
            compose.onNodeWithTag(BackupTags.CHOOSE).performClick()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
            rig.answerOpen("name,url,username,password\n".toByteArray())
            compose.waitUntil(TIMEOUT_MS) { (vm.step.value as? RestoreStep.Start)?.problem != null }
            compose.onNodeWithText(text(R.string.backup_restore_not_ours_locked)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.backup_restore_choose_other)).assertIsDisplayed()

            compose.onNodeWithTag(BackupTags.CHOOSE).performClick()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.size == 2 }
            rig.answerOpen()
            compose.waitUntil(TIMEOUT_MS) { vm.step.value is RestoreStep.Password }
            rig.opens()
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
            compose.onNodeWithTag(BackupTags.SUBMIT).performClick()
            compose.waitUntil(TIMEOUT_MS) { vm.step.value is RestoreStep.Summary }
            compose.onNodeWithText(text(R.string.backup_restore_stays)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.backup_restore_use)).assertIsDisplayed()
        }
    }

    @Test
    fun oldPasswordsAreDeletedWithTheirOwnMasterPassword() {
        BackupScreensRig().use { rig ->
            rig.putAside()
            val vm = rig.old()
            compose.setContent { PasswordVaultTheme(ThemeChoice.LIGHT) { OldPasswordsScreen(vm, rig.nav) } }
            compose.onNodeWithText(text(R.string.backup_old_item, date(ASIDE_NEWER.putAsideAtMs))).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.backup_old_unreadable)).assertIsDisplayed()
            compose.onNodeWithTag(BackupTags.oldDelete(ASIDE_NEWER.vaultId)).performClick()
            compose.onNodeWithText(text(R.string.backup_old_delete_title, date(ASIDE_NEWER.putAsideAtMs))).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.backup_old_delete_text)).assertIsDisplayed()

            rig.lock.asideResults.add(AsideResult.Wrong)
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput("current one")
            compose.onNodeWithTag(BackupTags.SUBMIT).performClick()
            compose.onNodeWithText(text(R.string.backup_old_wrong)).assertIsDisplayed()

            compose.onNodeWithTag(BackupTags.FIELD).performTextInput("its own")
            compose.onNodeWithTag(BackupTags.SUBMIT).performClick()
            compose.waitUntil(TIMEOUT_MS) { vm.step.value is OldStep.List }
            compose.onNodeWithText(text(R.string.backup_old_deleted)).assertIsDisplayed()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val NAME = "PasswordVault-backup-2026-10-08.pvault"
    }
}
