package io.github.dilshodbekmx.passwordvault.platform

import android.app.ActivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 2.7, [device], API 37 only [V-P7]: Android 17 limits every app's memory (no number is
 * published; it depends on the phone's RAM), and `am memory-limiter manual <pid> <limit>` sets a
 * limit for one process by hand. Under a limit just above and one just below what a derivation
 * needs, the app's own KDF (`Platform.kdf`: the gate, argon2kt, the Bouncy Castle fallback) must
 * give the right key or `KdfUnavailable`: never another key, never another exception.
 *
 * What the API 37 image says, as of 2026-10-07 (`am help`): the limit is a **percentage of total
 * RAM** (`manual <PID> <PERCENT|none>`, 1 to 99), not megabytes as architecture.md §8.3 writes it;
 * and the emulator image runs the limiter `disabled` (its log: "MemoryLimiter: no config file:
 * /vendor/etc/memory-limiter-config.xml"), so a limit set by hand is accepted and does nothing.
 *
 * **The result goes into the test report**: every line below is logged with the tag [TAG], which
 * the report's logcat capture of this test keeps
 * (`androidTest-results/connected/debug/<device>/logcat-…MemoryLimiterTest-…txt`), and is also
 * written to `memory-limiter.txt` in the run's additional test output when the runner offers one.
 * The lines name the limiter's status, the limits set, and the outcome of each derivation.
 */
@RunWith(AndroidJUnit4::class)
class MemoryLimiterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val lines = mutableListOf<String>()

    private fun record(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    /** Runs [command] as `adb shell` would (the shell user) and gives its output. */
    private fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().toString(Charsets.UTF_8) }.trim()
    }

    /** A "Name:   123 kB" line of a /proc file, in kB. */
    private fun procKb(file: String, name: String): Long =
        File(file).readLines().firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L

    /** API 37 only, by `@SdkSuppress`: AGP 9.4.1 reports a test that ends in an assumption failure as failed. */
    @Test
    @SdkSuppress(minSdkVersion = 37)
    fun aDerivationUnderTheMemoryLimiterGivesTheKeyOrKdfUnavailable() = runBlocking {
        val pid = Process.myPid()
        val kdf = appGraph().platform.kdf
        val salt = ByteArray(16) { it.toByte() }

        // A warm-up derivation loads argon2kt, so that its library is in the memory measured below.
        derive(kdf, salt)

        record("device: ${Build.FINGERPRINT}, pid $pid")
        record("limiter status: ${shell("am memory-limiter status").replace('\n', ' ')}")
        val totalKb = procKb("/proc/meminfo", "MemTotal")
        val anonKb = procKb("/proc/self/status", "RssAnon") + procKb("/proc/self/status", "VmSwap")
        // One derivation adds argon2kt's 64 MiB work area and four lanes' threads to what the process holds.
        val needKb = anonKb + 65_536 + 8_192
        val above = percent(needKb * 3 / 2, totalKb, roundUp = true)
        val below = percent(anonKb + 8_192, totalKb, roundUp = false)
        record("total RAM $totalKb kB; this process holds $anonKb kB (RssAnon + VmSwap); a derivation needs about $needKb kB")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) exitsByTheLimiter().forEach { record("an earlier exit of this app: $it") }

        val outcomes = mutableListOf<String>()
        try {
            for ((name, limit) in listOf("just above" to above, "just below" to below)) {
                val answer = shell("am memory-limiter manual $pid $limit")
                record("limit $name: $limit % of total RAM (${totalKb * limit / 100} kB); am answered '${answer.ifEmpty { "nothing" }}'")
                val started = SystemClock.elapsedRealtime()
                val outcome = try {
                    val key = derive(kdf, salt)
                    assertEquals("the right key or none: never another one", KEK, key)
                    "derived the right key"
                } catch (e: KdfUnavailable) {
                    "KdfUnavailable"
                }
                outcomes += outcome
                record("under the limit $name: $outcome in ${SystemClock.elapsedRealtime() - started} ms")
            }
        } finally {
            record("limit removed: am answered '${shell("am memory-limiter manual $pid none").ifEmpty { "nothing" }}'")
            save()
        }
        assertTrue(outcomes.toString(), outcomes.all { it == "derived the right key" || it == "KdfUnavailable" })
    }

    private suspend fun derive(kdf: io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf, salt: ByteArray): String {
        val bytes = MasterPasswordBytes.of("correct horse battery staple".toCharArray())
        try {
            val key = kdf.kek(bytes, salt)
            try {
                return Bytes.hex(key)
            } finally {
                Bytes.wipe(key)
            }
        } finally {
            Bytes.wipe(bytes)
        }
    }

    /** [kb] as a whole percentage of [totalKb], between 1 and 99. */
    private fun percent(kb: Long, totalKb: Long, roundUp: Boolean): Long {
        val exact = kb * 100.0 / totalKb
        return (if (roundUp) Math.ceil(exact) else Math.floor(exact)).toLong().coerceIn(1, 99)
    }

    /** This app's exits that the limiter caused, as `ApplicationExitInfo` keeps them (API 30+). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitsByTheLimiter(): List<String> {
        val manager = appContext().getSystemService(ActivityManager::class.java)
        return manager.getHistoricalProcessExitReasons(appContext().packageName, 0, 20)
            .filter { it.description?.contains("MemoryLimiter") == true }
            .map { "pid ${it.pid} at ${it.timestamp}: reason ${it.reason}, ${it.description}" }
    }

    /** The lines, into the run's additional test output, which the runner copies back with the report. */
    private fun save() {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        try {
            File(dir).mkdirs()
            File(dir, "memory-limiter.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            Log.w(TAG, "could not write the additional test output: $e")
        }
    }

    private companion object {
        const val TAG = "PV-V-P7"
        const val KEK = "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e"
    }
}
