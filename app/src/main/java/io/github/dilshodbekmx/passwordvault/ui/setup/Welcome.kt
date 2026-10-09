package io.github.dilshodbekmx.passwordvault.ui.setup

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import io.github.dilshodbekmx.passwordvault.ui.common.AppLogo

/**
 * Welcome, the first screen when the phone has no vault (ux.md §5.1; lock.md F1 step 1, §13.1 row
 * 1): the app's logo (`AppLogo`, 72 dp), "Your passwords, safe on this phone" and three short
 * lines, then "Start", one "Open my previous passwords (locked on …)" for each vault put aside,
 * newest first (lock.md F17 step 4: the lock answers `LockIntent.OpenAside` with that vault's own
 * lock screen), and "Restore from a backup file". The globe button at the top opens the language
 * dialog. Back leaves the app.
 */
@Composable
fun WelcomeScreen(
    aside: List<AsideVault>,
    onStart: () -> Unit,
    onOpenAside: (vaultId: String) -> Unit,
    onRestore: () -> Unit,
    onLanguage: () -> Unit,
) {
    val formatters = rememberFormatters()
    SetupPage(
        title = stringResource(R.string.setup_welcome_title),
        top = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                AppLogo(Modifier.testTag(SetupTags.LOGO), size = WELCOME_LOGO)
                IconButton(onClick = onLanguage, modifier = Modifier.testTag(SetupTags.LANGUAGE)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_setup_language),
                        contentDescription = stringResource(R.string.setup_welcome_language),
                    )
                }
            }
        },
    ) {
        Point(R.drawable.ic_setup_phone, R.string.setup_welcome_point_offline)
        Point(R.drawable.ic_setup_lock, R.string.setup_welcome_point_lock)
        Point(R.drawable.ic_common_copy, R.string.setup_welcome_point_copy)
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().testTag(SetupTags.START)) {
            Text(stringResource(R.string.setup_welcome_start))
        }
        for (vault in aside) {
            OutlinedButton(
                onClick = { onOpenAside(vault.vaultId) },
                modifier = Modifier.fillMaxWidth().testTag(SetupTags.aside(vault.vaultId)),
            ) {
                Text(stringResource(R.string.setup_welcome_open_aside, formatters.date(vault.putAsideAtMs)))
            }
        }
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().testTag(SetupTags.RESTORE)) {
            Text(stringResource(R.string.setup_welcome_restore))
        }
    }
}

/**
 * The vaults put aside that Welcome offers to open: those the lock names in [state]
 * (`LockUiState.NoVault`) and in [status] (`LockStatus.aside`), each once, newest first.
 */
fun asideVaults(state: LockUiState, status: LockStatus): List<AsideVault> {
    val fromState = (state as? LockUiState.NoVault)?.aside.orEmpty()
    return (fromState + status.aside).distinctBy { it.vaultId }.sortedByDescending { it.putAsideAtMs }
}

/** One of Welcome's three lines: an icon that only decorates, and the text. */
@Composable
private fun Point(@DrawableRes icon: Int, @StringRes text: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text = stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** The logo's side on Welcome (ux.md §5.1). */
private val WELCOME_LOGO = 72.dp
