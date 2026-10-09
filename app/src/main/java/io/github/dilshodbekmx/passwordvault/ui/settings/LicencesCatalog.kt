package io.github.dilshodbekmx.passwordvault.ui.settings

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import io.github.dilshodbekmx.passwordvault.R

/**
 * A licence the app's parts come under: its name as the licence gives it, where it is published,
 * and its text in `res/raw` when every part under it has the same text (an MIT text names its own
 * copyright holder, so each part brings its own).
 */
enum class Licence(val title: String, val urls: List<String>, @RawRes val text: Int?) {
    APACHE_2_0("Apache License 2.0", listOf("https://www.apache.org/licenses/LICENSE-2.0"), R.raw.licence_apache_2_0),
    MIT("MIT License", listOf("https://opensource.org/license/mit"), null),
    CC_BY_4_0(
        "Creative Commons Attribution 4.0 International (CC BY 4.0)",
        listOf("https://creativecommons.org/licenses/by/4.0/"),
        R.raw.licence_cc_by_4_0,
    ),
    CC_BY_SA_4_0(
        "Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)",
        listOf("https://creativecommons.org/licenses/by-sa/4.0/"),
        R.raw.licence_cc_by_sa_4_0,
    ),

    /** The Argon2 reference code's own choice; the app takes it under the Apache License 2.0. */
    CC0_OR_APACHE_2_0(
        "CC0 1.0 Universal or Apache License 2.0",
        listOf("https://creativecommons.org/publicdomain/zero/1.0/", "https://www.apache.org/licenses/LICENSE-2.0"),
        R.raw.licence_apache_2_0,
    ),
}

/** A part's name: a proper name, which is never translated, or a description in `strings_help.xml`. */
sealed interface PartName {
    data class Fixed(val text: String) : PartName

    data class Res(@StringRes val id: Int) : PartName
}

/**
 * One part of the app that the licences page names.
 *
 * @property key its test tag's last word (`pv.licences.<key>`), and the key of its shown licence.
 * @property version the version in the app; [LicencesCatalog]'s test compares it with the build's.
 * @property holders the copyright lines or authors, as the part gives them; never translated.
 * @property use what it does in the app.
 * @property licence null for what was made for this app.
 * @property text the licence text under "Show licence": the part's own MIT text, or its licence's.
 * @property source where the part comes from.
 * @property change what was changed in it, when anything was.
 * @property groups the Maven groups (or the start of them) of the libraries it stands for, so that a
 *   test can find every runtime library of the build here.
 */
class LicencedPart(
    val key: String,
    val name: PartName,
    val version: String? = null,
    val holders: List<String> = emptyList(),
    @StringRes val use: Int,
    val licence: Licence?,
    @RawRes val text: Int? = licence?.text,
    val source: String? = null,
    @StringRes val change: Int? = null,
    val groups: List<String> = emptyList(),
)

/**
 * A heading of the licences page and its parts. [intro] is a paragraph under the heading; [details]
 * is a file of `:core`'s resources that tells where its words come from, what was taken and what
 * was changed (`wordlists/SOURCES.md`, `strength/SOURCES.md`), shown under "Show where the words
 * come from".
 */
class LicenceGroup(
    val key: String,
    @StringRes val title: Int,
    val parts: List<LicencedPart>,
    @StringRes val intro: Int? = null,
    val details: String? = null,
)

/**
 * Everything the licences page names (ux.md §5.22; architecture.md §7.1; build plan 7.3; V-C13):
 *
 * - **Words for passphrases:** EFF's short word list 1, CC BY 4.0, with its link and the change made
 *   to it ("yo-yo" left out; `core/src/main/resources/wordlists/SOURCES.md`).
 * - **Words for the strength meter** (build plan 2.12; `core/src/main/resources/strength/SOURCES.md`):
 *   GeoNames (CC BY 4.0), the Uzbek Wikipedia, Wiktionary and FrequencyWords from OpenSubtitles 2018
 *   (CC BY-SA 4.0); the list as a whole is shared under CC BY-SA 4.0. The entries written for this app
 *   need no credit.
 * - **Libraries:** every library the release build carries (its runtime classpath on 2026-10-09):
 *   zxcvbn4j 1.9.0 and the zxcvbn it ports, Argon2kt 1.6.0 and the Argon2 reference code inside it,
 *   Bouncy Castle 1.86, the AndroidX libraries (androidx.biometric 1.4.0-alpha07 apart, because
 *   lock.md §9.1 chose that version on purpose), Kotlin with kotlinx.coroutines and
 *   kotlinx.serialization, and the three small libraries they pull in. None of their artifacts has a
 *   NOTICE file; the AndroidX ones carry the Apache text, which `res/raw` holds.
 * - **Icons:** Material Icons (Apache License 2.0), the source of `ic_common_*` (taken from Android
 *   Studio's Vector Asset Studio) and of every other icon a package took from the same place
 *   (`ic_edit_*`, `ic_entry_*`, `ic_list_*`, `ic_settings_*`, `ic_setup_*`, and any later one: a test
 *   reads each drawable's note of its source); the bottom bar's `ic_shell_*`, the launcher icon and
 *   the logo were drawn for the app, from `docs/design/looks/icon/icon.svg` by `make-icon.py`, whose
 *   bitmaps librsvg draws (a tool of the build, not part of the app).
 *
 * No font is bundled (architecture.md D-22), so none is named. The names, holders, licence names
 * and addresses are not translated; what each part does is.
 */
object LicencesCatalog {
    val groups: List<LicenceGroup> = listOf(
        LicenceGroup(
            key = "passphrase",
            title = R.string.help_licences_group_passphrase,
            details = "wordlists/SOURCES.md",
            parts = listOf(
                LicencedPart(
                    key = "eff",
                    name = PartName.Fixed("EFF short word list 1"),
                    holders = listOf("Electronic Frontier Foundation"),
                    use = R.string.help_licences_eff_use,
                    licence = Licence.CC_BY_4_0,
                    source = "https://www.eff.org/deeplinks/2016/07/new-wordlists-random-passphrases",
                    change = R.string.help_licences_eff_change,
                ),
            ),
        ),
        LicenceGroup(
            key = "strength",
            title = R.string.help_licences_group_strength,
            intro = R.string.help_licences_strength_intro,
            details = "strength/SOURCES.md",
            parts = listOf(
                LicencedPart(
                    key = "geonames",
                    name = PartName.Fixed("GeoNames"),
                    holders = listOf("GeoNames"),
                    use = R.string.help_licences_geonames_use,
                    licence = Licence.CC_BY_4_0,
                    source = "https://www.geonames.org/",
                ),
                LicencedPart(
                    key = "uzwiki",
                    name = PartName.Fixed("Uzbek Wikipedia"),
                    holders = listOf("Wikipedia contributors"),
                    use = R.string.help_licences_uzwiki_use,
                    licence = Licence.CC_BY_SA_4_0,
                    source = "https://uz.wikipedia.org/",
                ),
                LicencedPart(
                    key = "wiktionary",
                    name = PartName.Fixed("Wiktionary"),
                    holders = listOf("Wiktionary contributors"),
                    use = R.string.help_licences_wiktionary_use,
                    licence = Licence.CC_BY_SA_4_0,
                    source = "https://en.wiktionary.org/",
                ),
                LicencedPart(
                    key = "frequencywords",
                    name = PartName.Fixed("FrequencyWords"),
                    holders = listOf("Hermit Dave", "OpenSubtitles 2018 (P. Lison and J. Tiedemann, 2016)"),
                    use = R.string.help_licences_frequencywords_use,
                    licence = Licence.CC_BY_SA_4_0,
                    source = "https://github.com/hermitdave/FrequencyWords",
                ),
            ),
        ),
        LicenceGroup(
            key = "libraries",
            title = R.string.help_licences_group_libraries,
            parts = listOf(
                LicencedPart(
                    key = "zxcvbn4j",
                    name = PartName.Fixed("zxcvbn4j"),
                    version = "1.9.0",
                    holders = listOf("Copyright (c) 2014 Nulab Inc"),
                    use = R.string.help_licences_zxcvbn4j_use,
                    licence = Licence.MIT,
                    text = R.raw.licence_mit_zxcvbn4j,
                    source = "https://github.com/nulab/zxcvbn4j",
                    groups = listOf("com.nulab-inc"),
                ),
                LicencedPart(
                    key = "zxcvbn",
                    name = PartName.Fixed("zxcvbn"),
                    holders = listOf("Copyright (c) 2012-2016 Dan Wheeler and Dropbox, Inc."),
                    use = R.string.help_licences_zxcvbn_use,
                    licence = Licence.MIT,
                    text = R.raw.licence_mit_zxcvbn,
                    source = "https://github.com/dropbox/zxcvbn",
                ),
                LicencedPart(
                    key = "argon2kt",
                    name = PartName.Fixed("Argon2kt"),
                    version = "1.6.0",
                    holders = listOf("Copyright (c) Daniel Hugenroth"),
                    use = R.string.help_licences_argon2kt_use,
                    licence = Licence.MIT,
                    text = R.raw.licence_mit_argon2kt,
                    source = "https://github.com/lambdapioneer/argon2kt",
                    groups = listOf("com.lambdapioneer.argon2kt"),
                ),
                LicencedPart(
                    key = "argon2",
                    name = PartName.Fixed("Argon2 reference implementation"),
                    holders = listOf("Copyright 2015 Daniel Dinu, Dmitry Khovratovich, Jean-Philippe Aumasson, and Samuel Neves"),
                    use = R.string.help_licences_argon2_use,
                    licence = Licence.CC0_OR_APACHE_2_0,
                    source = "https://github.com/P-H-C/phc-winner-argon2",
                ),
                LicencedPart(
                    key = "bouncycastle",
                    name = PartName.Fixed("Bouncy Castle"),
                    version = "1.86",
                    holders = listOf("Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc."),
                    use = R.string.help_licences_bouncycastle_use,
                    licence = Licence.MIT,
                    text = R.raw.licence_mit_bouncy_castle,
                    source = "https://www.bouncycastle.org/",
                    groups = listOf("org.bouncycastle"),
                ),
                LicencedPart(
                    key = "androidx",
                    name = PartName.Fixed("AndroidX (Android Jetpack)"),
                    holders = listOf("The Android Open Source Project"),
                    use = R.string.help_licences_androidx_use,
                    licence = Licence.APACHE_2_0,
                    source = "https://developer.android.com/jetpack/androidx",
                    groups = listOf("androidx."),
                ),
                LicencedPart(
                    key = "biometric",
                    name = PartName.Fixed("androidx.biometric"),
                    version = "1.4.0-alpha07",
                    holders = listOf("The Android Open Source Project"),
                    use = R.string.help_licences_biometric_use,
                    licence = Licence.APACHE_2_0,
                    source = "https://developer.android.com/jetpack/androidx/releases/biometric",
                    groups = listOf("androidx.biometric"),
                ),
                LicencedPart(
                    key = "kotlin",
                    name = PartName.Fixed("Kotlin"),
                    version = "2.4.20",
                    holders = listOf("JetBrains s.r.o. and Kotlin Programming Language contributors"),
                    use = R.string.help_licences_kotlin_use,
                    licence = Licence.APACHE_2_0,
                    source = "https://kotlinlang.org/",
                    groups = listOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx"),
                ),
                LicencedPart(
                    key = "others",
                    name = PartName.Fixed("Guava ListenableFuture · JetBrains Java Annotations · JSpecify"),
                    holders = listOf("The Guava Authors", "JetBrains s.r.o.", "The JSpecify Authors"),
                    use = R.string.help_licences_others_use,
                    licence = Licence.APACHE_2_0,
                    source = "https://github.com/google/guava",
                    groups = listOf("com.google.guava", "org.jetbrains", "org.jspecify"),
                ),
            ),
        ),
        LicenceGroup(
            key = "icons",
            title = R.string.help_licences_group_icons,
            parts = listOf(
                LicencedPart(
                    key = "materialicons",
                    name = PartName.Fixed("Material Icons"),
                    holders = listOf("Google LLC"),
                    use = R.string.help_licences_material_icons_use,
                    licence = Licence.APACHE_2_0,
                    source = "https://github.com/google/material-design-icons",
                ),
                LicencedPart(
                    key = "ownicons",
                    name = PartName.Res(R.string.help_licences_own_icons),
                    use = R.string.help_licences_own_icons_use,
                    licence = null,
                ),
            ),
        ),
    )

    /** Every part, in the page's order. */
    val parts: List<LicencedPart> get() = groups.flatMap { it.parts }

    /** The part [key]; an error for a key the page does not have. */
    fun part(key: String): LicencedPart = parts.first { it.key == key }
}

/** A paragraph of a licence text or of a word list's description, as the page shows it. */
data class TextBlock(val text: String, val heading: Boolean = false)

/**
 * Licence texts and the word lists' descriptions as the page shows them ([paragraphs]): the texts
 * are hard-wrapped at about 70 to 80 columns for a computer's screen, which a phone's narrower lines
 * would break a second time in the middle.
 */
object LicenceTexts {
    /**
     * [raw] cut into paragraphs at blank lines, the lines of one paragraph joined with spaces so that
     * it wraps to the screen; a line that starts a list item ("a.", "1.", "(b)", "-", "•") keeps its
     * own line, and a line that ends with a hyphen ("CC-") is joined without a space. Lines that are
     * only `=` or `-` are rules and are left out. With [markdown] (the SOURCES.md files): a heading
     * (`#`) is a paragraph of its own, the marks of bold and code are left out, a bullet becomes "•",
     * a table's rows keep their own lines with their cells parted by " · ", and its rule is left out.
     */
    fun paragraphs(raw: String, markdown: Boolean): List<TextBlock> {
        val out = mutableListOf<TextBlock>()
        val lines = mutableListOf<String>()
        fun flush() {
            if (lines.isNotEmpty()) out += TextBlock(lines.joinToString("\n"))
            lines.clear()
        }
        for (source in raw.replace("\r\n", "\n").split('\n')) {
            var line = source.trim()
            if (line.isEmpty()) {
                flush()
                continue
            }
            if (RULE.matches(line)) continue
            var ownLine = ITEM.containsMatchIn(line)
            if (markdown) {
                if (line.startsWith("#")) {
                    flush()
                    out += TextBlock(plain(line.trimStart('#').trim()), heading = true)
                    continue
                }
                if (TABLE_RULE.matches(line)) continue
                if (line.startsWith("|")) {
                    line = line.trim('|').split('|').joinToString(" · ") { plain(it.trim()) }
                    ownLine = true
                } else {
                    if (line.startsWith("- ") || line.startsWith("* ")) line = "• " + line.substring(2)
                    line = plain(line)
                }
            }
            when {
                lines.isEmpty() || ownLine -> lines += line
                lines.last().endsWith("-") && !lines.last().endsWith("--") -> lines[lines.lastIndex] = lines.last() + line
                else -> lines[lines.lastIndex] = lines.last() + " " + line
            }
        }
        flush()
        return out
    }

    /** [text] without the marks of Markdown's bold and code. */
    private fun plain(text: String): String = text.replace("**", "").replace("`", "")

    private val RULE = Regex("""^[=\-]{3,}$""")
    private val TABLE_RULE = Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?$""")
    private val ITEM = Regex("""^(\(?([0-9]{1,2}|[a-z]|[ivx]{1,4})[.)]\s|[-*•]\s)""")
}
