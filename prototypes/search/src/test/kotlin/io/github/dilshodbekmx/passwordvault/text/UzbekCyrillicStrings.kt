package io.github.dilshodbekmx.passwordvault.text

import java.io.File

/**
 * Developer tool: drafts res/values-b+uz+Cyrl/strings.xml from res/values-uz/strings.xml.
 * Every <string>, and every <item> of a <plurals> or <string-array>, is converted with
 * [UzbekScript.latinToCyrillic]; markup, entities, escapes and placeholders inside a value
 * stay as they are, and translatable="false" strings are copied unchanged. The result is a
 * draft for a person who reads Uzbek Cyrillic to review, never a finished translation.
 *
 * Usage: ./gradlew uzCyrl -PuzArgs="path/values-uz/strings.xml path/values-b+uz+Cyrl/strings.xml"
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: UzbekCyrillicStrings in.xml out.xml" }
    val input = File(args[0]).readText(Charsets.UTF_8)
    val output = convertStringsXml(input)
    File(args[1]).apply { parentFile?.mkdirs() }.writeText(output, Charsets.UTF_8)
    println("wrote ${args[1]}")
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
