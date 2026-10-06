import pv.formats.*
import java.io.File

/** Prints what the importer makes of each file given on the command line. */
fun main(args: Array<String>) {
    for (p in args) {
        try {
            val r = Importer.read(File(p).readBytes(), 0L)
            println("${File(p).name}: ${r.format}, ${r.entries.size} entries, charset ${r.charset}, delimiter ${r.delimiter?.let { "'" + it + "'" }}, " +
                "columns ${r.columns}, problems ${r.problems}, skipped ${r.skipped.size}, empty rows ${r.emptyRowsSkipped}, control chars removed ${r.controlCharsRemoved}")
            r.entries.take(2).forEach { e -> println("   ${e.title} | ${e.username} | ${"*".repeat(minOf(e.password.length, 8))} | ${e.urls} | fields ${e.fields.map { it.name }}") }
        } catch (e: Importer.Unsupported) { println("${File(p).name}: refused, ${e.reason}") }
    }
}
