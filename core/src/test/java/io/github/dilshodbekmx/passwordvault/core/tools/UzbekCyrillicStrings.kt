package io.github.dilshodbekmx.passwordvault.core.tools

import io.github.dilshodbekmx.passwordvault.core.text.UzbekScript
import org.junit.Test

/**
 * Developer tool: drafts res/values-b+uz+Cyrl/strings_<area>.xml from res/values-uz/strings_<area>.xml.
 * Every <string>, and every <item> of a <plurals> or <string-array>, is converted with
 * [UzbekScript.latinToCyrillic]; markup, entities, escapes and placeholders inside a value
 * stay as they are, and translatable="false" strings are copied unchanged. The result is a
 * draft for a person who reads Uzbek Cyrillic to review, never a finished translation.
 *
 * A program that runs as a test only when asked (build plan 1.2 and 6.3), with one or more pairs
 * of an input and an output file, absolute or relative to the project root:
 *
 *     tools/gradle.sh :core:testDebugUnitTest --tests '*UzbekCyrillicStrings*' \
 *         -Ppv.uzArgs="app/src/main/res/values-uz/strings_setup.xml app/src/main/res/values-b+uz+Cyrl/strings_setup.xml"
 *
 * An output file is written over, so name only files that are yours to change.
 */
class UzbekCyrillicStrings {
    @Test
    fun draftsTheCyrillicStrings() {
        val paths = ProgramRun.arguments("pv.uzArgs")
        require(paths.isNotEmpty() && paths.size % 2 == 0) {
            "usage: -Ppv.uzArgs=\"in.xml out.xml [in.xml out.xml ...]\"; got ${paths.size} paths"
        }
        for ((input, output) in paths.chunked(2)) {
            val xml = ProgramRun.file(input).readText(Charsets.UTF_8)
            ProgramRun.file(output).apply { parentFile?.mkdirs() }.writeText(convertStringsXml(xml), Charsets.UTF_8)
            ProgramRun.say("wrote $output (drafted from $input)")
        }
    }
}

private val ELEMENT = Regex("(<(string|item)\\b([^>]*)>)(.*?)(</\\2>)", RegexOption.DOT_MATCHES_ALL)

/** Converts the text of string resources in [xml]; everything else is copied as is. */
fun convertStringsXml(xml: String): String = ELEMENT.replace(xml) { m ->
    val attributes = m.groupValues[3]
    val value = m.groupValues[4]
    if (attributes.contains("translatable=\"false\"")) {
        m.value
    } else {
        m.groupValues[1] + UzbekScript.latinToCyrillic(value) + m.groupValues[5]
    }
}
