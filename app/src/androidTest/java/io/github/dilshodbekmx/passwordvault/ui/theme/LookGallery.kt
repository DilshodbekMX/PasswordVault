package io.github.dilshodbekmx.passwordvault.ui.theme

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.FileRef
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.VaultState
import io.github.dilshodbekmx.passwordvault.fake.FakeAppSettings
import io.github.dilshodbekmx.passwordvault.files.Place
import io.github.dilshodbekmx.passwordvault.lock.FingerprintStatus
import io.github.dilshodbekmx.passwordvault.lock.PinStatus
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryRig
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryScreen
import io.github.dilshodbekmx.passwordvault.ui.list.ListRig
import io.github.dilshodbekmx.passwordvault.ui.list.PasswordsScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.AppearanceDialog
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsRig
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsScreen
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsViewModel
import io.github.dilshodbekmx.passwordvault.window.WindowPolicy

/**
 * The screens that show a style, in one composition, for the screenshots of the looks (build plan
 * §12, 4.3b): the PIN keys ([StandInLockScreen]), the list of accounts and Settings in the frame of
 * the three tabs, an account's screen, and the dialog "Appearance" over Settings. They are the app's
 * own screens over the fakes of `APP/fake/`, drawn with the app's root theme, which follows
 * [settings]: a test changes the stored style and "light or dark", or chooses them in the dialog,
 * and every screen is in the new look.
 *
 * The frame of the tabs stands in for the shell's (`UnlockedScaffold`, which needs the app's whole
 * graph): the same Material 3 navigation bar with the shell's three items.
 */
class LookGallery(now: Long = System.currentTimeMillis()) {
    enum class Screen { PAD, LIST, ACCOUNT, SETTINGS, APPEARANCE }

    var shown by mutableStateOf(Screen.PAD)

    /** The list's vault has a backup newer than its accounts, so no reminder stands above the rows. */
    val list = ListRig(Vault(entries = accounts(now), backups = listOf(FileRef(DRIVE, "PasswordVault-backup.pvault", now - DAY_MS))))
    val entry = EntryRig(Vault(entries = listOf(EntryRig.gmail(now = now))))
    val settingsRig = SettingsRig()

    /** The app's settings: the look follows them. */
    val settings: FakeAppSettings get() = settingsRig.settings

    init {
        // Settings as a person who set the app up meets them: a PIN and fingerprint unlock, a backup
        // saved two days ago to Google Drive, one set of passwords put aside.
        settingsRig.files.places[DRIVE] = Place.GoogleDrive
        settingsRig.repository.state.value = VaultState.Unlocked(
            Vault(backups = listOf(FileRef(DRIVE, "PasswordVault-backup.pvault", now - 2 * DAY_MS))),
            1L,
            null,
            false,
        )
        settingsRig.lock.status.value = settingsRig.lock.status.value.copy(
            pin = PinStatus.On,
            fingerprint = FingerprintStatus.On,
            aside = listOf(AsideVault("00000000-0000-4000-8000-0000000000aa", now - 30 * DAY_MS, null)),
        )
    }

    fun close() {
        list.close()
        settingsRig.close()
    }

    companion object {
        const val DAY_MS = 86_400_000L
        private const val DRIVE = "content://com.google.android.apps.docs.storage/document/backup"

        /**
         * Eight accounts whose names have eight different tones (`EntryRow.toneOf`), in the README's
         * order: blue, rose, violet, green, amber, teal, slate, coral. Telegram is a favourite.
         */
        fun accounts(now: Long): List<Entry> = listOf(
            account("1", "Click", "+998 90 123 45 67", now),
            account("2", "Telegram", "@aziz_karimov", now, favourite = true),
            account("3", "Instagram", "aziz_karimov", now),
            account("4", "Xalq banki", "aziz.karimov", now),
            account("5", "Kapitalbank", "aziz.karimov", now),
            account("6", "Davlat xizmatlari", "+998 90 123 45 67", now),
            account("7", "Gmail", "aziz.karimov@gmail.com", now),
            account("8", "Uzum Bank", "+998 90 123 45 67", now),
        )

        private fun account(n: String, title: String, username: String, now: Long, favourite: Boolean = false) = Entry(
            id = "00000000-0000-4000-8000-00000000000$n",
            title = title,
            username = username,
            password = "Tz7#mq-Lo4!ve$n",
            favourite = favourite,
            createdAt = now - 9 * DAY_MS,
            updatedAt = now - 3 * DAY_MS,
        )
    }
}

/** The gallery's screen in the look its settings hold. */
@Composable
fun LookGalleryHost(gallery: LookGallery) {
    WindowPolicy.Root {
        PasswordVaultTheme(gallery.settings) {
            when (gallery.shown) {
                LookGallery.Screen.PAD -> StandInLockScreen(entered = 3, fingerprintKey = true)
                LookGallery.Screen.LIST -> TabsFrame(selected = 0) {
                    PasswordsScreen(viewModel(key = "look-list") { gallery.list.passwordsViewModel() }, gallery.list.nav)
                }
                LookGallery.Screen.ACCOUNT -> EntryScreen(viewModel(key = "look-account") { gallery.entry.entryViewModel() }, gallery.entry.nav)
                LookGallery.Screen.SETTINGS -> TabsFrame(selected = 2) { SettingsScreen(settingsViewModel(gallery), gallery.settingsRig.nav) }
                LookGallery.Screen.APPEARANCE -> TabsFrame(selected = 2) {
                    // As the route of "Appearance" draws it: the Settings list at the row, and the dialog over it.
                    val vm = settingsViewModel(gallery)
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    SettingsScreen(vm, gallery.settingsRig.nav, listState = rememberLazyListState(APPEARANCE_ROW))
                    AppearanceDialog(
                        styles = ui.styles,
                        style = ui.style,
                        theme = ui.theme,
                        onStyle = vm::setStyle,
                        onTheme = vm::setTheme,
                        onDone = { gallery.shown = LookGallery.Screen.SETTINGS },
                    )
                }
            }
        }
    }
}

@Composable
private fun settingsViewModel(gallery: LookGallery): SettingsViewModel = viewModel(key = "look-settings") { gallery.settingsRig.settingsViewModel() }

/** A screen in the frame of the three tabs, as the shell draws a tab's first screen. */
@Composable
private fun TabsFrame(selected: Int, content: @Composable () -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab(selected == 0, R.drawable.ic_shell_passwords, R.string.shell_tab_passwords)
                Tab(selected == 1, R.drawable.ic_shell_security, R.string.shell_tab_security)
                Tab(selected == 2, R.drawable.ic_shell_settings, R.string.shell_tab_settings)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

@Composable
private fun RowScope.Tab(selected: Boolean, @DrawableRes icon: Int, @StringRes label: Int) {
    NavigationBarItem(
        selected = selected,
        onClick = {},
        icon = { Icon(painterResource(icon), contentDescription = null) },
        label = { Text(stringResource(label)) },
    )
}

/** The Settings list's item that the row "Appearance" follows, so that the row is near the top behind the dialog. */
private const val APPEARANCE_ROW = 14
