package io.github.dilshodbekmx.passwordvault.ui.transfer.backup

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.BackupResult
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.lock.AsideResult
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import io.github.dilshodbekmx.passwordvault.platform.AndroidClock
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.layout.LayoutHook
import io.github.dilshodbekmx.passwordvault.ui.layout.press
import io.github.dilshodbekmx.passwordvault.ui.layout.revealTag
import io.github.dilshodbekmx.passwordvault.ui.layout.revealText
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupViewModel
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldPasswordsScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.OldStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreScreen
import io.github.dilshodbekmx.passwordvault.ui.transfer.RestoreStep
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupScreensRig.Companion.ASIDE_OLDER
import io.github.dilshodbekmx.passwordvault.ui.transfer.backup.BackupScreensRig.Companion.PASSWORD
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan §1 rule 5: the screens of package 5.2 walked in the `en-XA` pseudo-locale (the debug
 * build makes it, i18n.md §1.1, §5), and in `ar-XB` for a right-to-left layout. Every `backup_`
 * string comes out pseudo-localized, so none is plain English or marked untranslatable; each state
 * drawn in a pseudo-locale shows the pseudo-localized texts, so none is written into the code.
 *
 * Every state is kept as a picture in the app's cache directory (`cache/pv-screens/5.2-*.png`): in
 * English, light and dark, for the user, and in the two pseudo-locales, for a person to look for
 * clipped text. Run with `am instrument` to keep them: a Gradle connected run removes the app.
 */
@RunWith(AndroidJUnit4::class)
class BackupPseudoLocaleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var context by mutableStateOf<Context?>(null)
    private var theme by mutableStateOf(ThemeChoice.LIGHT)

    private fun inLocale(tag: String): Context {
        val base = compose.activity
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(configuration)
    }

    private fun host() {
        compose.setContent {
            val now = context ?: compose.activity
            val configuration = now.resources.configuration
            CompositionLocalProvider(
                LocalContext provides now,
                LocalConfiguration provides configuration,
                LocalResources provides now.resources,
                LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                PasswordVaultTheme(theme) { screen?.invoke() }
            }
        }
    }

    /** Keeps a picture of the whole screen as `5.2-[name].png`. */
    private fun save(name: String) {
        LayoutHook.look(compose, "backup", name)
        compose.waitForIdle()
        val dir = File(compose.activity.cacheDir, "pv-screens").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "5.2-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyBackupStringIsPseudoLocalized() {
        val english = compose.activity
        val pseudo = inLocale("en-XA")
        val strings = R.string::class.java.fields.filter { it.name.startsWith("backup_") }
        assertTrue("the package's strings are found: ${strings.size}", strings.size >= 85)
        for (field in strings) {
            val id = field.getInt(null)
            assertNotEquals("string ${field.name}", english.getString(id), pseudo.getString(id))
        }
        val plurals = R.plurals::class.java.fields.filter { it.name.startsWith("backup_") }
        assertTrue("its plurals: ${plurals.size}", plurals.size == 3)
        for (field in plurals) {
            val id = field.getInt(null)
            assertNotEquals("plural ${field.name}", english.resources.getQuantityString(id, 5, 5, "x"), pseudo.resources.getQuantityString(id, 5, 5, "x"))
        }
    }

    @Test
    fun theScreensInEnglishLightAndDark() {
        host()
        for (dark in listOf(false, true)) {
            theme = if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT
            walk(if (dark) "dark" else "light", english = true)
        }
    }

    @Test
    fun theScreensInEnXa() {
        host()
        context = inLocale("en-XA")
        walk("en-XA", english = false)
    }

    @Test
    fun theScreensInArXb() {
        host()
        context = inLocale("ar-XB")
        walk("ar-XB", english = false)
    }

    /**
     * Draws each state and keeps its picture with [suffix]. Unless [english], the English text of a
     * state's first line must not be on the screen: the pseudo-localized one is.
     */
    private fun walk(suffix: String, english: Boolean) {
        val res = (context ?: compose.activity).resources
        fun check(id: Int) {
            compose.revealText(res.getString(id)).assertExists()
            if (!english) compose.onNodeWithText(compose.activity.getString(id)).assertDoesNotExist()
        }

        // The backup: the explanation, the master password with a wrong try, the backup being made,
        // the choice of a place, the result on the phone and in Google Drive, a failure, a stop.
        BackupScreensRig().use { rig ->
            rig.settings.oneTime.value = emptySet()
            val vm = rig.backup()
            show { BackupScreen(vm, rig.nav) }
            check(R.string.backup_explain)
            save("backup-explain-$suffix")
            compose.revealTag(BackupTags.CONTINUE).press()
            rig.lock.mpResults.add(MpResult.Wrong)
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput("Toshkent2020")
            compose.revealTag(BackupTags.SUBMIT).press()
            compose.waitUntil(TIMEOUT_MS) { (vm.ui.value.step as? BackupStep.Password)?.error != null }
            check(R.string.backup_wrong)
            save("backup-password-wrong-$suffix")
        }
        BackupScreensRig().use { rig ->
            val hold = CompletableDeferred<BackupResult>()
            val slow = object : VaultRepository by rig.vault {
                override suspend fun openBackup(bytes: ByteArray, passwordBytes: ByteArray): BackupResult = hold.await()
            }
            val vm = rig.backupOver(slow)
            show { BackupScreen(vm, rig.nav) }
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
            compose.revealTag(BackupTags.SUBMIT).press()
            compose.waitUntil(TIMEOUT_MS) { vm.ui.value.step is BackupStep.Working }
            check(R.string.backup_making)
            save("backup-making-$suffix")
            hold.complete(BackupResult.Opened(Vault(entries = BackupScreensRig.ENTRIES), BackupScreensRig.MADE_MS))
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
            rig.closePicker()
            compose.waitUntil(TIMEOUT_MS) { (vm.ui.value.step as? BackupStep.Choose)?.opening == false }
            check(R.string.backup_choose_text)
            save("backup-choose-$suffix")
        }
        for ((place, name) in listOf(Place.PhoneStorage to "phone", Place.GoogleDrive to "drive")) {
            BackupScreensRig().use { rig ->
                rig.opens()
                rig.documents.places[BackupScreensRig.DOC] = place
                rig.documents.names[BackupScreensRig.DOC] = "PasswordVault-backup-2026-10-08.pvault"
                val vm = rig.backup()
                show { BackupScreen(vm, rig.nav) }
                compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
                compose.revealTag(BackupTags.SUBMIT).press()
                compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
                rig.answerSaveAs()
                compose.waitUntil(TIMEOUT_MS) { vm.ui.value.step is BackupStep.Saved }
                check(if (place == Place.PhoneStorage) R.string.backup_place_phone else R.string.backup_upload_later)
                save("backup-saved-$name-$suffix")
            }
        }
        BackupScreensRig().use { rig ->
            rig.opens()
            rig.documents.writeResults.add(false)
            val vm = rig.backup()
            show { BackupScreen(vm, rig.nav) }
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
            compose.revealTag(BackupTags.SUBMIT).press()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
            rig.answerSaveAs()
            compose.waitUntil(TIMEOUT_MS) { vm.ui.value.step is BackupStep.Failed }
            check(R.string.backup_write_failed)
            save("backup-failed-$suffix")
        }

        // The restore: its first step, a message, the summary over the lock screen.
        BackupScreensRig(vault = Vault(), state = LockUiState.Locked(FakeLockManager().lockScreen)).use { rig ->
            val vm = rig.restore()
            show { RestoreScreen(vm, rig.nav) }
            check(R.string.backup_restore_intro)
            save("restore-start-$suffix")
            compose.revealTag(BackupTags.CHOOSE).press()
            compose.waitUntil(TIMEOUT_MS) { rig.launcher.calls.isNotEmpty() }
            rig.answerOpen()
            compose.waitUntil(TIMEOUT_MS) { vm.step.value is RestoreStep.Password }
            rig.vault.backupResults.add(BackupResult.Wrong)
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput("not it")
            compose.revealTag(BackupTags.SUBMIT).press()
            compose.waitUntil(TIMEOUT_MS) { (vm.step.value as? RestoreStep.Password)?.problem != null }
            check(R.string.backup_restore_wrong)
            save("restore-wrong-$suffix")
            rig.opens()
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput(PASSWORD)
            compose.revealTag(BackupTags.SUBMIT).press()
            compose.waitUntil(TIMEOUT_MS) { vm.step.value is RestoreStep.Summary }
            check(R.string.backup_restore_stays)
            save("restore-summary-$suffix")
        }

        // Old passwords: the list, and "Delete" with a wrong try.
        BackupScreensRig().use { rig ->
            rig.putAside()
            val vm = rig.old()
            show { OldPasswordsScreen(vm, rig.nav) }
            check(R.string.backup_old_intro)
            save("old-list-$suffix")
            compose.revealTag(BackupTags.oldDelete(ASIDE_OLDER.vaultId)).press()
            rig.lock.asideResults.add(AsideResult.Wrong)
            compose.onNodeWithTag(BackupTags.FIELD).performTextInput("current one")
            compose.revealTag(BackupTags.SUBMIT).press()
            compose.waitUntil(TIMEOUT_MS) { (vm.step.value as? OldStep.Asking)?.problem != null }
            check(R.string.backup_old_wrong)
            save("old-delete-wrong-$suffix")
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { screen = content }
        compose.waitForIdle()
    }

    private fun BackupScreensRig.closePicker() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            store.returned(io.github.dilshodbekmx.passwordvault.files.FileTaskStore.Action.CREATE, null)
        }
    }

    private fun BackupScreensRig.backupOver(repository: VaultRepository): BackupViewModel {
        var made: BackupViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            made = BackupViewModel(lock, repository, files, settings, AndroidClock, AppDispatchers.Default, null as MasterProof?)
        }
        return made!!
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
