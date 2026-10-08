package io.github.dilshodbekmx.passwordvault.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.ui.common.PinPad

/**
 * A stand-in for the PIN lock screen (ux.md §5.9), for the device tests and the screenshots of the
 * looks: the real one is the lock screens package's (build plan 4.2), on another branch until the
 * round is merged. Like the real one it is drawn with the app's theme and the shared [PinPad], which
 * is all that the looks touch of it: the logo, the name, "Enter your app PIN", the dots, the keys
 * with the fingerprint key, and the way to the master password. Its two English lines are written
 * here because their resources are the lock screens'.
 *
 * [LOOK] reads back the style's details as this screen got them from the theme.
 */
@Composable
fun StandInLockScreen(entered: Int, fingerprintKey: Boolean) {
    val look = LocalLook.current
    Scaffold(Modifier.testTag(LockTags.SCREEN)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp).testTag(LockTags.LOGO)) {
                Icon(painterResource(R.drawable.ic_app_logo), contentDescription = null, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text("Enter your app PIN", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            PinPad(entered = entered, onDigit = {}, onDelete = {}, onFingerprint = if (fingerprintKey) ({}) else null)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {}) { Text("Forgot PIN? Use master password") }
            Spacer(
                Modifier.size(1.dp).testTag(LockTags.LOOK).semantics {
                    stateDescription = "${look.pinKeys}/${if (look.fingerprintKeyTonal) "tonal" else "plain"}"
                },
            )
        }
    }
}

object LockTags {
    const val SCREEN = "standin.lock"
    const val LOGO = "standin.lock.logo"
    const val LOOK = "standin.lock.look"
}
