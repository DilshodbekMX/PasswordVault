package io.github.dilshodbekmx.passwordvault.ui.layout

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.MainActivity
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.NavState
import io.github.dilshodbekmx.passwordvault.nav.NavViewModel
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.ui.common.PinPadTags
import io.github.dilshodbekmx.passwordvault.ui.edit.EditTags
import io.github.dilshodbekmx.passwordvault.ui.edit.GenTags
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryTags
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryValue
import io.github.dilshodbekmx.passwordvault.ui.list.ListTags
import io.github.dilshodbekmx.passwordvault.ui.lock.LockTags
import io.github.dilshodbekmx.passwordvault.ui.lock.NoticeTags
import io.github.dilshodbekmx.passwordvault.ui.security.GuidedChangeTags
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityTags
import io.github.dilshodbekmx.passwordvault.ui.settings.LanguageTags
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesCatalog
import io.github.dilshodbekmx.passwordvault.ui.settings.MasterTags
import io.github.dilshodbekmx.passwordvault.ui.settings.PartName
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTags
import io.github.dilshodbekmx.passwordvault.ui.setup.BackupsExplainedTags
import io.github.dilshodbekmx.passwordvault.ui.setup.FingerprintOfferTags
import io.github.dilshodbekmx.passwordvault.ui.setup.PinTags
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupTags
import io.github.dilshodbekmx.passwordvault.ui.shell.FirstCopyTipTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.BackupTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportChoice
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportTags
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportSource
import io.github.dilshodbekmx.passwordvault.ui.transfer.ImportTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 6.2, [script] (`tools/emu/layout/walk.sh`): the real app walked screen by screen in a
 * pseudo-locale, on a phone the script has set to 320 dp wide and 200 % font size, each screen looked
 * at by [LayoutProbe] (no clipped button, no truncated text, no plain English, no control under 48 dp)
 * and kept as a picture. It starts from a fresh install (the script clears the app's data first):
 * setup, the PIN steps, the list, an account, the form and the generator, the Security tab, Settings
 * and every screen its rows open, and the lock.
 *
 * It runs only when the script passes `-e pvLayout <run>` ([LayoutRun]); in a plain connected run it
 * returns at once.
 */
@RunWith(AndroidJUnit4::class)
class LayoutWalkTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: PasswordVaultApp get() = instrumentation.targetContext.applicationContext as PasswordVaultApp

    @Test
    fun walkTheApp() {
        val run = LayoutRun.fromArguments() ?: return
        val walk = Walker(compose, run)
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            if (walk.step("start in ${run.localeTag}") { start(walk, run.localeTag, run.style) }) {
                walkFromSetup(walk)
            }
        }
        val failed = run.end()
        assertEquals("failed checks or broken steps: see the report ${run.name}", 0, failed)
    }

    /** The app on Welcome (a fresh install), in the pseudo-locale [tag], in the [style] given (a [StyleChoice] name). */
    private fun start(walk: Walker, tag: String, style: String?) {
        compose.waitUntil(Walker.WAIT_MS) { app.graph.lock.state.value !is LockUiState.Starting }
        check(app.graph.lock.state.value is LockUiState.NoVault) { "the app has a vault: the script clears its data first" }
        if (style != null) {
            val choice = StyleChoice.valueOf(style)
            check(app.graph.settings.update { it.style = choice }) { "the style $style was not saved" }
            compose.waitUntil(Walker.WAIT_MS) { app.graph.settings.style.value == choice }
        }
        walk.onRoute(Route.Welcome)
        val before = walk.activity()
        instrumentation.runOnMainSync { AppLanguages.setTags(app, tag) }
        compose.waitUntil(Walker.WAIT_MS) {
            val now = walk.activity()
            now != null && now !== before && AppLanguages.localesOf(now.resources.configuration).firstOrNull()?.toLanguageTag() == tag
        }
        walk.onRoute(Route.Welcome)
    }

    private fun walkFromSetup(walk: Walker) {
        val ok = walk.step("setup") { setup(walk) } && walk.step("PIN steps") { pinSteps(walk) }
        if (!ok) return
        walk.probe.data += DATA
        walk.step("an account through the form") { accountThroughTheForm(walk) }
        walk.step("its screen") { account(walk) }
        walk.step("the list") { list(walk) }
        walk.step("Recently deleted") { recentlyDeleted(walk) }
        walk.step("the Security tab") { security(walk) }
        walk.step("Settings") { settings(walk) }
        walk.step("Settings' screens") { settingsScreens(walk) }
        walk.step("Backup, export and import") { transfers(walk) }
        walk.step("Change master password") { masterPassword(walk) }
        walk.step("Help and licences") { help(walk) }
        walk.step("the lock") { lock(walk) }
    }

    private fun setup(walk: Walker) {
        walk.look("Welcome")
        walk.tap(SetupTags.LANGUAGE)
        walk.await(LanguageTags.DIALOG)
        walk.look("Language")
        walk.tap(LanguageTags.CANCEL)
        walk.awaitGone(LanguageTags.DIALOG)

        walk.tap(SetupTags.RESTORE)
        walk.onRoute(Route.Restore)
        walk.look("Restore")
        walk.back()
        walk.onRoute(Route.Welcome)

        walk.tap(SetupTags.START)
        walk.onRoute(Route.CreateMasterPassword)
        walk.await(SetupTags.PASSPHRASE)
        val words = checkNotNull(walk.textOf(SetupTags.PASSPHRASE)) { "no suggested words" }
        walk.probe.data += words
        walk.probe.data += words.split('-')
        walk.look("CreateMasterPassword")
        walk.tap(SetupTags.TYPE_OWN)
        walk.await(SetupTags.OWN_FIELD)
        walk.type(SetupTags.OWN_FIELD, "tashkent")
        walk.tap(SetupTags.USE_OWN)
        walk.look("CreateMasterPassword-own")
        walk.tap(SetupTags.SHOW_WORDS)
        walk.await(SetupTags.PASSPHRASE)
        val chosen = checkNotNull(walk.textOf(SetupTags.PASSPHRASE))
        walk.probe.data += chosen
        walk.probe.data += chosen.split('-')

        walk.tap(SetupTags.USE_WORDS)
        walk.onRoute(Route.WriteDown)
        walk.look("WriteDown")
        walk.tap(SetupTags.NEXT)
        walk.onRoute(Route.TypeAgain)
        walk.look("TypeAgain")
        walk.type(SetupTags.AGAIN_FIELD, "not-the-words")
        walk.tap(SetupTags.CREATE)
        walk.await(SetupTags.AGAIN_MESSAGE)
        walk.look("TypeAgain-mismatch")
        walk.clear(SetupTags.AGAIN_FIELD)
        walk.type(SetupTags.AGAIN_FIELD, chosen)
        walk.tap(SetupTags.CREATE)
        walk.onRoute(Route.CreatePin, timeoutMs = 120_000)
        password = chosen
    }

    private var password: String = ""

    private fun pinSteps(walk: Walker) {
        walk.look("CreatePin")
        pin(walk, PIN)
        walk.onRoute(Route.ConfirmPin)
        walk.look("ConfirmPin")
        pin(walk, "135790")
        walk.await(PinTags.LINE)
        walk.look("ConfirmPin-mismatch")
        // A mismatch starts the steps again from the first PIN.
        if (walk.isOn(Route.CreatePin)) {
            pin(walk, PIN)
            walk.onRoute(Route.ConfirmPin)
        }
        pin(walk, PIN)
        walk.onRoute(Route.FingerprintOffer)
        walk.look("FingerprintOffer")
        walk.tap(FingerprintOfferTags.NOT_NOW)
        walk.onRoute(Route.BackupsExplained)
        walk.look("BackupsExplained")
        walk.tap(BackupsExplainedTags.FINISH)
        walk.onRoute(Route.Passwords)
        walk.look("Passwords-empty")
    }

    /** The digits of [digits] on the pad shown. */
    private fun pin(walk: Walker, digits: String) {
        for (d in digits) walk.tap(PinPadTags.key(d - '0'))
    }

    // ---- the unlocked app ----------------------------------------------------------------------------

    /** The navigation state of [activity] (read on the main thread). */
    private fun nav(activity: MainActivity): NavState = ViewModelProvider(activity)[NavViewModel::class.java].nav

    /** The tab [root] at its root, with nothing over it. */
    private fun tab(walk: Walker, root: Route) {
        if (walk.activity() == null) {
            // A step left the app (a Back too many): it comes back to the front, still unlocked.
            walk.run.note("the app was not in front before the tab ${root.name}; started again")
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(Walker.WAIT_MS) { walk.activity() != null }
        }
        repeat(3) { if (walk.exists(NoticeTags.NOTICE)) answerNotice(walk) }
        val activity = walk.activity() as MainActivity
        instrumentation.runOnMainSync { nav(activity).tab(root) }
        compose.waitForIdle()
        walk.onRoute(root)
    }

    /** A notice of the lock over the app: looked at, then answered "Later" or "OK". */
    private var notices = 0

    private fun answerNotice(walk: Walker) {
        walk.look("Notice-${++notices}")
        if (!walk.tapIfShown("pv.answer.LATER")) walk.tapIfShown("pv.answer.OK")
    }

    /** The ids of the accounts outside Recently deleted, by title. */
    private fun accounts(): Map<String, String> {
        val state = app.graph.data.repository.state.value as? VaultState.Unlocked ?: return emptyMap()
        return state.vault.entries.filter { it.deletedAt == null }.associate { it.title to it.id }
    }

    /** Adds [draft] the way the form's Save does (the debug command `entry add` does the same). */
    private fun add(draft: DraftEntry) = kotlinx.coroutines.runBlocking {
        app.graph.data.repository.edit(VaultEdits.add(draft, NO_TEXTS, app.graph.platform.random))
    }

    private fun accountThroughTheForm(walk: Walker) {
        tab(walk, Route.Passwords)
        if (!walk.tapIfShown(ListTags.EMPTY_ADD)) walk.tap(ListTags.ADD)
        walk.onRoute(Route.Edit())
        walk.look("Edit-new")
        walk.type(EditTags.NAME, "Gmail")
        walk.type(EditTags.USERNAME, "aziz.karimov@gmail.com")
        walk.type(EditTags.PASSWORD, "Toshkent2024")
        walk.tap(EditTags.ADD_WEBSITE)
        walk.type(EditTags.website(0), "mail.google.com")
        walk.type(EditTags.NOTES, "Recovery codes are in the safe")
        walk.hideKeyboard()
        walk.look("Edit-filled")
        walk.tap(EditTags.GENERATE)
        walk.await(GenTags.USE)
        walk.look("Generator")
        walk.tap(GenTags.WORDS)
        walk.look("Generator-words")
        walk.tap(GenTags.USE)
        // The field had a password: "Replace the password?" first.
        if (runCatching { walk.await(EditTags.DIALOG_REPLACE, 5_000) }.isSuccess) {
            walk.look("Edit-replacePassword")
            walk.tap(EditTags.DIALOG_REPLACE)
        }
        walk.awaitGone(GenTags.USE)
        walk.tap(EditTags.ADD_FIELD)
        walk.await(EditTags.FIELD_ADD)
        walk.look("Edit-addField")
        walk.back()
        walk.tap(EditTags.SAVE)
        walk.onRoute(Route.Entry(""), timeoutMs = Walker.WAIT_MS)
    }

    private fun account(walk: Walker) {
        if (!walk.isOn(Route.Entry(""))) {
            tab(walk, Route.Passwords)
            walk.tap(ListTags.row(accounts().getValue("Gmail")))
            walk.onRoute(Route.Entry(""))
        }
        walk.look("Entry")
        walk.tap(EntryTags.show(EntryValue.Password))
        walk.look("Entry-shown")
        walk.tap(EntryTags.big(EntryValue.Password))
        walk.onRoute(Route.BigLetters("", ""))
        walk.look("BigLetters")
        walk.back()
        walk.onRoute(Route.Entry(""))
        walk.tap(EntryTags.MORE)
        walk.await(EntryTags.MENU_DELETE)
        walk.look("Entry-menu", scrollLists = false)
        walk.back()
        walk.tap(EntryTags.copy(EntryValue.Password))
        if (runCatching { walk.await(FirstCopyTipTags.OK, 5_000) }.isSuccess) {
            walk.look("FirstCopyTip")
            walk.tap(FirstCopyTipTags.OK)
        }
        walk.look("Entry-copied")
        walk.tap(EntryTags.EDIT)
        walk.onRoute(Route.Edit(""))
        walk.look("Edit")
        walk.type(EditTags.NOTES, " and at home")
        walk.hideKeyboard()
        walk.back()
        // A Back that a closing keyboard still took asks nothing: once more.
        if (runCatching { walk.await(EditTags.DIALOG_DISCARD, 3_000) }.isFailure && walk.isOn(Route.Edit(""))) walk.back()
        if (walk.exists(EditTags.DIALOG_DISCARD)) {
            walk.look("Edit-saveChanges")
            walk.tap(EditTags.DIALOG_DISCARD)
        }
        walk.onRoute(Route.Entry(""))
    }

    private fun list(walk: Walker) {
        add(DraftEntry(title = "Bank", username = "aziz", password = "Toshkent2024", urls = listOf("bank.uz")))
        add(DraftEntry(title = "Telegram", username = "+998901234567", password = "Toshkent2024"))
        add(DraftEntry(title = "Mail.ru", username = "aziz@mail.ru", password = "q", favourite = true))
        add(DraftEntry(title = "Olx", username = "aziz", password = "Samarqand1999", notes = "Old account"))
        tab(walk, Route.Passwords)
        walk.look("Passwords")
        walk.type(ListTags.SEARCH, "bank")
        walk.look("Passwords-search")
        walk.clear(ListTags.SEARCH)
        walk.type(ListTags.SEARCH, "gmal")
        walk.await(ListTags.ADD_AS_NEW)
        walk.look("Passwords-noResults")
        walk.clear(ListTags.SEARCH)
        walk.hideKeyboard()
        val bank = accounts().getValue("Bank")
        walk.longPress(ListTags.row(bank))
        walk.look("Passwords-rowMenu", scrollLists = false)
        walk.tap(ListTags.menuItem("bigLetters"))
        walk.onRoute(Route.BigLetters("", ""))
        walk.look("BigLetters-fromList")
        walk.back()
    }

    private fun recentlyDeleted(walk: Walker) {
        tab(walk, Route.Passwords)
        walk.tap(ListTags.row(accounts().getValue("Olx")))
        walk.onRoute(Route.Entry(""))
        walk.tap(EntryTags.MORE)
        walk.tap(EntryTags.MENU_DELETE)
        walk.await(EntryTags.DELETED)
        walk.look("Entry-deleted")
        tab(walk, Route.Passwords)
        walk.tap(ListTags.RECENTLY_DELETED)
        walk.onRoute(Route.RecentlyDeleted)
        walk.look("RecentlyDeleted")
    }

    private fun security(walk: Walker) {
        tab(walk, Route.Security)
        walk.awaitScrolledTo(SecurityTags.cardButton(SecurityTags.REUSED), 60_000)
        walk.look("Security")
        walk.tap(SecurityTags.cardButton(SecurityTags.REUSED))
        walk.onRoute(Route.Group(""))
        walk.look("Group")
        walk.tap(SecurityTags.rowAction(accounts().getValue("Bank")))
        walk.onRoute(Route.GuidedChange(""))
        walk.look("GuidedChange")
        walk.tap(GuidedChangeTags.CREATE)
        walk.await(GuidedChangeTags.SAVED)
        walk.look("GuidedChange-created")
    }

    private fun settings(walk: Walker) {
        tab(walk, Route.Settings)
        walk.look("Settings")
        walk.tap(SettingsTags.open(Route.Theme))
        walk.await(SettingsTags.theme(io.github.dilshodbekmx.passwordvault.settings.ThemeChoice.SYSTEM))
        walk.look("Theme")
        walk.back()
        walk.tap(SettingsTags.open(Route.Language))
        walk.await(LanguageTags.DIALOG)
        walk.look("Language-settings")
        walk.tap(LanguageTags.CANCEL)
        walk.tap(SettingsTags.LOCK_DELAY)
        walk.await(SettingsTags.lockDelay(LockDelay.IMMEDIATELY))
        walk.look("Settings-lockDelay")
        walk.tap(SettingsTags.DIALOG_CANCEL)
        walk.tap(SettingsTags.CLIP_DELAY)
        walk.await(SettingsTags.clipDelay(ClipDelay.SECONDS_30))
        walk.look("Settings-clipDelay")
        walk.tap(SettingsTags.DIALOG_CANCEL)
    }

    private fun settingsScreens(walk: Walker) {
        tab(walk, Route.Settings)
        walk.tap(SettingsTags.PIN)
        walk.onRoute(Route.ChangePin)
        walk.look("ChangePin")
        tab(walk, Route.Settings)
    }

    private fun transfers(walk: Walker) {
        tab(walk, Route.Settings)
        walk.tap(SettingsTags.open(Route.Backup))
        walk.onRoute(Route.Backup)
        walk.look("Backup")
        if (walk.tapIfShown(BackupTags.CONTINUE)) walk.look("Backup-password")
        walk.type(BackupTags.FIELD, "not the password")
        walk.tap(BackupTags.SUBMIT)
        walk.await(BackupTags.ERROR, 60_000)
        walk.look("Backup-wrong")
        tab(walk, Route.Settings)
        walk.tap(SettingsTags.open(Route.Export()))
        walk.onRoute(Route.Export())
        walk.look("Export")
        walk.tap(ExportTags.choice(ExportChoice.CSV))
        walk.tap(ExportTags.CONTINUE)
        walk.await(ExportTags.WARNING)
        walk.look("Export-warning")
        walk.tap(ExportTags.UNDERSTAND)
        walk.tap(ExportTags.WARNING_CONTINUE)
        walk.await(ExportTags.PASSWORD)
        walk.look("Export-password")
        tab(walk, Route.Settings)
        walk.tap(SettingsTags.open(Route.Import()))
        walk.onRoute(Route.Import())
        walk.look("Import")
        walk.tap(ImportTags.source(ImportSource.GOOGLE))
        walk.await(ImportTags.HELP)
        walk.look("Import-google")
        tab(walk, Route.Settings)
    }

    private fun masterPassword(walk: Walker) {
        tab(walk, Route.Settings)
        walk.tap(SettingsTags.CHANGE_MASTER_PASSWORD)
        walk.onRoute(Route.ChangeMasterPassword)
        walk.look("ChangeMasterPassword")
        walk.type(MasterTags.CURRENT_FIELD, "not the password")
        walk.tap(MasterTags.CURRENT_NEXT)
        walk.await(MasterTags.LINE, 60_000)
        walk.look("ChangeMasterPassword-wrong")
        walk.tap(MasterTags.DONT_REMEMBER)
        walk.onRoute(Route.NewMasterPassword)
        walk.look("NewMasterPassword")
        walk.tapIfShown(MasterTags.CANCEL)
        tab(walk, Route.Settings)
    }

    private fun help(walk: Walker) {
        for (page in HelpPage.entries) {
            tab(walk, Route.Settings)
            walk.tap(SettingsTags.help(page))
            walk.onRoute(Route.Help(page))
            walk.look("Help-${page.name}")
        }
        // The names, versions, holders, licences and links of the parts the app is built from: data, the
        // same in every language (a licence's name and text are not translated).
        for (part in LicencesCatalog.parts) {
            (part.name as? PartName.Fixed)?.let { walk.probe.data += it.text }
            part.version?.let { walk.probe.data += it }
            walk.probe.data += part.holders
            part.licence?.let { licence ->
                walk.probe.data += licence.title
                walk.probe.data += licence.urls
            }
            part.source?.let { walk.probe.data += it }
            walk.probe.data += part.groups
        }
        tab(walk, Route.Settings)
        walk.tap(SettingsTags.open(Route.Licences))
        walk.onRoute(Route.Licences)
        walk.look("Licences")
    }

    private fun lock(walk: Walker) {
        tab(walk, Route.Settings)
        walk.tap(SettingsTags.LOCK_NOW)
        walk.onRoute(Route.Locked)
        walk.await(LockTags.PAD)
        walk.look("Locked-pad")
        walk.tap(LockTags.FORGOT_PIN)
        walk.await(LockTags.FIELD)
        walk.look("Locked-masterPassword")
        walk.type(LockTags.FIELD, "not the password")
        walk.tap(LockTags.UNLOCK)
        walk.await(LockTags.line(0), 60_000)
        walk.look("Locked-wrong")
        walk.tap(LockTags.FORGOT)
        walk.await(LockTags.SHEET)
        walk.look("Locked-forgotSheet")
        walk.back()
        walk.awaitGone(LockTags.SHEET)
        walk.clear(LockTags.FIELD)
        walk.type(LockTags.FIELD, password)
        walk.tap(LockTags.UNLOCK)
        compose.waitUntil(60_000) { app.graph.lock.state.value is LockUiState.Unlocked }
        Thread.sleep(2_000)
        if (walk.exists(NoticeTags.NOTICE)) answerNotice(walk)
    }

    private companion object {
        const val PIN = "582913"

        /** The texts the walk types or adds, which may be plain English in any language. */
        val DATA = setOf(
            "Gmail", "aziz.karimov@gmail.com", "mail.google.com", "Recovery codes are in the safe",
            "Recovery codes are in the safe and at home", "Bank", "aziz", "bank.uz", "Telegram", "+998901234567",
            "Mail.ru", "aziz@mail.ru", "Olx", "Old account", "gmal", "bank", "G", "B", "T", "M", "O",
        )

        /** The form's texts for a title it never makes up: the walk always gives one. */
        val NO_TEXTS = object : EditTexts {
            override fun untitled(nowMs: Long): String = "Untitled"

            override fun fromBackup(title: String): String = title

            override fun fromFile(title: String): String = title
        }
    }
}
