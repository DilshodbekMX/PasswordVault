package io.github.dilshodbekmx.passwordvault.ui.security.change

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryResult
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.data.SaveResult
import io.github.dilshodbekmx.passwordvault.lock.CreateResult
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test of its own: the accounts of the emulator script `tools/emu/change/guided.sh` (build
 * plan 7.2's [script] line), made in the installed app with fixed ids, so that the script can open
 * the guided change of one of them. The script runs it on a fresh install with the instrumentation
 * argument `pvChangeSeed`, the master password of the vault it makes:
 *
 * ```
 * adb shell am instrument -w -e pvChangeSeed PASSWORD \
 *     -e class io.github.dilshodbekmx.passwordvault.ui.security.change.GuidedChangeSeed \
 *     io.github.dilshodbekmx.passwordvault.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * It makes the vault as setup does (`LockManager.createVault`) and adds the accounts as an import
 * does (`VaultEdits.addImported`, which keeps an id that is a free UUID). [GMAIL] is the id of
 * Gmail's row in 7.1's group list (`pv.group.action.<id>`). Without the argument, as in every
 * connected run, it does nothing.
 */
@RunWith(AndroidJUnit4::class)
class GuidedChangeSeed {
    @Test
    fun seed() {
        val password = InstrumentationRegistry.getArguments().getString(ARGUMENT) ?: return
        val graph = ApplicationProvider.getApplicationContext<PasswordVaultApp>().graph
        runBlocking {
            withTimeout(TIMEOUT_MS) { graph.lock.state.first { it !is LockUiState.Starting } }
            assertTrue("a fresh install: no vault yet, not ${graph.lock.state.value}", graph.lock.state.value is LockUiState.NoVault)
            val made = graph.lock.createVault(password.toCharArray(), password.toCharArray(), null)
            assertTrue("the vault is made: $made", made is CreateResult.Created)
            withTimeout(TIMEOUT_MS) { graph.lock.state.first { it is LockUiState.Unlocked } }

            val now = System.currentTimeMillis()
            val result = graph.data.repository.edit(VaultEdits.addImported(accounts(now), graph.platform.random) { it })
            assertTrue("the accounts are saved: $result", result is SaveResult.Saved)
            val added = (result as SaveResult.Saved).value.addedIds
            assertEquals("every account keeps its id", listOf(GMAIL, FACEBOOK, BANK, UZUM), added)
            // An account the guided change is never about leaves the list as a person's would be.
            val starred = graph.data.repository.edit(VaultEdits.setFavourite(UZUM, true))
            assertTrue((starred as SaveResult.Saved).value is EntryResult.Done)
        }
    }

    companion object {
        /** The instrumentation argument: the master password of the vault to make. */
        const val ARGUMENT = "pvChangeSeed"

        /** Gmail: the account whose password the script changes. */
        const val GMAIL = "00000000-0000-4000-8000-00000000e001"
        const val FACEBOOK = "00000000-0000-4000-8000-00000000e002"
        const val BANK = "00000000-0000-4000-8000-00000000e003"
        const val UZUM = "00000000-0000-4000-8000-00000000e004"

        /** The password that Gmail, Facebook and Kapitalbank share (what their websites have). */
        const val SHARED = "Toshkent2020!"

        private const val DAY_MS = 86_400_000L
        private const val TIMEOUT_MS = 60_000L

        /** Gmail, Facebook and Kapitalbank share [SHARED]; Uzum Market has its own password. */
        fun accounts(now: Long): List<Entry> = listOf(
            entry(GMAIL, "Gmail", "aziz.karimov@gmail.com", SHARED, "https://accounts.google.com/", now - 40 * DAY_MS),
            entry(FACEBOOK, "Facebook", "aziz.karimov", SHARED, "https://www.facebook.com/", now - 30 * DAY_MS),
            entry(BANK, "Kapitalbank", "998901234567", SHARED, "https://kapitalbank.uz/", now - 20 * DAY_MS),
            entry(UZUM, "Uzum Market", "aziz.karimov@gmail.com", "Qovun-Ari-Tosh-77", "https://uzum.uz/", now - 10 * DAY_MS),
        )

        private fun entry(id: String, title: String, username: String, password: String, url: String, at: Long) = Entry(
            id = id,
            title = title,
            username = username,
            password = password,
            urls = listOf(url),
            createdAt = at,
            updatedAt = at,
            passwordChangedAt = at,
        )
    }
}
