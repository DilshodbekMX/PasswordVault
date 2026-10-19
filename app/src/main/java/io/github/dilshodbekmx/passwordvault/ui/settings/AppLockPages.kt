package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.ui.common.VaultDialog
import io.github.dilshodbekmx.passwordvault.ui.common.VaultTopBar
import io.github.dilshodbekmx.passwordvault.ui.setup.MasterPasswordAsk
import io.github.dilshodbekmx.passwordvault.ui.setup.MasterPasswordInput

/**
 * Settings › App lock on a page of its own (ux.md §5.18; [Route.AppLock]): the same rows as the
 * group in the Settings list, for the screens that send the user to "Settings › App lock" (lock.md
 * §13.3: "You can turn it on again in Settings"; the notices of §13.4).
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun AppLockPage(vm: SettingsViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.screenShown() }
    val snackbar = remember { SnackbarHostState() }
    SettingsMessage(ui.message, snackbar, vm::messageShown)
    val title = stringResource(R.string.settings_group_app_lock)
    Scaffold(
        modifier = modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = {
            VaultTopBar(
                title = title,
                navigationIcon = { BackArrow(nav) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(SettingsTags.LIST)) {
            appLockRows(ui, AppLockActions.of(vm, nav))
        }
    }
    SettingsDialogs(vm, ui)
}

/** What the App lock rows do. */
class AppLockActions(
    val onPin: () -> Unit,
    val onFingerprint: (Boolean) -> Unit,
    val onLockDelay: () -> Unit,
    val onClipDelay: () -> Unit,
    val onChangeMasterPassword: () -> Unit,
    val onLockNow: () -> Unit,
) {
    companion object {
        /** The rows over [vm], moving with [nav]. */
        fun of(vm: SettingsViewModel, nav: Navigator) = AppLockActions(
            onPin = { nav.go(Route.ChangePin) },
            onFingerprint = vm::setFingerprint,
            onLockDelay = vm::openLockDelay,
            onClipDelay = vm::openClipDelay,
            onChangeMasterPassword = { nav.go(Route.ChangeMasterPassword) },
            onLockNow = vm::lockNow,
        )
    }
}

/**
 * The rows of the group "App lock" (ux.md §5.18): "Change PIN" or "Set a PIN" (no switch turns the
 * PIN off, lock.md F6); "Fingerprint unlock", a switch with who can open the app or why it cannot be
 * used (lock.md §9.4); "Lock the app after leaving it" with the rules that cannot be changed under
 * it; "Clear copied text after"; "Change master password"; "Lock now".
 */
fun LazyListScope.appLockRows(ui: SettingsUi, actions: AppLockActions) {
    item(key = "pin") {
        SettingsItem(
            title = ui.pin.title.resolve(),
            supporting = ui.pin.supporting?.resolve(),
            icon = R.drawable.ic_settings_lock,
            enabled = ui.pin.enabled,
            tag = SettingsTags.PIN,
            onClick = actions.onPin,
        )
    }
    item(key = "fingerprint") {
        SettingsSwitchItem(
            title = ui.fingerprint.title.resolve(),
            supporting = ui.fingerprint.supporting.resolve(),
            icon = R.drawable.ic_common_fingerprint,
            checked = ui.fingerprint.checked,
            enabled = ui.fingerprint.enabled && !ui.fingerprintBusy,
            busy = ui.fingerprintBusy,
            tag = SettingsTags.FINGERPRINT,
            onCheckedChange = actions.onFingerprint,
        )
    }
    item(key = "lockDelay") {
        SettingsItem(
            title = stringResource(R.string.settings_lock_delay),
            supporting = ui.lockDelayText.resolve(),
            icon = R.drawable.ic_settings_timer,
            tag = SettingsTags.LOCK_DELAY,
            onClick = actions.onLockDelay,
        )
    }
    item(key = "lockAlways") {
        Text(
            text = stringResource(R.string.settings_lock_always),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp).testTag(SettingsTags.LOCK_ALWAYS),
        )
    }
    item(key = "clipDelay") {
        SettingsItem(
            title = stringResource(R.string.settings_clip_delay),
            supporting = ui.clipDelayText.resolve(),
            icon = R.drawable.ic_settings_clipboard,
            tag = SettingsTags.CLIP_DELAY,
            onClick = actions.onClipDelay,
        )
    }
    item(key = "masterPassword") {
        SettingsItem(
            title = stringResource(R.string.settings_change_master_password),
            icon = R.drawable.ic_common_key,
            tag = SettingsTags.CHANGE_MASTER_PASSWORD,
            onClick = actions.onChangeMasterPassword,
        )
    }
    item(key = "lockNow") {
        SettingsItem(
            title = stringResource(R.string.settings_lock_now),
            icon = R.drawable.ic_settings_lock,
            tag = SettingsTags.LOCK_NOW,
            onClick = actions.onLockNow,
        )
    }
}

/** The dialog that [SettingsUi.dialog] says is open, over the Settings list or the App lock page. */
@Composable
fun SettingsDialogs(vm: SettingsViewModel, ui: SettingsUi) {
    when (ui.dialog) {
        null -> Unit
        SettingsDialog.LOCK_DELAY -> ChoiceDialog(
            title = stringResource(R.string.settings_lock_delay),
            options = LockDelay.entries,
            selected = ui.lockDelay,
            label = { SettingsViewModel.lockDelayText(it).resolve() },
            tagOf = { SettingsTags.lockDelay(it) },
            onChoose = vm::setLockDelay,
            onDismiss = vm::dismissDialog,
            note = stringResource(R.string.settings_lock_delay_note),
        )
        SettingsDialog.CLIP_DELAY -> ChoiceDialog(
            title = stringResource(R.string.settings_clip_delay),
            options = ClipDelay.entries,
            selected = ui.clipDelay,
            label = { SettingsViewModel.clipDelayText(it).resolve() },
            tagOf = { SettingsTags.clipDelay(it) },
            onChoose = vm::setClipDelay,
            onDismiss = vm::dismissDialog,
        )
        SettingsDialog.FINGERPRINT_ON -> FingerprintOnDialog(vm.fingerprintPassword, onSubmit = vm::submitFingerprintPassword, onDismiss = vm::dismissDialog)
    }
}

/**
 * The master password before fingerprint unlock is turned on (lock.md F9 steps 2 and 3, §8.2):
 * "Turn on fingerprint unlock", why the master password is asked, who can open the app with a
 * fingerprint, the field with its lines, "Turn on" and "Cancel". A right master password closes the
 * dialog and the phone's fingerprint prompt follows.
 */
@Composable
private fun FingerprintOnDialog(ask: MasterPasswordAsk, onSubmit: () -> Unit, onDismiss: () -> Unit) {
    val ui by ask.ui.collectAsStateWithLifecycle()
    VaultDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = ui.enabled, modifier = Modifier.testTag(SettingsTags.FINGERPRINT_TURN_ON)) {
                Text(stringResource(R.string.settings_fingerprint_on_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !ui.checking, modifier = Modifier.testTag(SettingsTags.DIALOG_CANCEL)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = { Text(stringResource(R.string.pin_fingerprint_turn_on), modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_fingerprint_on_body), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.pin_fingerprint_anyone), style = MaterialTheme.typography.bodyMedium)
                MasterPasswordInput(ask = ask, onNext = onSubmit, focusFirst = true)
            }
        },
    )
}

/** The top bar's back arrow of a Settings page; [enabled] false while a step that must not be cut short runs. */
@Composable
internal fun BackArrow(nav: Navigator, enabled: Boolean = true) {
    IconButton(onClick = { nav.back() }, enabled = enabled, modifier = Modifier.testTag(SettingsTags.BACK)) {
        Icon(painterResource(R.drawable.ic_settings_back), contentDescription = stringResource(R.string.settings_back))
    }
}

/** A small spinner, in place of a switch while its change is under way. */
@Composable
internal fun SmallSpinner() {
    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
}
