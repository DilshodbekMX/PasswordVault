package io.github.dilshodbekmx.passwordvault.ui.list

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.edit.NewAccountName
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.5, [device]: the list's screens as a person and TalkBack use them (ux.md §5.11,
 * §5.23, §6, §8), over the fakes: rows and their copy buttons, the long-press menu and the same
 * actions for TalkBack, bold matches and the wrong-layout line, Back clearing a query, the empty
 * and no-result states, a banner's button, and Recently deleted's restore and deletions.
 */
@RunWith(AndroidJUnit4::class)
class PasswordsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    private fun entry(
        id: String,
        title: String,
        username: String = "",
        password: String = "",
        urls: List<String> = emptyList(),
        favourite: Boolean = false,
        changePending: Boolean = false,
        deletedAt: Long? = null,
    ) = Entry(
        id = id,
        title = title,
        username = username,
        password = password,
        urls = urls,
        favourite = favourite,
        changePending = changePending,
        pendingOldPassword = if (changePending) "old-$id" else null,
        createdAt = now - 2 * day,
        updatedAt = now - 2 * day,
        deletedAt = deletedAt,
    )

    private val accounts = listOf(
        entry("gm", "Gmail", username = "aziz@gmail.com", password = "s3cret", urls = listOf("https://mail.google.com"), favourite = true),
        entry("in", "Instagram", username = "aziz_insta", password = "insta-pw"),
        entry("fb", "Facebook", password = "new-fb", changePending = true),
        entry("sms", "Bank by SMS"),
        entry("tg", "Telegram", password = "tg-pw", deletedAt = now - day),
    )

    private var rig: ListRig? = null

    @After
    fun closeRig() {
        rig?.close()
        NewAccountName.clear()
    }

    private fun passwords(vault: Vault = Vault(entries = accounts)): Pair<ListRig, PasswordsViewModel> {
        val made = ListRig(vault).also { rig = it }
        made.awaitList()
        lateinit var vm: PasswordsViewModel
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                vm = viewModel { made.passwordsViewModel() }
                PasswordsScreen(vm, made.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body !is ListBody.Loading }
        return made to vm
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    /** Scrolls the list to the row with [key] (an `f.`, `e.` or `r.` key): a short screen (API 24) shows only the first rows. */
    private fun scrollTo(key: String) {
        compose.onNodeWithTag(ListTags.LIST).performScrollToKey(key)
    }

    @Test
    fun rowsShowTitleAndDetailAndTheirCopyButtonsCopy() {
        val (rig, _) = passwords()
        compose.onNodeWithTag(ListTags.favourite("gm")).assertIsDisplayed()
        compose.onNodeWithTag(ListTags.FAVOURITES).assertIsDisplayed()
        scrollTo(ENTRY_KEY + "gm")
        compose.onNodeWithTag(ListTags.row("gm")).assertTextContains("Gmail").assertTextContains("aziz@gmail.com")

        // One tap copies, with the sensitive flag (ux.md §6).
        compose.onNodeWithTag(ListTags.copy(ENTRY_KEY + "gm")).performClick()
        compose.waitForIdle()
        assertEquals("s3cret", rig.clipboard.copies.last().value)
        assertTrue(rig.clipboard.copies.last().sensitive)

        // An account without a password has no copy button; one with a change pending asks which (ux.md §5.17).
        scrollTo(ENTRY_KEY + "sms")
        compose.onNodeWithTag(ListTags.row("sms")).assertExists()
        compose.onNodeWithTag(ListTags.copy(ENTRY_KEY + "sms")).assertDoesNotExist()
        scrollTo(ENTRY_KEY + "fb")
        compose.onNodeWithContentDescription(text(R.string.list_change_pending), useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(text(R.string.list_copy_password_choose, "Facebook")).performClick()
        compose.onNodeWithText(text(R.string.list_menu_copy_old_password)).performClick()
        compose.waitForIdle()
        assertEquals("old-fb", rig.clipboard.copies.last().value)
        compose.onNodeWithContentDescription(text(R.string.list_copy_password_choose, "Facebook")).performClick()
        compose.onNodeWithText(text(R.string.list_menu_copy_new_password)).performClick()
        compose.waitForIdle()
        assertEquals("new-fb", rig.clipboard.copies.last().value)

        // A tap on the row opens the account.
        scrollTo(ENTRY_KEY + "in")
        compose.onNodeWithTag(ListTags.row("in")).performClick()
        assertEquals(Route.Entry("in"), rig.nav.routes.last())
    }

    @Test
    fun theLongPressMenuAndTalkBackHaveTheSameActions() {
        val (rig, _) = passwords()
        scrollTo(ENTRY_KEY + "gm")
        val row = compose.onNodeWithTag(ListTags.row("gm"))
        val expected = listOf(
            text(R.string.list_menu_copy_username),
            text(R.string.list_menu_copy_password),
            text(R.string.list_menu_open_website),
            text(R.string.list_menu_big_letters),
            text(R.string.list_menu_remove_favourite),
            text(R.string.list_menu_edit),
            text(R.string.common_delete),
        )
        val actions = row.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
        assertEquals(expected, actions?.map { it.label })

        row.performTouchInput { longClick() }
        for (label in expected) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.list_menu_edit)).performClick()
        assertEquals(Route.Edit("gm"), rig.nav.routes.last())

        row.performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.list_menu_big_letters)).performClick()
        assertEquals(Route.BigLetters("gm", "password"), rig.nav.routes.last())

        // Delete, then Undo in the snackbar (ux.md §5.23).
        row.performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.common_delete)).performClick()
        compose.waitUntil(TIMEOUT_MS) { rig.repository.stored.entries.first { it.id == "gm" }.deletedAt != null }
        compose.onNodeWithText(text(R.string.list_deleted_message, "Gmail")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.common_undo)).performClick()
        compose.waitUntil(TIMEOUT_MS) { rig.repository.stored.entries.first { it.id == "gm" }.deletedAt == null }
    }

    @Test
    fun searchMakesMatchesBoldAndSaysWhichQueryAfterAWrongLayout() {
        passwords()
        compose.onNodeWithTag(ListTags.SEARCH).performTextInput("штые")
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTagExists(ListTags.RETRIED) }
        compose.onNodeWithTag(ListTags.RETRIED).assertTextContains(text(R.string.list_search_retried, "inst"))
        val texts = compose.onNodeWithTag(ListTags.row("in")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        val title = texts?.first { it.text == "Instagram" }
        val bold = title?.spanStyles?.filter { it.item.fontWeight == FontWeight.Bold }?.map { it.start until it.end }
        assertEquals(listOf(0 until 4), bold)
        compose.onNodeWithTag(ListTags.FAVOURITES).assertDoesNotExist()
        compose.onNodeWithTag(ListTags.row("gm")).assertDoesNotExist()
    }

    @Test
    fun backClearsTheQueryFirst() {
        val (rig, vm) = passwords()
        compose.onNodeWithTag(ListTags.SEARCH).performTextInput("gma")
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body is ListBody.Results }
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body is ListBody.Browse }
        assertEquals("", vm.query.text.toString())
        assertEquals("Back went to the query, not to the navigation", 0, rig.nav.backs)
        compose.onNodeWithTag(ListTags.FAVOURITES).assertIsDisplayed()
    }

    @Test
    fun noResultsOffersTheNewAccountAndTheEmptyVaultItsTwoButtons() {
        val (rig, _) = passwords()
        compose.onNodeWithTag(ListTags.SEARCH).performTextInput("gmal")
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTagExists(ListTags.NO_RESULTS) }
        compose.onNodeWithText(text(R.string.list_no_results, "gmal")).assertIsDisplayed()
        compose.onNodeWithTag(ListTags.ADD_AS_NEW).assertTextContains(text(R.string.list_add_as_new, "gmal")).performClick()
        // The form opens with no id in its key; the typed name waits for it in memory (architecture.md §3.6).
        assertEquals(Route.Edit(), rig.nav.routes.last())
        assertEquals("gmal", NewAccountName.take())
    }

    @Test
    fun anEmptyVaultOffersAddAndImport() {
        val (rig, _) = passwords(Vault())
        compose.onNodeWithTag(ListTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.list_empty_title)).assertIsDisplayed()
        NewAccountName.offer("older offer")
        compose.onNodeWithTag(ListTags.EMPTY_ADD).performClick()
        assertNull("a plain \"Add account\" hands no name over", NewAccountName.take())
        compose.onNodeWithTag(ListTags.EMPTY_IMPORT).performClick()
        assertEquals(listOf(Route.Edit(), Route.Import()), rig.nav.routes.toList())
        compose.onNodeWithTag(ListTags.RECENTLY_DELETED).assertDoesNotExist()
    }

    @Test
    fun thePracticeBannerAsksForTheMasterPassword() {
        val (rig, vm) = passwords()
        compose.runOnUiThread { rig.lock.status.value = LockStatus.NONE.copy(practiceOverdueSinceMs = now - 40 * day) }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.banners.firstOrNull() is ListBanner.PracticeOverdue }
        val practice = vm.state.value.banners.first()
        compose.onNodeWithTag(ListTags.banner(practice)).assertIsDisplayed()

        compose.onNodeWithTag(ListTags.bannerButton(practice, "forgotIt")).performClick()
        assertEquals(Route.NewMasterPassword, rig.nav.routes.last())

        compose.onNodeWithTag(ListTags.bannerButton(practice, "typeItNow")).performClick()
        compose.onNodeWithTag(ListTags.PRACTICE_FIELD).performTextInput("the master password")
        compose.onNodeWithTag(ListTags.PRACTICE_CHECK).performClick()
        compose.waitUntil(TIMEOUT_MS) { vm.practice.value == null }
        assertEquals(listOf(MpPurpose.PRACTICE), rig.lock.purposes)
        compose.onNodeWithText(text(R.string.list_practice_right)).assertIsDisplayed()
        assertEquals("the field is empty again", "", vm.practicePassword.text.toString())
    }

    @Test
    fun finishNowOnTheReadyBanner_opensTheNewMasterPassword_andLocksNothing() {
        // build-plan.md §12, decision 14; lock.md F11 step 13: the new master password is typed once there.
        val (rig, vm) = passwords()
        val ready = PendingMasterPassword(now - 2 * day, Shortcut.PIN, creditedMs = day, remainingMs = 0L, ready = true)
        compose.runOnUiThread { rig.lock.status.value = LockStatus.NONE.copy(pending = ready) }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.banners.firstOrNull() == ListBanner.PendingReady }

        compose.onNodeWithTag(ListTags.bannerButton(ListBanner.PendingReady, "finish")).performClick()

        assertEquals(Route.NewMasterPassword, rig.nav.routes.last())
        assertTrue("nothing is sent to the lock", rig.lock.sent.isEmpty())
        assertTrue("and nothing locks", rig.lock.lockReasons.isEmpty())
    }

    @Test
    fun recentlyDeletedRestoresAndDeletesForever() {
        val (rig, _) = passwords()
        val list = compose.onNodeWithTag(ListTags.LIST)
        list.performScrollToKey("deleted")
        compose.onNodeWithTag(ListTags.RECENTLY_DELETED).assertTextContains(text(R.string.list_recently_deleted_row, "1")).performClick()
        assertEquals(Route.RecentlyDeleted, rig.nav.routes.last())
    }

    @Test
    fun theRecentlyDeletedScreen() {
        val vault = Vault(
            entries = listOf(
                entry("tg", "Telegram", deletedAt = now - day),
                entry("fb", "Facebook", deletedAt = now - 2 * day),
                entry("ok", "Odnoklassniki", deletedAt = now - 3 * day),
                entry("gm", "Gmail"),
            ),
        )
        val made = ListRig(vault).also { rig = it }
        lateinit var vm: RecentlyDeletedViewModel
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                vm = viewModel { made.recentlyDeletedViewModel() }
                RecentlyDeletedScreen(vm, made.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.rows.value != null }
        compose.onNodeWithText(text(R.string.list_deleted_info)).assertIsDisplayed()

        // Restore.
        compose.onNodeWithTag(ListTags.restore("tg")).performClick()
        compose.waitUntil(TIMEOUT_MS) { made.repository.stored.entries.first { it.id == "tg" }.deletedAt == null }
        compose.onNodeWithText(text(R.string.list_restored_message, "Telegram")).assertIsDisplayed()

        // Delete forever, after its question.
        compose.onNodeWithTag(ListTags.deletedMore("fb")).performClick()
        compose.onNodeWithText(text(R.string.list_deleted_delete_forever)).performClick()
        compose.onNodeWithText(text(R.string.list_deleted_forever_question, "Facebook")).assertIsDisplayed()
        compose.onNodeWithTag(ListTags.DIALOG_CONFIRM).performClick()
        compose.waitUntil(TIMEOUT_MS) { made.repository.stored.entries.none { it.id == "fb" } }

        // Empty, after its question with the count.
        compose.onNodeWithTag(ListTags.DELETED_EMPTY).performClick()
        compose.onNode(hasText(compose.activity.resources.getQuantityString(R.plurals.list_deleted_empty_question, 1, "1"))).assertIsDisplayed()
        compose.onNodeWithTag(ListTags.DIALOG_CONFIRM).performClick()
        compose.waitUntil(TIMEOUT_MS) { made.repository.stored.entries.map { it.id } == listOf("tg", "gm") }
        compose.onNodeWithTag(ListTags.DELETED_NOTHING).assertIsDisplayed()
        compose.onNodeWithTag(ListTags.DELETED_EMPTY).assertDoesNotExist()
        assertNull(vm.dialog.value)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/** Whether a node with [tag] exists now (for `waitUntil`, without failing while it does not). */
private fun SemanticsNodeInteractionsProvider.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
