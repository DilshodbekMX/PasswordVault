#!/usr/bin/env bash
# tools/emu/checks/6-pictures.sh - the owner's first picture set (build plan 12, "From the merge of R6 to
# round R7", "A picture of every screen in each of the four languages"; architecture.md 8.6 item 8; the
# owner's decision 26): every screen the app shows on purpose, in English, Uzbek in Latin letters, Uzbek
# in Cyrillic letters and Russian, on API 37 with the debug build, for the owner to read every text. The
# part pictures of checks/6.sh runs it; it can run alone.
#
#   tools/emu/checks/6-pictures.sh --s37 SERIAL [--s24 SERIAL] [--apk FILE] [--test-apk FILE] [--out DIR]
#                                  [--logs DIR] [--langs en,uz-Latn,uz-Cyrl,ru] [--sources walk,tests,session,prompt24]
#                                  [--assemble-only]
#
# The sources, each run in every language (the app's own tags: en, uz, uz-Cyrl, ru):
#   walk      the real app: tools/emu/layout/walk.sh, its part app at the phone's own size (411 dp, font
#             100 %) with --stretch-shots: every screen the walk reaches, and every further stretch of
#             one that scrolls.
#   tests     the states the real app reaches only with time or a damaged file, drawn by the device
#             tests that keep pictures in the activity's own language, with the app's language set by
#             cmd locale set-app-locales (as R6's ru-shots.sh and ru-a-states.sh ran them): Settings,
#             Security, Import, Export (their *ScreenshotsTest), Backup (BackupPseudoLocaleTest
#             #theScreensInEnglishLightAndDark), the lock (LockScreensTest, PadScreenTest,
#             NoticesScreenTest, ForgotSheetScreenTest with -e pvShots 1) and the master password
#             (MasterScreenshotsTest). A test's failed assertion is listed; its pictures count.
#   session   the real app through the states the walk does not reach: checks/6-pictures-session.sh
#             (the system's fingerprint prompts, the pad's lines, the fingerprint-only screen, the
#             notices, a new master password up to its backup step, the import's preview and results,
#             the Security tab with fifty accounts, the backups and exports with their results, the
#             list's banners, the practice screens, the Keystore keys gone, "Start with an empty app",
#             Old passwords, a restore).
#   prompt24  on API 24 (--s24): androidx.biometric's own fingerprint dialog, which it draws in the
#             activity on Android 7 to 8.1 (the parts setup and pad of the session script).
# Then the pictures are gathered into DIR/<en|uz-Latn|uz-Cyrl|ru>/ as NNN-SOURCE-NAME-LANGUAGE.png: the
# number and the name are the same in the four folders, so that the four pictures of one screen sort
# together. The dark copies and the other colour styles' copies of states the tests also keep in the
# default style are left out (the same texts); LookScreenshotsTest's list in Bank blue, Emerald and Night
# vault, light and dark, is the source "look". checks/6-pictures-check.py then checks every picture: in
# uz-Latn, uz-Cyrl and ru it differs from the English one and from the other two, and it shows its own
# letters (the session's texts beside its pictures; the walk's and the tests' by OCR when PV_OCR names a
# command that prints tesseract's TSV, `$PV_OCR IMAGE LANGS tsv`; report LOGS/check.txt). DIR/index.html
# shows the screens one per row, each with its name in English (checks/6-pictures-names.tsv) and its four
# pictures side by side, with what the owner should look at first and the check's findings; DIR/README.md
# lists them. The raw pictures and the logs stay in LOGS (default build/emu-checks/6/pictures).
#
# Exit 0 when every source ran in every language, the set was written and its check passed; 1 otherwise.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

OUT=/home/asus/pvwork/shots-langs/final
APK=$REPO/build/emu-checks/6/apk/app-debug.apk
TEST_APK=$REPO/build/emu-checks/6/apk/app-debug-androidTest.apk
LOGS=$REPO/build/emu-checks/6/pictures
S37=""
S24=""
LANGS=en,uz-Latn,uz-Cyrl,ru
SOURCES=walk,tests,session,prompt24
ASSEMBLE_ONLY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --s37) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; S37=$2; shift ;;
        --s24) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; S24=$2; shift ;;
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        --test-apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; TEST_APK=$2; shift ;;
        --out) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; OUT=$2; shift ;;
        --logs) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; LOGS=$2; shift ;;
        --langs) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; LANGS=$2; shift ;;
        --sources) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SOURCES=$2; shift ;;
        --assemble-only) ASSEMBLE_ONLY=1 ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done
RAW=$LOGS/raw
mkdir -p "$RAW" "$OUT"
has_source() { [[ ",$SOURCES," == *",$1,"* ]]; }

# tag_of LANGUAGE: the app's own tag (AppLanguages) for a folder's language.
tag_of() {
    case "$1" in
        en) echo en ;;
        uz-Latn) echo uz ;;
        uz-Cyrl) echo uz-Cyrl ;;
        ru) echo ru ;;
        *) return 1 ;;
    esac
}

# ---- walk ----------------------------------------------------------------------------------------------
# walk_lang LANGUAGE: the part app at 411 dp and font 1.0 with a picture of every stretch; the pictures in
# RAW/LANGUAGE/walk/app-TAG/, the walk's own log and report in LOGS/walk-LANGUAGE/.
walk_lang() {
    local l=$1 t rc
    t=$(tag_of "$l")
    rm -rf "${RAW:?}/$l/walk"
    mkdir -p "$RAW/$l/walk" "$LOGS/walk-$l"
    "$EMU/layout/walk.sh" "$S37" --apk "$APK" --test-apk "$TEST_APK" --parts app --locales "$t" --width 411 --font 1.0 \
        --stretch-shots --shots "$RAW/$l/walk" > "$LOGS/walk-$l.log" 2>&1
    rc=$?
    cp "$REPO/build/emu-layout/$S37/app-$t".* "$LOGS/walk-$l/" 2> /dev/null
    echo "   walk $l: exit $rc; $(grep -E '^SUMMARY' "$LOGS/walk-$l.log" | tail -n 1); $(ls "$RAW/$l/walk/app-$t" 2> /dev/null | wc -l) pictures"
    # The walk itself must have ended; its layout findings at the phone's own size are listed, not judged
    # here (the part walk of checks/6.sh judges the walk at 320 dp and 200 %).
    grep -q '^END ' "$LOGS/walk-$l/app-$t.txt" 2> /dev/null || { echo "   walk $l: the walk did not end"; return 1; }
    grep -E '^FAIL' "$LOGS/walk-$l.log" | cut -c1-220 | sed 's/^/     /'
    return 0
}

# ---- tests ---------------------------------------------------------------------------------------------
P=io.github.dilshodbekmx.passwordvault
RUNNER=$P.test/androidx.test.runner.AndroidJUnitRunner
# NAME|CLASS[#METHOD]|ARGUMENTS|WHERE THE PICTURES ARE (device folders, or cache:FOLDER in the app's data)
TEST_SPECS="settings|$P.ui.settings.SettingsScreenshotsTest||/data/local/tmp/pv-shots-4A
security|$P.ui.security.SecurityScreenshotsTest||/data/local/tmp/pv-shots-7.1
import|$P.ui.transfer.import.ImportScreenshotsTest||/data/local/tmp/pv-shots-5.4
export|$P.ui.transfer.export.ExportScreenshotsTest||/data/local/tmp/pv-shots-5.3
backup|$P.ui.transfer.backup.BackupPseudoLocaleTest#theScreensInEnglishLightAndDark||cache:pv-screens
lock|$P.ui.lock.LockScreensTest|-e pvShots 1|/data/local/tmp/shots-4A
pad|$P.ui.lock.PadScreenTest|-e pvShots 1|/data/local/tmp/shots-4A
notices|$P.ui.lock.NoticesScreenTest|-e pvShots 1|/data/local/tmp/shots-4A
forgot|$P.ui.lock.ForgotSheetScreenTest|-e pvShots 1|/data/local/tmp/shots-4A
master|$P.ui.settings.master.MasterScreenshotsTest||/data/local/tmp/pv-shots-5.5 /data/local/tmp/pv-shots-5.5-banners
look|$P.ui.theme.LookScreenshotsTest||/data/local/tmp/pv-shots-look"

# tests_lang LANGUAGE: each test with the app's data cleared and its language set first; its pictures
# pulled to RAW/LANGUAGE/tests/NAME/.
tests_lang() {
    local l=$1 t line name class args where dir tmp n f rc=0 result
    t=$(tag_of "$l")
    rm -rf "${RAW:?}/$l/tests"
    mkdir -p "$RAW/$l/tests" "$LOGS/tests-$l"
    (
        pv_use "$S37"
        pva_install "$APK" || exit 1
        pv_adb install -r -t "$TEST_APK" > "$LOGS/tests-$l/install-test.log" 2>&1 || exit 1
        pv_unlock > /dev/null 2>&1
    ) || { echo "   tests $l: the APKs did not install"; return 1; }
    # The specs come on descriptor 3: adb reads its standard input, which would eat the list.
    while IFS='|' read -r name class args where <&3; do
        [ -n "$name" ] || continue
        PV_SERIAL=$S37 pv_shell pm clear "$P" > /dev/null 2>&1
        PV_SERIAL=$S37 pv_shell cmd locale set-app-locales "$P" --locales "$t" > /dev/null 2>&1
        for dir in $where; do
            case "$dir" in cache:*) ;; *) PV_SERIAL=$S37 pv_shell rm -rf "$dir" > /dev/null 2>&1 ;; esac
        done
        # shellcheck disable=SC2086 # the arguments are words
        PV_SERIAL=$S37 pv_adb shell am instrument -w -r $args -e class "$class" "$RUNNER" > "$LOGS/tests-$l/$name.log" 2>&1 < /dev/null
        result=$(grep -m 1 -E '^(OK \(|Tests run:)' "$LOGS/tests-$l/$name.log" || echo "no result")
        mkdir -p "$RAW/$l/tests/$name"
        for dir in $where; do
            case "$dir" in
                cache:*)
                    for f in $(PV_SERIAL=$S37 pv_adb exec-out run-as "$P" ls "cache/${dir#cache:}" 2> /dev/null | tr -d '\r'); do
                        case "$f" in *-en-XA.png | *-ar-XB.png | *XA* | *XB*) continue ;; esac
                        PV_SERIAL=$S37 pv_adb exec-out run-as "$P" cat "cache/${dir#cache:}/$f" > "$RAW/$l/tests/$name/$f"
                    done ;;
                *)
                    tmp=$RAW/$l/tests/$name/.pull
                    rm -rf "$tmp"
                    mkdir -p "$tmp"
                    PV_SERIAL=$S37 pv_adb pull "$dir/." "$tmp/" > /dev/null 2>&1
                    for f in "$tmp"/*.png; do [ -f "$f" ] && mv "$f" "$RAW/$l/tests/$name/"; done
                    rm -rf "$tmp" ;;
            esac
        done
        n=$(ls "$RAW/$l/tests/$name" 2> /dev/null | wc -l)
        echo "   tests $l $name: $result; $n pictures"
        grep -A1 'INSTRUMENTATION_STATUS: stack=' "$LOGS/tests-$l/$name.log" | grep -v '^--' | grep -v 'INSTRUMENTATION_STATUS: stack=$' \
            | sed -n 's/^INSTRUMENTATION_STATUS: stack=//p' | cut -c1-200 | sed 's/^/     failed: /'
        [ "$n" -gt 0 ] || rc=1
    done 3<<< "$TEST_SPECS"
    PV_SERIAL=$S37 pv_shell cmd locale set-app-locales "$P" > /dev/null 2>&1
    PV_SERIAL=$S37 pv_adb uninstall "$P.test" > /dev/null 2>&1
    return $rc
}

# ---- session, prompt24 ------------------------------------------------------------------------------------
session_lang() {
    local l=$1 t rc
    t=$(tag_of "$l")
    rm -rf "${RAW:?}/$l/session"
    "$EMU/checks/6-pictures-session.sh" "$S37" --apk "$APK" --lang "$t" --out "$RAW/$l/session" > "$LOGS/session-$l.log" 2>&1
    rc=$?
    echo "   session $l: exit $rc; $(grep -E '^SUMMARY' "$LOGS/session-$l.log" | tail -n 1); $(ls "$RAW/$l/session" 2> /dev/null | wc -l) pictures"
    grep -E '^(FAIL|SKIPPED)' "$LOGS/session-$l.log" | sed 's/^/     /'
    return $rc
}

prompt24_lang() {
    local l=$1 t rc
    t=$(tag_of "$l")
    rm -rf "${RAW:?}/$l/prompt24"
    "$EMU/checks/6-pictures-session.sh" "$S24" --apk "$APK" --lang "$t" --out "$RAW/$l/prompt24" --only setup,pad > "$LOGS/prompt24-$l.log" 2>&1
    rc=$?
    echo "   prompt24 $l: exit $rc; $(grep -E '^SUMMARY' "$LOGS/prompt24-$l.log" | tail -n 1); $(ls "$RAW/$l/prompt24" 2> /dev/null | wc -l) pictures"
    grep -E '^(FAIL|SKIPPED)' "$LOGS/prompt24-$l.log" | sed 's/^/     /'
    return $rc
}

# ---- the set ----------------------------------------------------------------------------------------------
# assemble: RAW/<language>/<source>/... into OUT/<language>/NNN-SOURCE-NAME-LANGUAGE.png. One number per
# screen: the union of the four languages' names, in the order of the sources and, within a source, of the
# first language that has the picture. The colour styles (LookScreenshotsTest's lists) are a source of their
# own, "look", after the tests.
assemble() {
    python3 - "$RAW" "$OUT" "$LANGS" <<'EOF'
import os, re, shutil, sys
raw, out, langs = sys.argv[1], sys.argv[2], sys.argv[3].split(",")
SOURCES = ["walk", "session", "tests", "look", "prompt24"]
PROMPT24 = re.compile(r"^(102-prompt-turn-on|110-prompt-unlock|116-fingerprint-only-prompt|117-fingerprint-only)\.png$")
TAGS = {"en": "en", "uz-Latn": "uz", "uz-Cyrl": "uz-Cyrl", "ru": "ru"}

def natural(s):
    return [int(p) if p.isdigit() else p for p in re.split(r"(\d+)", s)]

def keys_of(lang, source):
    base = os.path.join(raw, lang, "tests/look" if source == "look" else source)
    found = []
    if not os.path.isdir(base):
        return found
    for root, _, files in os.walk(base):
        for f in files:
            if not f.endswith(".png"):
                continue
            rel = os.path.relpath(os.path.join(root, f), base)
            parts = rel.split(os.sep)
            name = f[:-4]
            if source == "walk":
                # app-<tag>/<Screen>.png: the run's folder differs by language; the screen's name does not.
                found.append((name, rel))
            elif source == "look":
                # Of LookScreenshotsTest's pictures only the list, in each of the three styles, light and
                # dark (the phone's colours depend on the wallpaper; the other screens show the same texts).
                m = re.match(r"^look-api\d+-(bank-blue|emerald|night-vault)-(light|dark)-list$", name)
                if m:
                    found.append((f"{m.group(1)}-{m.group(2)}-list", rel))
            elif source == "tests":
                if parts[0] == "look":
                    continue
                # The dark copies and the other colour styles' copies of a state show the same texts.
                if "dark" in name.split("-") or "emerald" in name or "night-vault" in name or "bank-blue" in name:
                    continue
                name = re.sub(r"-api\d+$", "", name)
                name = re.sub(r"-light$", "", name)
                found.append((parts[0] + "-" + name, os.path.join(*parts)))
            elif source == "session":
                found.append((name, rel))
            elif source == "prompt24":
                if PROMPT24.match(f):
                    found.append((name, rel))
    return found

def walk_order(lang):
    # The order in which the walk looked at its screens: its report's SCREEN lines (LOGS/walk-LANG/app-TAG.txt).
    report = os.path.join(os.path.dirname(raw), f"walk-{lang}", f"app-{TAGS.get(lang, lang)}.txt")
    order = {}
    if os.path.exists(report):
        for line in open(report, encoding="utf-8", errors="replace"):
            if line.startswith("SCREEN "):
                order.setdefault(line[7:].split("|", 1)[0], len(order))
    return order

def ordered(lang, source, found):
    # The walk's pictures in the order the walk looked at the screens (a stretch "<screen>--N" right after
    # its screen); the others by name; the styles light before dark.
    if source == "walk":
        order = walk_order(lang)
        def place(kv):
            screen, _, stretch = kv[0].partition("--")
            return (order.get(screen, 10_000), screen, int(stretch) if stretch.isdigit() else 0)
        return sorted(found, key=place)
    if source == "look":
        styles = ["bank-blue", "emerald", "night-vault"]
        return sorted(found, key=lambda kv: (styles.index(kv[0].rsplit("-", 2)[0]), "dark" in kv[0]))
    return sorted(found, key=lambda kv: natural(kv[0]))

catalog, seen, files = [], set(), {}
for source in SOURCES:
    for lang in langs:
        for key, rel in ordered(lang, source, keys_of(lang, source)):
            base = os.path.join(raw, lang, "tests/look" if source == "look" else source)
            files[(source, key, lang)] = os.path.join(base, rel)
            if (source, key) not in seen:
                seen.add((source, key))
                catalog.append((source, key))

def slug(s):
    return re.sub(r"[^A-Za-z0-9._-]+", "-", s).strip("-")

for lang in langs:
    d = os.path.join(out, lang)
    if os.path.isdir(d):
        for f in os.listdir(d):
            if f.endswith(".png"):
                os.remove(os.path.join(d, f))
    os.makedirs(d, exist_ok=True)
counts = {l: 0 for l in langs}
missing = []
for number, (source, key) in enumerate(catalog, 1):
    have = []
    for lang in langs:
        src = files.get((source, key, lang))
        if src:
            shutil.copyfile(src, os.path.join(out, lang, f"{number:03d}-{source}-{slug(key)}-{lang}.png"))
            counts[lang] += 1
            have.append(lang)
    if len(have) < len(langs):
        missing.append(f"{number:03d}-{source}-{key}: none in {', '.join(l for l in langs if l not in have)}")
print(f"   {len(catalog)} screens; " + ", ".join(f"{l} {counts[l]}" for l in langs) + f"; {len(missing)} screens lack a language")
for line in missing[:40]:
    print("     " + line)
EOF
}

# check_set: tools/emu/checks/6-pictures-check.py over the set: every picture in uz-Latn, uz-Cyrl and ru
# differs from English and from the others, and shows its own letters (the session's texts beside its
# pictures; the walk's and the tests' pictures by OCR when PV_OCR names a command that prints tesseract's
# TSV: `$PV_OCR IMAGE LANGS tsv`). Its report goes to LOGS/check.txt, every picture's result to
# LOGS/check.json for the index.
check_set() {
    local rc
    python3 "$EMU/checks/6-pictures-check.py" "$OUT" "$RAW" "$REPO" --langs "$LANGS" ${PV_OCR:+--ocr "$PV_OCR"} \
        --cache "$LOGS/ocr" --json "$LOGS/check.json" > "$LOGS/check.txt" 2>&1
    rc=$?
    grep -E '^(SUMMARY|FAIL|MISSING)' "$LOGS/check.txt" | head -n 60 | cut -c1-240 | sed 's/^/   /'
    [ -n "${PV_OCR:-}" ] || echo "   (PV_OCR not set: the letters of the walk's and the tests' pictures were not read)"
    return $rc
}

# index: OUT/index.html (the four languages side by side, screen by screen, each with its name in English
# from checks/6-pictures-names.tsv) and OUT/README.md, from the pictures in OUT and LOGS/check.json.
index() {
    python3 - "$OUT" "$LANGS" "$EMU/checks/6-pictures-names.tsv" "$LOGS/check.json" "$LOGS/check.txt" \
        "$(git -C "$REPO" rev-parse --short HEAD)" "$(date '+%F %H:%M')" <<'EOF'
import glob, html, json, os, re, sys
out, langs, names_file, check_file, check_txt, commit, when = sys.argv[1], sys.argv[2].split(","), *sys.argv[3:]
LANG_NAMES = {"en": "English", "uz-Latn": "Oʻzbekcha (lotin)", "uz-Cyrl": "Ўзбекча (кирилл)", "ru": "Русский"}
SOURCES = [
    ("walk", "The app, screen by screen",
     "The real app, walked from a fresh install at the phone's own size: Welcome, setup, the PIN steps, the list, an account, "
     "the form and the password generator, the Security check, Settings and every screen its rows open, Backup, Export, "
     "Import, Change master password, Help and the licences, and the lock screen. A screen taller than the phone has one "
     "picture per part (part 1, part 2, ...)."),
    ("session", "The lock, the files and the reminders on the real app",
     "States the walk does not reach, driven on the real app: the phone's fingerprint prompts, the PIN pad's lines after "
     "wrong PINs, the fingerprint-only screen, the messages after an unlock, a new master password up to its backup step, "
     "the import's preview and results, the Security check with fifty accounts, the backup and the exports with their "
     "results, the list's banners, the practice screens (the phone's clock moved on), the keys of the PIN and the "
     "fingerprint gone, \"Start with an empty app\", Old passwords and a restore."),
    ("tests", "States drawn by the app's own tests",
     "States the real app reaches only with time, a damaged file or a slow phone, drawn by the app's device tests in each "
     "language: Settings, Security, Import, Export, Backup and Restore, the lock screens, the PIN pad, the messages after an "
     "unlock, the \"Forgot master password?\" sheet, and the steps of changing or replacing the master password with the "
     "list's banners. These use made-up accounts and dates."),
    ("look", "The three colour styles",
     "The list in each colour style of Settings › Appearance, light and dark (made-up accounts). The other pictures show "
     "Bank blue, light."),
    ("prompt24", "Android 7's own fingerprint dialog",
     "On Android 7 to 8.1 the fingerprint dialog is drawn by the app's library, not by the phone (API 24 here): its texts "
     "come from the app."),
]
# What the owner should look at first (build plan 12: the owner's questions and their answers in
# /home/asus/pvwork/kept/owner-answers-R5-R6.md). Plain text; each item one paragraph.
LOOK_AT = [
    ("Every text in your languages",
     "Read every text in Oʻzbekcha, Ўзбекча and Русский and note a wrong or unnatural word, a sentence that says something "
     "other than the English one beside it, English left on a screen, or a word cut off or split in a strange place. Write "
     "down the picture's number (the three digits at the start of each row and of each file's name) with what is wrong."),
    ("Two questions that wait for your answer",
     "1. \"Random\" in the password generator (\"Password generator: random characters\"): the app now says \"Tasodifiy\" / "
     "\"Тасодифий\" (the exact word; on a small phone with the largest text the two choices become two rows). The other "
     "choice is \"Aralash\" / \"Аралаш\" (mixed). 2. \"Turned off\" for the PIN after five wrong tries and for fingerprint "
     "unlock (the PIN pad's lines after wrong PINs, the messages after an unlock, Help): the app now says \"faolsiz qilindi\" / "
     "\"фаолсиз қилинди\" (shorter, so it does not split on small phones). The other choice is \"faolsizlantirildi\" / "
     "\"фаолсизлантирилди\". Until you answer, the app keeps what these pictures show."),
    ("Words chosen for you to confirm",
     "Uzbek: \"Ekrandan oʻqish vositasi\" for TalkBack; \"Kodli soʻz\" (code word); \"Esimda yoʻq\" (I don't remember it); "
     "\"Saqlamasdan chiqish\" (Discard); \"Yana tekshiruvga kiritish\" (Check it again); \"Topish qiyin\" / \"oson\" (hard or "
     "easy to guess); the dates \"… kuni\", \"… sanasidan oldingi\" and \"… da\"; the style names \"Bank koʻki\", \"Zumrad\", "
     "\"Tungi seyf\"; \"Mavzu\" for Appearance; \"operativ xotira\"; \"Test yigʻmasi\" for the test build. Russian: «Забыли свой "
     "мастер-пароль?»; «Показать крупно»; «Недавно удалённые»; «Не помню»; «Файл уже удалён»; «Пароль записан»; «Задать "
     "PIN-код»; «хранилище телефона» and «оперативная память»; «Лицензии открытого ПО»; «Блокировка после выхода из "
     "приложения»; «Очистка буфера обмена»; «Google Таблицы»; Chrome's and Excel's menu names in the import help."),
    ("Decided since the first pictures, and in these",
     "In Russian «Это не я» (not «Это был не я»), «Менеджер паролей Google» in every text, «Google Диск», and one wording of "
     "\"Until then, open the app with your PIN or fingerprint as usual\"; in Ўзбекча the word to search for in the phone's "
     "Settings, \"Parollar menejeri\", in Latin letters, as an Uzbek phone shows it (the menu path after it stays in "
     "Cyrillic letters); in all four languages \"in this app's Settings\" where a text sends you to the app's own Settings, "
     "\"Your keyboard may be set to Cyrillic letters (Russian, for example).\", and \"Try again in 30 seconds\" without a "
     "full stop."),
    ("New sentences, in all four languages",
     "\"Forgot master password?\" while the PIN cannot be checked now: \"Your PIN can't be checked right now. When it works "
     "again, open the app with it, then choose a new master password.\" (its translations are new: please read them). In "
     "the generator at the largest text, the separators read \"- Hyphen\", \". Dot\", \"_ Underscore\" from one sentence "
     "per language. A message with \"Try again\" or \"Undo\" puts the button under its text where the two would not fit "
     "side by side."),
    ("Three questions from the last review",
     "1. «Google Диск» in Russian is also in the PIN steps' sentence about backups outside the phone (pin_backups_outside), "
     "beyond the seven texts your answer named: keep it there too, or «Google Drive» there? 2. A long note on an account's "
     "screen breaks with a hyphen at the end of a line, in every language, as other long texts now do; a password, a user "
     "name, a website and a field never do, because you may type them elsewhere. Should a note never be hyphenated either? "
     "3. In English three texts keep a plain space after a number (\"After 5 wrong tries\" twice, \"After 1 more wrong PIN\"), "
     "so the number can end a line on a small phone; their translations keep it with its word. Join them in English too?"),
    ("What is not the app",
     "The phone's own windows speak the phone's language, English on this test phone: the fingerprint prompt's lower line "
     "(\"Touch the fingerprint sensor\"), the file picker (\"Downloads\", \"SAVE\"), the keyboard and the bar at the top. "
     "The accounts, logins, dates and file names are made-up test data. The red line \"TEST BUILD: PIN keys are not "
     "protected\" on the lock screen and \"Test build\" in Help › Version show only in these test builds, never in the app "
     "you install, but they are translated too."),
]

names = {}
for line in open(names_file, encoding="utf-8"):
    if line.startswith("#") or not line.strip():
        continue
    p = line.rstrip("\n").split("\t")
    if len(p) == 3:
        names[(p[0], p[1])] = p[2]
check = json.load(open(check_file, encoding="utf-8")) if os.path.exists(check_file) else {}
summary = ""
if os.path.exists(check_txt):
    for line in open(check_txt, encoding="utf-8"):
        if line.startswith("SUMMARY"):
            summary = line.strip()

rows = {}  # number -> {"source", "key", lang: path}
for lang in langs:
    for f in sorted(glob.glob(os.path.join(out, lang, "*.png"))):
        m = re.match(r"^(\d{3})-([a-z0-9]+)-(.*)-" + re.escape(lang) + r"\.png$", os.path.basename(f))
        if m:
            r = rows.setdefault(m.group(1), {"source": m.group(2), "key": m.group(3)})
            r[lang] = f"{lang}/{os.path.basename(f)}"
keys = {(r["source"], r["key"]) for r in rows.values()}

def name_of(source, key):
    if (source, key) in names:
        n = names[(source, key)]
        if source == "walk" and (source, key + "--2") in keys:
            n += " (part 1)"
        return n
    if source == "walk" and "--" in key:
        screen, _, part = key.partition("--")
        if (source, screen) in names:
            return f"{names[(source, screen)]} (part {part})"
    if source == "session":
        m = re.match(r"^(.*)-(\d+)$", key)
        if m and (source, m.group(1)) in names:
            more = any(k == (source, f"{m.group(1)}-2") for k in keys)
            return names[(source, m.group(1))] + (f" (part {m.group(2)})" if more else "")
    return key.replace("-", " ")

counts = {l: sum(1 for r in rows.values() if l in r) for l in langs}
groups = [(s, t, d, [(n, rows[n]) for n in sorted(rows) if rows[n]["source"] == s]) for s, t, d in SOURCES]
groups = [g for g in groups if g[3]]

def flags(n, lang):
    return (check.get(n, {}).get(lang) or {}).get("flags") or []

h = ["<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>",
     "<title>PasswordVault in four languages</title><style>"
     ":root{color-scheme:light dark;--bg:#fff;--fg:#1b1b1f;--muted:#5f6368;--line:#dadce0;--head:#f1f3f4;--bad:#b3261e;--card:#f8f9fa}"
     "@media (prefers-color-scheme:dark){:root{--bg:#121212;--fg:#e8eaed;--muted:#9aa0a6;--line:#3c4043;--head:#202124;--bad:#f2b8b5;--card:#1b1b1f}}"
     "body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,sans-serif}"
     "h1{font-size:24px;margin:0 0 8px}h2{font-size:20px;margin:36px 0 4px;padding-top:10px;border-top:2px solid var(--line)}"
     "h3{font-size:16px;margin:16px 0 2px}p,li{max-width:75em}p.muted{color:var(--muted)}"
     ".how{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:8px 16px}"
     "table{border-collapse:collapse;width:100%;table-layout:fixed}"
     "th{position:sticky;top:0;z-index:1;background:var(--head);text-align:left;padding:8px 6px;font-weight:600}"
     "th.n,td.n{width:13em}td{vertical-align:top;padding:8px 6px;border-bottom:1px solid var(--line)}"
     "td.n b{font-family:ui-monospace,monospace;font-size:15px;display:block}td.n small{color:var(--muted);word-break:break-all}"
     "td img{width:100%;max-width:340px;height:auto;border:1px solid var(--line);display:block}"
     ".none{color:var(--muted);font-style:italic;font-size:14px}.flag{color:var(--bad);font-size:13px}"
     "nav a{margin-right:14px;white-space:nowrap}"
     "@media (max-width:700px){th.n,td.n{width:6.5em}td.n small{display:none}body{padding:8px}}"
     "</style></head><body>",
     "<h1>PasswordVault: every screen in four languages</h1>",
     f"<p class='muted'>Made on {html.escape(when)} from commit {html.escape(commit)}: the test build of the app on an "
     "Android 17 emulator (1080 × 2400 pixels) at the phone's own size and text size. "
     + " · ".join(f"{html.escape(LANG_NAMES.get(l, l))}: {counts[l]} pictures" for l in langs) + f" · {len(rows)} screens.</p>",
     "<div class='how'><h3>How to read this page</h3><ul>"
     "<li>Each row is one screen of the app. Its name in English and its number are on the left; its four pictures follow: "
     "English, Oʻzbekcha, Ўзбекча and Русский.</li>"
     "<li>Tap or click a picture to see it at full size. The browser's Back button brings you here again.</li>"
     "<li>When you find a mistake, write down the screen's number (three digits) and the language.</li>"
     "<li>A screen taller than the phone has a picture for each part. A language with longer texts may need one part "
     "more than English; its row then says \"no picture in this language\" for the others.</li>"
     "<li>The same pictures are in the folders en, uz-Latn, uz-Cyrl and ru next to this page, named by the same number.</li>"
     "</ul></div>",
     "<nav><a href='#look-at'>What to look at</a>" + "".join(f"<a href='#{s}'>{html.escape(t)}</a>" for s, t, _, _ in groups) + "</nav>",
     "<h2 id='look-at'>What to look at</h2>"]
for title, text in LOOK_AT:
    h.append(f"<h3>{html.escape(title)}</h3><p>{html.escape(text)}</p>")
if summary:
    h.append(f"<h3>Checked</h3><p>Every picture in Oʻzbekcha, Ўзбекча and Русский was compared with the English one and with "
             f"each other, and the letters on it were read: {html.escape(summary.replace('SUMMARY ', ''))}. A finding is "
             "written in red under its picture.</p>")
for source, title, text, group in groups:
    h.append(f"<h2 id='{source}'>{html.escape(title)}</h2><p>{html.escape(text)}</p>")
    h.append("<table><tr><th class='n'>Screen</th>" + "".join(f"<th>{html.escape(LANG_NAMES.get(l, l))}</th>" for l in langs) + "</tr>")
    for n, r in group:
        nm = name_of(r["source"], r["key"])
        h.append(f"<tr id='s{n}'><td class='n'><b>{n}</b>{html.escape(nm)}<br><small>{html.escape(r['key'])}</small></td>")
        for l in langs:
            if l in r:
                fl = "".join(f"<div class='flag'>{html.escape(x)}</div>" for x in flags(n, l))
                h.append(f"<td><a href='{html.escape(r[l])}'><img loading='lazy' src='{html.escape(r[l])}' "
                         f"alt='{n} {html.escape(nm)} ({html.escape(LANG_NAMES.get(l, l))})'></a>{fl}</td>")
            else:
                h.append("<td class='none'>no picture in this language</td>")
        h.append("</tr>")
    h.append("</table>")
h.append("</body></html>")
open(os.path.join(out, "index.html"), "w", encoding="utf-8").write("\n".join(h))

m = ["# PasswordVault: every screen in four languages", "",
     f"Made on {when} from commit {commit}: the test build of the app on an Android 17 emulator (1080 × 2400 pixels) at the "
     "phone's own size and text size. " + " · ".join(f"{LANG_NAMES.get(l, l)}: {counts[l]} pictures" for l in langs)
     + f" · {len(rows)} screens.", "",
     "## How to read this set", "",
     "- Open `index.html` in a browser (double-click it). Each row is one screen: its number and its name in English, then its "
     "pictures in English, Oʻzbekcha, Ўзбекча and Русский side by side. Click a picture to see it at full size.",
     "- The pictures are also in the folders " + ", ".join(f"`{l}/`" for l in langs) + ", named "
     "`NNN-<source>-<name>-<language>.png`: the same number NNN in every folder is the same screen.",
     "- When you find a mistake, write down the screen's number and the language.",
     "- A screen taller than the phone has a picture for each part; a language with longer texts may need one part more.", ""]
if summary:
    m += ["## Checked", "", f"Every picture in Oʻzbekcha, Ўзбекча and Русский was compared with the English one and with each "
          f"other, and the letters on it were read: {summary.replace('SUMMARY ', '')}.", ""]
m += ["## What to look at", ""]
for title, text in LOOK_AT:
    m += [f"**{title}.** {text}", ""]
for source, title, text, group in groups:
    m += [f"## {title}", "", text, ""]
    for n, r in group:
        links = ", ".join(f"[{l}]({r[l]})" if l in r else f"{l}: none" for l in langs)
        fl = [f"{l}: " + "; ".join(flags(n, l)) for l in langs if flags(n, l)]
        m.append(f"- {n} {name_of(r['source'], r['key'])}: {links}" + (f" (check: {' / '.join(fl)})" if fl else ""))
    m.append("")
open(os.path.join(out, "README.md"), "w", encoding="utf-8").write("\n".join(m))
print(f"   index.html and README.md: {len(rows)} screens; " + ", ".join(f"{l} {counts[l]}" for l in langs))
EOF
}

# ---- run ------------------------------------------------------------------------------------------------------
rc=0
echo "the picture set in $LANGS from $SOURCES; raw pictures in $RAW; the set in $OUT"
if [ "$ASSEMBLE_ONLY" = 0 ]; then
    [ -n "$S37" ] || { echo "6-pictures.sh: --s37 is needed" >&2; exit 2; }
    pv_require_serial "$S37"
    [ -z "$S24" ] || pv_require_serial "$S24"
    [ -f "$APK" ] && [ -f "$TEST_APK" ] || { echo "6-pictures.sh: no APK at $APK or $TEST_APK" >&2; exit 2; }
    # API 24's dialogs run beside the rest, on their own device.
    p24=""
    if has_source prompt24 && [ -n "$S24" ]; then
        (
            r=0
            for l in ${LANGS//,/ }; do prompt24_lang "$l" || r=1; done
            exit $r
        ) > "$LOGS/prompt24.log" 2>&1 &
        p24=$!
    fi
    for l in ${LANGS//,/ }; do
        if has_source walk; then pv_step "walk in $l: every screen of the real app at the phone's own size" -- walk_lang "$l" || rc=1; fi
        if has_source tests; then pv_step "tests in $l: the states the device tests draw" -- tests_lang "$l" || rc=1; fi
        if has_source session; then pv_step "session in $l: the real app's states beyond the walk" -- session_lang "$l" || rc=1; fi
    done
    if [ -n "$p24" ]; then
        wait "$p24"
        status=$?
        cat "$LOGS/prompt24.log"
        if [ $status = 0 ]; then pv_report PASS "prompt24: androidx.biometric's own dialog on API 24 in $LANGS"; else pv_report FAIL "prompt24: androidx.biometric's own dialog on API 24 in $LANGS"; rc=1; fi
    fi
fi
pv_step "the set gathered in $OUT" -- assemble || rc=1
pv_step "every picture checked: not equal to English or another language, its own letters" -- check_set || rc=1
pv_step "index.html and README.md in $OUT" -- index || rc=1
PV_SERIAL="" pv_summary
exit $rc
