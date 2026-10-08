package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import java.net.IDN
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The CSV export in the layout Google Password Manager and Chrome write and read (formats.md §4).
 *
 * - **The file**: the header `name,url,username,password,note`, UTF-8 without a byte-order mark,
 *   CRLF after every row; a value is quoted when it holds `,` `"` CR or LF or starts or ends with a
 *   space or a tab (`Csv`). Values are written exactly: nothing is put in front of `=` or `+`
 *   (formats.md §4.6).
 * - **One row per website**, in the order given; an account without a website gets one row with an
 *   empty url. `name` is the account's name. A website without a scheme that looks like a host gets
 *   `https://` (`EntryRules.normalizeUrl`; Chrome refuses `gmail.com`).
 * - **note** is the user's notes and, behind them, the block of [FieldsBlock]: the name when it
 *   differs from the first website's host, the extra fields, and the old password of a pending change.
 * - **password** is the entry's password, the new one while a change is pending (D-10d).
 * - **Deleted accounts have no row** (D-10e).
 * - **Parts**: Google takes at most 3,000 rows and 1,000 KB per file, so the export is cut into
 *   `-part1`, `-part2`, … (`ExportNames.csv`); the rows of one account are never in two parts. A part
 *   that is still over a limit, because one account's rows alone are, is written all the same and
 *   reported ([Part.tooLarge]).
 * - **The report** says, for the summary card of ux.md §5.20.2, which rows Chrome's importer would
 *   refuse and why, by Chrome's own checks in Chrome's own order ([Refusal], formats-review P15).
 * - Unpaired surrogates are written as U+FFFD and counted (formats.md §2 rule 8).
 */
object GoogleCsv {
    /** The header row, exactly as Chrome's exporter writes it. */
    val HEADER: List<String> = listOf("name", "url", "username", "password", "note")

    /** Chrome's `kMaxPasswordsPerCSVFile`: a file with more rows is refused as a whole. */
    const val MAX_ROWS = 3_000

    /** Chrome's `kMaxFileSizeBytes`, 1,000 KB: a larger file is refused as a whole. */
    const val MAX_BYTES = 1_000 * 1_024

    /** Chrome's limits for one row, in bytes of UTF-8: password, username and note. */
    const val MAX_PASSWORD_BYTES = 1_000
    const val MAX_USERNAME_BYTES = 1_000
    const val MAX_NOTE_BYTES = 1_000

    /** Chrome's limit for a url, counted on its canonical form (punycode, percent-encoding). */
    const val MAX_URL_LENGTH = ChromeUrl.MAX_LENGTH

    /** Why Chrome's importer would refuse a row: its own reasons, in the order it checks them. */
    enum class Refusal {
        /** No password (`MISSING_PASSWORD`). */
        NO_PASSWORD,

        /** A password over 1,000 bytes (`LONG_PASSWORD`). */
        LONG_PASSWORD,

        /** A username over 1,000 bytes (`LONG_USERNAME`). */
        LONG_USERNAME,

        /** A note over 1,000 bytes, the block of extra fields included (`LONG_NOTE`). */
        LONG_NOTE,

        /** No website (`MISSING_URL`). */
        NO_WEBSITE,

        /** A website that is no valid `http`, `https` or Android app address (`INVALID_URL`). */
        BAD_WEBSITE,

        /** A website whose canonical form is over 2,048 characters (`LONG_URL`). */
        LONG_WEBSITE,
    }

    /** One row that Chrome's importer would refuse. */
    class Refused(
        /** The id of the account the row belongs to. */
        val entryId: String,
        /** The row's url, as written; empty for [Refusal.NO_WEBSITE] and for an account without a website. */
        val url: String,
        val reason: Refusal,
    ) {
        override fun toString(): String = "Refused(entryId=$entryId, reason=$reason, url hidden)"
    }

    /** One file of the export. */
    class Part(
        /** The rows under the header. */
        val rows: Int,
        /** The size of the file. */
        val bytes: Int,
    ) {
        /** Over one of Chrome's limits for a file: Google refuses this file as a whole. */
        val tooLarge: Boolean get() = rows > MAX_ROWS || bytes > MAX_BYTES

        override fun toString(): String = "Part(rows=$rows, bytes=$bytes)"
    }

    /** What the summary card says before saving (formats.md §4.5, ux.md §5.20.2). */
    class Report(
        /** The accounts written: every account outside Recently deleted. */
        val accounts: Int,
        /** The rows under the headers of all parts. */
        val rows: Int,
        /** The files, in order; more than one are named `-part1`, `-part2`, … */
        val parts: List<Part>,
        /** The rows Chrome's importer would refuse, in the order of the file. */
        val refused: List<Refused>,
        /** The accounts whose note carries a block: a name, extra fields or an old password. */
        val accountsWithBlock: Int,
        /** Unpaired surrogates written as U+FFFD. */
        val replacedSurrogates: Int,
    ) {
        /** How many rows Chrome would refuse for [reason]. */
        fun rows(reason: Refusal): Int = refused.count { it.reason == reason }

        /** The ids of the accounts with a row Chrome would refuse for [reason], each once, in order. */
        fun accounts(reason: Refusal): List<String> =
            refused.asSequence().filter { it.reason == reason }.map { it.entryId }.distinct().toList()

        /** The numbers, from 1, of the parts that are still over one of Chrome's limits for a file. */
        val partsTooLarge: List<Int> get() = parts.indices.filter { parts[it].tooLarge }.map { it + 1 }

        override fun toString(): String =
            "Report(accounts=$accounts, rows=$rows, parts=$parts, refused=${refused.size}, " +
                "accountsWithBlock=$accountsWithBlock, replacedSurrogates=$replacedSurrogates)"
    }

    /** An export: the bytes of each file and the report. */
    class Export(
        /** The files in order, at least one: an empty vault gives one file with the header only. */
        val parts: List<ByteArray>,
        val report: Report,
    ) {
        override fun toString(): String = "Export($report)"
    }

    /**
     * The rows of [entry] as they are written, one per website: name, url, username, password, note.
     * Unpaired surrogates are U+FFFD already.
     */
    fun rows(entry: Entry): List<List<String>> = rowsOf(entry, null).rows

    /** The rows of every account of [entries] outside Recently deleted, in order. */
    fun rows(entries: List<Entry>): List<List<String>> =
        entries.filter { it.deletedAt == null }.flatMap { rowsOf(it, null).rows }

    /** The report alone: what [export] would say. */
    fun report(entries: List<Entry>): Report = export(entries).report

    /** Writes the accounts of [entries] outside Recently deleted, cut into parts Google accepts. */
    fun export(entries: List<Entry>): Export {
        val replaced = TextHygiene.Counter()
        val header = Csv.write(HEADER, emptyList())
        val headerBytes = header.length // ASCII
        val files = ArrayList<ByteArray>()
        val parts = ArrayList<Part>()
        val refused = ArrayList<Refused>()
        var accounts = 0
        var totalRows = 0
        var withBlock = 0
        var text = StringBuilder(header)
        var rowsInPart = 0
        var bytesInPart = headerBytes

        fun endPart() {
            files.add(text.toString().toByteArray(Charsets.UTF_8))
            parts.add(Part(rowsInPart, bytesInPart))
            text = StringBuilder(header)
            rowsInPart = 0
            bytesInPart = headerBytes
        }

        for (entry in entries) {
            if (entry.deletedAt != null) continue
            accounts++
            val account = rowsOf(entry, replaced)
            val rows = account.rows
            if (account.hasBlock) withBlock++
            val chunk = Csv.write(HEADER, rows).substring(header.length)
            val chunkBytes = utf8Length(chunk)
            // An account's rows stay together: a part ends before them when they would not fit.
            if (rowsInPart > 0 && (rowsInPart + rows.size > MAX_ROWS || bytesInPart + chunkBytes > MAX_BYTES)) endPart()
            text.append(chunk)
            rowsInPart += rows.size
            bytesInPart += chunkBytes
            totalRows += rows.size

            val whateverTheUrl = refusalOfAccount(rows[0][USERNAME], rows[0][PASSWORD], rows[0][NOTE])
            for (row in rows) {
                val reason = whateverTheUrl ?: refusalOfUrl(row[URL]) ?: continue
                refused.add(Refused(entry.id, row[URL], reason))
            }
        }
        if (rowsInPart > 0 || files.isEmpty()) endPart()
        return Export(files, Report(accounts, totalRows, parts, refused, withBlock, replaced.count))
    }

    private const val URL = 1
    private const val USERNAME = 2
    private const val PASSWORD = 3
    private const val NOTE = 4

    private class AccountRows(val rows: List<List<String>>, val hasBlock: Boolean)

    /** The rows of [entry]; what was replaced in them is counted in [replaced], once per row it is written in. */
    private fun rowsOf(entry: Entry, replaced: TextHygiene.Counter?): AccountRows {
        val inEveryRow = TextHygiene.Counter()
        val note = FieldsBlock.note(entry)
        val name = TextHygiene.replaceUnpairedSurrogates(entry.title, inEveryRow)
        val username = TextHygiene.replaceUnpairedSurrogates(entry.username, inEveryRow)
        val password = TextHygiene.replaceUnpairedSurrogates(entry.password, inEveryRow)
        val cleanNote = TextHygiene.replaceUnpairedSurrogates(note, inEveryRow)
        val urls = entry.urls.map(EntryRules::normalizeUrl).filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        val rows = urls.map { url -> listOf(name, TextHygiene.replaceUnpairedSurrogates(url, replaced), username, password, cleanNote) }
        replaced?.add(inEveryRow.count * rows.size)
        return AccountRows(rows, hasBlock = note.length != entry.notes.length)
    }

    /** Chrome's checks that do not depend on the url, in its order; null when the row passes them. */
    private fun refusalOfAccount(username: String, password: String, note: String): Refusal? = when {
        password.isEmpty() -> Refusal.NO_PASSWORD
        utf8Length(password) > MAX_PASSWORD_BYTES -> Refusal.LONG_PASSWORD
        utf8Length(username) > MAX_USERNAME_BYTES -> Refusal.LONG_USERNAME
        utf8Length(note) > MAX_NOTE_BYTES -> Refusal.LONG_NOTE
        else -> null
    }

    private fun refusalOfUrl(url: String): Refusal? = when (ChromeUrl.verdict(url)) {
        ChromeUrl.Verdict.OK -> null
        ChromeUrl.Verdict.MISSING -> Refusal.NO_WEBSITE
        ChromeUrl.Verdict.INVALID -> Refusal.BAD_WEBSITE
        ChromeUrl.Verdict.TOO_LONG -> Refusal.LONG_WEBSITE
    }

    /** The number of bytes of [s] in UTF-8; an unpaired surrogate counts as U+FFFD (3 bytes). */
    internal fun utf8Length(s: String): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }
}

/**
 * What Chrome's importer makes of the url of one row (formats.md §4.2 and §4.5, formats-review P15):
 * `GURL(field)` must be valid, its canonical text at most 2,048 characters, and it must be an
 * `http` or `https` address or an Android app address `android://<hash>@<package>/`.
 *
 * This follows Chromium's code as read on 2026-10-07 (`password_importer.cc`, `csv_password.cc`,
 * `credential_utils.cc`, `affiliation_utils.cc`, and `url/url_canon_*.cc`, `url_parse.cc` for the
 * canonical form), closely enough for the summary card:
 * - the scheme is compared without regard to case, and any number of slashes may follow it;
 * - the host is unescaped, turned into punycode, lower-cased, and refused when it is empty or holds
 *   a character Chrome refuses there (`#`, `%`, `/`, `<`, `>`, `?`, `@`, `\`, `^`, `|`, a control
 *   character, a space); a host that ends in a number must be an IPv4 address, and brackets must
 *   hold an IPv6 address;
 * - a port must be a number up to 65535; the scheme's own port is left out;
 * - in the path, the query and the fragment every character outside ASCII counts as its
 *   percent-encoded UTF-8 (six characters for a Cyrillic letter), and so do the ASCII characters
 *   Chrome escapes there; `.` and `..` segments are resolved;
 * - an Android app address needs a base64 hash before the `@`, a package name after it, and nothing
 *   else but a final `/`.
 *
 * Checked against the vectors of Chromium's own unit tests (`url/url_canon_unittest.cc`: hosts, IPv4,
 * IPv6, paths, queries, fragments, whole addresses). Where it knowingly differs:
 * - **A space in a host.** formats-review P15 counts `https://exa mple.com/` as refused, and so does
 *   this. Chromium refuses it only with its feature `DisallowSpaceCharacterInURLHostParsing`, which
 *   its code had off by default on that day (it then writes the space as `%20`); the URL Standard
 *   refuses it.
 * - **Punycode.** `java.net.IDN` is IDNA 2003 where Chrome uses UTS #46. For Latin and Cyrillic names
 *   the two agree. They differ for `ß` and the Greek final sigma, for letters newer than Unicode 3.2,
 *   and for right-to-left names that end in a digit or a vowel sign, which IDNA 2003 refuses: such a
 *   website is counted as invalid here although Chrome takes it.
 * - **Another scheme.** Chrome measures a valid address before it looks at the scheme
 *   (`password_importer.cc`, read again on 2026-10-08), so an `ftp:` or `mailto:` address over 2,048
 *   characters is refused there for its length and here as invalid. The row is refused either way.
 *
 * It decides nothing in this app: our own importer takes every website (formats.md §8.4).
 */
internal object ChromeUrl {
    enum class Verdict { OK, MISSING, INVALID, TOO_LONG }

    /** Chrome's `kMaxUrlLengthForImport`. */
    const val MAX_LENGTH = 2_048

    fun verdict(url: String): Verdict {
        if (url.isEmpty()) return Verdict.MISSING
        val s = trimmed(url)
        val colon = s.indexOf(':')
        if (colon <= 0 || !isScheme(s, colon)) return Verdict.INVALID
        return when (asciiLowerCase(s.substring(0, colon))) {
            "http" -> web(s, colon + 1, "http", 80)
            "https" -> web(s, colon + 1, "https", 443)
            "android" -> android(s, colon + 1)
            // Any other scheme: a valid address for Chrome, perhaps, but not one a password can have.
            else -> Verdict.INVALID
        }
    }

    /**
     * The canonical text Chrome would hold for the `http` or `https` address [url], or null when it
     * is not a valid one. For tests: [verdict] counts this text.
     */
    fun webSpec(url: String): String? {
        val s = trimmed(url)
        val colon = s.indexOf(':')
        if (colon <= 0 || !isScheme(s, colon)) return null
        return when (asciiLowerCase(s.substring(0, colon))) {
            "http" -> canonicalWeb(s, colon + 1, "http", 80)
            "https" -> canonicalWeb(s, colon + 1, "https", 443)
            else -> null
        }
    }

    private fun web(s: String, after: Int, scheme: String, defaultPort: Int): Verdict {
        val spec = canonicalWeb(s, after, scheme, defaultPort) ?: return Verdict.INVALID
        return if (spec.length > MAX_LENGTH) Verdict.TOO_LONG else Verdict.OK
    }

    /** Leading and trailing spaces and control characters go, and tab, CR and LF anywhere. */
    private fun trimmed(url: String): String {
        var start = 0
        var end = url.length
        while (start < end && url[start] <= ' ') start++
        while (end > start && url[end - 1] <= ' ') end--
        val s = url.substring(start, end)
        return if (s.none { it == '\t' || it == '\r' || it == '\n' }) s else s.filterNot { it == '\t' || it == '\r' || it == '\n' }
    }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    private fun isScheme(s: String, end: Int): Boolean {
        if (!isAsciiLetter(s[0])) return false
        for (i in 1 until end) {
            val c = s[i]
            if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '+' || c == '-' || c == '.')) return false
        }
        return true
    }

    private fun asciiLowerCase(s: String): String {
        if (s.none { it in 'A'..'Z' }) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
        return sb.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private const val HEX = "0123456789ABCDEF"

    private fun appendEscapedByte(out: StringBuilder, b: Int) {
        out.append('%').append(HEX[(b shr 4) and 0xF]).append(HEX[b and 0xF])
    }

    /** Appends the code point at [i] of [s] as percent-encoded UTF-8; returns the index after it. */
    private fun appendEscapedCodePoint(out: StringBuilder, s: String, i: Int): Int {
        val c = s[i]
        val paired = Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
        val cp = when {
            paired -> Character.toCodePoint(c, s[i + 1])
            Character.isSurrogate(c) -> 0xFFFD
            else -> c.code
        }
        when {
            cp < 0x80 -> appendEscapedByte(out, cp)
            cp < 0x800 -> {
                appendEscapedByte(out, 0xC0 or (cp shr 6))
                appendEscapedByte(out, 0x80 or (cp and 0x3F))
            }
            cp < 0x10000 -> {
                appendEscapedByte(out, 0xE0 or (cp shr 12))
                appendEscapedByte(out, 0x80 or ((cp shr 6) and 0x3F))
                appendEscapedByte(out, 0x80 or (cp and 0x3F))
            }
            else -> {
                appendEscapedByte(out, 0xF0 or (cp shr 18))
                appendEscapedByte(out, 0x80 or ((cp shr 12) and 0x3F))
                appendEscapedByte(out, 0x80 or ((cp shr 6) and 0x3F))
                appendEscapedByte(out, 0x80 or (cp and 0x3F))
            }
        }
        return if (paired) i + 2 else i + 1
    }

    /** Appends [text], escaping what is outside ASCII and the ASCII characters for which [keep] is false. */
    private fun appendComponent(out: StringBuilder, text: String, keep: (Char) -> Boolean) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code >= 0x80) {
                i = appendEscapedCodePoint(out, text, i)
            } else {
                if (keep(c)) out.append(c) else appendEscapedByte(out, c.code)
                i++
            }
        }
    }

    private fun isUserInfoChar(c: Char) = isAsciiLetter(c) || isAsciiDigit(c) || c in "!$%&()*+,-._~"

    private fun isQueryChar(c: Char) = c > ' ' && c.code < 0x7F && c != '"' && c != '#' && c != '\'' && c != '<' && c != '>'

    private fun isFragmentChar(c: Char) = c > ' ' && c.code < 0x7F && c != '"' && c != '<' && c != '>' && c != '`'

    /** The ASCII characters Chrome writes as they are in a path; `.`, `%` and `\` are handled apart. */
    private fun isPathChar(c: Char) =
        c > ' ' && c.code < 0x7F && c != '"' && c != '#' && c != '<' && c != '>' && c != '?' && c != '^' && c != '`' &&
            c != '{' && c != '|' && c != '}'

    private class Authority(val userInfo: String?, val host: String, val port: String?, val end: Int)

    /** Splits what follows the scheme's colon: slashes, then user info, host and port up to the path. */
    private fun authority(s: String, after: Int): Authority {
        var i = after
        while (i < s.length && (s[i] == '/' || s[i] == '\\')) i++
        var end = i
        while (end < s.length && s[end] != '/' && s[end] != '\\' && s[end] != '?' && s[end] != '#') end++
        val all = s.substring(i, end)
        val at = all.lastIndexOf('@')
        val server = all.substring(at + 1)
        // The port follows the last colon, unless that colon is inside the brackets of an IPv6 address.
        var bracket = if (server.startsWith("[")) server.length else -1
        var colon = -1
        for (k in server.indices) {
            when (server[k]) {
                ']' -> bracket = k
                ':' -> colon = k
            }
        }
        val hasPort = colon > bracket
        return Authority(
            userInfo = if (at >= 0) all.substring(0, at) else null,
            host = if (hasPort) server.substring(0, colon) else server,
            port = if (hasPort) server.substring(colon + 1) else null,
            end = end,
        )
    }

    /** The port as Chrome keeps it: -1 for none, -2 for one it refuses. */
    private fun port(text: String?): Int {
        if (text.isNullOrEmpty()) return -1
        val digits = text.trimStart('0')
        if (digits.isEmpty()) return 0
        if (digits.length > 5 || !digits.all(::isAsciiDigit)) return -2
        val n = digits.toInt()
        return if (n > 65_535) -2 else n
    }

    private fun canonicalWeb(s: String, after: Int, scheme: String, defaultPort: Int): String? {
        val authority = authority(s, after)
        val host = canonicalHost(authority.host) ?: return null
        val port = port(authority.port)
        if (port == -2) return null
        val out = StringBuilder(s.length + 16)
        out.append(scheme).append("://")
        val userInfo = authority.userInfo
        if (userInfo != null) {
            val split = userInfo.indexOf(':')
            val user = if (split >= 0) userInfo.substring(0, split) else userInfo
            val password = if (split >= 0) userInfo.substring(split + 1) else ""
            if (user.isNotEmpty() || password.isNotEmpty()) {
                appendComponent(out, user, ::isUserInfoChar)
                if (password.isNotEmpty()) {
                    out.append(':')
                    appendComponent(out, password, ::isUserInfoChar)
                }
                out.append('@')
            }
        }
        out.append(host)
        if (port >= 0 && port != defaultPort) out.append(':').append(port)

        val rest = s.substring(authority.end)
        val hash = rest.indexOf('#')
        val beforeFragment = if (hash >= 0) rest.substring(0, hash) else rest
        val question = beforeFragment.indexOf('?')
        appendPath(out, if (question >= 0) beforeFragment.substring(0, question) else beforeFragment)
        if (question >= 0) {
            out.append('?')
            appendComponent(out, beforeFragment.substring(question + 1), ::isQueryChar)
        }
        if (hash >= 0) {
            out.append('#')
            appendComponent(out, rest.substring(hash + 1), ::isFragmentChar)
        }
        return out.toString()
    }

    /** The length of a dot at [i] of [path]: 1 for `.`, 3 for `%2e` or `%2E`, 0 for anything else. */
    private fun dotLength(path: String, i: Int): Int = when {
        i >= path.length -> 0
        path[i] == '.' -> 1
        path[i] == '%' && i + 2 < path.length && path[i + 1] == '2' && (path[i + 2] == 'e' || path[i + 2] == 'E') -> 3
        else -> 0
    }

    private fun isSlash(c: Char) = c == '/' || c == '\\'

    /** Appends the canonical form of [path], which is empty or starts with a slash. */
    private fun appendPath(out: StringBuilder, path: String) {
        val begin = out.length
        if (path.isEmpty()) {
            out.append('/')
            return
        }
        var i = 0
        while (i < path.length) {
            val c = path[i]
            if (c.code >= 0x80) {
                i = appendEscapedCodePoint(out, path, i)
                continue
            }
            val dot = dotLength(path, i)
            if (dot > 0) {
                val afterSlash = out.length > begin && out[out.length - 1] == '/'
                var segment = 0 // 1: this is a "." segment; 2: a ".." segment
                var consumed = 0
                if (afterSlash) {
                    val next = i + dot
                    val second = dotLength(path, next)
                    when {
                        next >= path.length -> segment = 1
                        isSlash(path[next]) -> {
                            segment = 1
                            consumed = 1
                        }
                        second > 0 && next + second >= path.length -> {
                            segment = 2
                            consumed = second
                        }
                        second > 0 && isSlash(path[next + second]) -> {
                            segment = 2
                            consumed = second + 1
                        }
                    }
                }
                when (segment) {
                    0 -> out.append(path, i, i + dot) // part of a name: kept as written
                    2 -> {
                        // Back to the slash before the last segment, but never above the first one.
                        var k = out.length - 1
                        if (k > begin) {
                            do k-- while (out[k] != '/' && k > begin)
                            out.setLength(k + 1)
                        }
                    }
                }
                i += dot + consumed
                continue
            }
            when {
                c == '\\' -> out.append('/')
                c == '%' -> {
                    val escape = i + 2 < path.length && hexValue(path[i + 1]) >= 0 && hexValue(path[i + 2]) >= 0
                    if (escape) {
                        out.append(path, i, i + 3)
                        i += 2
                    } else {
                        out.append('%')
                    }
                }
                isPathChar(c) -> out.append(c)
                else -> appendEscapedByte(out, c.code)
            }
            i++
        }
    }

    // ------------------------------------------------------------------ hosts

    /** Whether Chrome refuses [c] in a host (its `kHostCharLookup`, with the space refused). */
    private fun isForbiddenInHost(c: Char) =
        c <= ' ' || c.code == 0x7F || c == '#' || c == '%' || c == '/' || c == '<' || c == '>' || c == '?' || c == '@' ||
            c == '\\' || c == '^' || c == '|'

    /** The canonical host, or null when Chrome refuses it. */
    private fun canonicalHost(text: String): String? {
        if (text.isEmpty()) return null
        val unescaped = if (text.indexOf('%') >= 0) unescapeHost(text) ?: return null else text
        val ascii = if (unescaped.all { it.code < 0x80 }) {
            unescaped
        } else {
            if (unescaped.any { it.code < 0x80 && isForbiddenInHost(it) }) return null
            try {
                IDN.toASCII(unescaped, IDN.ALLOW_UNASSIGNED)
            } catch (_: RuntimeException) { // not a name: a label too long, a forbidden letter
                return null
            }
        }
        val out = StringBuilder(ascii.length)
        for (c in ascii) {
            when {
                c.code >= 0x80 || isForbiddenInHost(c) -> return null
                c == '*' -> out.append("%2A")
                c in 'A'..'Z' -> out.append(c + 32)
                else -> out.append(c)
            }
        }
        val host = out.toString()
        when (val v4 = ipv4(host)) {
            IPV4_BROKEN -> return null
            IPV4_NOT_ONE -> Unit
            else -> return "${(v4 shr 24) and 0xFF}.${(v4 shr 16) and 0xFF}.${(v4 shr 8) and 0xFF}.${v4 and 0xFF}"
        }
        if (host.startsWith("[") && host.endsWith("]")) ipv6(host.substring(1, host.length - 1))?.let { return it }
        return if (host.any { it == '[' || it == ']' || it == ':' }) null else host
    }

    /** `%XX` in a host stands for a byte of UTF-8; null when an escape or the UTF-8 is not valid. */
    private fun unescapeHost(text: String): String? {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(utf8.size)
        var n = 0
        var i = 0
        while (i < utf8.size) {
            val b = utf8[i]
            if (b == '%'.code.toByte()) {
                if (i + 2 >= utf8.size) return null
                val high = hexValue(utf8[i + 1].toInt().toChar())
                val low = hexValue(utf8[i + 2].toInt().toChar())
                if (high < 0 || low < 0) return null
                bytes[n++] = ((high shl 4) or low).toByte()
                i += 3
            } else {
                bytes[n++] = b
                i++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, n))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private const val IPV4_NOT_ONE = -1L
    private const val IPV4_BROKEN = -2L

    /**
     * One part of an IPv4 address: its value; [IPV4_NOT_ONE] when it is not a number; [IPV4_BROKEN]
     * when it is one that does not fit 32 bits, or has a digit its base does not have.
     */
    private fun ipv4Part(part: String): Long {
        if (part.isEmpty()) return IPV4_NOT_ONE
        var base = 10
        var start = 0
        if (part[0] == '0' && part.length > 1) {
            if (part[1] == 'x' || part[1] == 'X') {
                base = 16
                start = 2
            } else {
                base = 8
                start = 1
            }
        }
        var value = 0L
        var tooLarge = false
        var wrongDigit = false
        for (i in start until part.length) {
            val digit = hexValue(part[i])
            if (digit < 0 || (base != 16 && digit > 9)) return IPV4_NOT_ONE
            if (digit >= base) wrongDigit = true // an 8 or a 9 in an octal number
            if (!tooLarge) {
                value = value * base + digit
                if (value > 0xFFFF_FFFFL) tooLarge = true
            }
        }
        return if (wrongDigit || tooLarge) IPV4_BROKEN else value
    }

    /**
     * [host] as an IPv4 address: the 32 bits; [IPV4_NOT_ONE] when its last part is not a number (a
     * name); [IPV4_BROKEN] when it ends in a number and is no address.
     */
    private fun ipv4(host: String): Long {
        val text = if (host.endsWith(".")) host.substring(0, host.length - 1) else host
        if (text.isEmpty()) return IPV4_NOT_ONE
        val parts = text.split('.')
        val last = ipv4Part(parts[parts.size - 1])
        if (last == IPV4_NOT_ONE) return IPV4_NOT_ONE
        if (last == IPV4_BROKEN || parts.size > 4) return IPV4_BROKEN
        var address = 0L
        for (i in 0 until parts.size - 1) {
            val value = ipv4Part(parts[i])
            if (value < 0 || value > 0xFF) return IPV4_BROKEN
            address = (address shl 8) or value
        }
        val bitsLeft = 8 * (5 - parts.size)
        if (last shr bitsLeft != 0L) return IPV4_BROKEN
        return (address shl bitsLeft) or last
    }

    /** The canonical form, brackets included, of the IPv6 address [text] (without brackets), or null. */
    private fun ipv6(text: String): String? {
        if (text.isEmpty()) return null
        val groups = ArrayList<Int>(8)
        var gap = -1 // how many groups stand before "::", or -1 without one
        var tail = -1L // an IPv4 address at the end, or -1
        var i = 0
        var start = 0
        while (true) {
            val atEnd = i >= text.length
            val colon = !atEnd && text[i] == ':'
            val doubleColon = colon && i + 1 < text.length && text[i + 1] == ':'
            if (colon || atEnd) {
                val length = i - start
                if (length > 4) return null
                if (length == 0 && !((doubleColon && i == 0) || (atEnd && gap == groups.size))) return null
                if (length > 0) {
                    if (groups.size >= 8) return null
                    groups.add(text.substring(start, i).toInt(16))
                }
            }
            if (atEnd) break
            if (doubleColon) {
                if (gap != -1) return null
                gap = groups.size
                i++
            }
            if (colon) {
                start = i + 1
            } else if (hexValue(text[i]) < 0) {
                // Not a hex digit: the rest must be an IPv4 address of four parts, without a final dot.
                val rest = text.substring(start)
                if (rest.endsWith(".") || rest.count { it == '.' } != 3) return null
                tail = ipv4(rest)
                if (tail < 0) return null
                break
            }
            i++
        }
        val bytes = groups.size * 2 + (if (tail >= 0) 4 else 0)
        val gapBytes = if (gap < 0) 0 else maxOf(2, 16 - bytes)
        if (bytes + gapBytes != 16) return null
        val words = IntArray(8)
        var w = 0
        for (g in 0..groups.size) {
            if (g == gap) w += gapBytes / 2
            if (g < groups.size) words[w++] = groups[g]
        }
        if (tail >= 0) {
            words[6] = ((tail shr 16) and 0xFFFF).toInt()
            words[7] = (tail and 0xFFFF).toInt()
        }
        // The longest run of two or more zero words becomes "::"; the first one when two are equal.
        var bestStart = -1
        var bestLength = 0
        var k = 0
        while (k < 8) {
            if (words[k] != 0) {
                k++
                continue
            }
            var end = k
            while (end < 8 && words[end] == 0) end++
            if (end - k > 1 && end - k > bestLength) {
                bestStart = k
                bestLength = end - k
            }
            k = end
        }
        val out = StringBuilder("[")
        k = 0
        while (k < 8) {
            if (k == bestStart) {
                out.append(if (k == 0) "::" else ":")
                k += bestLength
                continue
            }
            out.append(Integer.toHexString(words[k]))
            k++
            if (k < 8) out.append(':')
        }
        return out.append(']').toString()
    }

    // ------------------------------------------------------------------ Android apps

    /** `%XX` becomes its byte, as a character; anything else stays (Chrome's binary unescape). */
    private fun unescapeBinary(text: String): String {
        if (text.indexOf('%') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length && hexValue(text[i + 1]) >= 0 && hexValue(text[i + 2]) >= 0) {
                out.append(((hexValue(text[i + 1]) shl 4) or hexValue(text[i + 2])).toChar())
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Chrome's `IsValidAndroidFacetURI`: `android://<base64 hash>@<package>` with at most a final slash. */
    private fun android(s: String, after: Int): Verdict {
        if (s.length > MAX_LENGTH) return Verdict.TOO_LONG
        val authority = authority(s, after)
        val userInfo = authority.userInfo ?: return Verdict.INVALID
        val split = userInfo.indexOf(':')
        if (split >= 0 && split < userInfo.length - 1) return Verdict.INVALID // a password
        var hash = unescapeBinary(if (split >= 0) userInfo.substring(0, split) else userInfo)
        if (hash.isEmpty()) return Verdict.INVALID
        while (hash.length % 4 != 0) hash += "="
        val padding = hash.indexOf('=')
        if (padding >= 0 && (hash.length - padding > 2 || hash.substring(padding).any { it != '=' })) return Verdict.INVALID
        if (!hash.all { isAsciiLetter(it) || isAsciiDigit(it) || it == '-' || it == '_' || it == '=' }) return Verdict.INVALID
        val packageName = unescapeBinary(authority.host)
        if (packageName.isEmpty() || !packageName.all { isAsciiLetter(it) || isAsciiDigit(it) || it == '.' || it == '_' }) {
            return Verdict.INVALID
        }
        if (!authority.port.isNullOrEmpty()) return Verdict.INVALID
        val rest = s.substring(authority.end)
        return if (rest.isEmpty() || rest == "/") Verdict.OK else Verdict.INVALID
    }
}
