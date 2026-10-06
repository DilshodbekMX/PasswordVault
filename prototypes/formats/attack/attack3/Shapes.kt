import pv.formats.*
fun main() {
    val samples = linkedMapOf(
        "Firefox" to "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\r\n" +
            "\"https://mail.ru\",\"ivan\",\"Пароль1\",,\"https://mail.ru\",\"{0b1c2d3e-aaaa-bbbb-cccc-000000000001}\",\"1700000000000\",\"1700000000000\",\"1700000000000\"\r\n",
        "sep= line" to "sep=;\r\nname;url;username;password\r\nBank;https://bank.uz;ali;P1\r\n",
        "Chrome Windows" to "name,url,username,password,note\r\ncom.example.app,android://AbCd==@com.example.app/,ali,P1,\r\nexample.com,https://example.com/,ali,P1,\"line1\r\nline2\"\r\n",
        "Dashlane" to "username,username2,username3,title,password,note,url,category,otpSecret\nali,,,Bank,P1,,https://bank.uz,Finance,\n",
    )
    for ((k, v) in samples) {
        try {
            val p = Importer.read(v.toByteArray(), 0L) { "id" }
            println("$k: ${p.format}; ${p.entries.map { e -> "${e.title} | ${e.username} | ${e.password} | ${e.urls} | created=${e.createdAt} | fields=${e.fields.map { it.name + (if (it.secret) "(hidden)" else "") + "=" + it.value }}" }}")
        } catch (e: Importer.Unsupported) { println("$k: refused ${e.message}") }
    }
}
