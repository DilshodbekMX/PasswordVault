package io.github.dilshodbekmx.passwordvault.ui.shell

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.MainActivity
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.nav.routeTag
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
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
import io.github.dilshodbekmx.passwordvault.ui.settings.MasterTags
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTags
import io.github.dilshodbekmx.passwordvault.ui.setup.BackupsExplainedTags
import io.github.dilshodbekmx.passwordvault.ui.setup.FingerprintOfferTags
import io.github.dilshodbekmx.passwordvault.ui.setup.SetupTags
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
 * Build plan 8.1, [device] (ux.md §8 "No gesture-only actions … Switch Access reach everything,
 * including the keypad"; §12 check 9): the real app walked from a fresh install the way Switch Access
 * and TalkBack reach it, through the accessibility tree alone ([A11yWalker]): every control pressed by
 * its accessibility action, every field filled by `ACTION_SET_TEXT`, Back by the global action, the
 * tabs by their nodes, a list row's "Show in big letters" by its custom action, the PIN typed on the
 * pad's sensitive keys. At each screen every node a user can act on must have a name and the actions
 * it says it has; every such node is written down as TalkBack would read it. Setup with the PIN steps,
 * an account through the form and the generator, its screen, the list with search and a row's menu,
 * Recently deleted, the Security tab with a group and a guided change, Settings and its dialogs,
 * Change PIN, backup, export and import, Change master password, Help and the licences, and the lock:
 * the pad, the master password, "Forgot master password?", an unlock by the master password and one by
 * the PIN.
 *
 * It runs only with `-e pvA11y <run>` (a run name: letters, digits, `.`, `_`, `-`), from a fresh install
 * (`pm clear` first), at the phone's own size; in a plain connected run it returns at once. Its report is
 * in the app's cache (`cache/pv-a11y/<run>.txt`) and in the instrumentation's status lines (`pva11y`).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityWalkTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: PasswordVaultApp get() = instrumentation.targetContext.applicationContext as PasswordVaultApp
    private fun text(id: Int, vararg args: Any): String = instrumentation.targetContext.getString(id, *args)

    private var password = ""

    @Test
    fun walkTheAppThroughTheAccessibilityTree() {
        val run = InstrumentationRegistry.getArguments().getString("pvA11y") ?: return
        require(Regex("[A-Za-z0-9._-]+").matches(run)) { "pvA11y: a run name of letters, digits, . _ -" }
        val walk = A11yWalker(compose, run)
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(A11yWalker.WAIT_MS) { app.graph.lock.state.value !is LockUiState.Starting }
            check(app.graph.lock.state.value is LockUiState.NoVault) { "the app has a vault: clear its data first" }
            val ok = walk.step("setup") { setup(walk) } && walk.step("PIN steps") { pinSteps(walk) }
            if (ok) {
                walk.step("an account through the form") { accountThroughTheForm(walk) }
                walk.step("its screen") { account(walk) }
                walk.step("the list") { list(walk) }
                walk.step("Recently deleted") { recentlyDeleted(walk) }
                walk.step("the Security tab") { security(walk) }
                walk.step("Settings") { settings(walk) }
                walk.step("Backup, export and import") { transfers(walk) }
                walk.step("Change master password") { masterPassword(walk) }
                walk.step("Help and licences") { help(walk) }
                walk.step("the lock and the master password") { lockWithMasterPassword(walk) }
                walk.step("the lock and the PIN") { lockWithPin(walk) }
            }
        }
        assertEquals("failed checks or broken steps: see the report $run", 0, walk.end())
    }

    // ---- setup -------------------------------------------------------------------------------------

    private fun route(walk: A11yWalker, route: Route, timeoutMs: Long = A11yWalker.WAIT_MS) = walk.awaitCompose(routeTag(route), timeoutMs)

    private fun composeText(tag: String): String? =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .firstNotNullOfOrNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }

    private fun setup(walk: A11yWalker) {
        route(walk, Route.Welcome)
        walk.look("Welcome")
        walk.click(SetupTags.LANGUAGE)
        walk.await(LanguageTags.DIALOG)
        walk.look("Language")
        walk.click(LanguageTags.CANCEL)
        walk.awaitGone(LanguageTags.DIALOG)
        walk.click(SetupTags.RESTORE)
        route(walk, Route.Restore)
        walk.look("Restore")
        walk.back()
        route(walk, Route.Welcome)

        walk.click(SetupTags.START)
        route(walk, Route.CreateMasterPassword)
        walk.await(SetupTags.PASSPHRASE)
        walk.look("CreateMasterPassword")
        val words = checkNotNull(composeText(SetupTags.PASSPHRASE)) { "no suggested words" }
        walk.click(SetupTags.USE_WORDS)
        route(walk, Route.WriteDown)
        walk.look("WriteDown")
        walk.click(SetupTags.BIG_LETTERS)
        walk.await(SetupTags.BIG_LETTERS_CLOSE)
        walk.look("WriteDown-bigLetters")
        walk.click(SetupTags.BIG_LETTERS_CLOSE)
        walk.click(SetupTags.NEXT)
        route(walk, Route.TypeAgain)
        walk.setText(SetupTags.AGAIN_FIELD, "not-the-words")
        walk.click(SetupTags.CREATE)
        walk.await(SetupTags.AGAIN_MESSAGE)
        walk.look("TypeAgain-mismatch")
        walk.setText(SetupTags.AGAIN_FIELD, words)
        walk.click(SetupTags.CREATE)
        route(walk, Route.CreatePin, timeoutMs = 120_000)
        password = words
    }

    private fun pin(walk: A11yWalker, digits: String) {
        for (d in digits) walk.click(PinPadTags.key(d - '0'))
    }

    private fun pinSteps(walk: A11yWalker) {
        walk.look("CreatePin")
        pin(walk, PIN)
        route(walk, Route.ConfirmPin)
        pin(walk, PIN)
        route(walk, Route.FingerprintOffer)
        walk.look("FingerprintOffer")
        walk.click(FingerprintOfferTags.NOT_NOW)
        route(walk, Route.BackupsExplained)
        walk.look("BackupsExplained")
        walk.click(BackupsExplainedTags.FINISH)
        route(walk, Route.Passwords)
        walk.look("Passwords-empty")
    }

    // ---- the unlocked app --------------------------------------------------------------------------

    /**
     * The tab [root], pressed on the bottom bar by its node; a notice of the lock is answered first, and
     * from a screen without the bar Back leads to one (a form's "Save changes?" answered "Discard").
     */
    private fun tab(walk: A11yWalker, root: Route) {
        repeat(3) { if (walk.exists(NoticeTags.NOTICE)) answerNotice(walk) }
        val tab = "pv.tab.${root.name}"
        for (i in 0 until MAX_BACKS) {
            if (walk.exists(NoticeTags.NOTICE)) {
                answerNotice(walk)
                continue
            }
            // Back closes the keyboard first (KeyboardFirstOnBack), which covers the bar while it is up.
            if (walk.keyboardShown()) {
                walk.back()
                continue
            }
            if (walk.composeHas(routeTag(root)) && !walk.exists(EditTags.DIALOG_DISCARD)) return
            // The bar's tab of the screen shown is selected and offers no click (as Material's tabs do):
            // Back leads to its root, as a person would go.
            if (walk.shown(tab) && walk.clickable(tab)) {
                walk.click(tab)
                route(walk, root)
                return
            }
            walk.note("$tab not to be pressed here: Back")
            walk.back()
            if (walk.exists(EditTags.DIALOG_DISCARD)) walk.click(EditTags.DIALOG_DISCARD)
        }
        route(walk, root)
    }

    private var notices = 0

    private fun answerNotice(walk: A11yWalker) {
        walk.look("Notice-${++notices}")
        if (!walk.clickIfShown("pv.answer.LATER")) walk.clickIfShown("pv.answer.OK")
    }

    private fun accounts(): Map<String, String> {
        val state = app.graph.data.repository.state.value as? VaultState.Unlocked ?: return emptyMap()
        return state.vault.entries.filter { it.deletedAt == null }.associate { it.title to it.id }
    }

    private fun add(draft: DraftEntry) = kotlinx.coroutines.runBlocking {
        app.graph.data.repository.edit(VaultEdits.add(draft, NO_TEXTS, app.graph.platform.random))
    }

    private fun accountThroughTheForm(walk: A11yWalker) {
        if (!walk.clickIfShown(ListTags.EMPTY_ADD)) walk.click(ListTags.ADD)
        route(walk, Route.Edit())
        walk.look("Edit-new")
        walk.setText(EditTags.NAME, "Gmail")
        walk.setText(EditTags.USERNAME, "aziz.karimov@gmail.com")
        walk.setText(EditTags.PASSWORD, "Toshkent2024")
        walk.click(EditTags.ADD_WEBSITE)
        walk.setText(EditTags.website(0), "mail.google.com")
        walk.setText(EditTags.NOTES, "Recovery codes are in the safe")
        walk.look("Edit-filled")
        walk.click(EditTags.GENERATE)
        walk.await(GenTags.USE)
        walk.look("Generator")
        walk.click(GenTags.WORDS)
        walk.look("Generator-words")
        walk.click(GenTags.USE)
        if (runCatching { walk.await(EditTags.DIALOG_REPLACE, 5_000) }.isSuccess) {
            walk.look("Edit-replacePassword")
            walk.click(EditTags.DIALOG_REPLACE)
        }
        walk.awaitGone(GenTags.USE)
        walk.click(EditTags.ADD_FIELD)
        walk.await(EditTags.FIELD_ADD)
        walk.look("Edit-addField")
        walk.back()
        walk.click(EditTags.SAVE)
        route(walk, Route.Entry(""))
    }

    private fun account(walk: A11yWalker) {
        walk.look("Entry")
        walk.click(EntryTags.show(EntryValue.Password))
        walk.look("Entry-shown")
        walk.click(EntryTags.big(EntryValue.Password))
        route(walk, Route.BigLetters("", ""))
        walk.look("BigLetters")
        walk.back()
        route(walk, Route.Entry(""))
        walk.click(EntryTags.MORE)
        walk.awaitCompose(EntryTags.MENU_DELETE)
        walk.look("Entry-menu")
        walk.back()
        walk.click(EntryTags.copy(EntryValue.Password))
        if (runCatching { walk.await(FirstCopyTipTags.OK, 5_000) }.isSuccess) {
            walk.look("FirstCopyTip")
            walk.click(FirstCopyTipTags.OK)
        }
        walk.look("Entry-copied")
        walk.click(EntryTags.EDIT)
        route(walk, Route.Edit(""))
        walk.setText(EditTags.NOTES, "Recovery codes are in the safe and at home")
        walk.back()
        if (runCatching { walk.await(EditTags.DIALOG_DISCARD, 3_000) }.isFailure && walk.composeHas(routeTag(Route.Edit("")))) walk.back()
        if (walk.exists(EditTags.DIALOG_DISCARD)) {
            walk.look("Edit-saveChanges")
            walk.click(EditTags.DIALOG_DISCARD)
        }
        route(walk, Route.Entry(""))
    }

    private fun list(walk: A11yWalker) {
        add(DraftEntry(title = "Bank", username = "aziz", password = "Toshkent2024", urls = listOf("bank.uz")))
        add(DraftEntry(title = "Telegram", username = "+998901234567", password = "Toshkent2024"))
        add(DraftEntry(title = "Olx", username = "aziz", password = "Samarqand1999", notes = "Old account"))
        tab(walk, Route.Passwords)
        walk.look("Passwords")
        // A row's actions for TalkBack and Switch Access, as ux.md §8 lists them, and one of them done.
        val bank = ListTags.row(accounts().getValue("Bank"))
        val expected = listOf(
            R.string.list_menu_copy_password, R.string.list_menu_copy_username, R.string.list_menu_open_website,
            R.string.list_menu_big_letters, R.string.list_menu_add_favourite, R.string.list_menu_edit, R.string.common_delete,
        ).map(::text)
        val labels = walk.customLabels(bank)
        walk.check("the row's actions are ux.md §8's", labels.toSet() == expected.toSet(), "got $labels, expected $expected")
        walk.custom(bank, text(R.string.list_menu_big_letters))
        route(walk, Route.BigLetters("", ""))
        walk.look("BigLetters-fromRowAction")
        walk.back()
        route(walk, Route.Passwords)
        walk.setText(ListTags.SEARCH, "bank")
        walk.look("Passwords-search")
        walk.setText(ListTags.SEARCH, "gmal")
        walk.await(ListTags.ADD_AS_NEW)
        walk.look("Passwords-noResults")
        walk.setText(ListTags.SEARCH, "")
        walk.longClick(bank)
        walk.awaitCompose(ListTags.menuItem("bigLetters"))
        walk.look("Passwords-rowMenu")
        walk.back()
    }

    private fun recentlyDeleted(walk: A11yWalker) {
        tab(walk, Route.Passwords)
        walk.click(ListTags.row(accounts().getValue("Olx")))
        route(walk, Route.Entry(""))
        walk.click(EntryTags.MORE)
        walk.awaitCompose(EntryTags.MENU_DELETE)
        walk.click(EntryTags.MENU_DELETE)
        walk.await(EntryTags.DELETED)
        walk.look("Entry-deleted")
        tab(walk, Route.Passwords)
        walk.click(ListTags.RECENTLY_DELETED)
        route(walk, Route.RecentlyDeleted)
        walk.look("RecentlyDeleted")
    }

    private fun security(walk: A11yWalker) {
        tab(walk, Route.Security)
        walk.await(SecurityTags.cardButton(SecurityTags.REUSED), 60_000)
        walk.look("Security")
        walk.click(SecurityTags.cardButton(SecurityTags.REUSED))
        route(walk, Route.Group(""))
        walk.look("Group")
        walk.click(SecurityTags.rowAction(accounts().getValue("Bank")))
        route(walk, Route.GuidedChange(""))
        walk.look("GuidedChange")
        walk.click(GuidedChangeTags.CREATE)
        walk.await(GuidedChangeTags.SAVED)
        walk.look("GuidedChange-created")
    }

    private fun settings(walk: A11yWalker) {
        tab(walk, Route.Settings)
        walk.look("Settings")
        walk.click(SettingsTags.open(Route.Theme))
        walk.await(SettingsTags.theme(ThemeChoice.SYSTEM))
        walk.look("Theme")
        walk.back()
        walk.click(SettingsTags.open(Route.Language))
        walk.await(LanguageTags.DIALOG)
        walk.look("Language-settings")
        walk.click(LanguageTags.CANCEL)
        walk.click(SettingsTags.LOCK_DELAY)
        walk.await(SettingsTags.lockDelay(LockDelay.IMMEDIATELY))
        walk.look("Settings-lockDelay")
        walk.click(SettingsTags.DIALOG_CANCEL)
        walk.click(SettingsTags.CLIP_DELAY)
        walk.await(SettingsTags.clipDelay(ClipDelay.SECONDS_30))
        walk.look("Settings-clipDelay")
        walk.click(SettingsTags.DIALOG_CANCEL)
        walk.click(SettingsTags.PIN)
        route(walk, Route.ChangePin)
        walk.look("ChangePin")
        walk.back()
    }

    private fun transfers(walk: A11yWalker) {
        tab(walk, Route.Settings)
        walk.click(SettingsTags.open(Route.Backup))
        route(walk, Route.Backup)
        walk.look("Backup")
        if (walk.clickIfShown(BackupTags.CONTINUE)) walk.look("Backup-password")
        walk.setText(BackupTags.FIELD, "not the password")
        walk.click(BackupTags.SUBMIT)
        walk.await(BackupTags.ERROR, 60_000)
        walk.look("Backup-wrong")
        tab(walk, Route.Settings)
        walk.click(SettingsTags.open(Route.Export()))
        route(walk, Route.Export())
        walk.look("Export")
        walk.click(ExportTags.choice(ExportChoice.CSV))
        walk.click(ExportTags.CONTINUE)
        walk.await(ExportTags.WARNING)
        walk.look("Export-warning")
        walk.click(ExportTags.UNDERSTAND)
        walk.click(ExportTags.WARNING_CONTINUE)
        walk.await(ExportTags.PASSWORD)
        walk.look("Export-password")
        tab(walk, Route.Settings)
        walk.click(SettingsTags.open(Route.Import()))
        route(walk, Route.Import())
        walk.look("Import")
        walk.click(ImportTags.source(ImportSource.GOOGLE))
        walk.await(ImportTags.HELP)
        walk.look("Import-google")
    }

    private fun masterPassword(walk: A11yWalker) {
        tab(walk, Route.Settings)
        walk.click(SettingsTags.CHANGE_MASTER_PASSWORD)
        route(walk, Route.ChangeMasterPassword)
        walk.look("ChangeMasterPassword")
        walk.setText(MasterTags.CURRENT_FIELD, "not the password")
        walk.click(MasterTags.CURRENT_NEXT)
        walk.await(MasterTags.LINE, 60_000)
        walk.look("ChangeMasterPassword-wrong")
        walk.click(MasterTags.DONT_REMEMBER)
        route(walk, Route.NewMasterPassword)
        walk.look("NewMasterPassword")
        walk.clickIfShown(MasterTags.CANCEL)
    }

    private fun help(walk: A11yWalker) {
        for (page in HelpPage.entries) {
            tab(walk, Route.Settings)
            walk.click(SettingsTags.help(page))
            route(walk, Route.Help(page))
            walk.look("Help-${page.name}")
        }
        tab(walk, Route.Settings)
        walk.click(SettingsTags.open(Route.Licences))
        route(walk, Route.Licences)
        walk.look("Licences")
    }

    private fun lockWithMasterPassword(walk: A11yWalker) {
        tab(walk, Route.Settings)
        walk.click(SettingsTags.LOCK_NOW)
        route(walk, Route.Locked)
        walk.await(LockTags.PAD)
        walk.look("Locked-pad")
        walk.click(LockTags.FORGOT_PIN)
        walk.await(LockTags.FIELD)
        walk.look("Locked-masterPassword")
        walk.setText(LockTags.FIELD, "not the password")
        walk.click(LockTags.UNLOCK)
        walk.await(LockTags.line(0), 60_000)
        walk.look("Locked-wrong")
        walk.click(LockTags.FORGOT)
        walk.await(LockTags.SHEET)
        walk.look("Locked-forgotSheet")
        walk.back()
        walk.awaitGone(LockTags.SHEET)
        walk.setText(LockTags.FIELD, password)
        walk.click(LockTags.UNLOCK)
        compose.waitUntil(60_000) { app.graph.lock.state.value is LockUiState.Unlocked }
        answerNotices(walk)
    }

    /**
     * The notices after an unlock (lock.md §13.4), each looked at and answered, as they come: the lock
     * raises "Make a backup now?" a moment after a master-password unlock, so each is waited for.
     */
    private fun answerNotices(walk: A11yWalker) {
        repeat(MAX_NOTICES) {
            val end = System.currentTimeMillis() + NOTICE_WAIT_MS
            while (!walk.exists(NoticeTags.NOTICE) && System.currentTimeMillis() < end) Thread.sleep(200)
            if (!walk.exists(NoticeTags.NOTICE)) return
            answerNotice(walk)
        }
    }

    /** The PIN typed on the pad's keys by their accessibility actions: Switch Access reaches the keypad (ux.md §8). */
    private fun lockWithPin(walk: A11yWalker) {
        tab(walk, Route.Settings)
        walk.click(SettingsTags.LOCK_NOW)
        route(walk, Route.Locked)
        walk.await(LockTags.PAD)
        pin(walk, PIN)
        compose.waitUntil(60_000) { app.graph.lock.state.value is LockUiState.Unlocked }
        walk.check("the PIN typed through the accessibility tree opens the app", app.graph.lock.state.value is LockUiState.Unlocked)
        answerNotices(walk)
    }

    private companion object {
        const val PIN = "582913"
        const val MAX_BACKS = 6
        const val MAX_NOTICES = 4
        const val NOTICE_WAIT_MS = 6_000L

        val NO_TEXTS = object : EditTexts {
            override fun untitled(nowMs: Long): String = "Untitled"

            override fun fromBackup(title: String): String = title

            override fun fromFile(title: String): String = title
        }
    }
}
