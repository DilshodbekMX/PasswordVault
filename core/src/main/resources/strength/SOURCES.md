# Strength word list: sources, licences and counts

The files of this directory are the strength meter's Uzbek and Russian word list (ux.md §5.3 "Bundled dictionary", build plan 2.12): the places, first names, common words and phrases that people in Uzbekistan put in passwords. `core.strength.WordList` adds them to the English lists of zxcvbn4j, so that the meter, the master-password checklist and the security check know that "Toshkent2020", "Ташкент2020" and "Shahzoda2003!" are easy to guess. The list never leaves the phone and is never sent anywhere.

Built on 2026-10-07 by `WordListBuilder` (`core/src/test/java/io/github/dilshodbekmx/passwordvault/core/strength/wordlist/`, with `fetch_lists.py` and `uzwiki_counts.py` beside it) from the files named below, downloaded the same day.

## The files

| File | Entries | Bytes | SHA-256 | Licence |
|---|---|---|---|---|
| `common.txt` | 338 | 9,747 | `acdf00b21c3964b8c07afabcd7646c33d93e64021d5c8628755b158d82f510e5` | written for this app |
| `places.txt` | 296 | 11,644 | `2ae2fec3ae162470ff9daca35068f78dbd2d1a3b0aea4b1b58a5b954041ede23` | CC BY-SA 4.0 (GeoNames; Wikipedia contributors) |
| `names-uz.txt` | 779 | 17,527 | `65d3ed37cbe2e67cdb00987ed7f3c984130f2954aa7894223ab8284dcbbe6d35` | CC BY-SA 4.0 (Wikipedia and Wiktionary contributors) |
| `names-ru.txt` | 585 | 12,801 | `adebfb38b4e8baf0ed2575d7b567fbd5316f3de368551cefcd657fc538437bdb` | CC BY-SA 4.0 (Wiktionary contributors) |
| `words-uz.txt` | 20,000 | 534,632 | `a0f12184ef3465a59b8108436734ec1552566e92f3968ee65dc806646ebc995a` | CC BY-SA 4.0 (Wikipedia contributors) |
| `words-ru.txt` | 10,000 | 231,841 | `6bebb037e318553b2145f6563579946542159a7e9d7cc242f720286a6c6a4485` | CC BY-SA 4.0 (Hermit Dave; OpenSubtitles) |

Six files, 818,192 bytes. Each file starts with two lines of notes (`#`); then one entry per line, its spellings separated by tabs, the likeliest first: Uzbek in Latin and in Cyrillic, Russian in Cyrillic and in the Latin letters Russian speakers type ("Toshkent, Тошкент, Ташкент, Tashkent"). The Uzbek Cyrillic is made from the Uzbek Latin by the app's own converter (`UzbekScript.latinToCyrillic`); oʻ and gʻ are written with U+02BB and the tutuq belgisi with U+02BC, whatever apostrophe the source used. Following zxcvbn's own lists, a word is left out when its rank is at least 10 to the power of its length (a word of four letters ranked 10,000 or later is cheaper to guess letter by letter), and every spelling has at least three characters.

The names and places in these files are not about anyone: they are words that passwords are often made of.

## Where each entry comes from

The counts are entries of the files. An entry that two sources gave counts for both, so the counts of `names-uz.txt` and `places.txt` add up to more than their 779 and 296 entries.

### Written for this app: 338 entries of `common.txt`

The places, first names, words and phrases that people in Uzbekistan most often build passwords from, in Uzbek (both scripts) and Russian: "parol", "пароль", "assalomu alaykum", "men seni sevaman", "я тебя люблю", the big cities and the districts of Tashkent, popular first names, football clubs, the rows of the Russian keyboard ("йцукен", "фывапролдж"). Written by hand for this app from common knowledge; no third-party text. The hand-made list is `common-seed.txt` beside the builder.

### GeoNames: 266 entries of `places.txt`

- **What was taken:** the names of Uzbekistan's 14 first-level divisions (12 regions, the Republic of Karakalpakstan and the city of Tashkent), its districts, the capital, the regional and district centres, and the other places with 10,000 people or more, in Uzbek, Russian, English and other usual spellings, biggest first. Historic names were left out.
- **Changes:** the words "viloyati", "tumani", "shahri", "область", "район" and the like were removed; Russian adjectives ("Ферганская") were left out; the Uzbek Cyrillic was added; a spelling already given by a bigger place was not repeated (a region and its capital share most); a place left with no Latin or no Cyrillic spelling was dropped.
- **Origin:** GeoNames, `https://www.geonames.org/`. Files `https://download.geonames.org/export/dump/UZ.zip` (SHA-256 `2b6ac0765256ce26b1ce558927b6a7618aa78a59c210a037b05150ead2d43937`) and `https://download.geonames.org/export/dump/alternatenames/UZ.zip` (SHA-256 `322bac9d2bd167bcae781c902eb789578974314d69061439391a434d5012a2c2`), downloaded on 2026-10-07.
- **Licence:** Creative Commons Attribution 4.0 International (CC BY 4.0), `https://creativecommons.org/licenses/by/4.0/`. The data is provided "as is" without warranty. Because `places.txt` also holds names from the Uzbek Wikipedia (below), the file as a whole is shared under CC BY-SA 4.0, which CC BY 4.0 allows.

### Uzbek Wikipedia: 20,000 entries of `words-uz.txt`; 377 and 433 entries of `names-uz.txt`; 33 entries of `places.txt`

- **Words (20,000):** the most frequent words of the articles of the dump `uzwiki-20261001-pages-articles.xml.bz2` (`https://dumps.wikimedia.org/uzwiki/20261001/`; SHA-1 `249613455c2c92402451ea22bb910b8951ebc623` as Wikimedia publishes it, SHA-256 `43d58dbde818e4ecfaaa2681e5a0d3e285a9dfd7cc3a017572cddaf358d4cb4b`), counted by `uzwiki_counts.py` in distinct sentences, so that the thousands of machine-made articles that repeat one sentence count once. Uzbek Latin words of three letters or more; words with letters Uzbek does not use (w, or c without h) and the 3,000 most frequent English words (which zxcvbn already knows) were left out, and so were the names and places of the other files.
- **First names:** 377 entries from the article titles of the categories "Oʻzbekcha erkaklar ismlari", "Oʻzbekcha ayollar ismlari" and "Alifbo boʻyicha ismlar" (`https://uz.wikipedia.org/`, fetched on 2026-10-07 by `fetch_lists.py`), and 433 entries, given names from the titles of the dump's biographies (articles in a category "…-yilda tugʻilganlar"), taken when they appear mostly in biographies with an Uzbek category, at least twice or once with an ending of Uzbek names (-jon, -bek, -boy, -xon, -gul, …); surnames, Russian names (which are in `names-ru.txt`) and 16 given names of foreign sportsmen were left out. An entry may come from both. All are ranked by how often they occur in the articles.
- **Districts (33 entries):** the districts and district centres of the tables of the article "Oʻzbekiston tumanlari" (fetched on 2026-10-07 by `fetch_lists.py`) that GeoNames left out, because it has them with no population or only in an old romanisation: 30 new entries, after the places of GeoNames, and 3 Latin spellings added to the GeoNames entries that already had their Cyrillic.
- **Licence:** Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0), `https://creativecommons.org/licenses/by-sa/4.0/`; text of the Uzbek Wikipedia by its contributors, `https://uz.wikipedia.org/`. `words-uz.txt`, `names-uz.txt` and `places.txt` are adaptations and are shared under the same licence.

### English Wiktionary: 585 entries of `names-ru.txt` and 66 of `names-uz.txt`

- **What was taken:** the entries of the categories "Russian male given names", "Russian female given names", "Russian diminutives of male given names" and "Russian diminutives of female given names" (585 entries, with their usual Latin spellings added), and of "Uzbek given names", "Uzbek male given names" and "Uzbek female given names" (66 entries), fetched on 2026-10-07 by `fetch_lists.py` from `https://en.wiktionary.org/`. The Russian names are ranked by their frequency in FrequencyWords (below).
- **Licence:** Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0), `https://creativecommons.org/licenses/by-sa/4.0/`; Wiktionary contributors. `names-ru.txt` and `names-uz.txt` are shared under the same licence.

### FrequencyWords: 10,000 entries of `words-ru.txt`

- **What was taken:** the 10,000 most frequent Russian words of three letters or more of `content/2018/ru/ru_50k.txt` (SHA-256 `6095f507cc167488ec66ada5a85ac50433503a08ad24a07c6eabdf54352c4e7f`) in Hermit Dave's FrequencyWords, `https://github.com/hermitdave/FrequencyWords`, commit `525f9b560de45753a5ea01069454e72e9aa541c6`, made from the OpenSubtitles 2018 corpus (`http://opus.nlpl.eu/OpenSubtitles2018.php`; P. Lison and J. Tiedemann, 2016); first names were left out (they are in `names-ru.txt`) and each word got its usual Latin spelling.
- **Licence:** Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0), `https://creativecommons.org/licenses/by-sa/4.0/` ("MIT License for code. CC-by-sa-4.0 for content."). `words-ru.txt` is shared under the same licence.

## Inputs of this build

To build the same list again, `WordListBuilder` needs these eight files (see its comment); the wiki lists change as the wikis are edited, so a later download gives a slightly different list.

| Input | SHA-256 |
|---|---|
| `geonames-UZ.zip` | `2b6ac0765256ce26b1ce558927b6a7618aa78a59c210a037b05150ead2d43937` |
| `geonames-alternatenames-UZ.zip` | `322bac9d2bd167bcae781c902eb789578974314d69061439391a434d5012a2c2` |
| `frequencywords-ru_50k.txt` | `6095f507cc167488ec66ada5a85ac50433503a08ad24a07c6eabdf54352c4e7f` |
| `enwiktionary-given-names.tsv` (`fetch_lists.py`) | `8204ad4bb1015adb9f716a90e20b2e948ddb11542c156e56267255ce055e1dae` |
| `uzwiki-given-names.tsv` (`fetch_lists.py`) | `6d428e6fe922be97403bb88766a3650cd726021f9e6dfa5d8ba8f5f58f25b418` |
| `uzwiki-districts.tsv` (`fetch_lists.py`) | `8340f010d5b9db89d3c22622b033d8c5f0b3eabed1fe516b180eb6e9314efa6a` |
| `uzwiki-words.tsv` (`uzwiki_counts.py`) | `8645f0c9ace14c619940466b98a8126774c2ac6ea64c94e4b3b5331958620072` |
| `uzwiki-bio-names.tsv` (`uzwiki_counts.py`) | `178766dd3312818ca7d2493815cd9594ebf6eb0adc8363d8fbb08b2141c321b6` |

## Credit in the app

The licences screen (build plan 7.3) shows this file, so that it names every source, its licence with its link, and the changes made. `core/src/test/java/…/core/strength/WordListTest.kt` checks the table above against the files.
