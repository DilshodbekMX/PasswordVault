package io.github.dilshodbekmx.passwordvault.files

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.1: the intents the launchers open (formats.md §2 rules 2 and 4: `CreateDocument`
 * with the suggested name and the type that matches it; ux.md §5.21: an import opens any type) and
 * what they make of the picker's answer, and the registry keys, which must never change.
 */
@RunWith(AndroidJUnit4::class)
class PickerContractsTest {
    private val app = DocumentsRig.app

    @Test
    fun saveAsSuggestsTheNameWithItsType() {
        val intent = CreateDocumentContract.createIntent(app, CreateRequest("PasswordVault-backup-2026-10-08.pvault", DocumentTypes.BACKUP))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(setOf(Intent.CATEGORY_OPENABLE), intent.categories)
        assertEquals(DocumentTypes.BACKUP, intent.type)
        assertEquals("PasswordVault-backup-2026-10-08.pvault", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertNull("no data: the picker chooses the place", intent.data)
    }

    @Test
    fun openTakesOneTypeOrSeveral() {
        val any = OpenDocumentContract.createIntent(app, DocumentTypes.ANY)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, any.action)
        assertEquals(setOf(Intent.CATEGORY_OPENABLE), any.categories)
        assertEquals("*/*", any.type)
        assertFalse(any.hasExtra(Intent.EXTRA_MIME_TYPES))

        val two = OpenDocumentContract.createIntent(app, listOf(DocumentTypes.CSV, DocumentTypes.JSON))
        assertEquals("*/*", two.type)
        assertArrayEquals(arrayOf(DocumentTypes.CSV, DocumentTypes.JSON), two.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test
    fun theAnswerIsTheDocumentOrNothing() {
        val uri = Uri.parse("content://com.android.providers.downloads.documents/document/14")
        for (contract in listOf(CreateDocumentContract, OpenDocumentContract)) {
            assertEquals(uri, contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
            assertNull("closed", contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
            assertNull("closed", contract.parseResult(Activity.RESULT_CANCELED, null))
            assertNull("no document", contract.parseResult(Activity.RESULT_OK, null))
            assertNull("no document", contract.parseResult(Activity.RESULT_OK, Intent()))
        }
    }

    @Test
    fun theRegistryKeysNeverChange() {
        // A result that reaches a new process finds its callback by these keys (DocumentLaunchers).
        assertEquals("io.github.dilshodbekmx.passwordvault.files.create", DocumentLaunchers.CREATE_KEY)
        assertEquals("io.github.dilshodbekmx.passwordvault.files.open", DocumentLaunchers.OPEN_KEY)
    }
}
