# Third-party notices

PasswordVault is free software under the GNU General Public License v3.0 (see [LICENSE](LICENSE)).
This file lists what in this repository comes from other people: the libraries and word lists
inside the app, and the material that only the tests and tools use. PasswordVault is an
independent app, not affiliated with or endorsed by any project or company named here.

## Libraries inside the app

These libraries are compiled into the app and are used under their own licences. The app lists
them, with the full licence texts, under **Settings → Open-source licences**.

| Library | Version | Licence |
|---|---|---|
| [AndroidX](https://developer.android.com/jetpack/androidx): Jetpack Compose, Material 3, Navigation 3, Lifecycle, Activity, AppCompat, Core | Compose BOM 2026.09.00, Navigation 3 1.2.0, Lifecycle 2.11.0, Activity 1.13.0, AppCompat 1.8.0, Core 1.19.1; every version in [`app/gradle.lockfile`](app/gradle.lockfile) | Apache-2.0 |
| [androidx.biometric](https://developer.android.com/jetpack/androidx/releases/biometric) | 1.4.0-alpha07 | Apache-2.0 |
| [Kotlin](https://kotlinlang.org/) standard library, [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | 2.4.20 · 1.11.0 · 1.11.0 | Apache-2.0 |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`) | 1.86 | MIT (the Bouncy Castle Licence) |
| [Argon2kt](https://github.com/lambdapioneer/argon2kt) | 1.6.0 | MIT |
| ↳ the [Argon2 reference implementation](https://github.com/P-H-C/phc-winner-argon2) inside Argon2kt | – | CC0-1.0 or Apache-2.0, used under Apache-2.0 |
| [zxcvbn4j](https://github.com/nulab/zxcvbn4j) (`com.nulab-inc:zxcvbn`) | 1.9.0 | MIT |
| ↳ [zxcvbn](https://github.com/dropbox/zxcvbn), which zxcvbn4j ports to Java | – | MIT |
| Guava ListenableFuture and JSpecify (pulled in by AndroidX), JetBrains Java Annotations (pulled in by Kotlin and kotlinx.coroutines) | 1.0 · 1.0.0 · 23.0.0 | Apache-2.0 |
| [Material Icons](https://github.com/google/material-design-icons) (51 of the app's icons) | – | Apache-2.0 |

In all, the release build's runtime classpath locks 128 artifacts (`releaseRuntimeClasspath` in
`app/gradle.lockfile`): 125 under Apache-2.0, Argon2kt and zxcvbn4j under MIT, and Bouncy Castle
under its MIT-style licence. None of them ships a NOTICE file. The app bundles no fonts; it uses
the phone's own.

Build and test tools (Gradle and its wrapper, the Android Gradle Plugin, the Kotlin compiler,
JUnit, AndroidX Test, Espresso) are not part of the app. The Gradle wrapper files in this repository
(`gradlew`, `gradlew.bat`, `gradle/wrapper/`) are Apache-2.0.

## Word lists inside the app

| Files | What the app uses them for | Source | Licence |
|---|---|---|---|
| [`wordlists/eff_short_wordlist_1.txt`](core/src/main/resources/wordlists/) | The words of a suggested master password and of the generator's "Words" mode | The Electronic Frontier Foundation's [short word list 1](https://www.eff.org/deeplinks/2016/07/new-wordlists-random-passphrases), with the line `yo-yo` removed (1,295 words) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), © Electronic Frontier Foundation |
| [`strength/`](core/src/main/resources/strength/): `common.txt`, `places.txt`, `names-uz.txt`, `names-ru.txt`, `words-uz.txt`, `words-ru.txt` | A list written for this app, and the Uzbek and Russian words, names and places that the strength meter recognises | `common.txt` was written for this app; the others come from [GeoNames](https://www.geonames.org/) (CC BY 4.0), the [Uzbek Wikipedia](https://uz.wikipedia.org/), the English [Wiktionary](https://en.wiktionary.org/) and [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles 2018) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), the list as a whole |

Each folder's `SOURCES.md` records the exact source, date, changes, sizes and SHA-256 of every
file. The app shows them under **Open-source licences → Show where the words come from**.

## Used only by the tests and tools

None of this is packaged into the app.

| Material | Where | Origin | Licence |
|---|---|---|---|
| Chrome and Bitwarden export samples | [`core/src/test/resources/formats/proton/`](core/src/test/resources/formats/proton/) | The import tests of Proton Pass's web app ([ProtonMail/WebClients](https://github.com/ProtonMail/WebClients/tree/b4be0231837aec0511955b0ba969bcf9b12d10b1/packages/pass/lib/import/providers)), copied unchanged | GPL-3.0; the full text is in that folder |
| A Python transcription of Chrome's CSV password reader | [`tools/validate/check_csv.py`](tools/validate/check_csv.py) | Chromium, `components/password_manager/core/browser/import/` | BSD-3-Clause, [below](#chromium) |
| Test cases from Chromium's URL tests | [`ChromeUrlTest.kt`](core/src/test/java/io/github/dilshodbekmx/passwordvault/core/formats/writers/ChromeUrlTest.kt) | Chromium, `url/url_canon_unittest.cc` | BSD-3-Clause, [below](#chromium) |
| 36 pairs of Uzbek words in Cyrillic and Latin letters | [`OfficialPairsTest.kt`](core/src/test/java/io/github/dilshodbekmx/passwordvault/core/text/OfficialPairsTest.kt) | The two official editions of the Constitution of Uzbekistan on lex.uz, [in Cyrillic](https://lex.uz/docs/6445145) and [in Latin letters](https://lex.uz/docs/-6445145) | Official text |
| Four fixed streams of a password-protected Excel file | [`tools/validate/samples/prototype/`](tools/validate/samples/prototype/) `fixed-*.bin` | Written by the prototype from [MS-OFFCRYPTO] 2.1.5–2.1.9; byte-identical to the same streams of an Excel-written file in msoffcrypto-tool's tests (`example_password.xlsx`) | Fixed by the file format |
| A lab project made from Android Studio's project template, with its launcher icons | [`docs/lab/clipboard-artifacts/kit/project/`](docs/lab/clipboard-artifacts/kit/project/) | Android Studio | Apache-2.0 |

The app's own Kotlin code that checks a website the way Chrome's importer does
([`GoogleCsv.kt`](core/src/main/java/io/github/dilshodbekmx/passwordvault/core/formats/GoogleCsv.kt), `ChromeUrl`)
was written for this project from a reading of Chromium's code (`GoogleCsv.kt` names the files), to
behave as Chrome does; it is not a copy of that code.

<a id="chromium"></a>

### Chromium's licence

```
Copyright 2015 The Chromium Authors

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

   * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
   * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
   * Neither the name of Google LLC nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Projects studied while designing PasswordVault

Their documentation and source code were read for the research in [`docs/research/`](docs/research/).
No code from them was copied into PasswordVault.

| Project | Licence | How it was used |
|---|---|---|
| [PocketVault](https://github.com/greyfreedom/PocketVault) | Apache-2.0 | Its key design, a random vault key wrapped both by the password's key and by an Android Keystore key, was the model for PasswordVault's |
| [Bitwarden](https://github.com/bitwarden/android) | GPL-3.0 | Compared; its JSON export format is read, and PasswordVault's JSON export is one that Bitwarden can import |
| [Proton Pass](https://github.com/protonpass/android-pass) | GPL-3.0 or later | Compared; the import tests of its web app ([ProtonMail/WebClients](https://github.com/ProtonMail/WebClients)) supplied the samples above |
| [KeePassDX](https://github.com/Kunzisoft/KeePassDX), Keepass2Android, Monica, KeyPass, PassVault, Keyguard, 2FAS Pass | various | Compared in [`docs/research/offline.md`](docs/research/offline.md) |

The Uzbek and Russian glossary takes single terms from the translations of Android itself, Firefox,
Bitwarden, Proton Pass, KeePassDX, Aegis and GNOME, and from Microsoft's terminology collection
([`docs/design/i18n.md`](docs/design/i18n.md) §3).

Google, Chrome, Google Password Manager, Google Drive, Bitwarden, Proton Pass, KeePassXC, Firefox
and Excel are trademarks of their owners. They are named only to say which apps and services
PasswordVault's files work with, and which projects were studied.
