package io.github.dilshodbekmx.passwordvault.ui.security

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.strength.SecurityCheck
import io.github.dilshodbekmx.passwordvault.core.strength.StrengthMeter
import io.github.dilshodbekmx.passwordvault.data.CheckProgress
import io.github.dilshodbekmx.passwordvault.data.ListModelProvider
import io.github.dilshodbekmx.passwordvault.data.SecurityCheckRunner
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.entry
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityKit.id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Build plan 7.1, [JVM]: "the check of 480 entries finishes in the background without blocking the
 * list". The runner and the list (`ListModelProvider`) as the app builds them: the check on one thread
 * of `Dispatchers.Default`, the list on the pool, both following the same vault; the real meter with
 * the bundled dictionary. The vault is published from the test's thread, which plays the main thread.
 */
class SecurityCheckSpeedTest {
    private val locales = MutableStateFlow<Locale>(Locale.ENGLISH)

    /**
     * 480 accounts as this user has them (ux.md §5.16): 17 share one password, Gmail among them, 3
     * share another, 60 have passwords made of a place or a name and a year, the rest their own.
     */
    private val vault: Vault = run {
        val random = Random(480)
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789#%&*+-=?@_"
        fun strong() = String(CharArray(16) { alphabet[random.nextInt(alphabet.length)] })
        val words = listOf("Toshkent", "Samarqand", "Buxoro", "Andijon", "Namangan", "Shahzoda", "Dilshod", "Madina", "Aziz", "Mustaqillik")
        val entries = ArrayList<Entry>(480)
        entries += entry(1, "Gmail", password = "Qovun#Sabzi_2019", urls = listOf("https://mail.google.com"))
        for (n in 2..17) entries += entry(n, "Shared $n", password = "Qovun#Sabzi_2019")
        for (n in 18..20) entries += entry(n, "Payments $n", password = "Kv9#mT2pXq!w")
        for (n in 21..80) entries += entry(n, "Weak $n", password = words[n % words.size] + (1940 + n)) // each its own: 1961 to 2020
        for (n in 81..480) entries += entry(n, "Account $n", password = strong())
        Vault(entries = entries)
    }

    @Test
    fun theCheckOf480EntriesRunsBesideTheListAndNeverHoldsItUp() {
        val meter = StrengthMeter.shared() // the app loads it at the first check; here before, so that the gate below is the only wait
        val scope = CoroutineScope(SupervisorJob())
        try {
            val repository = FakeVaultRepository(clock = { SecurityKit.NOW })
            val scoring = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val runner = SecurityCheckRunner(
                repository = repository,
                locales = locales,
                collationOf = SecurityKit::collationOf,
                scope = scope,
                dispatcher = Dispatchers.Default.limitedParallelism(1),
                easyToGuess = { entry ->
                    scoring.countDown()
                    gate.await() // the check holds its thread until the list is there
                    SecurityCheck.isEasyToGuess(entry, meter)
                },
            )
            val lists = ListModelProvider(repository.state, locales, SecurityKit::collationOf, scope, Dispatchers.Default)
            runner.start()
            lists.start()

            val opened = System.nanoTime()
            repository.open(vault)
            val openMs = (System.nanoTime() - opened) / 1_000_000
            assertTrue("publishing the vault returns at once ($openMs ms); the check runs in the background", openMs < 1_000)

            assertTrue("the check has started", scoring.await(60, TimeUnit.SECONDS))
            val listed = waitFor(60_000) { lists.model.value != null }
            assertTrue("the list is built while the check holds its thread", listed)
            assertEquals(480, lists.model.value!!.entries.size)
            assertNull("the check has not ended", runner.report.value)
            assertEquals("Checking 0 of 480…", CheckProgress(0, 480), runner.progress.value)

            gate.countDown()
            assertTrue("the check of 480 entries ends", waitFor(120_000) { runner.report.value != null })
            val report = runner.report.value!!
            assertEquals(listOf(17, 3), report.reused.map { it.entryIds.size })
            assertEquals("email first: Gmail heads its group", id(1), report.reused[0].entryIds[0])
            val easy = vault.entries.filter { SecurityCheck.isEasyToGuess(it, meter) }.map { it.id }.toSet()
            assertEquals(easy, report.weak.toSet())
            assertTrue("the place-and-year passwords are easy to guess (${easy.size})", easy.size >= 50)
            assertNull("no progress once done", runner.progress.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theCheckOf480EntriesWithTheRealMeterTakesSecondsInTheBackground() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val repository = FakeVaultRepository(clock = { SecurityKit.NOW })
            val runner = SecurityCheckRunner(repository, locales, SecurityKit::collationOf, scope, Dispatchers.Default.limitedParallelism(1))
            runner.start()
            val started = System.nanoTime()
            repository.open(vault)
            val openMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("publishing the vault returns at once ($openMs ms)", openMs < 1_000)
            // Generous: the machine is shared with other builds (build plan §12, item 5 of R4's merge).
            assertTrue("the check ends", waitFor(180_000) { runner.report.value != null })
            val tookMs = (System.nanoTime() - started) / 1_000_000
            println("SecurityCheckSpeedTest: 480 entries checked in $tookMs ms, the meter's dictionary loaded on the way")
            assertEquals(listOf(17, 3), runner.report.value!!.reused.map { it.entryIds.size })
        } finally {
            scope.cancel()
        }
    }

    /** Polls [done] every 10 ms for at most [timeoutMs]; whether it came true. */
    private fun waitFor(timeoutMs: Long, done: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (done()) return true
            Thread.sleep(10)
        }
        return done()
    }
}
