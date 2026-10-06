# PasswordVault: search, and the Uzbek script converters

Date 2026-10-06. Status: proposal for review, with working code and measurements. Scope: how the account list is filtered as the user types, so that a name is found however it is typed (Uzbek Latin with any apostrophe or with the 2026 letters, Uzbek or Russian Cyrillic, English, any other script), plus two converters between the Uzbek scripts. Builds on [ux.md](ux.md) §5.11 (what search does on screen) and [i18n.md](i18n.md) §2.4–2.7 (alphabet and spelling decisions). §8 lists where this note differs from those two documents and why.

**Revised on 2026-10-06** by build-plan package 0.3 to follow [architecture.md](architecture.md) D-11: ş → s and ç → c in the second tier, and the retry of a query typed on the wrong keyboard layout (§0 items 5 and 12, §1, §2, §6 item 4, §8); and where the code now lives (§7, §9).

Code (pure Kotlin, no Android dependencies, JUnit 4 like the app): committed as `prototypes/search/`, with packages `io.github.dilshodbekmx.passwordvault.search` and `…passwordvault.text`. Build-plan package 1.2 moves it into the `:core` module as `…passwordvault.core.search` and `…core.text` (architecture.md §2.1) and adds the two D-11 changes.

How I know things: **[X]** measured or tested today with this code; **[V]** checked today in the named primary source; **[I]** my judgement.

## 0. Decisions in brief

1. **One search key for every spelling.** Both the query and every field are reduced by `searchKey()` (§1): apostrophes removed, NFKC, Cyrillic turned into Uzbek Latin by the official rules, case folded without locale, Latin accents removed, the 2026 letters mapped to the 1995 spellings. "o'zbek", "oʻzbek", "o‘zbek", "ozbek", "ўзбек", "ЎЗБЕК" and "Özbek" all become `ozbek` [X].
2. **Search works across scripts in version 1.** ux.md §11 deferred this ("Тошкент = Toshkent … next version") because ICU's `Transliterator` needs API 29; the converter here is plain Kotlin and runs on API 24 [X: every JDK call used is API 24 or lower in `android-37.0/data/api-versions.xml`].
3. **ц follows the official rule: s at the start of a word and after a consonant, ts after a vowel** (принцип → prinsip, Конституция → Konstitutsiya). The brief and i18n.md §2.7 say ц → ts always. That would make 100 of the 569 words with ц in four laws (17.6 %) miss their official Latin spelling [X]. The cost: Russian-style "tsentr" does not find "Центр" (§6).
4. **Ranking: the field first, then the position.** Title 300; user name and website host 200; extra fields and notes 100. Then +40 if the whole field matches, +30 at the start of the field, +20 at the start of a later word, +10 inside a word. Every query word must match somewhere (AND, any order). Equal scores keep the list order (A–Z). An empty query lists everything [X].
5. **A second, lower tier for Russian-keyboard and accent-free spellings.** у к г х for ў қ ғ ҳ, е for ё, u for oʻ, k for q, x for h; and s for ş, c for ç (architecture.md D-11), so that accent-free Turkish, Azerbaijani and French typing works: "besiktas" finds "Beşiktaş", "garcon" finds "Garçon". Matches in this tier score 5 less. Without it, 31 % of the Uzbek words in four laws, typed on a Russian keyboard, would not be found. With it, all but 3 of 112,262 words are found [X].
6. **Websites are matched on the host only**, without scheme, "www.", port or path, as the brief says. ux.md §5.11 says "hosts and paths"; see §8.
7. **Matched text in bold**: `matchedRanges(text, query)` returns the character ranges of the original text (ux.md §5.11 "matched text is bold") [X].
8. **Converters**: `cyrillicToLatin` writes the 1995 alphabet with oʻ/gʻ as **U+02BB** and the tutuq belgisi as **U+02BC**, as i18n.md §2.4 decides (and as lex.uz prints). An option writes the 2026 letters ö ğ ş ç. `latinToCyrillic` is a developer tool that drafts `values-b+uz+Cyrl` from `values-uz` for a human to review.
9. **Measured accuracy** on four laws published in both scripts on lex.uz (112,262 words). I tuned the rules on the Constitution and froze them before the first run on the other three laws (99,068 words). On those three: Cyrillic → Latin 99.999 % of words exact (1 miss, which is a slip in the reference); Latin → Cyrillic 99.88 %. 82 % of the misses are a soft sign ь that the Latin spelling does not show (kalendar: календарь) [X].
10. **Measured speed** with 5,000 entries on the JVM (JDK 25, i9-14900K): median 0.28–0.31 ms per keystroke, p95 under 0.7 ms; building the index at unlock takes 11–20 ms. Interpreted only (`-Xint`, a pessimistic stand-in for a phone before ART compiles the code): median 13–14 ms, p95 22–29 ms per keystroke; index build 1.2–1.3 s. Long notes are the main cost: with a 1,000-character note in every entry, a keystroke takes about 1 ms with the JIT and about 60 ms (median) interpreted. ux.md's budget is 50 ms per keystroke on a mid-range phone [X].
11. **Tests**: 527 JUnit cases, all passing, run under a Turkish default locale [X].
12. **The wrong keyboard layout** (architecture.md D-11). When a query finds nothing in either tier, it is retried once through the Russian ЙЦУКЕН ↔ Latin QWERTY key map (§2), and the list says which query it shows: "штые" gives the results of "inst", "ghbdtn" those of "привет".

## 1. The search key

`searchKey(text)` (file `search/SearchText.kt`) applies these steps in order. The query and every field go through the same function.

| # | Step | Examples [X] |
|---|---|---|
| 1 | Every apostrophe-like character is set aside, then Unicode **NFKC**. The apostrophes are U+0027 ' U+0060 ` U+00B4 ´ U+2018 ‘ U+2019 ’ U+02BB ʻ U+02BC ʼ U+02BD ʽ U+2032 ′, plus U+201B ‛ U+2035 ‵ U+02B9 ʹ U+02BE ʾ U+02BF ʿ U+A78B/U+A78C ꞌ U+FF07 ＇. They are set aside first because NFKC turns ´ into a space plus a combining accent, which would split "o´zbek" in two. NFKC makes full-width letters, ligatures and decomposed input one form | "Ｔｅｌｅｇｒａｍ" → telegram; "ﬁle" → file; и + U+0306 → й; У + U+0306 → Ў |
| 2 | **Cyrillic → Latin with the official Uzbek rules** (the same code as `cyrillicToLatin`, §3.1). For search only, letters of neighbouring Cyrillic alphabets map to the nearest Latin letter: Karakalpak/Kazakh ә ө ү ұ ң һ і, Tajik ҷ ӣ ӯ, Ukrainian ї є ґ, Serbian ј | ўзбек → ozbek; Сбербанк → sberbank; телеграм → telegram; поезд → poyezd; Принцип → prinsip; Объект → obyekt; Нөкис → nokis |
| 3 | **Case folding by code point**, independent of the default locale: upper case, then lower case. So ı, İ, ſ, ς fold to i, i, s, σ, and ß becomes ss | "TITLE" under a Turkish locale → title; İstanbul → istanbul; ΟΔΟΣ and οδος → οδοσ |
| 4 | **2026 letters → 1995 spellings**: ş (and Romanian ș) → sh, ç → ch; ö and ğ lose their marks in step 5 | Toşkent → toshkent; Çoy → choy; Özbek → ozbek; Ğalaba → galaba |
| 5 | **Combining marks are removed from Latin and Greek letters**. Letters without a decomposition are spelled out: ø đ ł ħ ŧ æ œ þ ð → o d l h t ae oe th d. Arabic short vowels (U+064B–U+065F, U+0670) and tatweel are removed. Marks in other scripts are kept, because there they are part of the spelling (Devanagari vowel signs). Cyrillic й and ё are not touched: they are letters, already mapped in step 2 | café, cafe + U+0301 → cafe; Köln → koln; Łódź → lodz; بَنْك → بنك; नमस्ते unchanged |
| 6 | **Apostrophes removed** (the oʻ/gʻ mark and the tutuq belgisi) | maʼlumot, ma'lumot, маълумот → malumot; Isʼhoq, Исҳоқ → ishoq |
| 7 | Decimal digits of every script → 0–9. Invisible format characters (zero-width space and joiners, direction marks, soft hyphen) and variation selectors are removed | ١٢٣, １２３ → 123; Tele + U+200B (zero-width space) + gram → telegram; ❤ + U+FE0F (emoji style) → ❤ |
| 8 | Runs of white space (including no-break and ideographic spaces) → one space; trimmed | "  Click \t Uz\n" → click uz |

Punctuation and symbols (emoji, +, @) stay in the key; the matcher decides how to use them (§2). Chinese, Arabic and other scripts pass through and match themselves (微信, بنك الراجحي) [X].

The **Cyrillic → Latin table** used by search (from §3.1): а a, б b, в v, г g, д d, е ye/e, ё yo, ж j, з z, и i, й y, к k, л l, м m, н n, о o, п p, р r, с s, т t, у u, ф f, х x, ц s/ts, ч ch, ш sh, щ sh, ъ — (removed), ы i, ь — (removed; y before о), э e, ю yu, я ya, ў o, қ q, ғ g, ҳ h. "е" is "ye" at the start of a word and after a vowel, ъ or ь, and "e" elsewhere.

**The second tier: Russian-keyboard and accent-free spellings** (`SearchText.russianKeyboard`, second tier only). Cyrillic ў қ ғ ҳ ё become у к г х е. In Latin text, oʻ and ö become u, ğ becomes g, q becomes k, and h becomes x except in sh and ch. "yo" becomes what е would give in that place: ye at the start of a word or after a vowel, e after a consonant. That last rule makes ё, е and yo meet: хаёт and hayot, Королёв and Королев, Ёлка and елка [X]. **ş (and ș) and ç become s and c** (architecture.md D-11), where step 4 of the first tier makes them sh and ch; so this tier must start from the letters before step 4 has replaced them. "besiktas" then finds "Beşiktaş" and "garcon" finds "Garçon", 5 points below an exact spelling, while "beshiktash" and "Toshkent" still meet "Beşiktaş" and "Toşkent" in the first tier.

Keys are computed in memory when the vault is unlocked and are never stored. So a change of these rules, or a newer Unicode version after an Android update, needs no migration. This is unlike the password itself, which must be stable (VERIFICATION.md V-C9).

## 2. Matching and ranking

File `search/SearchIndex.kt`. `SearchIndex(entries: List<SearchFields>)` folds every field once. `search(query)` returns `SearchHit(index, score, field)`, best first.

**Fields** (`SearchFields`): title, user name, websites, extras (names of extra fields and values that are *not* secret), notes. Never a password, previous password, one-time-code secret or secret field value.

**Words.** The query key is split at spaces; every word must be found in some field of the entry, in any order ("uz click" finds "Click Uz"; "gmail dilshod" matches the title and the user name). Each word is compared with the field **ignoring spaces and punctuation**, so "clickuz" finds "Click Uz", "mailru" finds "Mail.ru", "wifi" finds "Wi-Fi", "mygov" finds my.gov.uz, and "901234567" finds "+998 90 123 45 67". A match may run on into the next word only if it starts at the beginning of a word, so "kuz" does not find "Click Uz" [X]. A word made only of punctuation ("@") is looked up literally.

**Where a word starts**: after a space, punctuation or a symbol; at a lower-to-upper case change in the original (Pay**M**e, i**P**hone); at a letter–digit change (Top**100**); and in a website host after every dot.

**Score of one query word** = field weight + position bonus, from its best field:

| Field | Weight | | Position | Bonus |
|---|---|---|---|---|
| Title | 300 | | the whole field | +40 |
| User name | 200 | | start of the field | +30 |
| Website host (best of all) | 200 | | start of a later word | +20 |
| Extra fields (best of all) | 100 | | inside a word | +10 |
| Notes | 100 | | found only in the second tier (§1) | −5 |

The field decides first: any title match outranks any other field, including a title match inside a word against a user name that is matched whole. Within a field the position decides. The entry's score is the sum over its query words, plus 15 when the whole query also occurs in the title starting at a word (so "click uz" ranks "Click Uz" above "Uz … Click"). Ties keep the order of the input list, so give the index the list in display order (A–Z by the collator of i18n.md §2.7). An empty query, or one made only of spaces and apostrophes, returns every entry in list order with score 0.

Examples from the tests [X], with a 26-entry vault like the user's:

| Query | Result order | Why |
|---|---|---|
| bank | Bank card › Uzum Bank, Ипак Йўли банки › Сбербанк Онлайн, Kapitalbank | start of title (330) › start of a word (320) › inside a word (310) |
| mail | Mail.ru › Gmail › Ўзбектелеком (user dilshod@mail.uz) | title start › title inside › user name |
| 998 | Click Uz, Payme (user name starts 998) › Telegram (user "+998 90 …") | start of field › start of a word |
| google | Gmail, through its website accounts.google.com (score 220) | host, start of a word |
| узбек | Узбектелеком (330) › Oʻzbekiston…, Ўзбектелеком, Özbekiston… (325) | the Russian spelling matches exactly; Uzbek spellings in the second tier |

**The wrong keyboard layout** (`KeyboardLayout.otherLayout(query)`, architecture.md D-11 and §2.1). When `search(query)` returns nothing in either tier, the list tries the query once more as if it had been typed on the other layout. The direction is chosen once per query: with more Cyrillic letters than Latin ones, each Cyrillic letter of the table below becomes the QWERTY character on the same key; otherwise each QWERTY character of the table (the Latin letters and the seven keys `` [ ] ; ' , . ` ``) becomes the ЙЦУКЕН letter on the same key. Everything else (digits, spaces, other characters) is kept. If the result differs from the query and finds entries, the list shows them under "Showing results for "inst"" (ux.md §5.11) and makes bold the ranges of the retried query; otherwise it shows "No accounts match". The key map, lower case (upper case maps the same way):

| QWERTY | q | w | e | r | t | y | u | i | o | p | [ | ] | a | s | d | f | g | h | j | k | l | ; | ' | z | x | c | v | b | n | m | , | . | ` |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ЙЦУКЕН | й | ц | у | к | е | н | г | ш | щ | з | х | ъ | ф | ы | в | а | п | р | о | л | д | ж | э | я | ч | с | м | и | т | ь | б | ю | ё |

So "штые" becomes "inst", and "ghbdtn" becomes "привет". The Uzbek letters ў қ ғ ҳ sit on different keys in different Uzbek layouts and are left as they are.

**Website host** (`WebAddress.hostOf`, file `search/WebAddress.kt`). Steps: trim; drop "scheme://"; for Chrome's app links `android://<certificate hash>@<package>/` (formats.md) keep what follows the last "@", because the base64 hash may contain "/"; cut at the first / ? # \; drop "user:password@"; drop ":port" (IPv6 in brackets kept); trim a final dot; lower case; drop a leading "www."; show punycode labels as Unicode (`java.net.IDN`). Examples: "https://www.Google.com:443/accounts?x=1" → google.com; "androidapp://com.sberbank.online" → com.sberbank.online; "https://xn--80aswg.xn--p1ai/" → сайт.рф; "mailto:me@example.com" → example.com; text that is not an address is returned trimmed and lower-cased [X]. formats.md's duplicate check ("same website host") can use the same function.

**Bold matched text** (`matchedRanges(text, query)`, file `search/SearchHighlight.kt`). It returns UTF-16 ranges of the original text, one per query word, at the place where that word scored, merged where they touch. Examples: "Ўзбектелеком" + "o'zbek" → "Ўзбек"; "Toşkent metrosi" + "toshkent" → "Toşkent"; "Шаҳар" + "s" → "Ш" (a letter that folds to two letters is bold as a whole); a combining accent, a joiner or the mark of oʻ/gʻ stays with its letter [X]. It folds every prefix of the text, so its cost grows with the square of the length. Use it for visible titles, user names and hosts only (texts over 256 characters get no ranges).

## 3. The Uzbek script converters

File `text/UzbekScript.kt`. The rules are those of the 1995 Latin alphabet: Law No. 931-XII as amended on 6 May 1995 (i18n.md §2.1), and the spelling rules approved by Cabinet of Ministers Resolution No. 339 of 24 August 1995 [V lex.uz/docs/-1625271]. They are applied the way the official Latin editions of laws on lex.uz apply them.

### 3.1 `cyrillicToLatin(text, alphabet = OFFICIAL_1995)`

- Letters: as in the table of §1, with ў → **oʻ**, ғ → **gʻ** (U+02BB), and ъ → **ʼ** (U+02BC, the tutuq belgisi).
- **е** → "ye" at the start of a word and after a vowel, ъ or ь (Ер → Yer, поезд → poyezd, гигиена → gigiyena); "e" elsewhere (кеча → kecha).
- **ц** → "s" at the start of a word and after a consonant (Цензурага → Senzuraga, принцип → prinsip, коррупция → korrupsiya, Швейцария → Shveysariya); "ts" after a vowel (Конституция → Konstitutsiya, процессуал → protsessual). The spelling rules do not state this; it is what the official Latin editions do, and it matched all 569 words with ц in the four texts [X].
- **ъ** → ʼ (маъно → maʼno, эълон → eʼlon, қатъий → qatʼiy). It is dropped after a consonant before е ё ю я, as in Russian loanwords (объект → obyekt, субъект → subyekt). It is also dropped after ў, because the rules say moʻjiza, moʻtadil, moʻtabar take no tutuq [V lex.uz/docs/-1625271].
- **ь** is dropped (июль → iyul, судьялар → sudyalar, компьютер → kompyuter); before о it gives "y" (медальон → medalyon).
- **сҳ** → sʼh, because the rules write Isʼhoq and asʼhob so that it is not read as sh [V same].
- **Capitals**: a letter written with two Latin letters becomes "Sh" in a capitalised word and "SH" in a word in capitals: Шаҳар → Shahar, ШАҲАР → SHAHAR, Ч → Ch, ЁШЛАР → YOSHLAR, ЎЗБЕКИСТОН → OʻZBEKISTON.
- **Listed exceptions**: words whose official Latin spelling departs from the letter rules. They are сентябр- → sentabr- and октябр- → oktabr- (the rules spell "sentabr"), бюджет → budjet, and мавқеи → mavqeyi. The last two were found in the Constitution.
- Characters that are not Cyrillic are kept. Letters of other Cyrillic alphabets are left as they are, for a person to decide (Нөкис → Nөkis). Search maps them; display does not.
- `alphabet = REFORM_2026` writes ö ğ ş ç instead of oʻ gʻ sh ch (Ўзбекистон → Özbekiston, ЧОРСУ → ÇORSU), and сҳ as plain "sh", since ş removes the ambiguity. This mode is provisional, because the reform's spelling rules are not yet published (§3.4).

### 3.2 `latinToCyrillic(text, keep = DEFAULT_KEEP)` (developer tool)

It drafts the Uzbek Cyrillic UI strings from the Uzbek Latin ones. A person who reads Uzbek Cyrillic must review the result. It is not meant for user data.

- **oʻ → ў and gʻ → ғ with every apostrophe variant** (U+02BB, ', ‘, ’, ʼ, `, ´ …), also at the end of a word (togʻ → тоғ); in capitals (OʻZBEKISTON → ЎЗБЕКИСТОН); and the 2026 letters ö ğ ş ç (and ș) as input.
- **sh → ш, ch → ч** in any case (Sh, SH, Ch, CH); **ng → нг**, while n + gʻ stays нғ (mangʻit → манғит). **sʼh → сҳ**, not ш (Isʼhoq, Is'hoq → Исҳоқ). shh → шҳ (mashhur → машҳур).
- **e** → э at the start of a word or after a vowel (eshik → эшик, poeziya → поэзия, aeroport → аэропорт); е elsewhere (kecha → кеча).
- **ye** → е at the start of a word or after a vowel (yer → ер, poyezd → поезд, gigiyena → гигиена); ъе after a consonant (obyekt → объект).
- **yo, yu, ya → ё, ю, я**; but yoʻ is й + ў (yoʻl → йўл, Yoʻq → Йўқ); tayyor → тайёр.
- **Tutuq belgisi** between letters → ъ (maʼno, ma'lumot → маъно, маълумот; MAʼMURIY → МАЪМУРИЙ).
- **ц** for "ts" and "s" before i + vowel in loanwords: -tsiya, -tsion, -tsiyent (Konstitutsiya → Конституция, operatsion → операцион, koeffitsiyent → коэффициент); -siya/-sion after k, n or p (funksiya → функция, stansiya → станция, korrupsiya → коррупция). Native t + s stays тс (ketsa → кетса, otsiz → отсиз).
- **Exception list** (`LATIN_STEMS`): ц written s/ts in prinsip, protsess, protsedur, senzur, sivil, sement, sentner, sirk, sikl, silindr, sitat, sitrus, sex (цех), konsert, konsep, kansler, aksent, aksiz, litsenz, litsey, ofitser, spetsial, spetsifik, sotsial, abzats, shprits, shveysar. с kept in pensi- and ekspansi-. ь/ъ before я ю ё е in sudya, kompyuter, pyesa, premyer, intervyu, obyom. Other loanwords: budjet → бюджет, mavqeyi → мавқеи, umume- → умумэ-, rayon → район, mayor → майор. Month names keep ь only when they stand alone (aprel → апрель, aprelda → апрелда; oktabr → октябрь).
- **Copied unchanged**: Android format placeholders (%1$s, %d, %%), escapes (\n, \'), markup (<b>, <xliff:g>), entities, links, e-mail addresses, upper-case Roman numerals (XIV), and words in `keep`. The default `keep` holds Android, Google, Gmail, Wi-Fi, Bluetooth, PIN, QR, CSV, OK, ID, SMS and others; a word that starts with one (Googleda → Googleда) is also kept. In ICU MessageFormat, the syntax is kept and the messages are converted: "{count, plural, =1 {# ta ilova} other {# ta ilova}}" → "{count, plural, =1 {# та илова} other {# та илова}}".
- **Letters outside the Uzbek alphabet stay in Latin**: c without h, w (Microsoft → Миcрософт with a Latin c). The reviewer sees them, and i18n.md §5's check for Latin letters in `values-b+uz+Cyrl` flags them.

**Drafting the resource file** (`UzbekCyrillicStrings.kt`, Gradle task `uzCyrl`): it converts every `<string>`, and every `<item>` of `<plurals>` and `<string-array>`, and copies `translatable="false"` strings unchanged. On the 6,164 Uzbek strings of AOSP Settings (values-uz) it left no Uzbek word in Latin. Only names, units (W, °C), ICU keywords and links remain [X]. R8 removes `latinToCyrillic` from release builds if the app never calls it; better still, keep it in a build-time tool module.

### 3.3 Code points

Since i18n.md §2.4 decides it (and lex.uz, CLDR and Android's newer Uzbek strings agree), Latin output uses **U+02BB** ʻ in oʻ, gʻ, Oʻ, Gʻ and **U+02BC** ʼ for the tutuq belgisi. They are the constants `UzbekScript.OKINA` and `UzbekScript.TUTUQ`. Input accepts all variants.

### 3.4 The 2026 alphabet

The Legislative Chamber passed it on 7 July 2026, and the Senate approved it on 10 September 2026. It replaces Oʻ, Gʻ, Sh, Ch with Ö, Ğ, Ş, Ç, giving 28 letters and one apostrophe; ng stays a letter combination [V gazeta.uz 2026-09-10]. I found no report of the President's signature by 6 October 2026. i18n.md §2.2 adds that it would take effect six months after publication, and that new school textbooks start in 2027 and are all converted by 2031. Vault data will therefore mix both spellings for years. So search already treats ö = oʻ, ğ = gʻ, ş = sh and ç = ch (§1 step 4), `latinToCyrillic` accepts the new letters, and `cyrillicToLatin` can write them. The screens stay in the 1995 alphabet until i18n.md §2.4's conditions are met.

## 4. Measured accuracy of the converters

### 4.1 Texts and method

lex.uz publishes each law in Cyrillic (`lex.uz/docs/N`) and in Latin (`lex.uz/docs/-N`). Every paragraph is a `<div id="…">` whose number is the same in both editions, negated in the Latin one [V]. I downloaded four laws today, paired the paragraphs by number, cut them into words (letters and marks, with an apostrophe inside a word kept), and paired the words by position. This works because **all 5,617 paragraphs have the same number of words in both editions**. I then converted each word in each direction and compared it with the other edition, character for character (case and apostrophe code point included).

- **Tuning set**: the Constitution (6445145), 877 paragraphs, 13,194 words. I tuned the rules on it.
- **Held-out set**: rules frozen first (snapshot `frozen/UzbekScript.v1.kt`), then run once. It holds the Law on personal data (4396419, 5,027 words), the Law on cybersecurity (5960604, 4,038 words) and the Labour Code (6257288, 90,003 words), 99,068 words in all. Later changes (the 2026 letters, ICU syntax, keep list) do not touch these texts, and a re-run gave the same numbers.

**Caveat about the reference.** Equal word counts in every paragraph suggest the Latin edition was made from the Cyrillic one by a converter, with some hand editing. These numbers therefore measure agreement with the *official published* Latin edition, not with an independent human transcription. The reference also has slips. The cybersecurity law writes "u erda" where the Labour Code and spelling rule 60 write "u yerda". The Cyrillic editions print Roman numerals partly with a Cyrillic Х (ХХIV), and one word contains a Latin "a" (нaмунaвий).

### 4.2 Results [X]

| Text | Words | Cyrillic → Latin exact | Latin → Cyrillic exact |
|---|---|---|---|
| Constitution, before tuning | 13,194 | 13,178 (99.88 %) | 13,147 (99.64 %) |
| Constitution, after tuning (tuning set) | 13,194 | 13,194 (100 %) | 13,192 (99.98 %) |
| Personal data law (held out) | 5,027 | 5,027 (100 %) | 5,026 (99.98 %) |
| Cybersecurity law (held out) | 4,038 | 4,037 (99.98 %) | 4,035 (99.93 %) |
| Labour Code (held out) | 90,003 | 90,003 (100 %) | 89,890 (99.87 %) |
| **All held out** | **99,068** | **99,067 (99.999 %)** | **98,951 (99.88 %)** |

Counted once per distinct word in each held-out text: Cyrillic → Latin 8,904 of 8,905; Latin → Cyrillic 8,885 of 8,905 (99.78 %). The round trip Cyrillic → Latin → Cyrillic is as good as Latin → Cyrillic (99.87–99.98 %). Tuning on the Constitution added budjet and mavqeyi (16 words) and kept Roman numerals in Latin (29 words; 2 more are printed with a Cyrillic Х in the reference).

Several rules never occur in these four texts: сҳ/sʼh, ъ after ў, ь before о, щ, ы and йо. They rest on the 1995 rules and on unit tests only.

### 4.3 Error categories (held out)

**Cyrillic → Latin, 1 word**: ерда → yerda where this reference writes "erda". Spelling rule 60 and the Labour Code write "u yerda", so the slip is in the reference.

**Latin → Cyrillic, 117 words:**

| Category | Words | Examples |
|---|---|---|
| A soft sign ь that the Latin spelling does not show (Russian loanwords) | 96 (82 %) | kalendar → календар (official календарь) ×92; avtomobil, neft, fakultet (ь inside the word) |
| English words and abbreviations printed in Latin inside the Cyrillic edition | 14 (12 %) | ID ×6 (in the default keep list, but this measurement ran with an empty list), Doctor of Philosophy (PhD), DSc, Science, of |
| ц written ts in loanwords outside the patterns and the list | 3 | retsept (рецепт), dotsent (доцент), farmatsevtika (фармацевтика) |
| A morpheme boundary: y + a is й + а, not я | 1 | poyabzal → official пойабзал |
| Slips in the reference | 3 | ХХ with a Cyrillic Х; нaмунaвий with a Latin a; erda |

So no rule can recover the ь: the tool's output always needs a reviewer for Russian loanwords. i18n.md §2.5 chooses Парол without ь for the screens, as the government portals write it, and the tool's default agrees.

### 4.4 Search on the same texts [X]

- `searchKey(Cyrillic word) == searchKey(official Latin word)` for **112,261 of 112,262** words. The exception is the "erda" slip.
- The same Latin words typed **without any apostrophe** give the same key: 100 %.
- The Cyrillic words **typed on a Russian keyboard** (у к г х for ў қ ғ ҳ) are found by the first tier for about 69 % of words. With the second tier, **112,259 of 112,262** are found. The 3 misses are the "erda" slip and two forms of мавқеи typed with к, which then no longer meets its listed exception.
- Words with ё, also typed with е as many Russian writers do: 2,733 of 2,737 found.
- With the brief's literal ц → ts, 100 of the 569 words with ц would not match their official Latin spelling.

### 4.5 Against i18n.md's glossary

i18n.md §3 has 129 terms with Latin and hand-checked Cyrillic columns. Its Cyrillic was made from the Latin by a script using the same rules and then checked by hand, so this is a consistency check, not an independent test. `latinToCyrillic` gives the glossary's Cyrillic for **125 of 129** rows. The 4 others are translation choices, not conversions: Email → "Эл. почта"; "(A–Z)" → "(А–Я)" in a sort label; Latin "(A–Z)" and "(a–z)" kept for the generator [X].

## 5. Measured speed and memory

Method (`SearchBenchmark.kt`, `./gradlew bench`):
- **Entries**: 5,000 generated with a fixed seed from 74 real names in every script (Oʻzbekiston Temir Yoʻllari, Сбербанк Онлайн, Telegram, Тошкент шаҳар ҳокимлиги …), with prefixes, suffixes and numbers. Each has a user name (e-mail, phone number or handle) and 1–2 websites; 20 % have two extra fields and 30 % a 120-character note.
- **Queries**: 15 typed one letter at a time (o'zbek, telegram, сбербанк, ipak yo'li, gmail.com, ўзбекистон, хизмат, "bank karta", zzzz, a, +998 …), which makes 103 keystrokes per round. Each run does 30 warm-up rounds, then times 40 rounds.
- **Machine**: JDK 25.0.4.1 on an Intel i9-14900K, with other work running (load average 3–12).

| | JIT (range over 2–4 runs) | Interpreter only (`-Xint`, range over 2–3 runs) |
|---|---|---|
| Search per keystroke, median | 0.28–0.31 ms | 13.0–13.8 ms |
| p95 / p99 | 0.39–0.67 / 0.41–0.80 ms | 22–29 / 31–39 ms |
| Slowest keystroke | 1.6–2.9 ms | 49–81 ms |
| One-letter query "a" (4,310 hits, full sort) | 0.26–0.27 ms | 7.5–8.7 ms |
| Every entry with a 1,000-character note, median / p95 | 0.90–1.05 / 1.15–1.78 ms | 60–62 / 118–129 ms |
| Building the index (once per unlock), median | 10.6–19.8 ms | 1.20–1.27 s |
| Folding every field on every keystroke instead (no index) | 11.8–12.8 ms | 1.24–1.43 s |
| Index size | about 4.4 MiB | same |

No phone was measured: the emulators were in use by other work and off limits. A mid-range phone running ART-compiled code is probably 3–10 times slower than this desktop's JIT, which means about 1–3 ms per keystroke and 50–200 ms to build the index [I]. That is well inside ux.md's 50 ms budget. The interpreter column is the worst case before ART compiles the code. There, keystrokes stay mostly under 50 ms, but the index build takes over a second, and long notes push a keystroke past the budget, since a query that matches no title still scans every note. So:

- build the index on `Dispatchers.Default` right after unlock, and show the list at once (the unfiltered list needs no index);
- run each query off the main thread with "latest query wins", and call `matchedRanges` only for visible rows;
- add `SearchIndex`, `SearchText` and `UzbekScript` to the app's baseline profile, so ART compiles them at install;
- if vaults with many long notes turn out slow on real phones, search notes only for queries of three or more letters (a product decision for ux.md; not done here).

## 6. What does not match, and why

Items 1, 3, 4 and 6 are pinned by tests (`SearchIndexTest.honestLimits`, `SearchKeyTest`, `SearchIndexTest.websiteMatchesTheHostOnly`), so a change in behaviour is noticed [X]; the others are described from the rules.

1. **Brands spelled by sound in the other script.** "Yandex" does not find "Яндекс", which folds to `yandeks`, but "yande" does. Likewise Click/Клик (klik), Beeline/Билайн (bilayn), Wildberries/Вайлдберриз, Mail.ru/Мейл.ру. A transliteration cannot know a brand's Latin spelling. A small alias list could be added later if users ask.
2. **Russian-style Latin for Russian names.** zh, kh, shch, ts at the start of a word and ia/iu do not meet the Uzbek-rule keys: Жуков (jukov) vs "Zhukov", Михаил (mixail) vs "Mikhail", Центр (sentr) vs "tsentr", Юлия (yuliya) vs "Julia". х vs h does meet in the second tier (Хабр finds "habr"). Е vs e works one way only: "elena" finds Елена (`yelena` contains `elena`), but "Елена" does not find a Latin "Elena".
3. **A Russian spelling without ў, searched with a plain o.** "ozbek" does not find "Узбектелеком": nothing marks the o as oʻ. "o'zbek", "ўзбек" and "uzbek" do find it.
4. **ç and ş are Uzbek letters here; accent-free typing finds them one tier lower.** In the first tier French/Portuguese "Garçon" folds to `garchon` and Turkish "Beşiktaş" to `beshiktash`. Since architecture.md D-11 the second tier also turns ç into c and ş into s, so "garcon" and "besiktas" find them, 5 points below an exact spelling. *Changed on 2026-10-06:* before D-11, "garcon" did not find "Garçon" ("garc" did) and `SearchIndexTest.honestLimits` pinned that; package 1.2 changes that test with a note that names D-11. Still a limit: German "ue" and "oe" spellings are not merged with ü and ö (Mueller vs Müller).
5. **Scripts outside these alphabets only match themselves.** That means no simplified/traditional Chinese, pinyin, Arabic letter variants (أ/ا, ي/ی) or Japanese kana folding.
6. **Website paths are not searched** (ux.md wants them, §8), so "accounts.google.com/u/1" is found by "google", not by "u/1".
7. **"Contains" means wide results for very short queries.** One or two letters match most entries, and the ranking puts word starts first. The second tier adds more at lower rank: "x" also finds names with h, "k" names with q, "u" names with oʻ, "s" names with ş, "c" names with ç.
8. **Highlighting is approximate** where a listed exception changes the length of a word (сентябрь), and absent for texts over 256 characters.
9. **Old Android versions.** API 24 knows an older Unicode, so emoji or letters newer than the phone's Unicode only match exactly themselves. Keys are never stored, so this cannot corrupt anything.
10. **`latinToCyrillic`**: see §4.3, chiefly ь in Russian loanwords, Latin words inside Cyrillic text, and unlisted ц loanwords. It also mistakes a closing single quote right after a word ending in o or g for oʻ/gʻ ('Video' → Видеў); write UI quotes as “ ” or « ». In text written in the 2026 alphabet, "sh" is still read as ш, so Ishoq would come out as Ишоқ.
11. **`cyrillicToLatin`** knows only its listed exceptions, so a Latin query typed against a Cyrillic entry must follow the official spelling: "oktyabr" does not find "Октябрь" (key `oktabr`).
12. **The layout retry runs only when nothing is found** (§2). A short query typed on the wrong layout that happens to match something, as one or two letters usually do, shows those matches and is not retried. Only the Russian ЙЦУКЕН and the Latin QWERTY layouts are mapped.

## 7. Putting it in the app

- **Move** (architecture.md §2.1, build-plan package 1.2) `search/SearchText.kt`, `search/SearchIndex.kt`, `search/SearchHighlight.kt`, `search/WebAddress.kt` and `text/UzbekScript.kt` from `prototypes/search/` into the `:core` module as `core.search` and `core.text`, with `KeyboardLayout` (§2) beside them, and their tests (JUnit 4.13.2) into `:core`'s unit tests. The evaluation, benchmark and string-drafting mains (`ConverterEval.kt`, `SearchBenchmark.kt`, `UzbekCyrillicStrings.kt`) become tests that run only when their Gradle property is given (`-Ppv.evalArgs=…`, `-Ppv.bench`, `-Ppv.uzArgs=…`). The code compiles against the JDK 11 API (`-Xjdk-release=11`), and every JDK call is API 24 or lower; the newest are `Character.UnicodeScript` and `Integer.hashCode(int)`, both API 24 [X].
- **Lifecycle**: build `SearchIndex` from the decrypted entries after unlock, in display order. Rebuild it after an edit; that takes about 11 ms on the JIT, or keep the list and rebuild in the background. Drop it on lock together with the entries. It is a second plaintext copy of titles, user names, hosts and notes. It cannot be zeroed, the same as the entries themselves. Never write it to disk.
- **Never** put a password, previous password or secret field value in `SearchFields`; Proton Pass's search index also excludes them (account-based.md §3).
- **The list screen**: show `search(query)` in its order; bold `matchedRanges(title, query)` on visible rows. For "No accounts match", use ux.md §5.11's wording.

## 8. Relation to ux.md §5.11 and i18n.md §2.7

Every test vector in both documents passes in this code (`SearchIndexTest.uxDesignTestVectors`, `i18nDesignTestVectors`) [X]. The differences:

| Point | ux.md / i18n.md | This note | Reason |
|---|---|---|---|
| Across scripts | ux.md: next version; i18n.md §2.7: yes | Yes, version 1 | The brief requires "ўзбек" to find "o'zbek"; plain Kotlin, API 24 |
| ц | i18n.md §2.7: ts always | Official: s / ts | 100 of 569 ц-words would otherwise miss their official spelling (§4.4) |
| ё and е | ux.md: ё→е for all; i18n.md: try both | Second tier (−5), so the exact spelling ranks first | Uzbek ё is "yo" (ҳаёт = hayot); folding it to е everywhere would break that match |
| Russian keyboard (у к г х) | not covered; i18n.md accepts "Хамкорбанк" ≠ "Hamkorbank" | Second tier finds both | 31 % of Uzbek words typed on a Russian keyboard would be lost (§4.4) |
| Accent-free ş and ç; the wrong layout | ux.md §5.11 asked for both | Both, as architecture.md D-11 decides: ş → s and ç → c in the second tier; the ЙЦУКЕН ↔ QWERTY retry when nothing is found (§1, §2) | Accent-free and wrong-layout typing are common |
| Non-letters | i18n.md: turned into spaces | Kept in the key; spaces and punctuation ignored inside a match; symbols kept | Emoji and "+" must match themselves; "clickuz" and "mailru" should match |
| Websites | ux.md: hosts and paths | Host only | The brief; paths ("login", "index.do", ids) make short queries match everything. Add paths later as a field weighing 100 if wanted |
| Ranking | ux.md: title start › title contains › user name or website › other fields and notes; then A–Z | The same, refined: whole field and word starts, field weights, ties in list order | The brief: a word start ranks above a match inside a word |
| Extra fields | ux.md: names and non-secret values | `extras`, weight 100 like notes | — |

## 9. Reproduce

In `prototypes/search/`, with `JAVA_HOME=/home/asus/.jdks/jdk-25` (after build-plan package 1.2 has moved the code: `tools/gradle.sh :core:testDebugUnitTest`, and the three programs as tests with `-Ppv.bench`, `-Ppv.evalArgs=…` and `-Ppv.uzArgs=…`):

- `./gradlew test` runs the 527 tests: SearchKeyTest 112, OfficialPairsTest 108, LatinToCyrillicTest 91, FindsEntryTest 79, CyrillicToLatinTest 65, SearchIndexTest 28, WebAddressTest 23, MatchedRangesTest 20, UzbekCyrillicStringsTest 1.
- `./gradlew bench` gives the JIT numbers; add `-PbenchJvmArgs=-Xint` for the interpreter-only numbers.
- `./gradlew evalConverters -PevalArgs="build/eval const:corpus/const_cyr.tsv:corpus/const_lat.tsv personal-data:corpus/pd_cyr.tsv:corpus/pd_lat.tsv cybersecurity:corpus/cyber_cyr.tsv:corpus/cyber_lat.tsv labour-code:corpus/labour_cyr.tsv:corpus/labour_lat.tsv"` gives §4. The word errors are written to `build/eval/*-errors.tsv`. Corpus files are made by `tools/extract_lex.py page.html out.tsv` from `https://lex.uz/docs/N` and `https://lex.uz/docs/-N`.
- `./gradlew evalConverters -PevalArgs="--glossary corpus/glossary/glossary.tsv"` gives §4.5.
- `./gradlew uzCyrl -PuzArgs="values-uz/strings.xml values-b+uz+Cyrl/strings.xml"` drafts the Cyrillic file.

## Sources

- lex.uz, Constitution of the Republic of Uzbekistan: https://lex.uz/docs/6445145 and https://lex.uz/docs/-6445145 [V]
- lex.uz, Law on personal data: https://lex.uz/docs/4396419 and /-4396419; Law on cybersecurity: /5960604 and /-5960604; Labour Code: /6257288 and /-6257288 [V]
- lex.uz, Cabinet of Ministers Resolution No. 339 of 24 August 1995, "Oʻzbek tilining asosiy imlo qoidalari": https://lex.uz/docs/-1625271 (rules on sʼh, on moʻjiza, sentabr, and clause 60 "u yerda") [V]
- Gazeta.uz, "Senate approves new version of Uzbek alphabet of 28 letters", 2026-09-10: https://www.gazeta.uz/en/2026/09/10/uzb-alphabet/ [V]; The Times of Central Asia: https://timesca.com/uzbekistan-latin-alphabet-changes-senate/ [V]; zamin.uz on textbooks by 2031: https://zamin.uz/en/uzbekistan/220841-major-change-in-schools-all-textbooks-to-be-transferred-to-the-new-alphabet-by-2031.html [V]
- Android SDK `platforms/android-37.0/data/api-versions.xml` (API levels) [V]
- AOSP Settings `values-uz` strings, as downloaded for i18n.md (string-drafting test) [X]
