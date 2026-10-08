package io.github.dilshodbekmx.passwordvault.lock

import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import javax.crypto.Cipher

/**
 * The activity hook that builds the one `BiometricPrompt`, every time in `onCreate`, also after a
 * rotation or a language change (lock.md §9.2 rule 1; architecture.md §3.6), and routes its
 * callbacks to the process-wide lock (build plan 4.1).
 *
 * Only the last prompt constructed on an activity receives callbacks, and a prompt built lazily
 * delivered its result to a destroyed activity after a rotation [L-bio finding a, case 10]; so the
 * prompt is made here, before anything can ask for it, with the classic constructor
 * `BiometricPrompt(FragmentActivity, Executor, AuthenticationCallback)` and nothing else of the
 * library (lock.md §9.1, build check R-4). It hands the prompt to the lock manager's
 * [PromptLauncher], which holds what a prompt is for and who waits for it (rule 2), and tells it when
 * the activity is resumed or its window has the focus back: the moments at which a prompt may open
 * by itself (rule 7). The activity keeps nothing. When it is destroyed, the launcher only forgets its
 * prompt; nothing is cancelled, refunded or cleared (lock.md §11).
 *
 * **Android 6 to 8.1 in an optimised build.** There the library goes through `FingerprintManager`,
 * which the android.jar of compileSdk 37 no longer has. R8 then cannot see that the four methods of
 * the library's own `FingerprintManager.AuthenticationCallback` (`FingerprintManagerCompat$Api23Impl$1`)
 * override the framework's, and removes them: in a staging or release build no result of the prompt
 * reaches this class on those phones, and the prompt stays open. The app's R8 rules must keep them
 * (build plan 4.1 asked for the rule; check E6 on API 24 with the staging build shows it).
 */
object BiometricHost {
    fun onCreate(activity: FragmentActivity, graph: AppGraph) {
        val launcher = (graph.lock as? DefaultLockManager)?.prompts ?: return
        val window = ActivityPrompt(activity, launcher)
        launcher.attach(window)
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = launcher.ready(window)

                override fun onDestroy(owner: LifecycleOwner) = launcher.detach(window, forGood = !activity.isChangingConfigurations)
            },
        )
        // A dialog, a sheet or the prompt itself takes the focus from the activity's window; when it
        // closes, the focus comes back without a resume.
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) launcher.ready(window)
        }
    }

    /** The one prompt of [activity]. */
    private class ActivityPrompt(private val activity: FragmentActivity, launcher: PromptLauncher) : PromptWindow {
        private val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                // Never a cipher kept by the app: the one of the result, or none (lock.md §9.2 rule 6).
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    launcher.succeeded(result.cryptoObject?.cipher)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = launcher.failed(errorCode)

                // onAuthenticationFailed, a finger that is not recognised: the prompt stays open and says
                // so itself; the system counts (lock.md §9.3).
            },
        )

        override val resumed: Boolean get() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

        override val focused: Boolean get() = activity.hasWindowFocus()

        /**
         * lock.md §9.2 rule 5: Class 3 only, a title and a negative button from the table, and
         * `setConfirmationRequired` left at its default (`true`), which asks a Class 3 face unlock for
         * an explicit tap and changes nothing for a fingerprint. The texts are the activity's, so they
         * are in the app's language.
         */
        override fun show(cipher: Cipher, use: PromptUse, pinUsable: Boolean) {
            val title = when (use) {
                PromptUse.UNLOCK -> R.string.lock_prompt_unlock_title
                PromptUse.TURN_ON -> R.string.lock_prompt_turn_on_title
                PromptUse.KEEP_AFTER_CHANGE -> R.string.lock_prompt_keep_title
                PromptUse.CONFIRM_REPLACEMENT -> R.string.lock_prompt_confirm_title
            }
            val negative = when (use) {
                PromptUse.UNLOCK -> if (pinUsable) R.string.lock_mp_use_pin else R.string.lock_use_master_password
                PromptUse.TURN_ON, PromptUse.CONFIRM_REPLACEMENT -> R.string.common_cancel
                PromptUse.KEEP_AFTER_CHANGE -> R.string.common_not_now
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(title))
                .setNegativeButtonText(activity.getString(negative))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }

        override fun dismiss() = prompt.cancelAuthentication()
    }
}

/** The app's main thread, for [PromptLauncher]. */
internal object AndroidMain : MainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override val isCurrent: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    override fun post(action: () -> Unit) {
        handler.post(action)
    }
}
