package io.github.dilshodbekmx.passwordvault.ui.entry

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Parcel
import android.util.SparseArray
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.NOTES
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.OLD_PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PHONE
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PIN
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.PREVIOUS_0
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.USERNAME
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.theme.PasswordVaultTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.7, [device]: nothing of an account reaches saved state from its screen
 * (architecture.md §5.6: saved state holds route keys, scroll positions and booleans; never a
 * password, a username, a note or a field value). The method is the window lab's Experiment 5, as
 * `SecureFieldSavedStateTest` uses it: let the activity be recreated and read every leaf of the
 * Bundle the new activity receives, and its parcelled bytes in UTF-16 and UTF-8. A word saved on
 * purpose beside the screen must be found by both searches, or a clean result would mean nothing.
 *
 * The screen is brought to its fullest first: passwords, a field and a previous password shown,
 * and a word of the notes selected. That last one is why the notes hold their selection in a
 * `SelectionState` that is only remembered: the second test shows what `SelectionContainer`'s
 * plain form does with a selection, on the same kind of Bundle.
 */
@RunWith(AndroidJUnit4::class)
class EntrySavedStateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** What each saved Bundle held: its leaves as "path = value", and its parcelled bytes. */
    private class Saved(val leaves: List<String>, val bytes: ByteArray?)

    private val toolbar = RecordingToolbar()

    /**
     * Runs [steps], lets the activity be recreated as a rotation does, and gives every Bundle that a
     * new activity received. `onActivitySaveInstanceState` would see the Bundle too early, before
     * `ComponentActivity` adds the saved-state registry, where Compose's saved state is.
     */
    private fun savedAfter(steps: () -> Unit): List<Saved> {
        val saved = CopyOnWriteArrayList<Saved>()
        val application = compose.activity.application
        val watcher = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (savedInstanceState == null) return
                val leaves = mutableListOf<String>()
                leavesOf("savedInstanceState", savedInstanceState, leaves)
                saved += Saved(leaves, bytesOf(savedInstanceState))
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(watcher)
        try {
            steps()
            compose.activityRule.scenario.recreate()
        } finally {
            application.unregisterActivityLifecycleCallbacks(watcher)
        }
        assertTrue("the recreation saved a Bundle", saved.isNotEmpty())
        assertTrue("at least one Bundle could be parcelled and searched", saved.any { it.bytes != null })
        return saved
    }

    @Test
    fun nothingOfTheAccountIsInTheSavedBundle() {
        val rig = EntryRig(Vault(entries = listOf(gmail(changePending = true))))
        val clips = RecordingClipboard(compose.activity)
        lateinit var vm: EntryViewModel
        val saved = savedAfter {
            compose.setContent {
                CompositionLocalProvider(LocalClipboard provides clips, LocalTextContextMenuToolbarProvider provides toolbar) {
                    PasswordVaultTheme(ThemeChoice.LIGHT) {
                        // The control of the method: a word saved on purpose; never in the app (R-11).
                        rememberSaveable { CONTROL_WORD }
                        vm = viewModel { rig.entryViewModel() }
                        EntryScreen(vm, rig.nav)
                    }
                }
            }
            fun details() = (vm.state.value as EntryUiState.Shown).details
            fun scrollTo(key: String) {
                compose.onNodeWithTag(EntryTags.CONTENT).performScrollToKey(key)
                compose.waitForIdle()
            }
            compose.waitUntil(TIMEOUT_MS) { vm.state.value is EntryUiState.Shown }

            // Everything open and shown.
            scrollTo("previous")
            compose.onNodeWithTag(EntryTags.PREVIOUS).performClick()
            compose.waitUntil(TIMEOUT_MS) { details().previous.size == 2 }
            for ((key, value) in listOf(
                "old-password" to EntryValue.OldPassword,
                "password" to EntryValue.Password,
                "field.0" to EntryValue.Field(0),
                "previous.0" to EntryValue.Previous(0),
            )) {
                scrollTo(key)
                compose.onNodeWithTag(EntryTags.show(value)).performClick()
                compose.waitForIdle()
            }
            assertTrue("every secret is in the screen's state", details().passwords.all { it.shown != null } && details().fields[0].shown == PIN)
            assertEquals(PREVIOUS_0, details().previous[0].shown)

            // A word of the notes selected: the selection's toolbar is up when the state is saved.
            scrollTo("notes")
            compose.onNodeWithTag(EntryTags.value(EntryValue.Notes), useUnmergedTree = true)
                .performTouchInput { longClick(Offset(left + width * 0.08f, top + 14f)) }
            compose.waitUntil(TIMEOUT_MS) { toolbar.shown.isNotEmpty() }
            compose.waitForIdle()
        }

        val account = listOf(
            PASSWORD, OLD_PASSWORD, PIN, PREVIOUS_0, USERNAME, PHONE, NOTES, "Samarqand", "Registon", "Gmail", "PIN code",
            "mail.google.com", "com.google.android.gm",
        )
        for (bundle in saved) {
            val leaves = bundle.leaves.joinToString("\n")
            assertTrue("the method finds the control's word among the leaves:\n$leaves", CONTROL_WORD in leaves)
            for (text in account) assertFalse("\"$text\" is a leaf of the saved Bundle:\n$leaves", text in leaves)
            val bytes = bundle.bytes ?: continue
            assertTrue("the method finds the control's word in the parcelled Bundle", contains(bytes, CONTROL_WORD))
            for (text in account) assertFalse("\"$text\" is in the parcelled Bundle", contains(bytes, text))
        }
    }

    /**
     * Why the notes do not use `SelectionContainer { … }` as it comes: that form keeps its
     * `SelectionState` with `rememberSaveable`, and the state's saver writes the selected text
     * (Compose foundation 1.12.1). In the notes of an account that would be account data in saved
     * state. If this test fails after a library update, the plain form no longer does it, and
     * `NotesRow` may go back to it.
     */
    @Test
    fun aPlainSelectionContainerSavesTheSelectedText() {
        val saved = savedAfter {
            compose.setContent {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                    PasswordVaultTheme(ThemeChoice.LIGHT) {
                        SelectionContainer {
                            Text(text = PLAIN_TEXT, modifier = Modifier.padding(24.dp).testTag(PLAIN))
                        }
                    }
                }
            }
            compose.onNodeWithTag(PLAIN).performTouchInput { longClick(Offset(left + 60f, centerY)) }
            compose.waitUntil(TIMEOUT_MS) { toolbar.shown.isNotEmpty() }
            compose.waitForIdle()
        }
        assertTrue(
            "the selected word is in the saved Bundle:\n" + saved.joinToString("\n---\n") { it.leaves.joinToString("\n") },
            saved.any { bundle -> bundle.leaves.any { PLAIN_WORD in it } },
        )
        assertTrue("and in its parcelled bytes", saved.any { bundle -> bundle.bytes?.let { contains(it, PLAIN_WORD) } == true })
    }

    /** Every leaf of a saved-state value, as the lab's `dumpValue` logged them. */
    @Suppress("DEPRECATION")
    private fun leavesOf(path: String, value: Any?, out: MutableList<String>) {
        when (value) {
            is Bundle -> for (key in value.keySet()) leavesOf("$path/$key", value.get(key), out)
            is List<*> -> value.forEachIndexed { i, item -> leavesOf("$path[$i]", item, out) }
            is Array<*> -> value.forEachIndexed { i, item -> leavesOf("$path[$i]", item, out) }
            is Map<*, *> -> for ((key, item) in value) leavesOf("$path/$key", item, out)
            is SparseArray<*> -> for (i in 0 until value.size()) leavesOf("$path{${value.keyAt(i)}}", value.valueAt(i), out)
            is CharArray -> out += "$path = ${String(value)}"
            is IntArray -> out += "$path = ${value.contentToString()}"
            is LongArray -> out += "$path = ${value.contentToString()}"
            else -> out += "$path = $value"
        }
    }

    /** The Bundle as a Parcel's bytes, or null when it holds something that cannot be flattened. */
    private fun bytesOf(bundle: Bundle): ByteArray? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.marshall()
        } catch (_: RuntimeException) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun contains(bytes: ByteArray, text: String): Boolean =
        listOf(Charsets.UTF_16LE, Charsets.UTF_8).any { charset -> indexOf(bytes, text.toByteArray(charset)) >= 0 }

    private fun indexOf(bytes: ByteArray, part: ByteArray): Int {
        outer@ for (i in 0..bytes.size - part.size) {
            for (j in part.indices) if (bytes[i + j] != part[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val CONTROL_WORD = "savedAlphaAAA"
        const val PLAIN = "plain"
        const val PLAIN_WORD = "selectedBravoBBB"
        const val PLAIN_TEXT = "$PLAIN_WORD charlie delta"
    }
}
