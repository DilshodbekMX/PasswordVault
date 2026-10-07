package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.fake.FakeCollation
import io.github.dilshodbekmx.passwordvault.i18n.Collation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Build plan 3.1, [JVM]: `ListModelProvider` (architecture.md §3.5, §5.1; ux.md §5.11; search.md
 * §7). The list in display order with its sections, favourites apart and Recently deleted left out;
 * an index that never holds a secret; rebuilt when the vault or the language changes; dropped at
 * once with the vault.
 */
class ListModelProviderTest {
    private val vaults = MutableStateFlow<VaultState>(VaultState.Locked)
    private val locales = MutableStateFlow<Locale>(Locale.ENGLISH)
    private val made = mutableListOf<Locale>()

    /** English: FakeCollation. "Reversed" (the locale "xx"): the same headers, Z to A, so that a re-sort shows. */
    private val reversed = object : Collation {
        override fun compare(a: String, b: String): Int = FakeCollation.compare(b, a)

        override fun header(title: String): String = FakeCollation.header(title)
    }

    private fun collationOf(locale: Locale): Collation {
        made += locale
        return if (locale.language == "xx") reversed else FakeCollation
    }

    private fun TestScope.started(): Pair<ListModelProvider, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return ListModelProvider(vaults, locales, ::collationOf, scope, dispatcher).also { it.start() } to scope
    }

    private fun e(n: Int, title: String, favourite: Boolean = false, deletedAt: Long? = null): Entry =
        entry(n, title = title, favourite = favourite, deletedAt = deletedAt)

    private fun open(vararg entries: Entry) {
        vaults.value = VaultState.Unlocked(Vault(entries = entries.toList()), 1L, null, false)
    }

    @Test
    fun theListInDisplayOrderWithSectionsFavouritesAndNoDeletedEntries() = runTest {
        val (provider, scope) = started()
        try {
            assertNull("locked: no list", provider.model.value)
            open(e(1, "zebra"), e(2, "Apple", favourite = true), e(3, "banana"), e(4, "avocado", deletedAt = 5L), e(5, "1Password"), e(6, "apricot", favourite = true))
            testScheduler.advanceUntilIdle()
            val model = provider.model.value!!
            assertEquals(listOf("1Password", "Apple", "apricot", "banana", "zebra"), model.entries.map { it.title })
            assertEquals(listOf("#", "A", "B", "Z"), model.sections.map { it.header })
            assertEquals(listOf(listOf("Apple", "apricot")), model.sections.filter { it.header == "A" }.map { s -> s.entries.map { it.title } })
            assertEquals(listOf("Apple", "apricot"), model.favourites.map { it.title })
            assertEquals("the index is in the same order", model.entries.size, model.index.size)
            val hit = model.index.search("banana").single()
            assertEquals("banana", model.entries[hit.index].title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theIndexNeverHoldsASecret() = runTest {
        val (provider, scope) = started()
        try {
            val secretive = entry(1, title = "Bank").copy(
                password = "Uzumaki-Pa55",
                passwordHistory = listOf(HistoryItem("Kalashnikov-Old", 1L)),
                fields = listOf(CustomField("Card PIN", "Xylophone-4242", secret = true), CustomField("Branch", "Chilonzor", secret = false)),
                pendingOldPassword = "Quixotic-Older",
                changePending = true,
            )
            open(secretive)
            testScheduler.advanceUntilIdle()
            val index = provider.model.value!!.index
            for (secret in listOf("Uzumaki", "Kalashnikov", "Xylophone", "Quixotic")) {
                assertTrue("no hit for '$secret'", index.search(secret).isEmpty())
            }
            assertEquals("a hidden field's name is searchable", 1, index.search("Card PIN").size)
            assertEquals("a visible field's value is searchable", 1, index.search("Chilonzor").size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rebuiltWhenTheVaultOrTheLanguageChanges() = runTest {
        val (provider, scope) = started()
        try {
            open(e(1, "beta"), e(2, "alpha"))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("alpha", "beta"), provider.model.value!!.entries.map { it.title })

            // The vault changes.
            open(e(1, "beta"), e(2, "alpha"), e(3, "gamma"))
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("alpha", "beta", "gamma"), provider.model.value!!.entries.map { it.title })

            // The language changes: the list re-sorts, and the collation is made once for it.
            locales.value = Locale("xx")
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("gamma", "beta", "alpha"), provider.model.value!!.entries.map { it.title })
            assertEquals(listOf(Locale.ENGLISH, Locale("xx")), made.distinct())

            // A save that changed only vault-level data keeps the very same list.
            val before = provider.model.value
            val state = vaults.value as VaultState.Unlocked
            vaults.value = VaultState.Unlocked(state.vault.copy(securitySeenAt = 9L), 2L, null, false)
            testScheduler.advanceUntilIdle()
            assertSame(before, provider.model.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLockDropsTheListAtOnceAndALateBuildIsNotShown() = runTest {
        val (provider, scope) = started()
        try {
            open(e(1, "one"))
            testScheduler.advanceUntilIdle()
            assertNotNull(provider.model.value)
            vaults.value = VaultState.Locked
            assertNull("dropped in the thread that locked, before anything else ran", provider.model.value)

            // A build that was still running when the vault locked is never shown.
            open(e(2, "two"))
            vaults.value = VaultState.Locked
            testScheduler.advanceUntilIdle()
            assertNull(provider.model.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theRepositoryDrivesIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val phone = Phone()
            val repo = phone.process(scope, dispatcher)
            val provider = ListModelProvider(repo.state, locales, ::collationOf, scope, dispatcher).also { it.start() }
            repo.create()
            repo.add(form("zeta"), phone.random)
            repo.add(form("eta"), phone.random)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("eta", "zeta"), provider.model.value!!.entries.map { it.title })
            repo.lock()
            assertNull(provider.model.value)
        } finally {
            scope.cancel()
        }
    }
}
