import pv.formats.*
fun probe(text: String, stack: Long): String {
    var r = "ok"
    val th = Thread(null, {
        r = try { Importer.read(text.toByteArray(), 0L) { "x" }; "ok" }
            catch (e: Importer.Unsupported) { "refused ${e.reason}" }
            catch (e: StackOverflowError) { "StackOverflowError" }
    }, "t", stack)
    th.start(); th.join(); return r
}
fun main() {
    for (stack in listOf(1L shl 20, 512L shl 10)) {
        var lo = 100; var hi = 10_000
        while (hi - lo > 50) { val mid = (lo + hi) / 2; if (probe("{\"items\":" + "[".repeat(mid) + "]".repeat(mid) + "}", stack) == "StackOverflowError") hi = mid else lo = mid }
        val obj = 5000
        println("stack ${stack / 1024} KB: arrays overflow from depth ~$hi (file ${2 * hi + 10} bytes); objects depth $obj: " +
            probe("{\"items\":[" + "{\"a\":".repeat(obj) + "1" + "}".repeat(obj) + "]}", stack))
    }
}
