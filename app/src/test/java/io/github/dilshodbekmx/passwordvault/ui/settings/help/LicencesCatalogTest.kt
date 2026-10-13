package io.github.dilshodbekmx.passwordvault.ui.settings.help

import io.github.dilshodbekmx.passwordvault.R
import io.github.dilshodbekmx.passwordvault.i18n.UiText
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import io.github.dilshodbekmx.passwordvault.ui.settings.Licence
import io.github.dilshodbekmx.passwordvault.ui.settings.LicenceTexts
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencedPart
import io.github.dilshodbekmx.passwordvault.ui.settings.LicencesCatalog
import io.github.dilshodbekmx.passwordvault.ui.settings.PartName
import io.github.dilshodbekmx.passwordvault.ui.settings.TextBlock
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 7.3, [JVM]: the licences page names the EFF list with its licence link and the change
 * made to it, zxcvbn4j, Argon2kt, Bouncy Castle, the AndroidX libraries (androidx.biometric
 * 1.4.0-alpha07 among them), the sources of the strength word list, Material Icons (Apache License
 * 2.0) as the source of `ic_common_*` and of every other icon taken from the same place, and the
 * icons drawn for the app. Against the repository, so that the page cannot fall behind it: every
 * drawable and launcher file states a source the page names, every runtime library of the build
 * files is named with the build's version, the strength list's SOURCES.md has no source the page
 * leaves out, and each licence text is the one that was downloaded, with the part's copyright line.
 */
class LicencesCatalogTest {
    private val root = SourceTree.root()

    private fun name(part: LicencedPart): String = when (val name = part.name) {
        is PartName.Fixed -> name.text
        is PartName.Res -> English.text(UiText.of(name.id))
    }

    private fun use(part: LicencedPart): String = English.text(UiText.of(part.use))

    @Test
    fun namesTheEffListWithItsLicenceLinkAndTheChange() {
        val eff = LicencesCatalog.part("eff")
        assertEquals("EFF short word list 1", name(eff))
        assertEquals(listOf("Electronic Frontier Foundation"), eff.holders)
        assertEquals(Licence.CC_BY_4_0, eff.licence)
        assertEquals(listOf("https://creativecommons.org/licenses/by/4.0/"), eff.licence!!.urls)
        assertEquals(R.raw.licence_cc_by_4_0, eff.text)
        assertEquals("https://www.eff.org/deeplinks/2016/07/new-wordlists-random-passphrases", eff.source)
        val change = English.text(UiText.of(eff.change!!))
        assertTrue(change, change.contains("“yo-yo” was left out"))
        // The change is the one the word list's own note records, and the page shows that note.
        val group = LicencesCatalog.groups.single { eff in it.parts }
        assertEquals("wordlists/SOURCES.md", group.details)
        val note = File(root, "core/src/main/resources/${group.details}").readText()
        assertTrue(note.contains("the line `6652<TAB>yo-yo` was removed"))
        assertTrue(note.contains("Creative Commons Attribution 4.0 International (CC BY 4.0)"))
    }

    @Test
    fun namesTheLibraries() {
        val expected = mapOf(
            "zxcvbn4j" to Licence.MIT,
            "zxcvbn" to Licence.MIT,
            "argon2kt" to Licence.MIT,
            "argon2" to Licence.CC0_OR_APACHE_2_0,
            "bouncycastle" to Licence.MIT,
            "androidx" to Licence.APACHE_2_0,
            "biometric" to Licence.APACHE_2_0,
            "kotlin" to Licence.APACHE_2_0,
            "others" to Licence.APACHE_2_0,
        )
        val libraries = LicencesCatalog.groups.single { it.key == "libraries" }.parts
        assertEquals(expected.keys.toList(), libraries.map { it.key })
        for (part in libraries) assertEquals(part.key, expected[part.key], part.licence)
        assertEquals("zxcvbn4j", name(LicencesCatalog.part("zxcvbn4j")))
        assertEquals("Argon2kt", name(LicencesCatalog.part("argon2kt")))
        assertEquals("Bouncy Castle", name(LicencesCatalog.part("bouncycastle")))
        assertTrue(name(LicencesCatalog.part("androidx")).startsWith("AndroidX"))
        // lock.md §9.1 chose this alpha on purpose; the page says so.
        assertEquals("androidx.biometric", name(LicencesCatalog.part("biometric")))
        assertEquals("1.4.0-alpha07", LicencesCatalog.part("biometric").version)
        assertTrue(use(LicencesCatalog.part("biometric")).contains("used on purpose"))
    }

    @Test
    fun namesEverySourceOfTheStrengthWordList_withTheLicenceItsNoteGives() {
        val group = LicencesCatalog.groups.single { it.key == "strength" }
        assertEquals("strength/SOURCES.md", group.details)
        val note = File(root, "core/src/main/resources/strength/SOURCES.md").readText()
        // Each "### <source>: …" of the note's "Where each entry comes from", but the entries written for this app.
        val sources = Regex("""^### (.+?):""", RegexOption.MULTILINE).findAll(note).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Written for this app", "GeoNames", "Uzbek Wikipedia", "English Wiktionary", "FrequencyWords"), sources)
        val byName = mapOf(
            "GeoNames" to ("geonames" to Licence.CC_BY_4_0),
            "Uzbek Wikipedia" to ("uzwiki" to Licence.CC_BY_SA_4_0),
            "English Wiktionary" to ("wiktionary" to Licence.CC_BY_SA_4_0),
            "FrequencyWords" to ("frequencywords" to Licence.CC_BY_SA_4_0),
        )
        assertEquals(sources.drop(1), byName.keys.toList())
        for ((source, expected) in byName) {
            val (key, licence) = expected
            val part = group.parts.single { it.key == key }
            assertTrue("$source is named by ${name(part)}", source.endsWith(name(part)))
            assertEquals(source, licence, part.licence)
            // The note gives the same licence for that source.
            val section = note.substringAfter("### $source").substringBefore("\n### ")
            assertTrue("$source: ${licence.title}", section.contains(licence.title.substringBefore(" (")))
            assertNotNull(part.source)
        }
        assertTrue(LicencesCatalog.part("frequencywords").holders.any { it.contains("OpenSubtitles") })
        // The list as a whole is shared under CC BY-SA 4.0, as the page's paragraph says.
        assertTrue(English.text(UiText.of(group.intro!!)).contains("shared under CC BY-SA 4.0"))
    }

    @Test
    fun namesMaterialIcons_andTheIconsDrawnForTheApp() {
        val material = LicencesCatalog.part("materialicons")
        assertEquals("Material Icons", name(material))
        assertEquals(Licence.APACHE_2_0, material.licence)
        assertTrue(use(material).contains("Android Studio’s Vector Asset Studio"))
        val own = LicencesCatalog.part("ownicons")
        assertNull(own.licence)
        val ownUse = use(own)
        assertTrue(ownUse.contains("icons of the bottom bar were drawn for this app"))
        assertTrue(ownUse.contains("icon and logo"))
        assertTrue(ownUse.contains("librsvg"))
    }

    /**
     * Every drawable and launcher file says where it comes from, and the page names that place:
     * Material Icons (`ic_common_*` and every icon taken from the same place since), drawn for this app
     * (`ic_shell_*`), or made by `docs/design/looks/icon/make-icon.py` from the app's own drawing (the
     * launcher icon, its bitmaps and the logo, each listed in its `generated.sha256`). A new icon from
     * anywhere else fails here until the page names its source.
     */
    @Test
    fun everyIconStatesASourceThePageNames() {
        val made = File(root, "docs/design/looks/icon/generated.sha256").readLines()
            .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1) }.filter { it.startsWith("app/") }.toSet()
        val res = File(root, "app/src/main/res")
        val files = res.listFiles { f -> f.isDirectory && (f.name.startsWith("drawable") || f.name.startsWith("mipmap")) }.orEmpty()
            .flatMap { it.listFiles().orEmpty().toList() }.sortedBy { it.path }
        assertTrue("drawables found: ${files.size}", files.size >= 60)
        val bySource = mutableMapOf<String, MutableList<String>>()
        for (file in files) {
            val path = file.relativeTo(root).path
            val source = when {
                path in made -> "make-icon.py"
                file.extension == "xml" -> {
                    val head = Regex("<!--(.*?)-->", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).joinToString(" ") { it.groupValues[1] }
                    when {
                        head.contains("Material Icons") && head.contains("Apache License 2.0") -> "Material Icons"
                        head.contains("Drawn for this app") -> "drawn for this app"
                        else -> "unknown"
                    }
                }
                else -> "unknown"
            }
            bySource.getOrPut(source) { mutableListOf() } += file.name
        }
        assertEquals("files whose source the licences page does not name: ${bySource["unknown"]}", null, bySource["unknown"])
        assertTrue(bySource.getValue("Material Icons").filter { it.startsWith("ic_common_") }.size >= 9)
        assertEquals(listOf("ic_shell_passwords.xml", "ic_shell_security.xml", "ic_shell_settings.xml"), bySource.getValue("drawn for this app").sorted())
        assertTrue("ic_app_logo.xml" in bySource.getValue("make-icon.py"))
        assertTrue("ic_launcher.png" in bySource.getValue("make-icon.py"))
    }

    /** The versions the page gives are the build's (gradle/libs.versions.toml). */
    @Test
    fun theVersionsAreTheBuilds() {
        val versions = tomlSection("versions").mapValues { it.value.trim('"') }
        val expected = mapOf(
            "zxcvbn4j" to "zxcvbn",
            "argon2kt" to "argon2kt",
            "bouncycastle" to "bouncycastle",
            "biometric" to "biometric",
            "kotlin" to "kotlin",
        )
        for ((key, version) in expected) assertEquals(key, versions.getValue(version), LicencesCatalog.part(key).version)
    }

    /** Every library the app or :core is built with (not a test or debug one) is named by a part. */
    @Test
    fun everyRuntimeLibraryOfTheBuildIsNamed() {
        val libraries = tomlSection("libraries").mapValues { (_, spec) ->
            Regex("""group\s*=\s*"([^"]+)"""").find(spec)?.groupValues?.get(1)
                ?: Regex("""module\s*=\s*"([^":]+):""").find(spec)!!.groupValues[1]
        }
        val runtime = Regex("""^\s*(implementation|api)\((platform\()?libs\.([\w.]+)\)""", RegexOption.MULTILINE)
        val used = listOf("app/build.gradle.kts", "core/build.gradle.kts").flatMap { file ->
            runtime.findAll(File(root, file).readText()).map { it.groupValues[3].replace('.', '-') }.toList()
        }.distinct()
        assertTrue("runtime libraries found: $used", used.size >= 15)
        val parts = LicencesCatalog.parts
        for (alias in used) {
            val group = libraries[alias] ?: error("libs.versions.toml has no $alias")
            val part = parts.firstOrNull { part -> part.groups.any { group == it || (it.endsWith(".") && group.startsWith(it)) || group.startsWith("$it.") } }
            assertNotNull("$alias ($group) is not named on the licences page", part)
        }
    }

    @Test
    fun eachLicenceTextIsTheOneDownloaded_withThePartsCopyrightLine() {
        val sums = mapOf(
            "licence_apache_2_0.txt" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            "licence_cc_by_4_0.txt" to "9ba9550ad48438d0836ddab3da480b3b69ffa0aac7b7878b5a0039e7ab429411",
            "licence_cc_by_sa_4_0.txt" to "28a9529c7d0bb4dc51f4bf5c116a3d16ef247a052f7591466768ddf563fd1cf5",
            "licence_mit_argon2kt.txt" to "05ad83ab16adef6c1385bdf20ece73e6e7cb373243ea3015819d5441ff65a595",
            "licence_mit_bouncy_castle.txt" to "750fbcbc238b1908d192c62835b81450cb68ad8da7327e6623b7ee7f8a7fb1e9",
            "licence_mit_zxcvbn4j.txt" to "44f762a947e6879723398945dfeb1a558779dce2f5ae9e07bcf5320507220413",
            "licence_mit_zxcvbn.txt" to "8fd6c9ffe44291acd207a4d3cf7d6db5ac05f585ee1cf45db9fc6b153b08990f",
        )
        val raw = File(root, "app/src/main/res/raw")
        assertEquals(sums.keys, raw.list().orEmpty().toSet())
        for ((file, sum) in sums) assertEquals(file, sum, sha256(File(raw, file).readBytes()))
        // Every part with a licence has a text to show, and an MIT text carries the part's own copyright line.
        val files = R.raw::class.java.fields.associate { it.getInt(null) to "${it.name}.txt" }
        for (part in LicencesCatalog.parts) {
            if (part.licence == null) {
                assertNull(part.key, part.text)
                continue
            }
            val file = files[part.text] ?: error("${part.key} has no licence text")
            if (part.licence == Licence.MIT) {
                val text = File(raw, file).readText()
                for (holder in part.holders) assertTrue("$file names \"$holder\"", text.contains(holder))
            }
        }
        assertEquals(R.raw.licence_apache_2_0, Licence.APACHE_2_0.text)
        assertTrue(File(raw, "licence_apache_2_0.txt").readText().contains("Version 2.0, January 2004"))
        assertTrue(File(raw, "licence_cc_by_sa_4_0.txt").readText().startsWith("Attribution-ShareAlike 4.0 International"))
    }

    @Test
    fun aLicenceTextIsShownAsParagraphsThatWrap() {
        val raw = "Section 1 -- Definitions.\n\n  a. Adapted Material means material\n     that is derived from it.\n\n" +
            "  b. Other\n     material.\n       1. first item,\n          more;\n       2. second item.\n\n" +
            "=====================\n\nThis includes other CC-\nlicensed material.\n"
        assertEquals(
            listOf(
                TextBlock("Section 1 -- Definitions."),
                TextBlock("a. Adapted Material means material that is derived from it."),
                TextBlock("b. Other material.\n1. first item, more;\n2. second item."),
                TextBlock("This includes other CC-licensed material."),
            ),
            LicenceTexts.paragraphs(raw, markdown = false),
        )
        val apache = LicenceTexts.paragraphs(File(root, "app/src/main/res/raw/licence_apache_2_0.txt").readText(), markdown = false)
        assertTrue(apache.any { it.text.startsWith("\"License\" shall mean the terms and conditions for use, reproduction, and distribution as defined") })
        assertTrue(apache.none { it.text.contains("\n") && !it.text.lines().drop(1).all { line -> Regex("""^\(?[0-9a-z]{1,4}[.)] """).containsMatchIn(line) } })
    }

    @Test
    fun aWordListsNoteIsShownWithoutMarkdownMarks() {
        val raw = "# Word list: where it comes from\n\n`eff.txt` holds the words.\n\n- **Origin:** EFF's list,\n- **Licence:** CC BY 4.0.\n\n" +
            "| File | Entries |\n|---|---|\n| `common.txt` | 338 |\n"
        assertEquals(
            listOf(
                TextBlock("Word list: where it comes from", heading = true),
                TextBlock("eff.txt holds the words."),
                TextBlock("• Origin: EFF's list,\n• Licence: CC BY 4.0."),
                TextBlock("File · Entries\ncommon.txt · 338"),
            ),
            LicenceTexts.paragraphs(raw, markdown = true),
        )
        for (path in listOf("wordlists/SOURCES.md", "strength/SOURCES.md")) {
            val shown = LicenceTexts.paragraphs(File(root, "core/src/main/resources/$path").readText(), markdown = true)
            assertTrue(path, shown.first().heading)
            assertTrue(path, shown.none { it.text.contains("**") || it.text.contains("`") || it.text.contains("|---") })
        }
    }

    /** The `name = value` lines of the `[section]` of gradle/libs.versions.toml. */
    private fun tomlSection(section: String): Map<String, String> {
        val text = File(root, "gradle/libs.versions.toml").readText()
        val body = text.substringAfter("[$section]").substringBefore("\n[")
        return body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
