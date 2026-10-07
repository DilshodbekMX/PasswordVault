package io.github.dilshodbekmx.passwordvault

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import io.github.dilshodbekmx.passwordvault.files.DocumentLaunchers
import io.github.dilshodbekmx.passwordvault.lock.BiometricHost
import io.github.dilshodbekmx.passwordvault.ui.shell.AppRoot
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy

/**
 * The one activity (architecture.md §3.6). It calls the hooks of the table in §3.6, in that order,
 * and holds nothing else: the lock, the session and the vault live in [AppGraph], so a rotation or
 * a language change, which recreate this activity (no `configChanges`), neither locks nor loses
 * input (lock.md §11). An `AppCompatActivity`, for the per-app language and `BiometricPrompt`
 * (i18n.md §1.4.1, lock.md §9.1).
 */
class MainActivity : AppCompatActivity() {
    private val graph: AppGraph get() = (application as PasswordVaultApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowPolicy.onCreate(this)
        BiometricHost.onCreate(this, graph)
        DocumentLaunchers.onCreate(this, graph)
        setContent { AppRoot(graph) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        graph.session.touched()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        graph.session.touched()
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        graph.clipboard.appCameToFront()
    }
}
