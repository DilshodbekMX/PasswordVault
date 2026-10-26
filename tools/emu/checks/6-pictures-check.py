#!/usr/bin/env python3
"""tools/emu/checks/6-pictures-check.py - checks the owner's picture set that checks/6-pictures.sh makes.

    6-pictures-check.py SET RAW REPO [--langs en,uz-Latn,uz-Cyrl,ru] [--ocr COMMAND] [--cache DIR]
                        [--json FILE] [--jobs N]

SET is the set (SET/<lang>/NNN-SOURCE-KEY-LANG.png); RAW the raw pictures, where the session keeps beside
each picture the texts of the window in front (RAW/<lang>/session/N-NAME.nodes.txt); REPO the repository
(its strings, ResourceCheck's LATIN_IN_CYRILLIC and the test data). COMMAND, when given, reads the words of
a picture that has no texts beside it (the walk's and the tests' pictures): `COMMAND IMAGE LANGS --psm 11 tsv`
must print tesseract's TSV for the tesseract languages LANGS, as tesseract's own command line takes these
arguments after the picture; its answers are kept in DIR (default RAW/../ocr).

For every screen (one number in the four folders):
 1. Pixels. Each of uz-Latn, uz-Cyrl and ru differs from the English picture, and from the other two, in
    at least 0.2 % of the pixels below the status bar. A picture equal to English, or to another
    language, is a failure: it was not taken in its language.
 2. Letters, from the session's texts (exact): no Cyrillic word in English and Oʻzbekcha, no Latin word in
    Ўзбекча and Русский, beyond names (LATIN_IN_CYRILLIC, the language switch's names) and the user's
    data (the session's and the tests' made-up accounts, the fixtures, the generator's word list; a word
    with a digit or a sign, such as a login, a password or a web address); in Oʻzbekcha no word that only
    the English strings use; in the three others no text that is an English string translated otherwise.
    Read by OCR (inexact): a word of another language's strings that this language's strings, the names
    and the data do not have (OCR's garbage matches no language, so it is not judged).
 3. Ўзбекча and Русский apart: an Ўзбекча picture with Cyrillic text shows at least one of ў қ ғ ҳ; a
    Русский picture read from its texts none.
A picture whose window in front is the system's (the file picker, the fingerprint prompt: resource ids, none
of the app's) is listed as a NOTE, and only the app's own texts in it are judged: the system speaks the
phone's language (English).
Prints MISSING, NOTE and FAIL lines and a SUMMARY; with --json writes every picture's result for the
set's index. Exit 0 when nothing failed.
"""
import argparse
import concurrent.futures
import glob
import json
import os
import re
import subprocess
import sys

from PIL import Image, ImageChops

LANGS = ["en", "uz-Latn", "uz-Cyrl", "ru"]
LANG_NAMES = {"en": "English", "uz-Latn": "Oʻzbekcha", "uz-Cyrl": "Ўзбекча", "ru": "Русский"}
OCR_MODELS = {"en": "eng+rus", "uz-Latn": "uzb+rus", "uz-Cyrl": "uzb_cyrl+eng", "ru": "rus+eng"}
STATUS_BAR_PX = 110          # the status bar of a 1080 x 2400 screen at 420 dpi, with room
SAME_BELOW = 0.002           # a picture is "the same" when fewer pixels differ
PIXEL_DELTA = 40             # a pixel differs when a channel differs by more
OCR_MIN_CONF = 80            # OCR words below this confidence are left out
OCR_MIN_LEN = 4              # and shorter ones
OCR_SURE = 90                # a word read at least this surely in the other alphabet is listed for a look
UZ_CYRL_ONLY = set("ўқғҳЎҚҒҲ")
CYR = re.compile(r"[Ѐ-ӿ]")
LAT = re.compile(r"[A-Za-zÀ-ɏ]")
APOS = str.maketrans({"ʻ": "'", "ʼ": "'", "’": "'", "‘": "'", "`": "'", "´": "'"})
SIGNS = re.compile(r"[0-9@#%&*+=_/\\:|<>{}\[\]$^~]")
STRIP = ".,;:!?()«»\"“”„…—–·•›‹'’ʻʼ‘*"
# The language switch names each language in its own letters, in every language (donottranslate.xml), and
# the debug build's language dialog adds its two pseudo-locales.
LANGUAGE_NAMES = {"o'zbekcha", "ўзбекча", "узбекча", "english", "русский", "til", "тил", "язык", "language", "arabic", "xa", "xb"}


def norm(w):
    return w.translate(APOS).lower()


def tokens_of(text):
    """The words of a text that may be a language's: a token with a digit or a sign (a login, a password,
    a web address, a number) is left out, as is one with a dot inside (a file's or a site's name); the
    rest is split at hyphens."""
    out = []
    for raw in text.split():
        raw = raw.strip(STRIP)
        if not raw or SIGNS.search(raw) or re.search(r"\.\w", raw):
            continue
        for part in raw.split("-"):
            part = part.strip(STRIP)
            if part and (CYR.search(part) or LAT.search(part)):
                out.append(part)
    return out


def literals_of(code):
    """The string literals of Kotlin code, comments left out; escapes, commas and semicolons as spaces."""
    code = re.sub(r"/\*.*?\*/", " ", code, flags=re.S)
    code = re.sub(r"(?m)^\s*//.*$", " ", code)
    out = re.findall(r'"""(.*?)"""', code, flags=re.S)
    code = re.sub(r'""".*?"""', " ", code, flags=re.S)
    out += re.findall(r'"((?:[^"\\\n]|\\.)*)"', code)
    return [re.sub(r"\\[ntr]|[,;]", " ", lit) for lit in out]


# Lines that the system's keyboard (Gboard) draws over the app: its tooltips.
SYSTEM_LINES = {"see more features", "tap to enable contact suggestions", "tap to enable", "contact suggestions"}


# ---- what may show ----------------------------------------------------------------------------------------
def strings_of(res_dir):
    """name -> text of every <string> and plural item of a values directory (placeholders kept)."""
    out = {}
    for f in sorted(glob.glob(os.path.join(res_dir, "*.xml"))):
        s = open(f, encoding="utf-8").read()
        for m in re.finditer(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', s, re.S):
            out[m.group(1)] = unescape(m.group(3))
        for m in re.finditer(r'<plurals name="([^"]+)"[^>]*>(.*?)</plurals>', s, re.S):
            for q in re.finditer(r'<item quantity="([^"]+)">(.*?)</item>', m.group(2), re.S):
                out[m.group(1) + ":" + q.group(1)] = unescape(q.group(2))
    return out


def unescape(t):
    t = re.sub(r"<!\[CDATA\[(.*?)\]\]>", r"\1", t, flags=re.S)
    t = re.sub(r"<[^>]+>", "", t)
    t = t.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("&lt;", "<").replace("&gt;", ">")
    t = t.replace("&amp;", "&")
    t = re.sub(r"&#(\d+);", lambda m: chr(int(m.group(1))), t)
    t = re.sub(r"&#x([0-9a-fA-F]+);", lambda m: chr(int(m.group(1), 16)), t)
    t = t.strip()
    if len(t) >= 2 and t[0] == '"' and t[-1] == '"':
        t = t[1:-1]
    return t


def latin_in_cyrillic(repo):
    src = open(os.path.join(repo, "app/src/test/java/io/github/dilshodbekmx/passwordvault/i18n/ResourceCheck.kt"),
               encoding="utf-8").read()
    block = src[src.index("val LATIN_IN_CYRILLIC"):]
    block = block[:block.index(").sortedByDescending")]
    return re.findall(r'"((?:[^"\\]|\\.)*)"', block)


class Rules:
    def __init__(self, repo):
        res = os.path.join(repo, "app/src/main/res")
        dirs = {"en": "values", "uz-Latn": "values-uz", "uz-Cyrl": "values-b+uz+Cyrl", "ru": "values-ru"}
        self.strings = {lang: strings_of(os.path.join(res, d)) for lang, d in dirs.items()}
        self.names = set(LANGUAGE_NAMES)
        for phrase in latin_in_cyrillic(repo):
            for w in tokens_of(phrase) + phrase.split():
                self.names.add(norm(w.strip(STRIP)))
        catalog = os.path.join(repo, "app/src/main/java/io/github/dilshodbekmx/passwordvault/ui/settings/LicencesCatalog.kt")
        if os.path.exists(catalog):
            for lit in literals_of(open(catalog, encoding="utf-8").read()):
                # Every word of a work's name, author, licence and web address (which can wrap at a hyphen).
                for w in re.findall(r"[A-Za-z\u00C0-\u024F]+", lit):
                    self.names.add(norm(w))
        self.vocab = {}
        for lang, table in self.strings.items():
            ws = set()
            for text in table.values():
                for w in tokens_of(re.sub(r"%(\d+\$)?[sd]", " ", text)):
                    ws.add(norm(w))
            self.vocab[lang] = ws
        self.data = self._data(repo)
        # Words of another language's strings that this language's strings, the names and the data lack.
        self.foreign = {}
        for lang in LANGS:
            table = {}
            for other in LANGS:
                if other == lang:
                    continue
                for w in self.vocab[other] - self.vocab[lang] - self.names - self.data:
                    if len(w) >= OCR_MIN_LEN:
                        table.setdefault(w, other)
            self.foreign[lang] = table
        # Whole English texts that a language translates otherwise, as patterns for the session's texts.
        self.english_texts = {}
        for lang in LANGS:
            if lang == "en":
                continue
            pats = []
            for name, en in self.strings["en"].items():
                tr = self.strings[lang].get(name)
                if tr is None or tr == en or len(en) < 4 or not LAT.search(en) or not re.search(r"[a-z]{3}", en):
                    continue
                pat = re.escape(en)
                pat = re.sub(r"%(\\?\d+\\\$)?[sd]", ".*?", pat)
                pats.append((name, re.compile("^" + pat + "$", re.S)))
            self.english_texts[lang] = pats

    def _data(self, repo):
        """Words of the user's data that pictures show in any language: the session's accounts and files,
        the fixtures, the walk's and the tests' made-up data (every string literal of their code that is
        not itself an English app text), the generator's word list."""
        words = set()
        for f in glob.glob(os.path.join(repo, "app/src/androidTest/assets/fixtures/*")):
            for w in tokens_of(re.sub(r"[,;]", " ", open(f, encoding="utf-8", errors="replace").read())):
                words.add(norm(w))
        for f in [os.path.join(repo, "tools/emu/checks/6-pictures-session.sh"), os.path.join(repo, "tools/emu/app.sh"),
                  os.path.join(repo, "tools/emu/lock/lib.sh")]:
            if os.path.exists(f):
                for lit in re.findall(r"'([^'\n]*)'", open(f, encoding="utf-8").read()):
                    for w in tokens_of(re.sub(r"[,;]", " ", lit)):
                        words.add(norm(w))
        for line in open(os.path.join(repo, "core/src/main/resources/wordlists/eff_short_wordlist_1.txt"), encoding="utf-8"):
            parts = line.split()
            if len(parts) == 2:
                words.add(norm(parts[1]))
        english = {re.sub(r"\s+", " ", t).strip().lower() for t in self.strings["en"].values()}
        for f in glob.glob(os.path.join(repo, "app/src/androidTest/java/**/*.kt"), recursive=True) + \
                glob.glob(os.path.join(repo, "app/src/debug/java/**/*.kt"), recursive=True):
            for lit in literals_of(open(f, encoding="utf-8").read()):
                if re.sub(r"\s+", " ", lit).strip().lower() in english:
                    continue
                for w in tokens_of(lit):
                    words.add(norm(w))
        return words

    def exact(self, lang, w):
        """Why a word of the session's exact texts may not show in this language, or None."""
        lw = norm(w)
        if lw in self.names or lw in self.data:
            return None
        cyr, lat = bool(CYR.search(w)), bool(LAT.search(w))
        if cyr and lat:
            return "mixed letters"
        if lang in ("en", "uz-Latn"):
            if cyr:
                return "Cyrillic"
            if lang == "uz-Latn" and lw in self.vocab["en"] and lw not in self.vocab["uz-Latn"] and len(lw) >= 3:
                return "English word"
            return None
        if lat:
            return "English word" if lw in self.vocab["en"] and lw not in self.vocab[lang] else "Latin word"
        if lang == "ru" and lw in self.vocab["uz-Cyrl"] and lw not in self.vocab["ru"] and len(lw) >= OCR_MIN_LEN:
            return "Ўзбекча word"
        if lang == "uz-Cyrl" and lw in self.vocab["ru"] and lw not in self.vocab["uz-Cyrl"] and len(lw) >= OCR_MIN_LEN:
            return "Русский word"
        return None

    def unknown(self, lang, w):
        """A word in the other alphabet than the language's that is neither a name nor data."""
        lw = norm(w)
        if lw in self.names or lw in self.data:
            return False
        cyr, lat = bool(CYR.search(w)), bool(LAT.search(w))
        if lang in ("en", "uz-Latn"):
            return cyr and not lat
        return lat and not cyr

    def read(self, lang, w):
        """For a word OCR read: the language whose strings have it when this language's lack it, or None."""
        other = self.foreign[lang].get(norm(w))
        return f"{LANG_NAMES[other]} word" if other else None


# ---- the set ----------------------------------------------------------------------------------------------
def read_set(set_dir, langs):
    screens = {}  # number -> {"source", "key", lang: path}
    for lang in langs:
        for f in sorted(glob.glob(os.path.join(set_dir, lang, "*.png"))):
            m = re.match(r"^(\d{3})-([a-z0-9]+)-(.*)-" + re.escape(lang) + r"\.png$", os.path.basename(f))
            if m:
                s = screens.setdefault(m.group(1), {"source": m.group(2), "key": m.group(3)})
                s[lang] = f
    return screens


def raw_nodes(raw, lang, source, key):
    f = os.path.join(raw, lang, source, key + ".nodes.txt")
    if not os.path.exists(f):
        return None
    rows = []
    for line in open(f, encoding="utf-8", errors="replace"):
        parts = line.rstrip("\n").split("|")
        if len(parts) >= 3:
            rows.append(parts)
    return rows


def ocr_words(cmd, cache, image, lang):
    """(word, confidence) of every word OCR reads in the picture, from the cache when it has them; the
    lines of the system's keyboard (SYSTEM_LINES) left out."""
    os.makedirs(cache, exist_ok=True)
    out = os.path.join(cache, lang + "--" + os.path.basename(image)[:-4] + ".psm11.tsv")
    if not os.path.exists(out) or os.path.getmtime(out) < os.path.getmtime(image):
        # Sparse text (--psm 11): a screen's texts sit apart, around buttons and fields, where the
        # automatic page layout misses some.
        r = subprocess.run([cmd, image, OCR_MODELS[lang], "--psm", "11", "tsv"], capture_output=True, text=True)
        open(out, "w", encoding="utf-8").write(r.stdout)
    lines = {}
    for line in open(out, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 12 or p[0] != "5" or not p[11].strip():
            continue
        try:
            lines.setdefault((p[2], p[3], p[4]), []).append((p[11].strip(), float(p[10])))
        except ValueError:
            continue
    words = []
    for key, ws in lines.items():
        if " ".join(w for w, _ in ws).lower().strip(STRIP) in SYSTEM_LINES:
            continue
        words += ws
    return words


def differ(a, b):
    """The share of pixels below the status bar that differ between two pictures (1.0 when the sizes do)."""
    ia, ib = Image.open(a).convert("RGB"), Image.open(b).convert("RGB")
    if ia.size != ib.size:
        return 1.0
    w, h = ia.size
    box = (0, STATUS_BAR_PX, w, h)
    d = ImageChops.difference(ia.crop(box), ib.crop(box)).convert("L").point(lambda v: 255 if v > PIXEL_DELTA else 0)
    return d.histogram()[255] / float(w * (h - STATUS_BAR_PX))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("set_dir")
    ap.add_argument("raw")
    ap.add_argument("repo")
    ap.add_argument("--langs", default=",".join(LANGS))
    ap.add_argument("--ocr")
    ap.add_argument("--cache")
    ap.add_argument("--json")
    ap.add_argument("--jobs", type=int, default=8)
    a = ap.parse_args()
    langs = a.langs.split(",")
    rules = Rules(a.repo)
    screens = read_set(a.set_dir, langs)
    cache = a.cache or os.path.join(os.path.dirname(os.path.abspath(a.raw)), "ocr")
    results = {}  # (number, lang) -> dict
    failures, notes = [], []

    def label(n, lang, how=None):
        s = screens[n]
        return f"{n} {s['source']}-{s['key']} [{lang}" + (f", {how}]" if how else "]")

    # 1. pixels
    pairs = []
    for n, s in sorted(screens.items()):
        others = [l for l in langs if l != "en" and l in s]
        if "en" in s:
            pairs += [(n, l, "en") for l in others]
        pairs += [(n, others[i], others[j]) for i in range(len(others)) for j in range(i + 1, len(others))]
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.jobs) as ex:
        shares = dict(zip(pairs, ex.map(lambda p: differ(screens[p[0]][p[1]], screens[p[0]][p[2]]), pairs)))
    for (n, la, lb), share in sorted(shares.items()):
        results.setdefault((n, la), {"flags": []}).setdefault("differs", {})[lb] = round(share, 4)
        results.setdefault((n, lb), {"flags": []}).setdefault("differs", {})[la] = round(share, 4)
        if share < SAME_BELOW:
            failures.append(f"{label(n, la)}: the same picture as {lb} ({share * 100:.2f} % of the pixels differ)")
            results[(n, la)]["flags"].append(f"same as {lb}")

    # 2. and 3. letters
    def letters(job):
        n, lang = job
        s = screens[n]
        nodes = raw_nodes(a.raw, lang, s["source"], s["key"]) if s["source"] == "session" else None
        if nodes is not None:
            texts = [row[0] for row in nodes if row[0].strip()]
            # The system's window (the fingerprint prompt, the file picker) has resource ids of its own; the
            # app's own dialogs and menus are windows with none but the window's "content".
            system = not any(row[2].startswith("pv.") for row in nodes) and \
                any(row[2] not in ("", "content", "action_bar_root") for row in nodes)
            return job, "texts", system, texts, None
        if a.ocr:
            return job, "ocr", False, None, ocr_words(a.ocr, cache, s[lang], lang)
        return job, None, False, None, None

    jobs = [(n, lang) for n, s in sorted(screens.items()) for lang in langs if lang in s]
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.jobs) as ex:
        read = list(ex.map(letters, jobs))
    for (n, lang), how, system, texts, ocr in read:
        r = results.setdefault((n, lang), {"flags": []})
        r["read"] = how or "not read"
        if how is None:
            continue
        bad = []
        if how == "texts":
            words = [w for t in texts for w in tokens_of(t)]
            # In the system's window (the fingerprint prompt) only the app's own texts are judged: they are
            # its strings, which must be translated; the system's own words are the phone's language.
            for w in ([] if system else words):
                why = rules.exact(lang, w)
                if why:
                    bad.append(f"{why} \"{w}\"")
            if lang != "en":
                for t in texts:
                    for name, pat in rules.english_texts[lang]:
                        if pat.match(t.strip()):
                            bad.append(f"English text \"{t.strip()[:70]}\" ({name})")
                            break
        else:
            words = []
            unsure = set()
            for w, conf in ocr:
                for t in tokens_of(w):
                    words.append(t)
                    if conf >= OCR_MIN_CONF and len(t) >= OCR_MIN_LEN:
                        why = rules.read(lang, t)
                        if why:
                            bad.append(f"{why} \"{t}\"")
                        elif conf >= OCR_SURE and rules.unknown(lang, t):
                            # Read surely, in the other alphabet, and no name or data: listed for a look
                            # (a library's own label is in no string of the app).
                            unsure.add(t)
            if unsure:
                r["unsure"] = sorted(unsure)
                notes.append(f"{label(n, lang, how)}: other letters read, for a look: " + ", ".join(sorted(unsure)[:12]))
        cyr = sum(len(CYR.findall(w)) for w in words)
        uz = sum(1 for w in words for c in w if c in UZ_CYRL_ONLY)
        r["cyrillic_letters"], r["uzbek_letters"], r["words"] = cyr, uz, len(words)
        if lang == "uz-Cyrl" and how == "texts" and cyr >= 40 and uz == 0 and not system:
            bad.append("no ў қ ғ ҳ in Ўзбекча")
        if lang == "ru" and how == "texts" and uz > 0 and not system:
            bad.append(f"{uz} letters of ў қ ғ ҳ in Русский")
        bad = sorted(set(bad))
        if system:
            r["system"] = True
            notes.append(f"{label(n, lang, how)}: the system's window in front; the app's texts in it judged only")
        if bad:
            r["flags"] += bad
            failures.append(f"{label(n, lang, how)}: " + "; ".join(bad[:12]) + (f"; and {len(bad) - 12} more" if len(bad) > 12 else ""))

    missing = [(n, s["source"] + "-" + s["key"], [l for l in langs if l not in s]) for n, s in sorted(screens.items())
               if any(l not in s for l in langs)]
    print(f"{len(screens)} screens; " + ", ".join(f"{l} {sum(1 for s in screens.values() if l in s)}" for l in langs))
    for lang in langs:
        kinds = {}
        for (n, l), r in results.items():
            if l == lang:
                kinds[r.get("read", "not read")] = kinds.get(r.get("read", "not read"), 0) + 1
        print(f"  {lang}: letters read from " + ", ".join(f"{k} {v}" for k, v in sorted(kinds.items())))
    for n, key, ls in missing:
        print(f"MISSING {n} {key}: no picture in {', '.join(ls)}")
    for line in notes:
        print("NOTE " + line)
    for line in failures:
        print("FAIL " + line)
    print(f"SUMMARY {len(failures)} failures, {len(notes)} notes, {len(missing)} screens without every language")
    if a.json:
        out = {}
        for (n, lang), r in sorted(results.items()):
            out.setdefault(n, {"source": screens[n]["source"], "key": screens[n]["key"]})[lang] = r
        json.dump(out, open(a.json, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
