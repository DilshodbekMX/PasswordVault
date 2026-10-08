package io.github.dilshodbekmx.passwordvault.files

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.lock.MpPurpose
import io.github.dilshodbekmx.passwordvault.lock.MpResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.fail

/**
 * The app's own vault and lock, as the running app has them: until build plan 4.1 the lock is the
 * master-password gate of 3.2. These tests make the vault on a phone that has none, with
 * [PASSWORD]; a vault that another test made with another password cannot be opened here.
 */
internal object AppVault {
    /** The master password of the vault these tests make. */
    private const val PASSWORD = "files plumbing 5.1, test vault"

    val graph: AppGraph get() = (DocumentsRig.app.applicationContext as PasswordVaultApp).graph

    /** The lock's state now. */
    val state: LockUiState get() = graph.lock.state.value

    /**
     * Opens the vault: makes it when the phone has none, otherwise types [PASSWORD] on the lock
     * screen. The app must be in front, where a visit of the lock screen is open (lock.md §6.3).
     */
    fun unlock() = runBlocking {
        val lock = graph.lock
        withTimeout(STARTUP_MS) { lock.state.first { it !is LockUiState.Starting } }
        when (val state = lock.state.value) {
            is LockUiState.Unlocked -> return@runBlocking
            is LockUiState.NoVault -> {
                val made = lock.createVault(PASSWORD.toCharArray(), PASSWORD.toCharArray(), null)
                if (made !is CreateResult.Created) fail("the test vault could not be made: $made")
            }
            is LockUiState.Locked -> {
                // A visit opens when the lock screen is shown while the app is in front; the gate may
                // still be showing it.
                var result: MpResult = MpResult.TooLate
                val end = System.currentTimeMillis() + STARTUP_MS
                while (result is MpResult.TooLate && System.currentTimeMillis() < end) {
                    result = lock.submitMasterPassword(PASSWORD.toCharArray(), MpPurpose.UNLOCK)
                    if (result is MpResult.TooLate) Thread.sleep(200)
                }
                when (result) {
                    is MpResult.Right -> Unit
                    MpResult.Wrong -> fail("a vault made with another master password is on this phone; run on a fresh install")
                    else -> fail("the test vault did not open: $result")
                }
            }
            is LockUiState.Unreadable -> fail("the app's vault cannot be read: ${state.why}")
            LockUiState.Starting -> fail("the lock did not decide")
        }
        withTimeout(STARTUP_MS) { lock.state.first { it is LockUiState.Unlocked } }
    }

    /** "Lock now" (Settings), as a user would lock, and waits for the lock screen. */
    fun lockNow() {
        DocumentsRig.instrumentation.runOnMainSync { graph.session.lockNow() }
        DocumentsRig.await("the lock") { state is LockUiState.Locked }
    }

    private const val STARTUP_MS = 60_000L
}
