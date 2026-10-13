package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.dilshodbekmx.passwordvault.lock.NewMasterPasswordAsked
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route

/**
 * The open app after the lock-screen route of a new master password (lock.md F11 steps 1 and 3;
 * ux.md §5.10, the sheet's "Use my PIN": "Open the app with it, then choose a new master password"):
 * when the lock says that the PIN or fingerprint has just opened the app after "I can't remember it"
 * ([NewMasterPasswordAsked]), `Route.NewMasterPassword` opens once over the unlocked screens. Its
 * screen takes the proof that entry made, or tells the lock when it closes without a request, so
 * that proof never stands for the rest of the session (build-plan.md §12, merge of R3 item 13).
 *
 * The shell calls it while the app is unlocked, next to `LockNotices` (`UnlockedScaffold`); each
 * entry into the unlocked state looks once.
 */
@Composable
fun NewMasterPasswordAfterUnlock(nav: Navigator) {
    LaunchedEffect(nav) {
        if (NewMasterPasswordAsked.take()) nav.go(Route.NewMasterPassword)
    }
}
