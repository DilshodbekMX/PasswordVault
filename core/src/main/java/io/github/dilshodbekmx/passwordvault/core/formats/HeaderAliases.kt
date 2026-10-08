package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.text.UzbekScript
import java.text.Normalizer
import java.util.Locale

/**
 * What a column of an imported CSV holds (formats.md §8.3). The importer gives every column one
 * meaning, from its header ([HeaderAliases]) or from the column-mapping screen of ux.md §5.21.
 */
enum class ColumnMeaning {
    /** The account's name. */
    TITLE,

    /** A website. In Bitwarden's CSV (`login_uri`) one value holds a list of them. */
    WEBSITE,

    USERNAME,
    PASSWORD,
    NOTES,

    /** A one-time-code secret: kept as the hidden field "TOTP" (version 1 has no one-time codes). */
    TOTP,

    /** A folder or group: kept as the visible field "Folder" (version 1 has no folders, ux.md §2). */
    FOLDER,

    /** `1`, `true` or `yes` marks a favourite. */
    FAVOURITE,

    /** When the account was made: ISO 8601, or milliseconds since 1970 as Firefox writes them. */
    CREATED,

    /** When the account last changed. */
    MODIFIED,

    /** When the password last changed (Firefox's `timePasswordChanged`). */
    PASSWORD_CHANGED,

    /** Firefox's `formActionOrigin`: a second website, kept only when its host differs from the first's. */
    SECOND_WEBSITE,

    /** Bitwarden's `fields`: one extra field per line, `name: value`. Only in Bitwarden's CSV. */
    BITWARDEN_FIELDS,

    /** Bitwarden's `type`: `note` is a secure note, without username and password. Only in Bitwarden's CSV. */
    BITWARDEN_TYPE,

    /** An extra field named by the column's header. */
    FIELD,

    /** Not imported: a column of no use to this app (Firefox's `guid`, KeePassXC's `Icon`), or the user's "Skip". */
    SKIP,
}

/**
 * The header words of imported CSV files and what they mean (formats.md §8.3, formats-review P6
 * and P13): Chromium's own list first, then the words of Bitwarden, KeePassXC and Firefox, and
 * English, Russian and Uzbek words in both scripts.
 *
 * **Comparing headers** ([key]). A header is compared after its case, its spaces, `_`, `-` and `.`
 * are ignored and every apostrophe-like character (the list of search.md §1 step 1,
 * `UzbekScript.isApostrophe`) is dropped. Every Unicode space counts as a space and every dash as
 * `-`, and invisible format characters (a byte-order mark, a zero-width space, a soft hyphen) are
 * dropped wherever they stand. The text is put in NFKC form first, so that a letter typed as a base
 * letter and a combining mark is the same letter. So `Oʻzgartirilgan`, `O‘zgartirilgan` and
 * `O'zgartirilgan` are one header, `User Name` with a no-break space is `username`, and the header
 * `Номи,Сайт,Фойдаланувчи,Парол,Изоҳ` names a title, a website, a username, a password and notes.
 *
 * **Which column wins** ([Alias.rank]). Each meaning has a list of words, the strongest first; for
 * notes that is Chromium's priority (note › notes › comment › comments). When two columns of a
 * file have one meaning, the one whose word stands earlier in the list takes it, and of two with
 * the same word the first; the other becomes an extra field. Chromium would refuse such a file.
 *
 * **Hidden or visible** ([isHarmless]). A column with no meaning becomes an extra field named by
 * its header, hidden unless its label is harmless (an email, a phone, a question, a folder…), as
 * ux.md §2 asks for labels the app does not know.
 */
object HeaderAliases {
    /** One word of the lists: its meaning, and its place among the words of that meaning (0 the strongest). */
    class Alias(val meaning: ColumnMeaning, val rank: Int)

    /**
     * The header as it is compared: NFKC, lower case, with apostrophes, spaces, `_`, `-`, `.` and
     * invisible format characters dropped.
     */
    fun key(header: String): String {
        var withoutApostrophes: String = header
        if (header.any(UzbekScript::isApostrophe)) withoutApostrophes = header.filterNot(UzbekScript::isApostrophe)
        // Apostrophes go first: NFKC would turn ´ (U+00B4) into a space and a combining accent.
        val text = Normalizer.normalize(withoutApostrophes, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c == '_' || c == '.' || isSpace(c) || isDash(c) || Character.getType(c) == Character.FORMAT.toInt()) continue
            sb.append(c)
        }
        return sb.toString()
    }

    /** What [header] means, or null when it is none of the words. */
    fun alias(header: String): Alias? = ALIASES[key(header)]

    /** What [header] means, or null when it is none of the words. */
    fun meaning(header: String): ColumnMeaning? = alias(header)?.meaning

    /**
     * Whether an extra field labelled [label] may be shown: true when one of its words is a harmless
     * one (email, phone, question, folder, website, address, login, name, date, note…) and none is
     * a secret one (password, PIN, code, answer, key, token…). Every other label is hidden (ux.md §2).
     *
     * A secret word counts wherever it stands in a word: "EmailPassword", "userpin" and "ПочтаПароль"
     * are one word each, and their columns hold passwords. A harmless word counts at the start of a
     * word only, and a number is no name: "Номер карты" and "Karta nomeri" are not "ном…" and "nom…".
     */
    fun isHarmless(label: String): Boolean {
        var harmless = false
        for (word in words(label)) {
            if (SECRET.any { it in word }) return false
            if (!harmless && NUMBER.none { word.startsWith(it) } && HARMLESS.any { word.startsWith(it) }) harmless = true
        }
        return harmless
    }

    private fun isSpace(c: Char) = Character.isWhitespace(c) || Character.isSpaceChar(c)

    private fun isDash(c: Char) = c == '-' || Character.getType(c) == Character.DASH_PUNCTUATION.toInt()

    /** The words of [label]: its runs of letters and digits, apostrophes dropped, in lower case. */
    private fun words(label: String): List<String> {
        val text = Normalizer.normalize(label.filterNot(UzbekScript::isApostrophe), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = ArrayList<String>()
        var start = -1
        for (i in 0..text.length) {
            val wordChar = i < text.length && Character.isLetterOrDigit(text[i])
            if (wordChar && start < 0) start = i
            if (!wordChar && start >= 0) {
                out.add(text.substring(start, i))
                start = -1
            }
        }
        return out
    }

    /** The words of each meaning, the strongest first, as people write them; [key] makes them comparable. */
    private val WORDS: List<Pair<ColumnMeaning, List<String>>> = listOf(
        ColumnMeaning.WEBSITE to listOf(
            "url", "website", "origin", "hostname", "login_uri", "uri", "web site", "site", "address", "link", "web address",
            "сайт", "адрес", "адрес сайта", "ссылка", "веб-сайт", "sayt", "manzil", "havola", "veb-sayt", "манзил", "ҳавола",
        ),
        ColumnMeaning.USERNAME to listOf(
            "username", "user", "login", "account", "login_username", "user name", "email", "e-mail", "email address",
            "login name", "userid", "user id", "логин", "имя пользователя", "пользователь", "почта", "электронная почта",
            "эл. почта", "foydalanuvchi", "foydalanuvchi nomi", "pochta", "elektron pochta", "фойдаланувчи",
            "фойдаланувчи номи", "электрон почта",
        ),
        ColumnMeaning.PASSWORD to listOf("password", "login_password", "pass", "passwd", "pwd", "пароль", "parol", "парол"),
        ColumnMeaning.NOTES to listOf(
            "note", "notes", "comment", "comments", "extra", "description", "remarks", "заметка", "заметки", "примечание",
            "примечания", "комментарий", "описание", "izoh", "eslatma", "qayd", "sharh", "изоҳ", "эслатма",
        ),
        ColumnMeaning.TITLE to listOf(
            "name", "title", "item name", "entry", "site name", "название", "наименование", "имя записи", "nomi", "nom",
            "sarlavha", "номи", "сарлавҳа",
        ),
        ColumnMeaning.TOTP to listOf("totp", "login_totp", "otp", "otpauth"),
        ColumnMeaning.FOLDER to listOf("folder", "group", "grouping", "collections", "папка", "группа", "papka", "guruh", "гуруҳ"),
        ColumnMeaning.FAVOURITE to listOf("favorite", "favourite", "fav", "starred", "избранное", "sevimli", "севимли"),
        ColumnMeaning.CREATED to listOf(
            "created", "creationdate", "creation time", "created at", "timecreated", "создано", "yaratilgan", "яратилган",
        ),
        ColumnMeaning.MODIFIED to listOf(
            "modified", "last modified", "revisiondate", "updated", "lastmodified", "изменено", "oʻzgartirilgan", "ўзгартирилган",
        ),
        ColumnMeaning.PASSWORD_CHANGED to listOf("timepasswordchanged", "password changed"),
        ColumnMeaning.SECOND_WEBSITE to listOf("formactionorigin"),
        ColumnMeaning.BITWARDEN_FIELDS to listOf("fields"),
        ColumnMeaning.BITWARDEN_TYPE to listOf("type"),
        ColumnMeaning.SKIP to listOf("icon", "reprompt", "archiveddate", "httprealm", "guid", "timelastused"),
    )

    private val ALIASES: Map<String, Alias> = HashMap<String, Alias>().apply {
        for ((meaning, words) in WORDS) {
            words.forEachIndexed { rank, word ->
                val k = key(word)
                val earlier = get(k)
                check(earlier == null || earlier.meaning == meaning) { "a header word with two meanings" }
                if (earlier == null) put(k, Alias(meaning, rank))
            }
        }
    }

    /** Word starts that make a label harmless. */
    private val HARMLESS = listOf(
        "email", "mail", "почт", "pochta", "phone", "telephone", "tel", "telefon", "телефон", "mobil", "моб",
        "question", "вопрос", "savol", "савол", "folder", "group", "групп", "папк", "papka", "guruh", "гуруҳ", "tag", "тег",
        "categor", "категор", "url", "website", "site", "сайт", "sayt", "web", "веб", "veb", "link", "ссылк", "havola", "ҳавола",
        "address", "адрес", "manzil", "манзил", "user", "login", "логин", "пользоват", "foydalanuvchi", "фойдаланувчи",
        "name", "имя", "ism", "исм", "nom", "ном", "title", "назван", "date", "дата", "sana", "сана", "created", "modified",
        "updated", "note", "comment", "description", "заметк", "примечан", "комментар", "описан", "izoh", "изоҳ", "eslatma",
        "эслатма",
    )

    /** Word starts that are a number, not the name that [HARMLESS]'s "nom" and "ном" mean. */
    private val NUMBER = listOf("nomer", "номер")

    /** Words that make a label secret wherever they stand in it, whatever else it says. */
    private val SECRET = listOf(
        "pass", "пароль", "парол", "parol", "pin", "пин", "code", "kod", "код", "secret", "секрет", "maxfiy", "махфий",
        "answer", "ответ", "javob", "жавоб", "cvv", "cvc", "key", "ключ", "kalit", "калит", "token", "totp", "otp", "ssn",
        "private", "приват", "seed",
    )
}
