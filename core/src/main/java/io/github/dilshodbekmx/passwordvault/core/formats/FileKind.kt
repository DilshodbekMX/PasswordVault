package io.github.dilshodbekmx.passwordvault.core.formats

/**
 * What a file chosen on the import screen is, by its content and never by its name or type
 * (formats.md §8.2, ux.md §5.21): file pickers label a CSV `text/csv`, `text/comma-separated-values`
 * or `application/vnd.ms-excel`, and a backup sent through a messenger may lose its name.
 *
 * | First bytes or shape | Kind |
 * |---|---|
 * | nothing | [EMPTY] |
 * | `PVAULT` and two ASCII digits | [BACKUP]: restore or merge (formats.md §3); a later framing (`PVAULT02`) is the backup reader's NEWER |
 * | `PK 03 04` (a zip package: `.xlsx`) or `D0 CF 11 E0 A1 B1 1A E1` (a Compound File: `.xls`, a locked `.xlsx`) | [SPREADSHEET] |
 * | `03 D9 A2 9A` (the first signature of every KeePass database) | [KEEPASS] |
 * | text whose first character, after byte-order marks and white space, is `{` | [JSON] |
 * | text with a NUL character among its first 8 KB | [BINARY] |
 * | other text | [CSV] |
 *
 * "Text" is the start of the file as `TextDecoding` decodes it, so a UTF-16 file is text too.
 */
enum class FileKind {
    /** No bytes at all. */
    EMPTY,

    /** A backup file of this app: restore or merge, not the importer (formats.md §3). */
    BACKUP,

    /** "This is an Excel file. In Excel choose File › Save As › CSV UTF-8, then import that file." */
    SPREADSHEET,

    /** "This is a KeePass database. In KeePassXC: Database › Export › CSV." */
    KEEPASS,

    /** Bitwarden's JSON or ours (formats.md §5.2). */
    JSON,

    /** Binary data that is none of the above: not a file the app can read. */
    BINARY,

    /** Any other text: read as CSV (formats.md §8.2, §8.3). */
    CSV,
    ;

    companion object {
        /** How much of the file is decoded to tell JSON, CSV and binary data apart. */
        private const val SNIFF_BYTES = 8_192

        private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val COMPOUND_FILE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        private val KEEPASS_SIGNATURE = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte())
        private val PVAULT = "PVAULT".toByteArray(Charsets.US_ASCII)

        /** The kind of the file whose bytes are [bytes] (or begin with them). Never throws. */
        fun of(bytes: ByteArray): FileKind {
            if (bytes.isEmpty()) return EMPTY
            if (isBackup(bytes)) return BACKUP
            if (startsWith(bytes, ZIP) || startsWith(bytes, COMPOUND_FILE)) return SPREADSHEET
            if (startsWith(bytes, KEEPASS_SIGNATURE)) return KEEPASS
            val start = if (bytes.size <= SNIFF_BYTES) bytes else bytes.copyOf(SNIFF_BYTES)
            val text = TextDecoding.decode(start).text // leading byte-order marks are already gone
            var i = 0
            while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r' || text[i] == '\n')) i++
            if (i < text.length && text[i] == '{') return JSON
            if (text.indexOf('\u0000') >= 0) return BINARY
            return CSV
        }

        /** `PVAULT` and two ASCII digits: our container, whatever its framing version (architecture.md §4.1). */
        private fun isBackup(bytes: ByteArray): Boolean {
            if (bytes.size < PVAULT.size + 2 || !startsWith(bytes, PVAULT)) return false
            return bytes[6] in '0'.code.toByte()..'9'.code.toByte() && bytes[7] in '0'.code.toByte()..'9'.code.toByte()
        }

        private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
            if (bytes.size < prefix.size) return false
            for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
            return true
        }
    }
}
