package io.github.dilshodbekmx.passwordvault.ui.lock

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.util.SparseArray
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.fake.FakeLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 3.4, [device]: after a rotation nothing of the master-password field is in the saved
 * Bundle, and the keyboard is back on the field [V-P7] (architecture.md §5.4, §5.6; lock.md §5.8).
 *
 * The screen is hosted as `MainActivity` hosts the app: every activity of the test gets the window
 * policy and its content in `onCreate`, so the activity that a rotation makes builds the screen
 * again by itself, and its ViewModel comes from the activity's store, which the rotation keeps. The
 * rotation is a real one: the activity asks for landscape and Android recreates it.
 *
 * The saved Bundle is read by the method of the window lab's `e5_state.py`, as the window kit's
 * test does (build plan 2.8): every leaf of the Bundle the new activity receives, and its parcelled
 * bytes. The method is checked on the same Bundle with a field whose state comes from
 * `rememberTextFieldState()`, which must be found there. The keyboard is read from `dumpsys
 * input_method` ([keyboardShown]); it is shown before the rotation, or a clean result after it
 * would mean nothing.
 */
@RunWith(AndroidJUnit4::class)
class MasterPasswordRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** What each saved Bundle held: its leaves as "path = value", and its parcelled bytes. */
    private class Saved(val leaves: List<String>, val bytes: ByteArray?)

    @Test
    fun afterARotationNothingOfTheFieldIsInTheSavedBundleAndTheKeyboardIsBack() {
        val lock = FakeLockManager(initial = LockUiState.Locked(PLAIN_SCREEN))
        val application = ApplicationProvider.getApplicationContext<Application>()
        val saved = CopyOnWriteArrayList<Saved>()
        // Activities by identity only: a destroyed activity that the test still held would keep its
        // compose root alive, and Compose's idling check waits for every root it has seen, also a
        // detached one with a layout still pending, so waitForIdle() would never return.
        val created = CopyOnWriteArrayList<Int>()
        val resumed = AtomicInteger()
        val viewModel = AtomicReference<LockViewModel?>()
        val host = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is ComponentActivity) return
                created += System.identityHashCode(activity)
                if (savedInstanceState != null) {
                    val leaves = mutableListOf<String>()
                    leavesOf("savedInstanceState", savedInstanceState, leaves)
                    saved += Saved(leaves, bytesOf(savedInstanceState))
                }
                WindowPolicy.onCreate(activity)
                activity.setContent {
                    Column {
                        // The lab's leaking field E, the control of the method; never in the app (R-11).
                        OutlinedSecureTextField(state = rememberTextFieldState(), modifier = Modifier.testTag(CONTROL))
                        MasterPasswordHost(lock) { viewModel.set(it) }
                    }
                }
            }

            override fun onActivityResumed(activity: Activity) {
                val id = System.identityHashCode(activity)
                if (id in created) resumed.set(id)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(host)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                // The field takes the keyboard as soon as the screen shows.
                eventually("the field has the keyboard at the first show") { focused(LockTags.FIELD) && keyboardShown() }
                compose.onNodeWithTag(CONTROL).performTextInput(CONTROL_TEXT)
                compose.onNodeWithTag(LockTags.FIELD).performTextInput(SECRET)
                compose.waitForIdle()
                eventually("the field has the keyboard before the rotation") { focused(LockTags.FIELD) && keyboardShown() }
                val before = viewModel.get()!!
                assertEquals(SECRET, before.password.text.toString())
                assertEquals(1, created.size)

                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                eventually("the rotated activity is resumed", syncCompose = false) { created.size == 2 && resumed.get() == created.last() }
                collectOldRoots()
                compose.waitForIdle()
                var orientation = Configuration.ORIENTATION_UNDEFINED
                scenario.onActivity {
                    assertEquals("the activity on screen is the rotated one", created.last(), System.identityHashCode(it))
                    orientation = it.resources.configuration.orientation
                }
                assertEquals(Configuration.ORIENTATION_LANDSCAPE, orientation)

                // Nothing of the field in the Bundle the rotated activity received.
                assertTrue("the rotation saved a Bundle", saved.isNotEmpty())
                for (bundle in saved) {
                    val leaves = bundle.leaves.joinToString("\n")
                    assertTrue("the method finds the control's text among the leaves:\n$leaves", CONTROL_TEXT in leaves)
                    assertFalse("the master password is a leaf of the saved Bundle:\n$leaves", SECRET in leaves)
                    val bytes = bundle.bytes ?: continue
                    assertTrue("the method finds the control's text in the parcelled Bundle", contains(bytes, CONTROL_TEXT))
                    assertFalse("the master password is in the parcelled Bundle", contains(bytes, SECRET))
                }
                assertTrue("at least one Bundle could be parcelled and searched", saved.any { it.bytes != null })

                // The keyboard is back on the field, which still holds what was typed: nothing was lost.
                eventually("the field has the keyboard after the rotation") { focused(LockTags.FIELD) && keyboardShown() }
                assertSame("the rotation kept the ViewModel", before, viewModel.get())
                assertEquals(SECRET, before.password.text.toString())

                // Back to portrait before the next test starts, which would otherwise begin while the
                // screen turns; nothing waits for Compose after it.
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                eventually("back in portrait", syncCompose = false) { created.size == 3 && resumed.get() == created.last() }
                collectOldRoots()
            }
        } finally {
            application.unregisterActivityLifecycleCallbacks(host)
        }
    }

    /**
     * A rotation leaves the old activity's compose root behind, unreachable but not yet collected,
     * and Compose's idling check waits for every root it still holds: a detached one with a layout
     * pending would keep waitForIdle() waiting until a full collection. So the old root goes first.
     */
    private fun collectOldRoots() {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
        }
    }

    private fun focused(tag: String): Boolean = try {
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
    } catch (_: AssertionError) {
        false // no such node yet: the screen is being built
    }

    /**
     * Waits up to [timeoutMs] for [condition], checking every [stepMs]; fails with [what]. With
     * [syncCompose], Compose is idle at each check.
     */
    private fun eventually(what: String, timeoutMs: Long = 15_000, stepMs: Long = 250, syncCompose: Boolean = true, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (syncCompose) compose.waitForIdle()
            if (condition()) return
            if (SystemClock.uptimeMillis() > end) throw AssertionError("timed out: $what")
            SystemClock.sleep(stepMs)
        }
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
        const val CONTROL = "control"
        const val SECRET = "MasterEchoMMM"
        const val CONTROL_TEXT = "savedAlphaAAA"
    }
}
