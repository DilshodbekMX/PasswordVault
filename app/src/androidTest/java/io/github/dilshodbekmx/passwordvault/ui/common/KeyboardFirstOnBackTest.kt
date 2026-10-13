package io.github.dilshodbekmx.passwordvault.ui.common

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.edit.EditHost
import io.github.dilshodbekmx.passwordvault.ui.edit.EditTags
import io.github.dilshodbekmx.passwordvault.ui.edit.Keyboard
import io.github.dilshodbekmx.passwordvault.ui.list.ListBody
import io.github.dilshodbekmx.passwordvault.ui.list.ListRig
import io.github.dilshodbekmx.passwordvault.ui.list.ListTags
import io.github.dilshodbekmx.passwordvault.ui.list.PasswordsScreen
import io.github.dilshodbekmx.passwordvault.ui.list.PasswordsViewModel
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * build-plan.md §12, w5-carry step 3 item 3, [device]: on Android 13 and newer one Back with the
 * keyboard up closes the keyboard and does nothing else ([KeyboardFirstOnBack]), also where the
 * screen's own `BackHandler` was turned on by the typing that brought the keyboard up, which then
 * came before the keyboard: the list's search (Back emptied the query and left the keyboard up, seen
 * on API 37 in the real app) and the edit form ("Discard?" opened). Back is the system's, sent as
 * `input keyevent KEYCODE_BACK`; the second Back does what the screen's own Back does. The keyboard
 * is read from `dumpsys input_method` ([Keyboard]).
 */
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class KeyboardFirstOnBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var list: ListRig? = null
    private var form: EditHost? = null

    @After
    fun tearDown() {
        list?.close()
        form?.stop()
    }

    @Test
    fun onTheListBackWithTheKeyboardUpClosesTheKeyboardAndKeepsTheQuery() {
        val rig = ListRig(Vault(entries = listOf(Entry(id = "gm", title = "Gmail", createdAt = 1L, updatedAt = 1L)))).also { list = it }
        rig.awaitList()
        lateinit var vm: PasswordsViewModel
        compose.setContent {
            PasswordVaultTheme(ThemeChoice.LIGHT) {
                vm = viewModel { rig.passwordsViewModel() }
                PasswordsScreen(vm, rig.nav)
            }
        }
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body !is ListBody.Loading }
        compose.onNodeWithTag(ListTags.SEARCH).performClick()
        compose.onNodeWithTag(ListTags.SEARCH).performTextInput("gma")
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body is ListBody.Results }
        assertTrue("a tap on the search field shows the keyboard", Keyboard.waitFor(shown = true))

        back()

        assertTrue("one Back closes the keyboard", Keyboard.waitFor(shown = false))
        compose.waitForIdle()
        assertEquals("and keeps the query", "gma", vm.query.text.toString())
        assertEquals("and goes nowhere", 0, rig.nav.backs)

        back() // with the keyboard closed: the query goes first (ux.md §4)
        compose.waitUntil(TIMEOUT_MS) { vm.state.value.body is ListBody.Browse }
        assertEquals("", vm.query.text.toString())
        assertEquals(0, rig.nav.backs)
    }

    @Test
    fun onTheNewAccountFormBackWithTheKeyboardUpClosesTheKeyboardAndAsksNothing() {
        val host = EditHost(compose).also { form = it }
        host.start()
        compose.onNodeWithTag(EditTags.NAME).performClick()
        compose.onNodeWithTag(EditTags.NAME).performTextInput("Gmail") // the form is changed: its BackHandler is on
        compose.onNodeWithTag(EditTags.NAME).assertIsFocused()
        assertTrue("a tap on the name shows the keyboard", Keyboard.waitFor(shown = true))

        back()

        assertTrue("one Back closes the keyboard", Keyboard.waitFor(shown = false))
        compose.waitForIdle()
        compose.onNodeWithTag(EditTags.DIALOG_DISCARD).assertDoesNotExist()
        compose.onNodeWithTag(EditTags.DIALOG_SAVE).assertDoesNotExist()
        assertEquals("the name as typed", "Gmail", host.vm!!.form.title.text.toString())
        assertEquals("the form stays", emptyList<String>(), host.nav.calls)

        back() // with the keyboard closed: the form asks what to do with what was typed (ux.md §4)
        compose.waitUntil(TIMEOUT_MS) { runCatching { compose.onNodeWithTag(EditTags.DIALOG_DISCARD).assertExists() }.isSuccess }
    }

    /** The system's Back, as a person gives it. */
    private fun back() {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
