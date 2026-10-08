package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.BuildConfig
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.i18n.rememberFormatters
import io.github.dilshodbekmx.passwordvault.i18n.resolve
import io.github.dilshodbekmx.passwordvault.nav.HelpPage
import io.github.dilshodbekmx.passwordvault.nav.Navigator
import io.github.dilshodbekmx.passwordvault.nav.Route
import io.github.dilshodbekmx.passwordvault.settings.ClipDelay
import io.github.dilshodbekmx.passwordvault.settings.LockDelay
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import kotlinx.coroutines.launch

/**
 * The Settings tab (ux.md §5.18): one list grouped under headings.
 *
 * - **App lock** (glossary term 69): the rows of [appLockRows].
 * - **Backup & transfer**: "Make a backup" with the last backup ("Last backup: 3 days ago, Google
 *   Drive"), the "Backup reminders" switch, "Export", "Import", and one "Old passwords (locked on …)"
 *   per vault put aside (lock.md F17).
 * - **Appearance & language**: the language (its title the same in every language, i18n.md §1.4.5)
 *   and the theme.
 * - **Help** (ux.md §5.22): the four help pages, "Open-source licences" and "Version".
 *
 * The rows of later screens open their routes, placeholders until those are built. A row that opens
 * a route carries the test tag `pv.open.<Route>` (the shell walk's form), so tests and the emulator
 * scripts find it whatever the language.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar's signature names TopAppBarScrollBehavior (ux.md §10)
@Composable
fun SettingsScreen(vm: SettingsViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.screenShown() }
    val snackbar = remember { SnackbarHostState() }
    SettingsMessage(ui.message, snackbar, vm::messageShown)
    val title = stringResource(R.string.settings_title)
    val formatters = rememberFormatters()
    val context = LocalContext.current
    val language = remember(context) { AppLanguages.current(context) }
    Scaffold(
        modifier = modifier.semantics {
            paneTitle = title
            testTagsAsResourceId = true
        },
        topBar = { TopAppBar(title = { Text(title, modifier = Modifier.semantics { heading() }) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(SettingsTags.LIST)) {
            heading("appLock", R.string.settings_group_app_lock)
            appLockRows(ui, AppLockActions.of(vm, nav))

            heading("backup", R.string.settings_group_backup)
            item(key = "makeBackup") {
                val last = ui.lastBackup
                SettingsItem(
                    title = stringResource(R.string.settings_backup),
                    supporting = if (last == null) {
                        stringResource(R.string.settings_backup_none)
                    } else {
                        stringResource(R.string.settings_backup_last, formatters.relative(last.atMs, vm.nowMs()), placeText(last.place))
                    },
                    icon = R.drawable.ic_settings_backup,
                    tag = SettingsTags.open(Route.Backup),
                    onClick = { nav.go(Route.Backup) },
                )
            }
            item(key = "reminders") {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_backup_reminders),
                    icon = R.drawable.ic_settings_reminder,
                    checked = ui.backupReminder,
                    tag = SettingsTags.BACKUP_REMINDERS,
                    onCheckedChange = vm::setBackupReminder,
                )
            }
            item(key = "export") {
                SettingsItem(
                    title = stringResource(R.string.settings_export),
                    supporting = stringResource(R.string.settings_export_body),
                    icon = R.drawable.ic_settings_export,
                    tag = SettingsTags.open(Route.Export()),
                    onClick = { nav.go(Route.Export()) },
                )
            }
            item(key = "import") {
                SettingsItem(
                    title = stringResource(R.string.settings_import),
                    supporting = stringResource(R.string.settings_import_body),
                    icon = R.drawable.ic_settings_import,
                    tag = SettingsTags.open(Route.Import()),
                    onClick = { nav.go(Route.Import()) },
                )
            }
            itemsIndexed(ui.oldPasswords, key = { index, _ -> "old$index" }) { index, atMs ->
                SettingsItem(
                    title = stringResource(R.string.settings_old_passwords, formatters.date(atMs)),
                    icon = R.drawable.ic_settings_history,
                    tag = SettingsTags.oldPasswords(index),
                    onClick = { nav.go(Route.OldPasswords) },
                )
            }

            heading("appearance", R.string.settings_group_appearance)
            item(key = "language") {
                val own = language.ownName
                SettingsItem(
                    title = stringResource(AppLanguages.switchLabel),
                    supporting = if (own == null) stringResource(R.string.settings_language_system) else stringResource(own),
                    icon = R.drawable.ic_settings_language,
                    tag = SettingsTags.open(Route.Language),
                    onClick = { nav.go(Route.Language) },
                )
            }
            item(key = "theme") {
                SettingsItem(
                    title = stringResource(R.string.settings_theme),
                    supporting = ui.themeText.resolve(),
                    icon = R.drawable.ic_settings_theme,
                    tag = SettingsTags.open(Route.Theme),
                    onClick = { nav.go(Route.Theme) },
                )
            }

            heading("help", R.string.settings_group_help)
            for ((page, text) in HELP_ROWS) {
                item(key = "help$page") {
                    SettingsItem(
                        title = stringResource(text),
                        icon = R.drawable.ic_settings_help,
                        tag = SettingsTags.help(page),
                        onClick = { nav.go(Route.Help(page)) },
                    )
                }
            }
            item(key = "licences") {
                SettingsItem(
                    title = stringResource(R.string.settings_licences),
                    icon = R.drawable.ic_settings_licences,
                    tag = SettingsTags.open(Route.Licences),
                    onClick = { nav.go(Route.Licences) },
                )
            }
            item(key = "version") {
                SettingsItem(
                    title = stringResource(R.string.settings_version),
                    supporting = BuildConfig.VERSION_NAME,
                    icon = R.drawable.ic_common_info,
                    tag = SettingsTags.help(HelpPage.VERSION),
                    onClick = { nav.go(Route.Help(HelpPage.VERSION)) },
                )
            }
        }
    }
    SettingsDialogs(vm, ui)
}

/** The help pages in the order of ux.md §5.18; "Version" comes after "Open-source licences". */
private val HELP_ROWS = listOf(
    HelpPage.PROTECTION to R.string.settings_help_protection,
    HelpPage.LOST_PHONE to R.string.settings_help_lost_phone,
    HelpPage.FORGOT_MASTER_PASSWORD to R.string.settings_help_forgot_master_password,
    HelpPage.FORGOT_PIN to R.string.settings_help_forgot_pin,
)

/** The place in "Last backup: 3 days ago, Google Drive": this phone, Google Drive, or the provider's own name. */
@Composable
private fun placeText(place: Place): String = when (place) {
    Place.PhoneStorage -> stringResource(R.string.settings_place_phone)
    Place.GoogleDrive -> stringResource(R.string.settings_place_drive)
    is Place.Other -> place.name
}

/** A group's heading (ux.md §5.18): a heading for TalkBack (ux.md §8 "Screen structure"). */
private fun LazyListScope.heading(key: String, text: Int) {
    item(key = "heading-$key") {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                .semantics { heading() }
                .testTag(SettingsTags.heading(key)),
        )
    }
}

/**
 * A row of Settings: its icon, title and the line under it; the whole row is the tap target (at least
 * 56 dp high, ux.md §8). [enabled] false draws it dimmed and takes no tap, while TalkBack still reads
 * the line under it, which says why.
 */
@Composable
internal fun SettingsItem(
    title: String,
    tag: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
        trailingContent = trailing,
        colors = if (enabled) ListItemDefaults.colors() else dimmed(),
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .testTag(tag),
    )
}

/**
 * A row with a switch (ux.md §5.18): the whole row toggles it. While [busy], a spinner stands in
 * for the switch and the row takes no tap.
 */
@Composable
internal fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
        trailingContent = {
            if (busy) SmallSpinner() else Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
        colors = if (enabled || busy) ListItemDefaults.colors() else dimmed(),
        modifier = modifier
            .toggleable(value = checked, enabled = enabled && !busy, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(tag),
    )
}

@Composable
private fun dimmed() = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA).let { faded ->
    ListItemDefaults.colors(headlineColor = faded, leadingIconColor = faded, trailingIconColor = faded)
}

/**
 * Shows [message] once in [snackbar], then reports it ([onShown]); the snackbar runs in a scope of
 * its own, so that reporting it does not cut it short.
 */
@Composable
internal fun SettingsMessage(message: UiText?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    val scope = rememberCoroutineScope()
    val text = message?.resolve()
    LaunchedEffect(message) {
        if (text == null) return@LaunchedEffect
        scope.launch { snackbar.showSnackbar(text) }
        onShown()
    }
}

/** The resource ids (test tags) of Settings, for tests and the emulator scripts, whatever the language. */
object SettingsTags {
    const val LIST = "pv.settings.list"
    const val PIN = "pv.open.ChangePin"
    const val FINGERPRINT = "pv.settings.fingerprint"
    const val FINGERPRINT_TURN_ON = "pv.settings.fingerprint.turnOn"
    const val LOCK_DELAY = "pv.settings.lockDelay"
    const val LOCK_ALWAYS = "pv.settings.lockAlways"
    const val CLIP_DELAY = "pv.settings.clipDelay"
    const val CHANGE_MASTER_PASSWORD = "pv.open.ChangeMasterPassword"
    const val LOCK_NOW = "pv.action.lockNow"
    const val BACKUP_REMINDERS = "pv.settings.backupReminders"
    const val DIALOG_CANCEL = "pv.settings.dialog.cancel"
    const val BACK = "pv.settings.back"

    /** A row that opens [route]: the shell walk's `pv.open.<Route>`. */
    fun open(route: Route): String = "pv.open.${route.name}"

    /** The heading of the group [key]: appLock, backup, appearance, help. */
    fun heading(key: String): String = "pv.settings.heading.$key"

    /** The row of the help page [page]. */
    fun help(page: HelpPage): String = "pv.open.Help.${page.name}"

    /** The [index]th "Old passwords (locked on …)" row, from 0. */
    fun oldPasswords(index: Int): String = if (index == 0) "pv.open.OldPasswords" else "pv.open.OldPasswords.$index"

    /** A choice of the theme dialog. */
    fun theme(choice: ThemeChoice): String = "pv.settings.theme.${choice.name}"

    /** A choice of the lock-delay dialog. */
    fun lockDelay(delay: LockDelay): String = "pv.settings.lockDelay.${delay.name}"

    /** A choice of the clipboard-delay dialog. */
    fun clipDelay(delay: ClipDelay): String = "pv.settings.clipDelay.${delay.name}"
}

private const val DISABLED_ALPHA = 0.38f
