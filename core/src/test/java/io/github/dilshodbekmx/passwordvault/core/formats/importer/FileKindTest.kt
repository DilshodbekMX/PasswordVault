package io.github.dilshodbekmx.passwordvault.core.formats.importer

import io.github.dilshodbekmx.passwordvault.core.formats.FileKind
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.formats.Xlsx
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.read
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** formats.md §8.2: what a file is, by its content, and where the importer sends each kind. */
class FileKindTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    private val bom = bytes(0xEF, 0xBB, 0xBF)

    /** A real backup file: vector A of architecture.md §4.13. */
    private fun backup(): ByteArray = ImportFixtures::class.java.getResourceAsStream("/vectors/v1-container.pvault")!!.use { it.readBytes() }

    /** A real `.xlsx`, as our own writer makes it. */
    private fun workbook(): ByteArray {
        val out = ByteArrayOutputStream()
        Xlsx.write(out, "Passwords", listOf(Xlsx.Column("Name", 20.0)), listOf(listOf("Gmail")), "PasswordVault")
        return out.toByteArray()
    }

    @Test
    fun theFirstBytesDecide() {
        assertEquals(FileKind.EMPTY, FileKind.of(ByteArray(0)))
        assertEquals(FileKind.BACKUP, FileKind.of(backup()))
        assertEquals(FileKind.BACKUP, FileKind.of(utf8("PVAULT02") + ByteArray(100))) // a later framing: the backup reader's NEWER
        assertEquals(FileKind.CSV, FileKind.of(utf8("PVAULT,x\n1,2\n"))) // no digits: just text
        assertEquals(FileKind.SPREADSHEET, FileKind.of(workbook()))
        assertEquals(FileKind.SPREADSHEET, FileKind.of(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0, 0, 0, 0)))
        assertEquals(FileKind.KEEPASS, FileKind.of(bytes(0x03, 0xD9, 0xA2, 0x9A, 0x67, 0xFB, 0x4B, 0xB5, 0, 0, 4, 0)))
        assertEquals(FileKind.JSON, FileKind.of(utf8("{\"items\":[]}")))
        assertEquals(FileKind.JSON, FileKind.of(utf8(" \r\n\t{\"items\":[]}")))
        assertEquals(FileKind.JSON, FileKind.of(bom + bom + utf8("{\"items\":[]}"))) // formats-review P11
        assertEquals(FileKind.JSON, FileKind.of(bytes(0xFF, 0xFE) + "{\"items\":[]}".toByteArray(Charsets.UTF_16LE)))
        assertEquals(FileKind.CSV, FileKind.of(utf8("name,url,username,password\n")))
        assertEquals(FileKind.CSV, FileKind.of("name,url,username,password\nПочта,pochta.uz,ali,Парол\n".toByteArray(Charsets.UTF_16LE)))
        assertEquals(FileKind.CSV, FileKind.of(bytes(0xC0, 0xE1, 0xE2))) // Windows-1251 text
        // A PNG: NUL bytes at both parities, so not UTF-16; not text.
        assertEquals(FileKind.BINARY, FileKind.of(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52)))
        // Only the start is looked at: a NUL after the first 8 KB does not make a text file binary.
        assertEquals(FileKind.CSV, FileKind.of(utf8("a,b\n" + "x,y\n".repeat(3_000)) + bytes(0)))
    }

    @Test
    fun theImporterSendsEachKindItsWay() {
        assertEquals(Importer.Result.Empty, read(ByteArray(0)))
        for ((bytes, kind) in listOf(backup() to FileKind.BACKUP, workbook() to FileKind.SPREADSHEET,
            bytes(0x03, 0xD9, 0xA2, 0x9A, 0x67, 0xFB, 0x4B, 0xB5) to FileKind.KEEPASS)) {
            val result = read(bytes)
            assertTrue(result.toString(), result is Importer.Result.NotForImport)
            assertEquals(kind, (result as Importer.Result.NotForImport).kind)
        }
        assertEquals(Importer.Result.NotText, read(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)))
        // JSON is UTF-8 or nothing (formats.md §5.2): a UTF-16 JSON file is refused where its first byte is.
        val utf16 = read(bytes(0xFF, 0xFE) + "{\"items\":[]}".toByteArray(Charsets.UTF_16LE))
        assertEquals(ParseError(0, "malformed-utf8"), (utf16 as Importer.Result.Unreadable).error)
        // Larger than 10 MB: refused before anything is read.
        val big = read(ByteArray(Importer.MAX_BYTES + 1) { 'a'.code.toByte() })
        assertEquals(Importer.MAX_BYTES, (big as Importer.Result.TooBig).limit)
        // White space only, and a header with nothing under it.
        assertEquals(Importer.Result.Empty, read(utf8(" \r\n \n")))
        assertTrue(read(utf8("name,url,username,password\n")) is Importer.Result.Read)
        // JSON without "items"; Bitwarden's encrypted export.
        assertEquals(Importer.Result.NotAnExport, read(utf8("{\"folders\":[]}")))
        val encrypted = read(utf8("{\"encrypted\":true,\"encKeyValidation_DO_NOT_EDIT\":\"2.x\",\"items\":[]}"))
        assertTrue(encrypted is Importer.Result.Encrypted && !encrypted.passwordProtected)
    }
}
