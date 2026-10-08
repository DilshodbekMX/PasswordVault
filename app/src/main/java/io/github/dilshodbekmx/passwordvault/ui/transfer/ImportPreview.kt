package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.core.formats.ImportAccount
import io.github.dilshodbekmx.passwordvault.core.formats.ImportChange
import io.github.dilshodbekmx.passwordvault.core.formats.ImportFile
import io.github.dilshodbekmx.passwordvault.core.formats.ImportLeftOut
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlan
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlanner
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding

/**
 * What the import's preview shows (ux.md §5.21, "Preview"; formats.md §8): made by [of] from the
 * importer's [ImportFile] and the planner's [ImportPlan], before anything is saved.
 *
 * **It never holds a password** (build plan 5.4's first acceptance line). It holds names and
 * usernames of accounts, counts and line numbers, and nothing else of the file:
 * - The sample's names and usernames come only from rows with the right number of values. In a row
 *   with a wrong number, the values may have moved to another column (a comma inside a password
 *   splits it, and its second half lands in the next column), so such a row is named by its line
 *   only, never by a value ([AccountName.Line]).
 * - A CSV row that is left out is named by its line too ([LeftOutRow]): the importer does not say
 *   whether its values had moved. A JSON item has no columns, so it is named by its name.
 *
 * Its `toString` shows counts only (architecture.md §5.6).
 */
internal data class ImportPreview(
    /** The layout the importer recognised. */
    val format: Importer.Format,
    /** CSV: how the bytes became text; null for JSON, which is strict UTF-8. */
    val decoding: DecodingLine?,
    /** Up to three accounts, those with letters outside ASCII first, so that garbled text shows. */
    val samples: List<SampleRow>,
    /** "Found 48 accounts." */
    val found: Int,
    /** "Import 45 accounts": how many accounts the import adds. */
    val toAdd: Int,
    /** "3 are already here … and will be skipped." */
    val alreadyHere: Int,
    /** Rows that repeat an earlier row of the file exactly: imported once. */
    val repeated: Int,
    /** "2 have the same website and username but a different password; they will be added so you can compare." */
    val conflicts: Int,
    /** "1 row has no password." */
    val withoutPassword: Int,
    /** The rows with the wrong number of values (formats-review P4), in the file's order. */
    val wrongRows: List<WrongRow>,
    /** The user confirmed those rows: they are imported as they are. */
    val wrongRowsIncluded: Boolean,
    /** What is not imported, listed by line, place or name. */
    val leftOut: List<LeftOutRow>,
    /** What the importer changed in accounts to add, one line per account and kind ("Gmail: 3 websites moved to notes"). */
    val changes: List<ChangeRow>,
    /** "1 note was longer than 10,000 characters and was shortened." */
    val notesCut: Int,
    /** "6 rows were the same login on related websites and were kept together as 2 accounts."; null when none were. */
    val related: Related?,
    /** "Keep them separate" is on. */
    val keepSeparate: Boolean,
    /** The "Tidy names" switch: on or off, and two names of the file before and after; no switch without examples. */
    val tidy: Tidy?,
    /** Invisible characters removed from names, usernames, websites, notes and fields. */
    val removedControls: Int,
    /** Halves of character pairs that became "�". */
    val replacedCharacters: Int,
    /** CSV rows with no value at all, skipped. */
    val emptyRows: Int,
    /** CSV: the line of a quote that was never closed, or 0. */
    val unclosedQuoteLine: Int,
    /** Our JSON with data of a newer version of the app, which is not imported (the owner's decision 9 of 2026-10-08). */
    val newerData: Boolean,
    /** CSV: columns that another column's meaning took (now extra fields): "Check columns". */
    val ambiguousColumns: Int,
    /** CSV: the user chose the columns' meanings. */
    val columnsChosen: Boolean,
    /** The vault still fits in one file after the import (architecture.md §3.5); false refuses the import. */
    val fits: Boolean,
) {
    /** "Read as UTF-8" and why (formats.md §8.2), and the lines with characters that could not be read. */
    data class DecodingLine(
        val encoding: TextDecoding.Encoding,
        val why: TextDecoding.Why,
        /** "Text looks wrong? Read as …": the other encoding; null for UTF-16. */
        val alternative: TextDecoding.Encoding?,
        /** How many lines hold a "�" for bytes that could not be read, and the first of them. */
        val damagedLines: Int,
        val firstDamagedLine: Int,
    )

    /** One account of the sample: its name and username, never its password. */
    data class SampleRow(val title: String, val username: String) {
        override fun toString(): String = "SampleRow(texts hidden)"
    }

    /** "Line 2 has 7 values; the header has 5." */
    data class WrongRow(val line: Int, val values: Int, val expected: Int)

    /** Something not imported: what, and where (a CSV line, or a JSON item's place and name). */
    data class LeftOutRow(val kind: ImportLeftOut.Kind, val line: Int, val position: Int, val name: String?) {
        override fun toString(): String = "LeftOutRow($kind, line=$line, position=$position)"
    }

    /** How an account of the preview is named in a line of it. */
    sealed interface AccountName {
        /** Its name. */
        data class Title(val title: String) : AccountName {
            override fun toString(): String = "Title(hidden)"
        }

        /** A CSV row whose values may have moved: "Line 12". */
        data class Line(val line: Int) : AccountName

        /** A JSON item without a name to show: "Item 3". */
        data class Item(val position: Int) : AccountName
    }

    /** One change the importer made to one account: "Gmail: 3 websites moved to notes". */
    data class ChangeRow(val account: AccountName, val kind: ImportChange.Kind, val count: Int)

    /** Rules (b) and (c) of ux.md §5.21 kept [rows] rows together as [accounts] accounts. */
    data class Related(val rows: Int, val accounts: Int)

    /** "Tidy names": on or off, and two names of the file as they are and as they would be. */
    data class Tidy(val on: Boolean, val examples: List<Example>) {
        data class Example(val before: String, val after: String) {
            override fun toString(): String = "Example(texts hidden)"
        }
    }

    override fun toString(): String =
        "ImportPreview($format, found=$found, toAdd=$toAdd, alreadyHere=$alreadyHere, repeated=$repeated, conflicts=$conflicts, " +
            "withoutPassword=$withoutPassword, wrongRows=${wrongRows.size}, leftOut=${leftOut.size}, changes=${changes.size}, fits=$fits)"

    companion object {
        /** How many accounts the sample shows (ux.md §5.21). */
        const val SAMPLE_SIZE: Int = 3

        /** How many names "Tidy names" shows before and after. */
        const val TIDY_EXAMPLES: Int = 2

        /**
         * The preview of [file] as [plan] would import it. [tidyNames] is the switch's position (the plan
         * has the names tidied when it is on).
         */
        fun of(file: ImportFile, plan: ImportPlan, tidyNames: Boolean, wrongRowsIncluded: Boolean, keepSeparate: Boolean): ImportPreview {
            val decoding = file.decoding?.let {
                DecodingLine(it.encoding, it.why, it.alternative, it.replacedLineCount, it.replacedLines.firstOrNull() ?: 0)
            }
            val columns = file.columns
            return ImportPreview(
                format = file.format,
                decoding = decoding,
                samples = samples(file.accounts),
                found = plan.found,
                toAdd = plan.accounts.size,
                alreadyHere = plan.alreadyHere,
                repeated = plan.repeatedInFile,
                conflicts = plan.conflicts.size,
                withoutPassword = plan.withoutPassword,
                wrongRows = file.accounts.mapNotNull { a ->
                    a.wrongLength?.let { WrongRow(a.lines.firstOrNull() ?: 0, it.values, it.expected) }
                },
                wrongRowsIncluded = wrongRowsIncluded,
                leftOut = file.leftOut.map { leftOut(file, it) },
                changes = plan.changed.flatMap { a -> a.changes.map { ChangeRow(nameOf(file, a), it.kind, it.count) } },
                notesCut = plan.count(ImportChange.Kind.NOTES_CUT),
                related = if (plan.relatedAccounts > 0) Related(plan.relatedRows, plan.relatedAccounts) else null,
                keepSeparate = keepSeparate,
                tidy = tidy(file, tidyNames),
                removedControls = file.removedControls,
                replacedCharacters = file.replacedCharacters,
                emptyRows = file.emptyRows,
                unclosedQuoteLine = file.unclosedQuoteLine,
                newerData = file.newerVersion || file.vaultExtras.isNotEmpty() ||
                    file.leftOut.any { it.kind == ImportLeftOut.Kind.UNKNOWN_DATA },
                ambiguousColumns = columns?.ambiguous?.size ?: 0,
                columnsChosen = columns?.fromUser ?: false,
                fits = plan.fits,
            )
        }

        /**
         * The sample of ux.md §5.21: the names and usernames of [SAMPLE_SIZE] accounts, those with
         * letters outside ASCII (or a "�") first, then the first others, in the file's order
         * (`ImportFile.sample`'s rule), from the rows with the right number of values only.
         */
        fun samples(accounts: List<ImportAccount>): List<SampleRow> {
            val sure = accounts.filter { it.wrongLength == null }
            fun telling(a: ImportAccount) = (a.entry.title + a.entry.username).any { it.code > 0x7F && (Character.isLetter(it) || it == '\uFFFD') }
            val picked = sure.filter(::telling).take(SAMPLE_SIZE).toMutableList()
            if (picked.size < SAMPLE_SIZE) picked += sure.filterNot(::telling).take(SAMPLE_SIZE - picked.size)
            val order = sure.withIndex().associate { (i, a) -> a to i }
            return picked.sortedBy { order[it] }.map { SampleRow(it.entry.title, it.entry.username) }
        }

        /** "Tidy names" for [file]: two of its names that the rule changes, before and after; null when it changes none. */
        fun tidy(file: ImportFile, on: Boolean): Tidy? {
            val examples = ArrayList<Tidy.Example>(TIDY_EXAMPLES)
            for (a in file.accounts) {
                if (a.wrongLength != null) continue
                val after = ImportPlanner.tidyName(a.entry.title)
                if (after != a.entry.title) examples.add(Tidy.Example(a.entry.title, after))
                if (examples.size == TIDY_EXAMPLES) break
            }
            return if (examples.isEmpty()) null else Tidy(on, examples)
        }

        private fun nameOf(file: ImportFile, a: ImportAccount): AccountName = when {
            a.wrongLength != null -> AccountName.Line(a.lines.firstOrNull() ?: 0)
            a.entry.title.isNotBlank() -> AccountName.Title(a.entry.title)
            file.format.isCsv -> AccountName.Line(a.lines.firstOrNull() ?: 0)
            else -> AccountName.Item(a.position)
        }

        private fun leftOut(file: ImportFile, l: ImportLeftOut): LeftOutRow =
            LeftOutRow(l.kind, l.line, l.position, if (file.format.isCsv) null else l.name?.takeIf { it.isNotBlank() })
    }
}
