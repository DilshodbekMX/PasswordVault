package io.github.dilshodbekmx.passwordvault.text

import org.junit.Assert.assertEquals
import org.junit.Test

class UzbekCyrillicStringsTest {

    @Test
    fun convertsStringResourcesOnly() {
        val latin = """
            <resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
                <string name="app_name" translatable="false">PasswordVault</string>
                <string name="save">Saqlash</string>
                <string name="found"><xliff:g id="count" example="3">%1${'$'}d</xliff:g> ta yozuv topildi</string>
                <plurals name="accounts">
                    <item quantity="one">%d ta hisob</item>
                    <item quantity="other">%d ta hisob</item>
                </plurals>
                <string name="quote">\"O${'ʻ'}zbekiston\" &amp; Toshkent</string>
            </resources>
        """.trimIndent()
        val cyrillic = """
            <resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
                <string name="app_name" translatable="false">PasswordVault</string>
                <string name="save">Сақлаш</string>
                <string name="found"><xliff:g id="count" example="3">%1${'$'}d</xliff:g> та ёзув топилди</string>
                <plurals name="accounts">
                    <item quantity="one">%d та ҳисоб</item>
                    <item quantity="other">%d та ҳисоб</item>
                </plurals>
                <string name="quote">\"Ўзбекистон\" &amp; Тошкент</string>
            </resources>
        """.trimIndent()
        assertEquals(cyrillic, convertStringsXml(latin))
    }
}
