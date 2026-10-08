package io.github.dilshodbekmx.passwordvault.core.formats.importer

import io.github.dilshodbekmx.passwordvault.core.formats.ImportFile
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlan
import io.github.dilshodbekmx.passwordvault.core.formats.ImportPlanner
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.formats.IsoTime
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import org.junit.Assert.assertTrue

/**
 * What the tests of build-plan package 3.15 share: a time, the texts `:app` would pass in, entries,
 * and short ways to read a file and plan its import.
 *
 * Every number here is written with ASCII digits whatever the default locale, because the unit tests
 * also run under tr-TR and fa-IR (formats-review P18).
 */
internal object ImportFixtures {
    /** 2026-10-08 09:00 UTC: "now" for every read. */
    val NOW: Long = IsoTime.parse("2026-10-08T09:00:00.000Z")!!
    const val DAY = 86_400_000L
    const val HOUR = 3_600_000L

    /** `PasswordVault ` and a version name, as `BuildInfo.generator` gives it. */
    const val GENERATOR = "PasswordVault 1.0"

    /** The texts `:app` would pass in; in Uzbek, so that a name made from them shows where it came from. */
    object Texts : EditTexts {
        override fun untitled(nowMs: Long): String = "Nomsiz $nowMs"

        override fun fromBackup(title: String): String = "$title (zaxiradan)"

        override fun fromFile(title: String): String = "$title (fayldan)"
    }

    /** `00000000-0000-4000-8000-<n, 12 digits>`: a valid UUID of version 4. */
    fun id(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    fun entry(
        n: Int,
        title: String,
        username: String = "",
        password: String = "",
        urls: List<String> = emptyList(),
        notes: String = "",
        fields: List<CustomField> = emptyList(),
        favourite: Boolean = false,
        changePending: Boolean = false,
        createdAt: Long = NOW - 30 * DAY,
        updatedAt: Long = NOW - DAY,
        deletedAt: Long? = null,
        pendingOldPassword: String? = null,
        history: List<HistoryItem> = emptyList(),
    ): Entry = Entry(
        id = id(n),
        title = title,
        username = username,
        password = password,
        notes = notes,
        urls = urls,
        fields = fields,
        favourite = favourite,
        changePending = changePending,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        pendingOldPassword = pendingOldPassword,
        passwordHistory = history,
    )

    fun visible(name: String, value: String) = CustomField(name, value, secret = false)

    fun hidden(name: String, value: String) = CustomField(name, value, secret = true)

    fun read(bytes: ByteArray, choices: Importer.Choices = Importer.Choices(), random: RandomSource = FakeRandom(7)): Importer.Result =
        Importer.read(bytes, NOW, Texts, random, choices)

    fun read(text: String, choices: Importer.Choices = Importer.Choices()): Importer.Result = read(text.toByteArray(Charsets.UTF_8), choices)

    /** [bytes] read, which must give a file. */
    fun file(bytes: ByteArray, choices: Importer.Choices = Importer.Choices()): ImportFile {
        val result = read(bytes, choices)
        assertTrue(result.toString(), result is Importer.Result.Read)
        return (result as Importer.Result.Read).file
    }

    fun file(text: String, choices: Importer.Choices = Importer.Choices()): ImportFile = file(text.toByteArray(Charsets.UTF_8), choices)

    fun plan(vault: Vault, file: ImportFile, choices: ImportPlanner.Choices = ImportPlanner.Choices()): ImportPlan =
        ImportPlanner.plan(vault, file, Texts::fromFile, choices)

    /** The plan of [file] into an empty vault. */
    fun plan(file: ImportFile, choices: ImportPlanner.Choices = ImportPlanner.Choices()): ImportPlan = plan(Vault(), file, choices)

    /** A file of `core/src/test/resources/formats/`. */
    fun resource(path: String): ByteArray =
        ImportFixtures::class.java.getResourceAsStream("/formats/$path")!!.use { it.readBytes() }

    /** CSV text with LF after every row. */
    fun csv(vararg rows: String): String = rows.joinToString("") { it + "\n" }
}
