package io.github.dilshodbekmx.passwordvault.core.tools

import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue

/**
 * What the three programs of this directory (the search benchmark, the converter evaluation and
 * the Cyrillic string drafter) share. Each runs as a unit test that is skipped unless its Gradle
 * property is given (build plan 1.2): `-Ppv.<name>=<value>` reaches the test as the system
 * property `pv.<name>` (root build.gradle.kts), and such a run is never up to date or cached.
 */
internal object ProgramRun {

    /**
     * The words of the value of the system property [name], split at white space: an empty list
     * for a property given without a value. Skips the calling test when the property is absent.
     */
    fun arguments(name: String): List<String> {
        val value = System.getProperty(name)
        assumeTrue("runs only with -P$name", value != null)
        return value!!.split(WHITE_SPACE).filter { it.isNotEmpty() }
    }

    /**
     * A path given to a program: absolute, or relative to the project root, where tools/gradle.sh
     * runs Gradle (a unit test itself runs in its module's directory).
     */
    fun file(path: String): File = File(path).let { if (it.isAbsolute) it else File(SourceTree.root(), path) }

    /**
     * Prints [line] on the build's console and into the test report. Gradle keeps what a test
     * writes to System.out for the report and shows it only with --info; what the test JVM writes
     * to its own standard output reaches the console.
     */
    fun say(line: String) {
        console.println(line)
        println(line)
    }

    /**
     * Runs [mainClass] of the test classpath in a new JVM with [jvmOptions] and [arguments],
     * passes on what it prints ([say]), and fails unless it exits with 0.
     */
    fun runInNewJvm(jvmOptions: List<String>, mainClass: String, arguments: List<String> = emptyList()) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val utf8 = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
        val classpath = listOf("-cp", System.getProperty("java.class.path"))
        val process = ProcessBuilder(listOf(java) + utf8 + jvmOptions + classpath + mainClass + arguments)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { say(it) }
        assertEquals("exit status of $mainClass ${jvmOptions.joinToString(" ")}", 0, process.waitFor())
    }

    private val WHITE_SPACE = Regex("""\s+""")

    private val console: PrintStream by lazy { PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8") }
}
