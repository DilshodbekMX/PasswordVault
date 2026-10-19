package io.github.dilshodbekmx.passwordvault.ui.layout

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream

/**
 * One run of the layout walk (build plan 6.2, `tools/emu/layout/walk.sh`), started by the script with
 * the instrumentation arguments `-e pvLayout <run name>` and `-e pvLayoutLocale <tag>`, and
 * `-e pvLayoutStyle <style>` (a `StyleChoice` name) for a style other than the default. Without
 * `pvLayout` the walk's tests return at once, so that a plain connected run (`tools/emu/adb.sh
 * --connected`) does not walk the app at the size of the phone it runs on.
 *
 * Each screen's result is written three ways, one line per check: to the run's report in the app's
 * cache (`cache/pv-layout/<run>.txt`, read by the script with `run-as`), as an instrumentation status
 * (`am instrument -r` prints it at once), and to logcat under [TAG]:
 *
 *     SCREEN <name>|<check>|PASS
 *     SCREEN <name>|<check>|FAIL|<finding>; <finding>; ...
 *
 * A picture of each screen goes to `/data/local/tmp/pv-layout/<run>/<name>.png` (the shell's
 * `screencap`, which the debug build allows), from where the script takes it.
 */
class LayoutRun private constructor(val name: String, val localeTag: String, val style: String?) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val report = File(instrumentation.targetContext.cacheDir, "pv-layout/$name.txt").apply {
        parentFile?.mkdirs()
        writeText("")
    }
    private val shots = "$SHOTS_ROOT/$name"
    private var failed = 0
    private var screens = 0

    init {
        shell("mkdir -p $shots")
        line("RUN $name|$localeTag${style?.let { "|$it" }.orEmpty()}")
    }

    /** Records the checks of the screen [screen]. */
    fun record(screen: String, findings: LayoutProbe.Findings) {
        screens++
        for (check in LayoutCheck.entries) {
            val found = findings.of(check)
            if (found.isEmpty()) {
                line("SCREEN $screen|${check.name}|PASS")
            } else {
                failed++
                line("SCREEN $screen|${check.name}|FAIL|" + found.joinToString("; ").replace('\n', ' ').replace('|', '/'))
            }
        }
    }

    /** Records a step of the walk that could not be done (the screen after it was not reached). */
    fun broken(step: String, problem: Throwable) {
        failed++
        val message = problem.message.orEmpty().lineSequence().filter { it.isNotBlank() }.take(3).joinToString(" / ")
        line("BROKEN $step|${problem.javaClass.simpleName}: ${message.take(400).replace('|', '/')}")
        Log.w(TAG, "step $step", problem)
    }

    /** A note in the report (what the walk did or met). */
    fun note(text: String) = line("NOTE " + text.replace('\n', ' '))

    /** A picture of the whole screen as [screen].png. */
    fun shot(screen: String) {
        shell("screencap -p $shots/$screen.png")
    }

    /** The run's last line: how many screens and how many failed checks. */
    fun end(): Int {
        line("END $screens screens|$failed failed")
        return failed
    }

    private fun line(text: String) {
        report.appendText(text + "\n")
        Log.i(TAG, text)
        instrumentation.sendStatus(STATUS_CODE, Bundle().apply { putString("pvlayout", text) })
    }

    private fun shell(command: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { (it as FileInputStream).readBytes() }
    }

    companion object {
        const val TAG = "PVLAYOUT"
        private const val STATUS_CODE = 2
        const val SHOTS_ROOT = "/data/local/tmp/pv-layout"

        /** The run the script asked for, or null for a plain connected run. */
        fun fromArguments(): LayoutRun? {
            val args = InstrumentationRegistry.getArguments()
            val name = args.getString("pvLayout") ?: return null
            val tag = args.getString("pvLayoutLocale") ?: "en-XA"
            val style = args.getString("pvLayoutStyle")
            require(Regex("[A-Za-z0-9._-]+").matches(name)) { "pvLayout: a run name of letters, digits, . _ -" }
            return LayoutRun(name, tag, style)
        }
    }
}
