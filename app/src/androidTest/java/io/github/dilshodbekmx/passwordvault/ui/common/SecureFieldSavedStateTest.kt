package io.github.dilshodbekmx.passwordvault.ui.common

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Parcel
import android.util.SparseArray
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.8, [device]: a `VaultSecureField` leaves nothing in the saved bundle, checked by the
 * method of the window lab's `e5_state.py` (Experiment 5): type into the fields, let the activity
 * be recreated as a rotation does, and read every leaf of the Bundle that `onSaveInstanceState`
 * wrote; here also its parcelled bytes, in UTF-16 and UTF-8, which reach into the `Parcelable`s.
 *
 * The method is checked on the same Bundle: a field whose state comes from
 * `rememberTextFieldState()`, which the lab found in the Bundle twice (text and undo history),
 * must be found by both searches, or a clean result would mean nothing. The `VaultSecureField`'s
 * text is held by a ViewModel (architecture.md §5.6) and is shown with the show/hide button when
 * the state is saved; a `VaultTextField` is checked the same way.
 */
@RunWith(AndroidJUnit4::class)
class SecureFieldSavedStateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The screen's ViewModel. */
    class Fields : ViewModel() {
        val secret = TextFieldState()
        val plain = TextFieldState()
    }

    /** What each saved Bundle held: its leaves as "path = value", and its parcelled bytes. */
    private class Saved(val leaves: List<String>, val bytes: ByteArray?)

    @Test
    fun aVaultSecureFieldLeavesNothingInTheSavedBundle() {
        val saved = CopyOnWriteArrayList<Saved>()
        val application = compose.activity.application
        val watcher = object : Application.ActivityLifecycleCallbacks {
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                val leaves = mutableListOf<String>()
                leavesOf("outState", outState, leaves)
                saved += Saved(leaves, bytesOf(outState))
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(watcher)
        try {
            compose.setContent {
                WindowPolicy.Root {
                    Column {
                        val fields = viewModel { Fields() }
                        VaultSecureField(state = fields.secret, modifier = Modifier.testTag(SECURE))
                        VaultTextField(state = fields.plain, modifier = Modifier.testTag(PLAIN))
                        // The lab's leaking field E, the control of the method; never in the app (R-11).
                        OutlinedSecureTextField(state = rememberTextFieldState(), modifier = Modifier.testTag(CONTROL))
                    }
                }
            }
            compose.onNodeWithTag(SECURE).performTextInput(SECRET)
            compose.onNodeWithTag(PLAIN).performTextInput(PLAIN_TEXT)
            compose.onNodeWithTag(CONTROL).performTextInput(CONTROL_TEXT)
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_show_password)).performClick()
            compose.waitForIdle()

            compose.activityRule.scenario.recreate()
        } finally {
            application.unregisterActivityLifecycleCallbacks(watcher)
        }

        assertTrue("the recreation saved a Bundle", saved.isNotEmpty())
        for (bundle in saved) {
            val leaves = bundle.leaves.joinToString("\n")
            assertTrue("the method finds the control's text among the leaves:\n$leaves", CONTROL_TEXT in leaves)
            for (text in listOf(SECRET, PLAIN_TEXT)) {
                assertFalse("\"$text\" is a leaf of the saved Bundle:\n$leaves", text in leaves)
            }
            val bytes = bundle.bytes ?: continue
            assertTrue("the method finds the control's text in the parcelled Bundle", contains(bytes, CONTROL_TEXT))
            for (text in listOf(SECRET, PLAIN_TEXT)) {
                assertFalse("\"$text\" is in the parcelled Bundle", contains(bytes, text))
            }
        }
        assertTrue("at least one Bundle could be parcelled and searched", saved.any { it.bytes != null })
    }

    /** Every leaf of a saved-state value, as the lab's `dumpValue` logged them. */
    @Suppress("DEPRECATION")
    private fun leavesOf(path: String, value: Any?, out: MutableList<String>) {
        when (value) {
            is Bundle -> for (key in value.keySet()) leavesOf("$path/$key", value.get(key), out)
            is List<*> -> value.forEachIndexed { i, item -> leavesOf("$path[$i]", item, out) }
            is Array<*> -> value.forEachIndexed { i, item -> leavesOf("$path[$i]", item, out) }
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
        const val SECURE = "secure"
        const val PLAIN = "plain"
        const val CONTROL = "control"
        const val SECRET = "SecretEchoEEE"
        const val PLAIN_TEXT = "plainBravoBBB"
        const val CONTROL_TEXT = "savedAlphaAAA"
    }
}
