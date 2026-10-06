package io.github.dilshodbekmx.passwordvault.testing.arch

import java.io.File

/*
 * The source scanner behind the build checks that read the code (architecture.md §8.5: R-4, R-6,
 * R-10, R-11, R-13 and the offline class scan). The rules themselves live in the tests that use
 * them (`arch/` in the tests of :core and :app), so this file never spells out a forbidden name and
 * passes its own scans.
 *
 * Comments are ignored: they are blanked before any rule looks at the code. String and character
 * literals are kept, and their extent is known, so a rule can tell `Text("Hi")` from `Text(name)`.
 */

/** A Kotlin or Java source file. [path] is relative to the repository root, with `/`. */
class SourceFile(val path: String, val text: String) {
    val isKotlin: Boolean = path.endsWith(".kt") || path.endsWith(".kts")

    private val lexed: Lexed by lazy { lex(text, isKotlin) }

    /** [text] with every comment replaced by spaces; line breaks, offsets and literals unchanged. */
    val code: String get() = lexed.code

    /** Whether the character at [offset] belongs to a string or character literal (quotes included). */
    fun isLiteral(offset: Int): Boolean = lexed.literal[offset]

    /** The name in the `package` line, or "" when there is none. */
    val packageName: String by lazy {
        Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE).find(code)?.groupValues?.get(1).orEmpty()
    }

    private val lineStarts: IntArray by lazy {
        val starts = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        starts.toIntArray()
    }

    /** The 1-based line of [offset]. */
    fun lineOf(offset: Int): Int {
        val i = lineStarts.binarySearch(offset)
        return if (i >= 0) i + 1 else -i - 1
    }

    /** The original text of 1-based [line], comments included, without the line break. */
    fun lineText(line: Int): String {
        val start = lineStarts[line - 1]
        val end = if (line < lineStarts.size) lineStarts[line] - 1 else text.length
        return text.substring(start, end).trimEnd('\r')
    }

    /**
     * The arguments of the call whose `(` is at [open], split at its top-level commas, as ranges of
     * [code] without the surrounding blanks. Empty for `()`. Commas inside a nested call, a lambda,
     * an index or a literal do not split; generic type arguments are not understood.
     */
    fun arguments(open: Int): List<IntRange> {
        require(code[open] == '(') { "no '(' at $open in $path" }
        val args = mutableListOf<IntRange>()
        var depth = 0
        var start = open + 1
        var i = open + 1
        while (i < code.length) {
            val c = code[i]
            if (!isLiteral(i)) {
                when (c) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> {
                        if (depth == 0) {
                            if (c == ')') {
                                args += trim(start, i)
                                return args.filterNot { it.isEmpty() && args.size == 1 }
                            }
                        } else {
                            depth--
                        }
                    }
                    ',' -> if (depth == 0) {
                        args += trim(start, i)
                        start = i + 1
                    }
                }
            }
            i++
        }
        return args // unbalanced: what was found
    }

    /** Whether [range] of [code] holds a literal outside any brackets of its own. */
    fun hasTopLevelLiteral(range: IntRange): Boolean {
        var depth = 0
        for (i in range) {
            if (isLiteral(i)) {
                if (depth == 0) return true
                continue
            }
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        return false
    }

    private fun trim(from: Int, until: Int): IntRange {
        var a = from
        var b = until - 1
        while (a <= b && code[a].isWhitespace()) a++
        while (b >= a && code[b].isWhitespace()) b--
        return a..b
    }

    override fun toString(): String = path
}

/** One match of a rule: where it is and the line as written. */
data class Finding(val rule: String, val path: String, val line: Int, val source: String) {
    override fun toString(): String = "$path:$line  [$rule]  ${source.trim()}"
}

/** One thing a group of source files must not contain. */
abstract class Rule(
    /** The check's name, such as "R-11", and what it forbids. */
    val id: String,
    val what: String,
) {
    /** The offsets in [SourceFile.code] where [file] breaks the rule. */
    abstract fun offsetsIn(file: SourceFile): List<Int>

    /** One finding per line that breaks the rule. */
    fun check(file: SourceFile): List<Finding> = offsetsIn(file).map { file.lineOf(it) }.distinct().map { line ->
        Finding("$id $what", file.path, line, file.lineText(line))
    }
}

/**
 * A rule that forbids every match of [pattern] in the code (comments ignored, literals included),
 * except where [allowed] says otherwise.
 */
open class PatternRule(
    id: String,
    what: String,
    val pattern: Regex,
    private val allowed: (SourceFile, MatchResult) -> Boolean = { _, _ -> false },
) : Rule(id, what) {
    override fun offsetsIn(file: SourceFile): List<Int> =
        pattern.findAll(file.code).filterNot { allowed(file, it) }.map { it.range.first }.toList()
}

/** Every finding of [rules] in [files], in file and line order. */
fun scan(files: List<SourceFile>, rules: List<Rule>): List<Finding> =
    files.flatMap { file -> rules.flatMap { it.check(file) } }.sortedWith(compareBy({ it.path }, { it.line }, { it.rule }))

/** A readable list of [findings], one per line. */
fun describe(findings: List<Finding>): String =
    if (findings.isEmpty()) "no findings" else findings.joinToString("\n", prefix = "${findings.size} findings:\n")

object SourceTree {
    /**
     * The repository root: the nearest directory, from the working directory up, that holds
     * `settings.gradle.kts`. Unit tests run in their module's directory.
     */
    fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("No settings.gradle.kts above ${File("").absolutePath}")
    }

    /**
     * Every `.kt` and `.java` file under [dirs] (relative to [root]; a missing directory has none),
     * sorted by path.
     */
    fun files(root: File, dirs: List<String>): List<SourceFile> = dirs.flatMap { dir ->
        val base = File(root, dir)
        if (!base.isDirectory) {
            emptyList()
        } else {
            base.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .map { SourceFile(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
                .toList()
        }
    }.sortedBy { it.path }
}

private class Lexed(val code: String, val literal: BooleanArray)

private const val CODE = 0
private const val STRING = 1
private const val RAW = 2

/**
 * Blanks the comments of [src] and marks its literals. Kotlin: nested block comments, string
 * templates (`${…}` is code again, with its own strings and comments), raw strings. Java: plain
 * block comments, text blocks.
 */
private fun lex(src: String, kotlin: Boolean): Lexed {
    val n = src.length
    val out = src.toCharArray()
    val literal = BooleanArray(n)
    // A stack of modes; a CODE frame inside a template keeps the depth of its own braces.
    val modes = ArrayList<Int>()
    val depths = ArrayList<Int>()
    modes += CODE
    depths += -1
    fun at(i: Int) = if (i < n) src[i] else '\u0000'
    fun mark(from: Int, until: Int) {
        for (k in from until minOf(until, n)) literal[k] = true
    }
    fun blank(k: Int) {
        if (k < n && src[k] != '\n' && src[k] != '\r') out[k] = ' '
    }

    var i = 0
    while (i < n) {
        val c = src[i]
        when (modes.last()) {
            CODE -> when {
                c == '/' && at(i + 1) == '/' -> while (i < n && src[i] != '\n') blank(i++)
                c == '/' && at(i + 1) == '*' -> {
                    var depth = 0
                    while (i < n) {
                        if (src[i] == '/' && at(i + 1) == '*' && (kotlin || depth == 0)) {
                            depth++
                            blank(i)
                            blank(i + 1)
                            i += 2
                        } else if (src[i] == '*' && at(i + 1) == '/') {
                            depth--
                            blank(i)
                            blank(i + 1)
                            i += 2
                            if (depth == 0) break
                        } else {
                            blank(i++)
                        }
                    }
                }
                c == '"' && at(i + 1) == '"' && at(i + 2) == '"' -> {
                    mark(i, i + 3)
                    i += 3
                    modes += RAW
                    depths += -1
                }
                c == '"' -> {
                    mark(i, i + 1)
                    i++
                    modes += STRING
                    depths += -1
                }
                c == '\'' -> {
                    val start = i++
                    while (i < n && src[i] != '\'' && src[i] != '\n') i += if (src[i] == '\\') 2 else 1
                    if (i < n && src[i] == '\'') i++
                    mark(start, i)
                }
                c == '{' -> {
                    if (depths.last() >= 0) depths[depths.size - 1] = depths.last() + 1
                    i++
                }
                c == '}' -> {
                    if (depths.last() == 0) {
                        // The end of a template expression: back in its string.
                        modes.removeAt(modes.size - 1)
                        depths.removeAt(depths.size - 1)
                        mark(i, i + 1)
                    } else if (depths.last() > 0) {
                        depths[depths.size - 1] = depths.last() - 1
                    }
                    i++
                }
                else -> i++
            }
            STRING -> when {
                c == '\\' -> {
                    mark(i, i + 2)
                    i += 2
                }
                c == '"' -> {
                    mark(i, i + 1)
                    i++
                    modes.removeAt(modes.size - 1)
                    depths.removeAt(depths.size - 1)
                }
                kotlin && c == '$' && at(i + 1) == '{' -> {
                    mark(i, i + 2)
                    i += 2
                    modes += CODE
                    depths += 0
                }
                c == '\n' -> { // an unterminated string: give up on it at the end of its line
                    modes.removeAt(modes.size - 1)
                    depths.removeAt(depths.size - 1)
                    i++
                }
                else -> {
                    mark(i, i + 1)
                    i++
                }
            }
            else -> when { // RAW
                c == '"' && at(i + 1) == '"' && at(i + 2) == '"' -> {
                    var j = i
                    while (j < n && src[j] == '"') j++ // the last three quotes of a run close it
                    mark(i, j)
                    i = j
                    modes.removeAt(modes.size - 1)
                    depths.removeAt(depths.size - 1)
                }
                kotlin && c == '$' && at(i + 1) == '{' -> {
                    mark(i, i + 2)
                    i += 2
                    modes += CODE
                    depths += 0
                }
                !kotlin && c == '\\' -> {
                    mark(i, i + 2)
                    i += 2
                }
                else -> {
                    mark(i, i + 1)
                    i++
                }
            }
        }
    }
    return Lexed(String(out), literal)
}
