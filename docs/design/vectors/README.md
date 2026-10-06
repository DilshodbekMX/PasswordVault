# Vault file test vectors (architecture.md §4.13)

The two vault files of architecture.md §4.13, the script that writes them, and the small programs the architecture's review ran to check them. Written on 2026-10-06 in the session's scratchpad, rescued into `docs/holding/arch-review/`, and moved here by build-plan package 0.3.

| File | What it is | Size, SHA-256 |
|---|---|---|
| `vector-a.pvault` | Vector A, the container (§4.13.1). §4.13 names it `v1-container.pvault` | 4,667 bytes, `3210ed2c15bddde2e2d3793aeff65deda2d745d1ac10ed6c6443be1711ee1d2d` |
| `vector-b.pvault` | Vector B, the body as a version 1 writer produces it (§4.13.2). §4.13 names it `v1-body.pvault` | 4,667 bytes, `1bb8d230c949b197859bbd9fec613bd65f149595b3f9db98b40dd6dc2e5312c7` |
| `body-b.json` | Vector B's body, without the padding: one line of UTF-8, no final newline | 1,448 bytes, `610a407ad04359ae1fc8787f29f4ed943d46358eae7bdddc6dd7ad2490186cb7` |
| `generate-vectors.py` | The script of architecture.md's appendix, character for character. It writes `v1-container.pvault` and `v1-body.pvault` into the current directory | |
| `Chk.java` | Opens a vault file with the JDK's own AES-GCM, VK `a0 a1 … bf`: prints the size, the file's SHA-256, whether the trailer is right, the header length and the body's SHA-256 | |
| `mpb.py` | `MasterPasswordBytes` in three fold orders (lock.md §8.1 as audited, the coverage reviewer's proposal, and the order architecture.md §4.6 had before), compared over every code point and 200,000 random strings; prints the byte vectors of §4.13 | |
| `Esc.java` | What kotlinx-serialization 1.11.0 escapes and how it prints numbers, for the canonical body of §4.3 | |
| `Dig.java` | Which digits `String.format` writes under `tr-TR`, `fa-IR`, `ar-SA`, `ar-EG`, `th-TH-u-nu-thai`, `uz-Cyrl-UZ` and `ru-RU`, with and without `Locale.ROOT` (section 4's opening rule, R-11) | |

**For package 2.2.** It owns `core/src/test/resources/vectors/` and commits there the two files of §4.13 as `v1-container.pvault` and `v1-body.pvault`, written by the appendix script. They must equal `vector-a.pvault` and `vector-b.pvault` byte for byte.

**Reproduce** (checked on 2026-10-06 with the system Python 3.12 and its `cryptography` 41.0.7, and JDK 25):

```
cd "$(mktemp -d)"
python3 /path/to/docs/design/vectors/generate-vectors.py      # prints 4667 3210ed2c… and 4667 1bb8d230…
cmp v1-container.pvault /path/to/docs/design/vectors/vector-a.pvault
cmp v1-body.pvault      /path/to/docs/design/vectors/vector-b.pvault
java /path/to/docs/design/vectors/Chk.java v1-container.pvault v1-body.pvault   # trailerOk=true, body-sha256 610a407a… for B
python3 /path/to/docs/design/vectors/mpb.py                    # runs over all of Unicode; about a minute
```

`Esc.java` needs `kotlinx-serialization-core-jvm` and `kotlinx-serialization-json-jvm` 1.11.0 and the Kotlin standard library on the class path (all three are in the Gradle cache under `~/.gradle/caches/modules-2/files-2.1/`).

**Not kept from the holding area:** `appendix-final.py` (the same script as `generate-vectors.py` except for a final newline), `gram.md` (the grammar of D-10a, which is in architecture.md §6 and formats.md §4.4), and `rows.py` (a helper that rewrote table rows of architecture.md while it was being revised). They remain in the history of the tag `design-ok`.
