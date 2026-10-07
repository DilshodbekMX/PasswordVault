package io.github.dilshodbekmx.passwordvault.ui.edit

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.nav.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.6, [device]: the add and edit screen drawn and used on a phone (ux.md §5.14, §5.15),
 * beside the ViewModel tests of the JVM: an account typed and saved opens its detail, the generator
 * sheet puts its password into the field, "Add field" adds a field, Back with changes asks
 * "Save changes?". The form is shown as `MainActivity` shows it ([EditHost]).
 */
@RunWith(AndroidJUnit4::class)
class EditScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: EditHost

    @After
    fun tearDown() {
        if (::host.isInitialized) host.stop()
    }

    @Test
    fun anAccountTypedAndSavedOpensItsDetail() {
        host = EditHost(compose).also { it.start() }
        compose.onNodeWithText(compose.activity.getString(R.string.edit_title_new)).assertIsDisplayed()
        compose.onNodeWithTag(EditTags.NAME).performTextInput("Click")
        compose.onNodeWithTag(EditTags.USERNAME).performTextInput("+998901234567")
        compose.onNodeWithTag(EditTags.PASSWORD).performTextInput("Toshkent-2020-qiyin")
        compose.onNodeWithTag(EditTags.website(0)).performScrollTo().performTextInput("click.uz")
        compose.onNodeWithTag(EditTags.NOTES).performScrollTo().performTextInput("PIN on the card")
        // The meter shows once zxcvbn's word lists are read, which takes a while the first time on a slow phone.
        compose.waitUntil(METER_MS) { compose.onAllNodesWithTag(EditTags.STRENGTH).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(EditTags.STRENGTH).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(EditTags.SAVE).performClick()
        compose.waitUntil(WAIT_MS) { host.nav.calls.isNotEmpty() }
        val saved = compose.runOnUiThread { host.repository.stored.entries.single() }
        assertEquals(listOf("replace ${Route.Entry(saved.id)}"), host.nav.calls)
        assertEquals("Click", saved.title)
        assertEquals("+998901234567", saved.username)
        assertEquals("Toshkent-2020-qiyin", saved.password)
        assertEquals(listOf("https://click.uz"), saved.urls)
        assertEquals("PIN on the card", saved.notes)
        assertNull(compose.runOnUiThread { host.repository.stored.draft })
    }

    @Test
    fun theGeneratorSheetPutsItsPasswordIntoTheField() {
        host = EditHost(compose).also { it.start() }
        compose.onNodeWithTag(EditTags.GENERATE_BUTTON).performClick()
        compose.waitUntil(WAIT_MS) { host.vm!!.generator.password() != null }
        compose.onNodeWithTag(GenTags.PREVIEW).assertIsDisplayed()
        val generated = host.vm!!.generator.password()
        compose.onNodeWithTag(GenTags.USE).performScrollTo().performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag(GenTags.USE).fetchSemanticsNodes().isEmpty() }
        assertEquals(generated, compose.runOnUiThread { host.vm!!.form.password.text.toString() })
        assertEquals("the password is held as the draft at once", generated, compose.runOnUiThread { host.repository.heldDraft?.entry?.password })
    }

    @Test
    fun addFieldAddsAFieldToTheForm() {
        host = EditHost(compose).also { it.start() }
        compose.onNodeWithTag(EditTags.ADD_FIELD).performScrollTo().performClick()
        compose.onNodeWithTag(EditTags.chip(FieldLabel.PIN)).performClick()
        compose.onNodeWithTag(EditTags.FIELD_VALUE).performTextInput("4321")
        compose.onNodeWithTag(EditTags.FIELD_ADD).performClick()
        compose.waitUntil(WAIT_MS) { compose.runOnUiThread { host.vm!!.form.fields.isNotEmpty() } }
        val row = compose.runOnUiThread { host.vm!!.form.fields.single() }
        assertEquals(compose.activity.getString(R.string.edit_field_pin), row.name)
        assertTrue(row.secret)
        assertEquals("4321", row.value.text.toString())
        compose.onNodeWithTag(EditTags.field(0)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backWithChangesAsksAndDiscardLeavesNothing() {
        val gmail = Entry(id = GMAIL, title = "Gmail", username = "aziz", fields = listOf(CustomField("PIN code", "1234", true)), createdAt = 1L, updatedAt = 1L)
        host = EditHost(compose, Vault(entries = listOf(gmail)), GMAIL).also { it.start() }
        compose.onNodeWithText(compose.activity.getString(R.string.edit_title_edit)).assertIsDisplayed()
        compose.onNodeWithTag(EditTags.USERNAME).performTextInput("bek")
        compose.waitUntil(WAIT_MS) { compose.runOnUiThread { host.repository.heldDraft } != null }
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onNodeWithTag(EditTags.DIALOG_DISCARD).assertIsDisplayed().performClick()
        compose.waitUntil(WAIT_MS) { host.nav.calls.isNotEmpty() }
        assertEquals(listOf("back"), host.nav.calls)
        compose.runOnUiThread {
            assertNull(host.repository.heldDraft)
            assertNull(host.repository.stored.draft)
            assertEquals("aziz", host.repository.stored.entries.single().username)
        }
    }

    private companion object {
        const val GMAIL = "11111111-1111-4111-8111-111111111111"
        const val WAIT_MS = 10_000L
        const val METER_MS = 30_000L
    }
}
