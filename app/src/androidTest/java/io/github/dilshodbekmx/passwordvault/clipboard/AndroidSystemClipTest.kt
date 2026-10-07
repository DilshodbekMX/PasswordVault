package io.github.dilshodbekmx.passwordvault.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.8, on the phone's own clipboard: the clip [AndroidSystemClip] makes is the one of
 * ux.md §6 item 1 (an empty label, the sensitive flag, the token), its check tells our clip from
 * another and from none without reading any content, and its clear leaves nothing ([V-P2], [V-P10]).
 */
@RunWith(AndroidJUnit4::class)
class AndroidSystemClipTest {
    @get:Rule
    val activity = ActivityScenarioRule(ComponentActivity::class.java)

    private val clip = AndroidSystemClip(ClipDevice.manager)

    @Before
    fun inFront() {
        var current: ComponentActivity? = null
        activity.scenario.onActivity { current = it }
        ClipDevice.awaitFocus(current!!)
    }

    @After
    fun emptyTheClipboard() = ClipDevice.empty()

    @Test
    fun aSecretIsCopiedWithTheFlagTheTokenAndAnEmptyLabel() {
        ClipDevice.onMain { clip.set("s3cret Пароль oʻzbek", sensitive = true, token = TOKEN) }
        val description = ClipDevice.description()
        assertNotNull(description)
        assertEquals("the label is empty", "", description!!.label?.toString().orEmpty())
        assertEquals(ClipDescription.MIMETYPE_TEXT_PLAIN, description.getMimeType(0))
        assertEquals(true, ClipDevice.sensitive())
        assertEquals(TOKEN, ClipDevice.token())
        assertEquals("the flag and the token, nothing else", setOf(AndroidSystemClip.EXTRA_IS_SENSITIVE, AndroidSystemClip.EXTRA_TOKEN), description.extras.keySet())
        assertEquals("s3cret Пароль oʻzbek", ClipDevice.text())
    }

    @Test
    fun aUsernameIsCopiedWithTheFlagSetToFalse() {
        ClipDevice.onMain { clip.set("aziz", sensitive = false, token = TOKEN) }
        assertEquals("false, so that Android 13 and later show it in their preview", false, ClipDevice.sensitive())
        assertEquals(TOKEN, ClipDevice.token())
    }

    @SdkSuppress(minSdkVersion = 33)
    @Test
    fun theFlagsNameIsThePlatforms() {
        assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, AndroidSystemClip.EXTRA_IS_SENSITIVE)
    }

    @Test
    fun theCheckTellsOursFromAnotherAndFromNone() {
        ClipDevice.onMain { clip.set("s3cret", sensitive = true, token = TOKEN) }
        assertEquals(ClipHolder.OURS, ClipDevice.onMain { clip.holder(TOKEN) })
        assertEquals("an earlier copy's token is not this clip's", ClipHolder.OTHER, ClipDevice.onMain { clip.holder(OTHER_TOKEN) })

        ClipDevice.otherCopy("copied elsewhere")
        assertEquals(ClipHolder.OTHER, ClipDevice.onMain { clip.holder(TOKEN) })

        // Another app's extras, of every kind a description can carry, are read without harm.
        ClipDevice.onMain {
            val odd = ClipData.newPlainText("odd", "x")
            odd.description.extras = PersistableBundle().apply {
                putInt(AndroidSystemClip.EXTRA_TOKEN, 7) // our name, not a text
                putPersistableBundle("nested", PersistableBundle())
            }
            ClipDevice.manager.setPrimaryClip(odd)
        }
        assertEquals(ClipHolder.OTHER, ClipDevice.onMain { clip.holder(TOKEN) })
    }

    @Test
    fun theClearLeavesNothing() {
        ClipDevice.onMain { clip.set("s3cret", sensitive = true, token = TOKEN) }
        ClipDevice.onMain { clip.clear() }
        assertTrue(ClipDevice.isEmpty())
        assertNull("no token is left", ClipDevice.token())
        if (Build.VERSION.SDK_INT >= 28) {
            assertFalse(ClipDevice.onMain { ClipDevice.manager.hasPrimaryClip() })
            assertEquals("no description: nothing can be said", ClipHolder.UNKNOWN, ClipDevice.onMain { clip.holder(TOKEN) })
        } else {
            // Android 7 and 8 have no clearPrimaryClip: an empty clip takes the secret's place.
            assertEquals("", ClipDevice.text())
            assertEquals(ClipHolder.OTHER, ClipDevice.onMain { clip.holder(TOKEN) })
        }
    }

    @Test
    fun theClearOfAndroid7And8WorksOnThisPhoneToo() {
        val old = AndroidSystemClip(ClipDevice.manager, emptyClipClears = true)
        ClipDevice.onMain { old.set("s3cret", sensitive = true, token = TOKEN) }
        ClipDevice.onMain { old.clear() }
        assertEquals("an empty clip", "", ClipDevice.text())
        assertEquals("", ClipDevice.description()?.label?.toString().orEmpty())
        assertNull(ClipDevice.token())
    }

    @Test
    fun onlyAndroid10AndLaterNeedTheFocusToRead() {
        assertEquals(Build.VERSION.SDK_INT >= 29, clip.needsFocusToRead)
    }

    private companion object {
        const val TOKEN = "00112233445566778899aabbccddeeff"
        const val OTHER_TOKEN = "ffeeddccbbaa99887766554433221100"
    }
}
