package io.github.dilshodbekmx.passwordvault.platform

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import java.io.File
import java.util.UUID

/** The app under test, whose process the instrumented tests run in. */
internal fun appContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

/** The app's own graph, as `PasswordVaultApp` built it at process start. */
internal fun appGraph() = (appContext().applicationContext as PasswordVaultApp).graph

/**
 * A directory of its own for one test, in the app's `filesDir` (or [base]), where the vault lives:
 * the file system and its rules are those of the real vault. [remove] deletes it again, after giving
 * every directory in it back its write permission.
 */
internal class Scratch(base: File = appContext().filesDir) {
    val dir: String = File(base, "pvtest-" + UUID.randomUUID()).path

    init {
        Os.mkdir(dir, OsConstants.S_IRWXU)
    }

    fun path(vararg parts: String): String = parts.fold(dir) { acc, part -> "$acc/$part" }

    /** Writes [bytes] at [path] the plain way, for setting up a test. */
    fun put(path: String, bytes: ByteArray) {
        File(path).parentFile!!.mkdirs()
        File(path).writeBytes(bytes)
    }

    fun mode(path: String): Int = Os.lstat(path).st_mode and "777".toInt(8)

    fun chmod(path: String, mode: String) = Os.chmod(path, mode.toInt(8))

    fun remove() {
        fun open(file: File) {
            try {
                if (!OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) && file.isDirectory) {
                    Os.chmod(file.path, OsConstants.S_IRWXU)
                    file.listFiles()?.forEach(::open)
                }
            } catch (_: ErrnoException) {
                // gone already
            }
        }
        open(File(dir))
        File(dir).deleteRecursively()
    }
}
