import pv.formats.*

// Finds the smallest nesting depth that overflows the stack, for the import path and for bare parsing.
fun tryDepth(depth: Int, bare: Boolean): String {
    val text = "{\"items\":" + "[".repeat(depth) + "]".repeat(depth) + "}"
    var result = "ok"
    val th = Thread(null, {
        result = try {
            if (bare) { kotlinx.serialization.json.Json.parseToJsonElement(text); "ok" }
            else { Importer.read(text.toByteArray(), 0L) { "x" }; "ok" }
        } catch (e: Importer.Unsupported) { "refused ${e.reason}" }
          catch (e: StackOverflowError) { "StackOverflowError at " + e.stackTrace.take(3).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName } }
    }, "t", (System.getProperty("stack") ?: "1048576").toLong())
    th.start(); th.join()
    return result
}

fun main() {
    for (bare in listOf(true, false)) for (d in listOf(1_000, 10_000, 100_000, 1_000_000)) println("bare=$bare depth=$d: ${tryDepth(d, bare)}")
}
