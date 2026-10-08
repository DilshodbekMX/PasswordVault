package io.github.dilshodbekmx.passwordvault.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [StoredTexts] on a device (architecture.md §5.4; build plan 12, w3B-carry item 3): the
 * repository's "(from backup)" is `data_from_backup` of `strings_data.xml`, resolved in the
 * language the screens show at the moment of the change, not in the application context's own
 * language. The debug build's pseudo-locale `en-XA` (i18n.md §1.1) shows which one was used: the
 * phone is not in it, so only the screens' locale can give its text.
 */
@RunWith(AndroidJUnit4::class)
class StoredTextsTest {
    private val app: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun fromBackupIsTheResourceInTheLanguageOfTheScreens() {
        var screens = Locale.ENGLISH
        val texts = StoredTexts(app) { screens }
        assertEquals("Gmail (from backup)", texts.fromBackup("Gmail"))

        screens = Locale.forLanguageTag("en-XA")
        val pseudo = texts.fromBackup("Gmail")
        assertTrue("the name is kept: $pseudo", pseudo.contains("Gmail"))
        assertFalse("the words come from the screens' language: $pseudo", pseudo.contains("from backup"))
        assertEquals(StoredTexts.resourcesIn(app, screens).getString(R.string.data_from_backup, "Gmail"), pseudo)

        // Back to English: the resources of the earlier locale are not kept for the new one.
        screens = Locale.ENGLISH
        assertEquals("Gmail (from backup)", texts.fromBackup("Gmail"))
    }
}
