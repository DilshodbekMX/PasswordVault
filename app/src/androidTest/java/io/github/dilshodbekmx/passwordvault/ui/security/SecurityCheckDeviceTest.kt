package io.github.dilshodbekmx.passwordvault.ui.security

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.data.ListModelProvider
import io.github.dilshodbekmx.passwordvault.data.SecurityCheckRunner
import io.github.dilshodbekmx.passwordvault.fake.FakeVaultRepository
import io.github.dilshodbekmx.passwordvault.i18n.SortKeys
import io.github.dilshodbekmx.passwordvault.ui.security.SecurityAccounts.entry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Build plan 7.1 on a device: the check of 480 accounts runs in the background, with the bundled meter
 * and the phone's ICU, beside the list (`ListModelProvider`), as the app builds them. The list is
 * there within moments whatever the check does, and the main thread answers within a frame or two all
 * the while. (That the list never waits for the check is shown deterministically on the JVM, in
 * `SecurityCheckSpeedTest`, with the check held at its first account.)
 * The times are logged under [LOG_TAG] (`adb logcat -d -s PvSecurity`) for the package's report.
 */
@RunWith(AndroidJUnit4::class)
class SecurityCheckDeviceTest {
    /** As the JVM test's: 17 share one password, Gmail among them; 3 another; 60 a place or a name and a year. */
    private fun fourHundredEighty(): Vault {
        val random = Random(480)
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789#%&*+-=?@_"
        fun strong() = String(CharArray(16) { alphabet[random.nextInt(alphabet.length)] })
        val words = listOf("Toshkent", "Samarqand", "Buxoro", "Andijon", "Namangan", "Shahzoda", "Dilshod", "Madina", "Aziz", "Mustaqillik")
        val entries = ArrayList<Entry>(480)
        entries += entry(1, "Gmail", "Qovun#Sabzi_2019", urls = listOf("https://mail.google.com"))
        for (n in 2..17) entries += entry(n, "Shared $n", "Qovun#Sabzi_2019")
        for (n in 18..20) entries += entry(n, "Payments $n", "Kv9#mT2pXq!w")
        for (n in 21..80) entries += entry(n, "Weak $n", words[n % words.size] + (1940 + n))
        for (n in 81..480) entries += entry(n, "Account $n", strong())
        return Vault(entries = entries)
    }

    @Test
    fun theCheckOf480AccountsRunsBesideTheListAndTheMainThreadStaysFree() {
        val scope = CoroutineScope(SupervisorJob())
        val main = Handler(Looper.getMainLooper())
        val worstLateMs = AtomicLong(0)
        val probing = AtomicBoolean(true)
        try {
            val repository = FakeVaultRepository(clock = { System.currentTimeMillis() })
            val locales = MutableStateFlow<Locale>(Locale.ENGLISH)
            val runner = SecurityCheckRunner(repository, locales, ::SortKeys, scope, Dispatchers.Default.limitedParallelism(1))
            val lists = ListModelProvider(repository.state, locales, ::SortKeys, scope, Dispatchers.Default)
            runner.start()
            lists.start()

            // A probe on the main thread every 16 ms: how late it ran is how long the main thread was busy.
            fun probe(plannedAt: Long) {
                if (!probing.get()) return
                val late = SystemClock.uptimeMillis() - plannedAt
                worstLateMs.accumulateAndGet(late) { a, b -> maxOf(a, b) }
                val next = SystemClock.uptimeMillis() + FRAME_MS
                main.postAtTime({ probe(next) }, next)
            }
            main.post { probe(SystemClock.uptimeMillis()) }

            val opened = SystemClock.elapsedRealtime()
            repository.open(fourHundredEighty())
            var listedMs = -1L
            var checkedMs = -1L
            val end = opened + 180_000
            while (SystemClock.elapsedRealtime() < end && (listedMs < 0 || checkedMs < 0)) {
                val now = SystemClock.elapsedRealtime() - opened
                if (listedMs < 0 && lists.model.value != null) listedMs = now
                if (checkedMs < 0 && runner.report.value != null) checkedMs = now
                Thread.sleep(5)
            }
            probing.set(false)
            val worst = worstLateMs.get()
            Log.i(
                LOG_TAG,
                "API ${Build.VERSION.SDK_INT}: 480 accounts listed after $listedMs ms, checked after $checkedMs ms " +
                    "(the meter loaded on the way); the main thread was at most $worst ms late",
            )
            assertTrue("the list is built ($listedMs ms)", listedMs >= 0)
            assertTrue("the check ends ($checkedMs ms)", checkedMs >= 0)
            assertTrue("the list does not wait for the check: listed after $listedMs ms (checked after $checkedMs ms)", listedMs < LIST_MS)
            assertTrue("the main thread stays free while the check runs: at most $worst ms late", worst < MAIN_LATE_MS)
            assertEquals(listOf(17, 3), runner.report.value!!.reused.map { it.entryIds.size })
            assertEquals("Gmail heads its group", SecurityAccounts.id(1), runner.report.value!!.reused[0].entryIds[0])
        } finally {
            probing.set(false)
            scope.cancel()
        }
    }

    private companion object {
        const val LOG_TAG = "PvSecurity"

        const val FRAME_MS = 16L

        /** The list of 480 accounts is sorted and indexed in milliseconds; seconds would mean it waited. */
        const val LIST_MS = 5_000L

        /**
         * How late the main thread's probe may run. Nothing of the check runs there; the bound leaves room
         * for a garbage collection and for the emulator sharing the machine with other builds.
         */
        const val MAIN_LATE_MS = 250L
    }
}
