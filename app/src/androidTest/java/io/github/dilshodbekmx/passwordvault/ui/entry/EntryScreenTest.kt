package io.github.dilshodbekmx.passwordvault.ui.entry

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.BANK
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.GMAIL
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.NOTES
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.OLD_PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PHONE
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PIN
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PREVIOUS_0
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PREVIOUS_1
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.SECRETS
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.USERNAME
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.bare
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.common.linesWithAGlyphNotInTheText
import io.github.dilshodbekmx.passwordvault.ui.common.textLayoutOf
import io.github.dilshodbekmx.passwordvault.ui.list.GoneNotice
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.7, [device]: an account's screen as a person, TalkBack and an accessibility or
 * autofill service meet it (ux.md §5.12, §3, §6, §8), over the fakes. The acceptance line's two
 * cases are the first two tests: stored passwords are drawn with `Text`, never a read-only field,
 * and the notes can be selected. The rest walks the screen: the rows' buttons and menus, the
 * banners, Delete with Undo, the states, and what a stop and a recreation do to a shown value.
 */
@RunWith(AndroidJUnit4::class)
class EntryScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var rig: EntryRig
    private lateinit var vm: EntryViewModel
    private lateinit var clips: RecordingClipboard
    private val toolbar = RecordingToolbar()

    /**
     * Shows the account [id] of [vault] as the app's root would: its own clipboard and toolbar
     * provided above the theme. [backAllowed] false: Back has nowhere to go ([RecordingNav.backAllowed]).
     */
    private fun show(vault: Vault, id: String = GMAIL, justSaved: Boolean = false, backAllowed: Boolean = true) {
        rig = EntryRig(vault)
        rig.nav.backAllowed = backAllowed
        clips = RecordingClipboard(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalClipboard provides clips, LocalTextContextMenuToolbarProvider provides toolbar) {
                PasswordVaultTheme(ThemeChoice.LIGHT) {
                    vm = viewModel { rig.entryViewModel(id, justSaved) }
                    EntryScreen(vm, rig.nav)
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value !is EntryUiState.Loading }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    /** Scrolls the content to the row with [key]: a short screen (API 24 and 28) shows only the first rows. */
    private fun scrollTo(key: String) {
        compose.onNodeWithTag(EntryTags.CONTENT).performScrollToKey(key)
        compose.waitForIdle()
    }

    private fun details(): EntryDetails = (vm.state.value as EntryUiState.Shown).details

    /** The node that draws [value], as composed. */
    private fun valueConfig(value: EntryValue): SemanticsConfiguration =
        compose.onNodeWithTag(EntryTags.value(value), useUnmergedTree = true).fetchSemanticsNode().config

    /** Every node that is, or behaves like, a text field: an autofill node holding its text [V-P4]. */
    private fun fieldNodes(): List<SemanticsNode> = compose.everyNode(useUnmergedTree = true).filter { node ->
        val config = node.config
        listOf(
            SemanticsProperties.EditableText, SemanticsProperties.IsEditable, SemanticsProperties.InputText,
            SemanticsProperties.TextSelectionRange, SemanticsProperties.ImeAction, SemanticsProperties.MaxTextLength,
        ).any { it in config } ||
            listOf(
                SemanticsActions.SetText, SemanticsActions.InsertTextAtCursor, SemanticsActions.PasteText,
                SemanticsActions.CutText, SemanticsActions.OnImeAction, SemanticsActions.OnAutofillText,
            ).any { it in config }
    }

    /**
     * Every node that the phone's autofill service is offered: one with a content type, or with a
     * kind of value other than "none". A toggle is one unless it opts out (Compose foundation 1.12.1).
     */
    private fun autofillNodes(): List<SemanticsNode> = compose.everyNode(useUnmergedTree = true).filter { node ->
        val config = node.config
        SemanticsProperties.ContentType in config ||
            (SemanticsProperties.ContentDataType in config && config[SemanticsProperties.ContentDataType] != ContentDataType.None)
    }

    private class Secret(val value: EntryValue, val key: String, val text: String, val hidden: String)

    /** Every secret of [gmail] with a change pending, by the key of its row. */
    private fun secrets() = listOf(
        Secret(EntryValue.OldPassword, "old-password", OLD_PASSWORD, text(R.string.entry_old_password_hidden)),
        Secret(EntryValue.Password, "password", PASSWORD, text(R.string.entry_new_password_hidden)),
        Secret(EntryValue.Field(0), "field.0", PIN, text(R.string.entry_field_hidden, "PIN code")),
        Secret(EntryValue.Previous(0), "previous.0", PREVIOUS_0, text(R.string.entry_previous_hidden)),
        Secret(EntryValue.Previous(1), "previous.1", PREVIOUS_1, text(R.string.entry_previous_hidden)),
    )

    // ---- The acceptance line -----------------------------------------------------------------

    @Test
    fun storedPasswordsAreDrawnWithTextNeverAReadOnlyField() {
        show(Vault(entries = listOf(gmail(changePending = true))))
        scrollTo("previous")
        compose.onNodeWithTag(EntryTags.PREVIOUS).performClick()
        compose.waitUntil(TIMEOUT_MS) { details().previous.size == 2 }
        val secrets = secrets()

        // Hidden: 8 dots drawn, and nothing of any secret anywhere in the tree, on any part of the screen.
        for (secret in secrets) {
            scrollTo(secret.key)
            val config = valueConfig(secret.value)
            assertEquals(secret.key, listOf(secret.hidden), config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue("${secret.key}: password semantics", SemanticsProperties.Password in config)
            assertEquals(true, config.getOrNull(SemanticsProperties.IsSensitiveData))
            assertNull("${secret.key}: no text, so no length", config.getOrNull(SemanticsProperties.Text))
            val readable = compose.everythingReadable()
            for (text in SECRETS) assertFalse("\"$text\" can be read while it is hidden (at ${secret.key})", text in readable)
            assertTrue("a field on the screen at ${secret.key}: ${fieldNodes().map { it.config }}", fieldNodes().isEmpty())
            assertTrue("a node offered to autofill at ${secret.key}: ${autofillNodes().map { it.config }}", autofillNodes().isEmpty())
        }

        // Shown: each one is a Text node that holds exactly the value, flagged as sensitive, with
        // nothing of a text field about it; and still no field anywhere.
        val hint = text(R.string.entry_shown_hint)
        for (secret in secrets) {
            scrollTo(secret.key)
            compose.onNodeWithTag(EntryTags.show(secret.value)).performClick()
            compose.waitForIdle()
            val config = valueConfig(secret.value)
            assertEquals(secret.key, listOf(secret.text), config.getOrNull(SemanticsProperties.Text)?.map { it.text })
            assertEquals("${secret.key}: sensitive", true, config.getOrNull(SemanticsProperties.IsSensitiveData))
            assertEquals("${secret.key}: TalkBack hears when it hides again", hint, config.getOrNull(SemanticsProperties.StateDescription))
            assertNull(config.getOrNull(SemanticsProperties.EditableText))
            assertNull(config.getOrNull(SemanticsActions.SetText))
            assertFalse("${secret.key}: no password semantics on a shown value", SemanticsProperties.Password in config)
            assertTrue("a field on the screen with ${secret.key} shown: ${fieldNodes().map { it.config }}", fieldNodes().isEmpty())
            assertTrue("a node offered to autofill with ${secret.key} shown: ${autofillNodes().map { it.config }}", autofillNodes().isEmpty())
        }

        // The same as an accessibility service meets it: no editable node, no EditText, and the
        // shown password as the text of an ordinary node.
        scrollTo("old-password")
        compose.waitForIdle()
        val platform = platformNodes()
        assertTrue("the screen's nodes are found: ${platform.size}", platform.size > 10)
        for (node in platform) {
            assertFalse("an editable node: ${node.className} ${node.viewIdResourceName}", node.isEditable)
            assertFalse("an EditText: ${node.viewIdResourceName}", node.className?.toString().orEmpty().endsWith("EditText"))
        }
        assertTrue(
            "the shown password is a node's text: ${platform.mapNotNull { it.text }}",
            platform.any { it.text?.toString().orEmpty().contains(PASSWORD) },
        )
    }

    @Test
    fun notesAreSelectableAndTheirCopyGoesThroughTheAppsClipboard() {
        show(Vault(entries = listOf(gmail())))
        scrollTo("notes")
        val notes = compose.onNodeWithTag(EntryTags.value(EntryValue.Notes), useUnmergedTree = true)
        notes.assertTextEquals(NOTES)
        assertTrue("nothing is selected or offered before a touch", toolbar.shown.isEmpty())

        // A long press on the first word selects it and brings up the selection's toolbar with "Copy".
        notes.performTouchInput { longClick(Offset(left + width * 0.08f, top + 14f)) }
        compose.waitUntil(TIMEOUT_MS) { toolbar.shown.isNotEmpty() }
        val menu = toolbar.shown.last()
        val copy = menu.item(TextContextMenuKeys.CopyKey)
        assertNotNull("the selection offers Copy; it offers ${menu.data.components.map { it.key }}", copy)

        // "Copy" hands the selected text to the clipboard that the app's root provides: in the app
        // that is SecretClipboardBridge, which flags it and clears it later (architecture.md D-25).
        compose.runOnUiThread { copy!!.onClick(menu) }
        compose.waitUntil(TIMEOUT_MS) { clips.clips.isNotEmpty() }
        val copied = clips.texts().single()
        assertTrue("\"$copied\" is the selected part of the notes", copied.isNotEmpty() && copied in NOTES)
        assertTrue("the first word was under the finger: \"$copied\"", "Samarqand" in copied)
        assertTrue("selected text is not copied twice", rig.clipboard.copies.isEmpty())

        // The whole notes with their own copy button: through SecretClipboard, flagged.
        compose.onNodeWithTag(EntryTags.copy(EntryValue.Notes)).performClick()
        compose.waitForIdle()
        assertEquals(NOTES, rig.clipboard.copies.single().value)
        assertTrue(rig.clipboard.copies.single().sensitive)
    }

    // ---- The rows ----------------------------------------------------------------------------

    @Test
    fun theCopyButtonsCopyWithoutShowing() {
        show(Vault(entries = listOf(gmail())))
        compose.onNodeWithTag(EntryTags.TITLE).assertTextEquals("Gmail")
        compose.onNodeWithTag(EntryTags.value(EntryValue.Username), useUnmergedTree = true).assertTextEquals(USERNAME)

        compose.onNodeWithTag(EntryTags.copy(EntryValue.Password)).performClick()
        compose.onNodeWithTag(EntryTags.copy(EntryValue.Username)).performClick()
        compose.waitForIdle()
        val (password, username) = rig.clipboard.copies
        assertEquals(PASSWORD, password.value)
        assertTrue("a password is flagged as sensitive", password.sensitive)
        assertEquals(USERNAME, username.value)
        assertFalse("a username is not", username.sensitive)
        assertNull("the password was copied hidden", details().passwords.single().shown)
        val readable = compose.everythingReadable()
        assertFalse(PASSWORD in readable)

        // A field that is not hidden shows its value and copies it without the flag.
        scrollTo("field.1")
        compose.onNodeWithTag(EntryTags.value(EntryValue.Field(1)), useUnmergedTree = true).assertTextEquals(PHONE)
        compose.onNodeWithTag(EntryTags.copy(EntryValue.Field(1))).performClick()
        compose.waitForIdle()
        assertEquals(PHONE, rig.clipboard.copies.last().value)
        assertFalse(rig.clipboard.copies.last().sensitive)
    }

    @Test
    fun showInBigLettersEditAndTheRowsMenus() {
        show(Vault(entries = listOf(gmail())))

        // The button under the password and the item of the top bar's menu: the password in big letters.
        compose.onNodeWithTag(EntryTags.big(EntryValue.Password)).performClick()
        compose.onNodeWithTag(EntryTags.MORE).performClick()
        compose.onNodeWithTag(EntryTags.MENU_BIG_LETTERS).performClick()
        compose.waitForIdle()
        assertEquals(listOf<Route>(Route.BigLetters(GMAIL, "password"), Route.BigLetters(GMAIL, "password")), rig.nav.opened)

        // A tap on a row opens its menu: the username's has "Copy username" and "Show in big letters".
        compose.onNodeWithTag(EntryTags.USERNAME).performClick()
        compose.onNodeWithTag(EntryTags.menuItem(EntryValue.Username, 0)).assertTextEquals(text(R.string.entry_copy_username))
        compose.onNodeWithTag(EntryTags.menuItem(EntryValue.Username, 1)).assertTextEquals(text(R.string.entry_big_letters)).performClick()
        compose.waitForIdle()
        assertEquals(Route.BigLetters(GMAIL, "username"), rig.nav.opened.last())

        // A long press does the same; the hidden field's menu shows it, and then offers to hide it.
        scrollTo("field.0")
        val pin = EntryValue.Field(0)
        compose.onNodeWithTag(EntryTags.field(0)).performTouchInput { longClick() }
        compose.onNodeWithTag(EntryTags.menuItem(pin, 1)).assertTextEquals(text(R.string.entry_show_named, "PIN code")).performClick()
        compose.waitUntil(TIMEOUT_MS) { details().fields[0].shown == PIN }
        compose.onNodeWithTag(EntryTags.field(0)).performClick()
        compose.onNodeWithTag(EntryTags.menuItem(pin, 1)).assertTextEquals(text(R.string.entry_hide_named, "PIN code"))
        compose.onNodeWithTag(EntryTags.menuItem(pin, 2)).performClick()
        compose.waitForIdle()
        assertEquals(Route.BigLetters(GMAIL, "field-0"), rig.nav.opened.last())

        // "Edit" in the top bar opens the account's form.
        compose.onNodeWithTag(EntryTags.EDIT).performClick()
        compose.waitForIdle()
        assertEquals(Route.Edit(GMAIL), rig.nav.opened.last())
        assertEquals(0, rig.nav.backs)
    }

    @Test
    fun aRowIsOneNodeForTalkBackWithItsActions() {
        show(Vault(entries = listOf(gmail())))
        val hiddenRow = compose.onNodeWithTag(EntryTags.PASSWORD).fetchSemanticsNode().config

        // Hidden: "Password, hidden" and nothing else; the label is not said twice (ux.md §8).
        assertEquals(listOf(text(R.string.entry_password_hidden)), hiddenRow.getOrNull(SemanticsProperties.ContentDescription))
        assertNull("no text of the row while its value is hidden", hiddenRow.getOrNull(SemanticsProperties.Text))
        assertEquals(text(R.string.entry_row_options), hiddenRow.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals(
            listOf(text(R.string.entry_copy_password), text(R.string.common_show_password), text(R.string.entry_big_letters)),
            hiddenRow.getOrNull(SemanticsActions.CustomActions)?.map { it.label },
        )

        // A TalkBack action shows it: then the row reads its label, the value, and when it hides again.
        compose.runOnUiThread { hiddenRow[SemanticsActions.CustomActions][1].action() }
        compose.waitUntil(TIMEOUT_MS) { details().passwords.single().shown == PASSWORD }
        compose.waitForIdle()
        val shownRow = compose.onNodeWithTag(EntryTags.PASSWORD).fetchSemanticsNode().config
        assertEquals(listOf(text(R.string.entry_password), PASSWORD), shownRow.getOrNull(SemanticsProperties.Text)?.map { it.text })
        assertEquals(text(R.string.entry_shown_hint), shownRow.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(text(R.string.common_hide_password), shownRow[SemanticsActions.CustomActions][1].label)

        // The show button says what it will do; the website's buttons name the website.
        compose.onNodeWithTag(EntryTags.show(EntryValue.Password)).fetchSemanticsNode().config.let { toggle ->
            assertEquals(listOf(text(R.string.common_hide_password)), toggle.getOrNull(SemanticsProperties.ContentDescription))
        }
        scrollTo("website.0")
        assertEquals(
            listOf(text(R.string.entry_open_in_browser, "mail.google.com")),
            compose.onNodeWithTag(EntryTags.open(0)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
        // An app's address is "App: …" with copy only (ux.md §5.12).
        scrollTo("website.1")
        compose.onNodeWithTag(EntryTags.value(EntryValue.Website(1)), useUnmergedTree = true)
            .assertTextEquals(text(R.string.entry_app, "com.google.android.gm"))
        compose.onNodeWithTag(EntryTags.open(1)).assertDoesNotExist()
        compose.onNodeWithTag(EntryTags.copy(EntryValue.Website(1))).assertExists()
    }

    // ---- Banners -----------------------------------------------------------------------------

    @Test
    fun thePendingChangeIsAnsweredOnItsBanner() {
        show(Vault(entries = listOf(gmail(changePending = true))))
        compose.onNodeWithTag(EntryTags.BANNER_CHANGE).assertIsDisplayed()
        // Two password rows, each with its own show, copy and big letters (ux.md §5.12).
        scrollTo("old-password")
        for (value in listOf(EntryValue.OldPassword, EntryValue.Password)) {
            compose.onNodeWithTag(EntryTags.show(value)).assertExists()
            compose.onNodeWithTag(EntryTags.copy(value)).assertExists()
            compose.onNodeWithTag(EntryTags.big(value)).assertExists()
        }
        compose.onNodeWithTag(EntryTags.copy(EntryValue.OldPassword)).performClick()
        compose.onNodeWithTag(EntryTags.copy(EntryValue.Password)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(OLD_PASSWORD, PASSWORD), rig.clipboard.copies.map { it.value })
        compose.onNodeWithTag(EntryTags.big(EntryValue.OldPassword)).performClick()
        compose.waitForIdle()
        assertEquals(Route.BigLetters(GMAIL, "old-password"), rig.nav.opened.last())
        // With two passwords the top bar's menu does not choose one.
        compose.onNodeWithTag(EntryTags.MORE).performClick()
        compose.onNodeWithTag(EntryTags.MENU_BIG_LETTERS).assertDoesNotExist()
        compose.onNodeWithTag(EntryTags.MENU_DELETE).assertExists()
        Espresso.pressBack()
        compose.waitForIdle()

        // "Yes, it's changed": the banner goes, one password row is left, the old one is kept as previous.
        scrollTo("banner.change")
        compose.onNodeWithTag(EntryTags.BANNER_CHANGE_YES).performClick()
        compose.waitUntil(TIMEOUT_MS) { !details().changePending }
        compose.waitForIdle()
        compose.onNodeWithTag(EntryTags.BANNER_CHANGE).assertDoesNotExist()
        compose.onNodeWithTag(EntryTags.OLD_PASSWORD).assertDoesNotExist()
        assertEquals(PASSWORD, rig.entry().password)
        assertEquals(OLD_PASSWORD, rig.entry().passwordHistory.first().password)
    }

    @Test
    fun leftOutOfTheSecurityCheckAndTheMasterPasswordsTwin() {
        show(Vault(entries = listOf(gmail()), masterPasswordMatches = listOf(GMAIL)))

        // The password equals the master password: a warning with the way to a new master password.
        compose.onNodeWithTag(EntryTags.BANNER_MASTER).assertIsDisplayed()
        compose.onNodeWithTag(EntryTags.BANNER_MASTER_CHOOSE).performClick()
        compose.waitForIdle()
        assertEquals(listOf<Route>(Route.ChangeMasterPassword), rig.nav.opened)

        // "Leave out of the security check" in the menu; then its banner, whose button takes it back in.
        compose.onNodeWithTag(EntryTags.BANNER_LEFT_OUT).assertDoesNotExist()
        compose.onNodeWithTag(EntryTags.MORE).performClick()
        compose.onNodeWithTag(EntryTags.MENU_LEAVE_OUT).performClick()
        compose.waitUntil(TIMEOUT_MS) { details().checkIgnored }
        assertTrue(rig.entry().checkIgnored)
        scrollTo("banner.leftOut")
        compose.onNodeWithTag(EntryTags.BANNER_LEFT_OUT).assertIsDisplayed()
        compose.onNodeWithTag(EntryTags.MORE).performClick()
        compose.onNodeWithTag(EntryTags.MENU_LEAVE_OUT).assertDoesNotExist()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag(EntryTags.BANNER_CHECK_AGAIN).performClick()
        compose.waitUntil(TIMEOUT_MS) { !details().checkIgnored }
        assertFalse(rig.entry().checkIgnored)

        // The star.
        compose.onNodeWithTag(EntryTags.FAVOURITE).performClick()
        compose.waitUntil(TIMEOUT_MS) { details().favourite }
        assertTrue(rig.entry().favourite)
        assertEquals(
            listOf(text(R.string.entry_remove_favourite)),
            compose.onNodeWithTag(EntryTags.FAVOURITE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }

    // ---- Delete, Saved, and the states --------------------------------------------------------

    @Test
    fun deleteOffersUndoAndUndoBringsTheAccountBack() {
        show(Vault(entries = listOf(gmail())))
        compose.onNodeWithTag(EntryTags.MORE).performClick()
        compose.onNodeWithTag(EntryTags.MENU_DELETE).performClick()

        // At once and without a question (ux.md §5.23): the account is in Recently deleted, and the screen says so.
        compose.waitUntil(TIMEOUT_MS) { vm.state.value is EntryUiState.Deleted }
        assertNotNull(rig.entry().deletedAt)
        compose.onNodeWithTag(EntryTags.DELETED).assertIsDisplayed()
        compose.onNodeWithTag(EntryTags.CONTENT).assertDoesNotExist()
        compose.onNodeWithTag(EntryTags.MESSAGE).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.entry_deleted_message, "Gmail")).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.common_undo)).performClick()
        compose.waitUntil(TIMEOUT_MS) { vm.state.value is EntryUiState.Shown }
        compose.waitForIdle()
        assertNull(rig.entry().deletedAt)
        compose.onNodeWithTag(EntryTags.TITLE).assertTextEquals("Gmail")
        assertEquals("Undo keeps the screen", 0, rig.nav.backs)
    }

    @Test
    fun withoutUndoTheScreenClosesWhenTheMessageEnds() {
        show(Vault(entries = listOf(gmail())))
        compose.onNodeWithTag(EntryTags.MORE).performClick()
        compose.onNodeWithTag(EntryTags.MENU_DELETE).performClick()
        compose.waitUntil(TIMEOUT_MS) { vm.state.value is EntryUiState.Deleted }
        assertEquals(0, rig.nav.backs)
        // "About 10 s" (ux.md §5.12); longer when the phone's accessibility settings ask for more time.
        compose.waitUntil(MESSAGE_TIMEOUT_MS) { rig.nav.backs == 1 }
        assertNotNull("the account stays in Recently deleted", rig.entry().deletedAt)
    }

    @Test
    fun savedIsSaidForAnAccountTheFormJustAdded() {
        show(Vault(entries = listOf(gmail())), justSaved = true)
        compose.onNodeWithTag(EntryTags.MESSAGE).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.entry_saved)).assertIsDisplayed()
    }

    @Test
    fun anAccountWithANameOnlySaysSo() {
        show(Vault(entries = listOf(bare())), id = BANK)
        compose.onNodeWithTag(EntryTags.TITLE).assertTextEquals("Kapitalbank")
        compose.onNodeWithTag(EntryTags.EMPTY).assertTextEquals(text(R.string.entry_empty))
        compose.onNodeWithTag(EntryTags.PASSWORD).assertDoesNotExist()
        compose.onNodeWithTag(EntryTags.PREVIOUS).assertDoesNotExist()
        compose.onNodeWithTag(EntryTags.EMPTY_EDIT).performClick()
        compose.waitForIdle()
        assertEquals(listOf<Route>(Route.Edit(BANK)), rig.nav.opened)
        // Nothing to show in big letters: the menu has no such item.
        compose.onNodeWithTag(EntryTags.MORE).performClick()
        compose.onNodeWithTag(EntryTags.MENU_BIG_LETTERS).assertDoesNotExist()
    }

    @Test
    fun anAccountThatIsNotThereGoesBackToTheList_whichSaysSo() {
        // Not in the vault at all: back to the list by itself, with the mark the list turns into
        // "This account no longer exists." (ux.md §5.12; PasswordsScreenTest shows the list's side).
        GoneNotice.clear()
        try {
            show(Vault(entries = listOf(gmail())), id = BANK)
            compose.waitUntil(TIMEOUT_MS) { rig.nav.backs == 1 }
            assertTrue("the list is to say so", GoneNotice.take())
            compose.onNodeWithTag(EntryTags.GONE_OPEN_DELETED).assertDoesNotExist()
            compose.onNodeWithTag(EntryTags.EDIT).assertDoesNotExist()
            compose.waitForIdle()
            assertEquals("once", 1, rig.nav.backs)
        } finally {
            GoneNotice.clear()
        }
    }

    @Test
    fun withNowhereToGoBack_anAccountThatIsNotThereSaysSoItself() {
        GoneNotice.clear()
        try {
            show(Vault(entries = listOf(gmail())), id = BANK, backAllowed = false)
            compose.waitUntil(TIMEOUT_MS) { rig.nav.backs == 1 }
            assertFalse("no list is shown to say it", GoneNotice.take())
            compose.onNodeWithTag(EntryTags.GONE).assertTextEquals(text(R.string.entry_gone))
            compose.onNodeWithTag(EntryTags.GONE_BACK).performClick()
            compose.waitForIdle()
            assertEquals(2, rig.nav.backs)
        } finally {
            GoneNotice.clear()
        }
    }

    @Test
    fun anAccountInRecentlyDeletedLeadsThere() {
        show(Vault(entries = listOf(gmail().copy(deletedAt = System.currentTimeMillis() - 1_000L))))
        compose.onNodeWithTag(EntryTags.GONE).assertTextEquals(text(R.string.entry_gone_deleted))
        assertFalse("nothing of a deleted account is on the screen", USERNAME in compose.everythingReadable())
        compose.onNodeWithTag(EntryTags.GONE_OPEN_DELETED).performClick()
        compose.waitForIdle()
        assertEquals("in place of this screen, so that Back goes to the list", listOf<Route>(Route.RecentlyDeleted), rig.nav.replaced)
        assertTrue(rig.nav.opened.isEmpty())
    }

    @Test
    fun longNotesCollapseBehindShowMore() {
        val long = (1..20).joinToString("\n") { "Qator $it: bank xodimi bilan kelishilgan soʻz" }
        show(Vault(entries = listOf(gmail(notes = long))))
        scrollTo("notes")
        compose.onNodeWithTag(EntryTags.NOTES_MORE).assertTextEquals(text(R.string.entry_show_more)).performClick()
        compose.waitUntil(TIMEOUT_MS) { details().notesExpanded }
        scrollTo("notes")
        compose.onNodeWithTag(EntryTags.value(EntryValue.Notes), useUnmergedTree = true).assertTextEquals(long)
    }

    @Test
    fun shortNotesHaveNoShowMore() {
        show(Vault(entries = listOf(gmail())))
        scrollTo("notes")
        compose.onNodeWithTag(EntryTags.NOTES_MORE).assertDoesNotExist()
    }

    /**
     * Build plan 8.1 (the open problem of section 12, R5): TalkBack's heading navigation meets the
     * account's name once, the top bar's title, although the content shows the name whole again as its
     * first line (ux.md §5.12, §8).
     */
    @Test
    fun theAccountsNameIsOneHeading() {
        show(Vault(entries = listOf(gmail())))
        val headings = compose.everyNode(useUnmergedTree = true).filter { node ->
            SemanticsProperties.Heading in node.config &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == details().title }
        }
        assertEquals("headings with the name", 1, headings.size)
        compose.onNodeWithTag(EntryTags.TITLE).assertExists()
    }

    /**
     * Build plan 8.1's review: the theme hyphenates every language since 8.1, so a user name or a
     * field's value too long for its line, which the owner reads to type it elsewhere, wrapped with a
     * hyphen it does not have ("aleksandrpetro- / vichivanov" in English). At a font of 200 % each
     * wraps between two of its own letters, as the shown password does (`SecretTextLinesTest`).
     */
    @Test
    fun aLongUserNameOrFieldWrapsWithoutAHyphenItDoesNotHave() {
        val fields = listOf(CustomField("PIN code", PIN, secret = true), CustomField("Answer", LONG_USERNAME, secret = false))
        rig = EntryRig(Vault(entries = listOf(gmail().copy(username = LONG_USERNAME, fields = fields))))
        clips = RecordingClipboard(compose.activity)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalClipboard provides clips,
                LocalTextContextMenuToolbarProvider provides toolbar,
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                PasswordVaultTheme(ThemeChoice.LIGHT) {
                    vm = viewModel { rig.entryViewModel(GMAIL, false) }
                    EntryScreen(vm, rig.nav)
                }
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value !is EntryUiState.Loading }
        compose.waitForIdle()
        for ((key, value) in listOf("username" to EntryValue.Username, "field.1" to EntryValue.Field(1))) {
            scrollTo(key)
            val laidOut = textLayoutOf(compose.onNodeWithTag(EntryTags.value(value), useUnmergedTree = true).fetchSemanticsNode())
            assertEquals("$value", LONG_USERNAME, laidOut.layoutInput.text.text)
            assertTrue("$value takes more than one line", laidOut.lineCount > 1)
            assertEquals("$value", emptyList<String>(), linesWithAGlyphNotInTheText(laidOut))
        }
    }

    // ---- When the screen stops ---------------------------------------------------------------

    @Test
    fun aStopHidesWhatIsShownAndARecreationDoesNot() {
        show(Vault(entries = listOf(gmail())))
        compose.onNodeWithTag(EntryTags.show(EntryValue.Password)).performClick()
        compose.waitUntil(TIMEOUT_MS) { details().passwords.single().shown == PASSWORD }

        // The app is left: hidden at once (ux.md §3, §12 check 3: "the last screen with secrets masked").
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNull("hidden when the screen stops", details().passwords.single().shown)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag(EntryTags.value(EntryValue.Password), useUnmergedTree = true).assertExists()
        assertFalse("and still hidden when the person comes back", PASSWORD in compose.everythingReadable())

        // A recreation (a rotation, a language change) stops the activity too, but the person never
        // left the screen: what is shown stays shown, in the ViewModel that outlives the activity.
        compose.onNodeWithTag(EntryTags.show(EntryValue.Password)).performClick()
        compose.waitUntil(TIMEOUT_MS) { details().passwords.single().shown == PASSWORD }
        compose.activityRule.scenario.recreate()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals("still shown after a recreation", PASSWORD, details().passwords.single().shown)
    }

    /** The nodes of the app's window as an accessibility service gets them. */
    private fun platformNodes(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (true) {
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo?) {
                if (node == null) return
                nodes += node
                for (i in 0 until node.childCount) walk(node.getChild(i))
            }
            walk(automation.rootInActiveWindow)
            if (nodes.any { it.text?.toString().orEmpty().contains(PASSWORD) } || SystemClock.uptimeMillis() > deadline) return nodes
            SystemClock.sleep(200)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** The "Gmail deleted" message stays about 10 s. */
        const val MESSAGE_TIMEOUT_MS = 40_000L

        /** A user name (and a field's value) of letters only, which English hyphenation patterns can break. */
        const val LONG_USERNAME = "aleksandrpetrovichivanov"
    }
}
