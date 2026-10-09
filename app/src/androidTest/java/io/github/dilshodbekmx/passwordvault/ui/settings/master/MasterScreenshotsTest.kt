package io.github.dilshodbekmx.passwordvault.ui.settings.master

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.lock.LockStatus
import io.github.dilshodbekmx.passwordvault.lock.PendingMasterPassword
import io.github.dilshodbekmx.passwordvault.lock.ReplacedNotice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice
import io.github.dilshodbekmx.passwordvault.ui.list.ListBanner
import io.github.dilshodbekmx.passwordvault.ui.list.ListRig
import io.github.dilshodbekmx.passwordvault.ui.list.PasswordsScreen
import io.github.dilshodbekmx.passwordvault.ui.list.PasswordsViewModel
import io.github.dilshodbekmx.passwordvault.ui.settings.SettingsTestTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pictures of the screens of build plan 5.5 for the user, in English, on Android 17 (the user's
 * version): the shell user's `screencap` keeps them in `/data/local/tmp/pv-shots-5.5/5.5-*.png`, from
 * where the package's agent copies them into /home/asus/pvwork/shots-5/. The steps of "Change master
 * password" and of a new master password are drawn by the routes' own screens over the fake lock
 * ([MasterWalk]), in light and in dark; the list's banners of a new master password (waiting, ready)
 * and of a replacement by the list screen itself (3.5's), over the fake vault.
 */
@RunWith(AndroidJUnit4::class)
class MasterScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var walk: MasterWalk? = null
    private var list: ListRig? = null

    @After
    fun tearDown() {
        walk?.close()
        list?.close()
    }

    @Test
    fun theStepsOfBothRoutes_lightAndDark() {
        if (!MasterShots.on) return
        val shots = MasterShots.USER
        shots.start()
        var theme by mutableStateOf(ThemeChoice.LIGHT)
        var prefix = "5.5"
        val made = MasterWalk(compose) { step -> shots.take(compose, "$prefix-$step") }.also { walk = it }
        compose.setContent { SettingsTestTheme(theme) { made.Content() } }
        made.walkChange(pictures = true)
        made.walkNew(pictures = true)
        made.walkFinish(pictures = true)
        compose.runOnUiThread { theme = ThemeChoice.DARK }
        prefix = "5.5-dark"
        made.walkNew(pictures = true)
        made.walkFinish(pictures = true)
        assertTrue("the pictures were taken: ${shots.taken()}", shots.taken().size >= 25)
    }

    @Test
    fun theListsBannersOfANewMasterPassword() {
        if (!MasterShots.on) return
        val shots = MasterShots("/data/local/tmp/pv-shots-5.5-banners")
        shots.start()
        val made = ListRig(Vault(entries = ENTRIES)).also { list = it }
        lateinit var vm: PasswordsViewModel
        compose.setContent {
            SettingsTestTheme {
                vm = viewModel { made.passwordsViewModel() }
                PasswordsScreen(vm, made.nav)
            }
        }
        made.awaitList()
        val waiting = PendingMasterPassword(NOW - 4 * HOUR_MS, Shortcut.PIN, creditedMs = 4 * HOUR_MS, remainingMs = 20 * HOUR_MS, ready = false)
        show(made, vm, LockStatus.NONE.copy(pending = waiting)) { it is ListBanner.PendingMasterPassword }
        shots.take(compose, "5.5-21-banner-waiting")
        show(made, vm, LockStatus.NONE.copy(pending = waiting.copy(creditedMs = DAY_MS, remainingMs = 0L, ready = true))) { it == ListBanner.PendingReady }
        shots.take(compose, "5.5-22-banner-ready")
        show(made, vm, LockStatus.NONE.copy(replaced = ReplacedNotice(NOW - HOUR_MS, Shortcut.PIN, "00000000-0000-4000-8000-0000000000bb"))) { it is ListBanner.Replaced }
        shots.take(compose, "5.5-23-banner-replaced")
        show(made, vm, LockStatus.NONE.copy(practiceOverdueSinceMs = NOW - 40 * DAY_MS)) { it is ListBanner.PracticeOverdue }
        shots.take(compose, "5.5-24-banner-practice-i-forgot-it")
        assertTrue(shots.taken().size >= 4)
    }

    private fun show(made: ListRig, vm: PasswordsViewModel, status: LockStatus, banner: (ListBanner) -> Boolean) {
        compose.runOnUiThread { made.lock.status.value = status }
        compose.waitUntil(10_000L) { vm.state.value.banners.firstOrNull()?.let(banner) == true }
        compose.waitForIdle()
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        val NOW = System.currentTimeMillis()

        val ENTRIES = listOf("Gmail", "Telegram", "Payme", "Uzum Bank", "Facebook", "Instagram", "Click").mapIndexed { i, title ->
            Entry(
                id = "00000000-0000-4000-8000-0000000000%02d".format(i + 10),
                title = title,
                username = "dilshod@example.com",
                password = "pw-$title",
                createdAt = NOW - 30 * DAY_MS,
                updatedAt = NOW - 30 * DAY_MS,
            )
        }
    }
}
