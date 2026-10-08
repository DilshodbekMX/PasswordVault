package io.github.dilshodbekmx.passwordvault.core.formats.writers

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * A workbook read back for the tests with `java.util.zip` and the JDK's XML parser, nothing of ours
 * (architecture.md §8.1): the parts, the sheet's name, and the cells as a grid of text.
 */
internal class XlsxReader private constructor(
    /** The names of the zip entries, in the order of the file. */
    val names: List<String>,
    private val parts: Map<String, ByteArray>,
) {
    fun text(part: String): String = String(parts.getValue(part), Charsets.UTF_8)

    fun xml(part: String): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue(part)))

    val sheetName: String by lazy {
        (xml("xl/workbook.xml").documentElement.getElementsByTagNameNS(NS, "sheet").item(0) as Element).getAttribute("name")
    }

    /** The shared strings, with OOXML's `_xHHHH_` escapes decoded as Excel and LibreOffice do. */
    val sharedStrings: List<String> by lazy {
        val items = xml("xl/sharedStrings.xml").documentElement.getElementsByTagNameNS(NS, "si")
        List(items.length) { decode(items.item(it).textContent) }
    }

    /** The widths of the columns, as `xl/worksheets/sheet1.xml` gives them. */
    val widths: List<Double> by lazy {
        val cols = xml(SHEET).documentElement.getElementsByTagNameNS(NS, "col")
        List(cols.length) { (cols.item(it) as Element).getAttribute("width").toDouble() }
    }

    /**
     * The sheet as rows of [columns] cells, an empty cell as "": every cell must be a shared string
     * (`t="s"`), and its reference must be the place it is read at.
     */
    fun grid(columns: Int = 11): List<List<String>> {
        val rows = xml(SHEET).documentElement.getElementsByTagNameNS(NS, "row")
        return List(rows.length) { r ->
            val row = rows.item(r) as Element
            check(row.getAttribute("r") == (r + 1).toString()) { "row ${r + 1} is numbered ${row.getAttribute("r")}" }
            val out = MutableList(columns) { "" }
            val cells = row.getElementsByTagNameNS(NS, "c")
            for (c in 0 until cells.length) {
                val cell = cells.item(c) as Element
                check(cell.getAttribute("t") == "s") { "a cell that is not a shared string" }
                val ref = cell.getAttribute("r")
                val column = ref[0] - 'A'
                check(ref == ('A' + column).toString() + (r + 1)) { "cell $ref in row ${r + 1}" }
                out[column] = sharedStrings[cell.getElementsByTagNameNS(NS, "v").item(0).textContent.toInt()]
            }
            out
        }
    }

    /** The style index of every cell, row by row: 1 the header's, 2 the text format of the data. */
    fun styles(): List<List<String>> {
        val rows = xml(SHEET).documentElement.getElementsByTagNameNS(NS, "row")
        return List(rows.length) { r ->
            val cells = (rows.item(r) as Element).getElementsByTagNameNS(NS, "c")
            List(cells.length) { (cells.item(it) as Element).getAttribute("s") }
        }
    }

    companion object {
        const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val SHEET = "xl/worksheets/sheet1.xml"

        fun read(bytes: ByteArray): XlsxReader {
            val names = ArrayList<String>()
            val parts = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names.add(entry.name)
                    parts[entry.name] = zip.readBytes()
                }
            }
            return XlsxReader(names, parts)
        }

        private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

        /** `_xHHHH_` is the character U+HHHH (ECMA-376 Part 1 §22.4.2.4); `_x005F_` is a literal underscore. */
        fun decode(text: String): String = ESCAPE.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }
    }
}
