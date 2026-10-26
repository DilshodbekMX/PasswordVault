#!/usr/bin/env bash
# tools/emu/i18n/matrix.sh - build plan 6.1, [script]: the device matrix of i18n.md §5 on one private
# emulator, with the debug build (the pseudo-locales and run-as need it).
#
#   tools/emu/i18n/matrix.sh SERIAL [--apk FILE] [--shots DIR] [PART...]
#
# The APK defaults to app/build/outputs/apk/debug/app-debug.apk. Without PART the parts of the
# device's API level run (API 24 and 28: phone switch survives formats prompt; API 33 and later: phone
# switch survives page mixing formats); a part can be named on any device. Each part installs the APK
# afresh. --shots DIR keeps the screenshots (default build/emu-i18n/SERIAL/shots).
#
# What the parts check, the app found by its resource ids (tools/emu/app.sh, i18n/lib.sh) and its
# language read from the dialog's own texts (strings_language.xml, which 6.1 brings in all four
# languages; the other screens are translated since the merge of R6, 6.3 and 6.4):
#   phone     The phone's languages, set as Settings › Languages sets them (PvLocale), with the app on
#             "System language": uz-Latn-UZ gives Oʻzbekcha; uz-Cyrl-UZ added and moved first (before
#             en-US) gives Ўзбекча; uz-Arab-AF alone gives English; ru-RU, and de-DE then ru-RU then
#             en-US, and kk-KZ then ru-RU (the table of i18n.md §1.2), give Русский; and the dialog's
#             line under "System language" names that language. The phone is set back to en-US.
#   switch    The in-app switch from Welcome to each language and back to "System language", and to
#             the two pseudo-locales of the debug build: each choice closes the dialog, the process
#             stays (the same pid), Welcome comes back, and the dialog opened again is in that language
#             with that row checked. Where Android keeps the choice: from API 33 the system
#             (cmd locale get-app-locales: [uz-Latn], [uz-Cyrl], [en], [ru], []); below, AppCompat's
#             record file in files/ (run-as, i18n.md §1.4.3).
#   survives  Ўзбекча chosen; am force-stop and a new start: still Ўзбекча; a reboot of the phone and a
#             new start: still Ўзбекча; then "System language" again.
#   page      API 33 and later, the phone's own Settings › Apps › PasswordVault › Language: it lists
#             the four languages; it marks the app's choice made in the app for English, Русский,
#             Oʻzbekcha ("O‘zbek (lotin)") and "System language" ("System default"); for Ўзбекча it
#             marks nothing, which Android 17's page does whatever the tag (printed as KNOWN, not a
#             failure: the platform's, see LanguageDialog.kt); and choosing Русский there (with the
#             region Россия, which the page asks for) changes the app's language to ru-RU.
#   mixing    "With two phone languages the app never mixes languages": the APK's locale config for
#             API 35 and later has no android:defaultLocale (aapt2 on this computer); with the phone
#             set to uz-Cyrl-UZ then ru-RU and the app on "System language" the dialog is Ўзбекча, and
#             a library text that the library has in Russian and Latin Uzbek but not in Uzbek Cyrillic
#             (the bottom sheet's drag handle, from material3) is the app's own Ўзбекча override
#             (values-b+uz+Cyrl/strings_overrides.xml, build plan 6.3b; English before the merge of
#             R6), never Russian.
#   formats   A vault with an account; on its screen the date "Added …" in each language (switched
#             with the debug command `locale`, which keeps the screen) is what the phone's own ICU
#             writes for that language (skeleton yMMMd, Formatters.date); API 24 has ICU 56.
#   prompt    The fingerprint prompt's texts are the app's language, not the phone's: with the app
#             in English (XA) the PIN steps after setup ("Turn on" on the fingerprint offer) open the
#             prompt with the en-XA title and button (the system's prompt from API 28, the library's
#             own dialog in the activity on API 24), never the English ones; finger 1 turns it on.
#             (The Ўзбекча texts of the prompt come with 6.3's translations; until then Android would
#             show them in English, which is no test of the language.)
# and the app did not crash. Prints PASS, FAIL, SKIPPED or KNOWN per step and a summary; logs in
# build/emu-i18n/. Exit 0 when no step failed, 1 otherwise, 2 for a usage error, 3 for a refused
# serial. The E11 step (a language change in Settings with the lock delay "Immediately") is
# tools/emu/i18n/e11.sh.
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"
source "$EMU/i18n/lib.sh"

SERIAL=${1:-}
[ -n "$SERIAL" ] || pv_usage "${BASH_SOURCE[0]}"
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
SHOTS=""
PARTS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        phone | switch | survives | page | mixing | formats | prompt) PARTS+=("$1") ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done

pv_use "$SERIAL"
pva_init
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
API=$(pv_api)
LOGS=$REPO/build/emu-i18n/$SERIAL
SHOTS=${SHOTS:-$LOGS/shots}
mkdir -p "$LOGS" "$SHOTS"
if [ ${#PARTS[@]} -eq 0 ]; then
    if [ "$API" -ge 33 ]; then PARTS=(phone switch survives page mixing formats); else PARTS=(phone switch survives formats prompt); fi
fi
echo "i18n matrix on $SERIAL (API $API): ${PARTS[*]}; $APK"
pvi_quiet_network
KNOWN=0
# The prompt part's PIN: allowed by lock.md §7.2, and made of 1 to 6 only, the pad's first two rows. It
# was chosen when en-XA's longer texts pushed the row 7 8 9 and the 0 off a 1920-pixel screen (API 24
# and 28); since build plan 6.2 the keypad stays whole on the first screen (KeypadColumn, ux.md §5.9
# "Keypad pages"), so any allowed PIN would do. It stays as it is.
PIN=416253

known() { # NAME DETAIL: a platform behaviour that is not a failure of the app
    KNOWN=$((KNOWN + 1))
    echo "KNOWN $1 ($2)"
}

# shot NAME: a picture for the review, SHOTS/NAME.png; never a reason for a step to fail: with the staging
# build's secure window screencap gives nothing on API 24 and 28 (an empty file, or exit 1).
shot() { if pva_screenshot "$SHOTS/$1.png"; then pv_log "screenshot $SHOTS/$1.png"; fi; return 0; }

fresh() {
    pva_install "$APK" && pva_start && pva_on Welcome 30
}

# The phone's languages back to en-US, the app back to "System language" (where it can be told).
restore() {
    pvi_phone_set en-US >/dev/null 2>&1
    [ "$API" -lt 33 ] || pv_shell cmd locale set-app-locales "$PV_PACKAGE" >/dev/null 2>&1
}

# ---- phone: the phone's languages ------------------------------------------------------------------
# phone_case TAGS SHOWN: the phone set to TAGS, the app started anew on "System language": its dialog
# is in SHOWN and says SHOWN under "System language".
phone_case() {
    local tags=$1 shown=$2
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pvi_phone_set "$tags" || return 1
    pv_log "the phone's languages: $(pvi_phone_get)"
    pva_relaunch && pva_on Welcome 30 && pvi_open_language || return 1
    PVI_PHONE_SHOWN=$shown pvi_dialog_is SYSTEM "$shown"
    local rc=$?
    shot "phone-$tags"
    pvi_cancel Welcome || rc=1
    return $rc
}

part_phone() {
    fresh || { pv_report FAIL "phone: install and start"; return 1; }
    pv_step "phone: uz-Latn-UZ gives Oʻzbekcha" -- phone_case uz-Latn-UZ UZ_LATN
    pv_step "phone: Uzbek (Cyrillic) added and moved first (uz-Cyrl-UZ, en-US) gives Ўзбекча" -- phone_case uz-Cyrl-UZ,en-US UZ_CYRL
    pv_step "phone: uz-Arab-AF alone gives English" -- phone_case uz-Arab-AF EN
    pv_step "phone: ru-RU gives Русский" -- phone_case ru-RU RU
    pv_step "phone: de-DE, ru-RU, en-US gives Русский (the first language the app has)" -- phone_case de-DE,ru-RU,en-US RU
    pv_step "phone: kk-KZ, ru-RU gives Русский (localeFilters: no Kazakh library texts)" -- phone_case kk-KZ,ru-RU RU
    pv_step "phone: en-US gives English" -- phone_case en-US EN
}

# ---- switch: the in-app switch ----------------------------------------------------------------------
# stored CHOICE: where Android keeps the choice holds it.
stored() {
    local want
    if [ "$API" -ge 33 ]; then
        case "$1" in
            SYSTEM) want="[]" ;;
            UZ_LATN) want="[uz-Latn]" ;;
            UZ_CYRL) want="[uz-Cyrl]" ;;
            EN) want="[en]" ;;
            RU) want="[ru]" ;;
            EN_XA) want="[en-XA]" ;;
            AR_XB) want="[ar-XB]" ;;
        esac
        [ "$(pvi_app_locales)" = "$want" ] || { pv_log "the system keeps $(pvi_app_locales), not $want"; return 1; }
        pv_log "the system keeps $want"
    elif pv_is_debuggable; then
        case "$1" in
            SYSTEM)
                # The empty choice: AppCompat deletes its record (AppLocalesStorageHelper).
                local i
                for i in 1 2 3 4 5; do
                    [ -z "$(pvi_record_file)" ] && { pv_log "AppCompat's record is gone"; return 0; }
                    pv_sleep 1
                done
                pv_log "AppCompat's record is '$(pvi_record_file | tr '\n' ' ')', not gone"
                return 1 ;;
            UZ_LATN) want='application_locales="uz-Latn"' ;;
            UZ_CYRL) want='application_locales="uz-Cyrl"' ;;
            EN) want='application_locales="en"' ;;
            RU) want='application_locales="ru"' ;;
            EN_XA) want='application_locales="en-XA"' ;;
            AR_XB) want='application_locales="ar-XB"' ;;
        esac
        # AppCompat writes the record on a thread of its own: give it a moment.
        local i
        for i in 1 2 3 4 5; do
            pvi_record_file | grep -qF "$want" && { pv_log "AppCompat's record: $(pvi_record_file | tr '\n' ' ')"; return 0; }
            pv_sleep 1
        done
        pv_log "AppCompat's record is '$(pvi_record_file | tr '\n' ' ')', without $want"
        return 1
    fi
}

# switch_to CHOICE: from Welcome; the same process, Welcome again, the dialog in CHOICE's language
# with CHOICE checked, and the choice kept where Android keeps it.
switch_to() {
    local choice=$1 before after
    before=$(pva_pid)
    pvi_open_language && pvi_choose "$choice" Welcome || return 1
    after=$(pva_pid)
    [ -n "$before" ] && [ "$before" = "$after" ] || { pv_log "the process changed: $before -> $after"; return 1; }
    pvi_open_language || return 1
    case "$choice" in
        EN_XA | AR_XB) pseudo_dialog "$choice" ;;
        *) pvi_dialog_is "$choice" ;;
    esac
    local rc=$?
    shot "switch-$choice"
    stored "$choice" || rc=1
    pvi_cancel Welcome || rc=1
    return $rc
}

# pseudo_dialog CHOICE: the dialog's title is the APK's pseudo-localized one (compared without the
# bidirectional marks of ar-XB, which a dump may drop), and CHOICE is checked.
pseudo_dialog() {
    local qualifier want title
    case "$1" in EN_XA) qualifier=en-rXA ;; *) qualifier=ar-rXB ;; esac
    want=$(pvi_apk_string "$APK" language_title "$qualifier" | no_bidi)
    title=$(pvi_dialog_texts | head -n 1 | no_bidi)
    [ -n "$want" ] && [ "$title" = "$want" ] || { pv_log "the title is '$title', not the $qualifier '$want'"; return 1; }
    [ "$(pvi_selected)" = "$1" ] || { pv_log "the checked row is '$(pvi_selected)', not $1"; return 1; }
}

no_bidi() { python3 -c 'import sys; print(sys.stdin.read().translate({c: None for c in (0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E)}).strip())'; }

# The name of a choice in the step names.
choice_name() {
    case "$1" in
        SYSTEM) echo "System language" ;;
        EN_XA) echo "English (XA)" ;;
        AR_XB) echo "Arabic (XB)" ;;
        *) pvi_own_name "$1" ;;
    esac
}

part_switch() {
    fresh || { pv_report FAIL "switch: install and start"; return 1; }
    local c
    for c in UZ_LATN UZ_CYRL EN RU SYSTEM EN_XA AR_XB SYSTEM; do
        pv_step "switch: $(choice_name "$c") from Welcome: the same process, Welcome again, the dialog in it, kept by Android" -- switch_to "$c"
    done
}

# ---- survives: process death and reboot -------------------------------------------------------------
still_uzbek_cyrillic() {
    pva_on Welcome 60 && pvi_open_language || return 1
    pvi_dialog_is UZ_CYRL
    local rc=$?
    pvi_cancel Welcome || rc=1
    return $rc
}

after_force_stop() {
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    [ -z "$(pva_pid)" ] || { pv_log "the process is still there"; return 1; }
    pva_relaunch && still_uzbek_cyrillic
}

after_reboot() {
    pv_reboot || return 1
    pva_init
    pva_relaunch && still_uzbek_cyrillic
}

part_survives() {
    fresh || { pv_report FAIL "survives: install and start"; return 1; }
    pv_step "survives: Ўзбекча chosen" -- switch_to UZ_CYRL
    pv_step "survives: am force-stop and a new start: still Ўзбекча" -- after_force_stop
    pv_step "survives: a reboot and a new start: still Ўзбекча" -- after_reboot
    pv_step "survives: back to System language" -- switch_to SYSTEM
}

# ---- page: the phone's own language page (API 33 and later) -----------------------------------------
# page_dump: the phone's page for the app, as uiautomator dumps it (its checked attribute is wanted).
page_open() {
    pv_shell am force-stop com.android.settings >/dev/null 2>&1
    pv_shell am start -W -a android.settings.APP_LOCALE_SETTINGS -d "package:$PV_PACKAGE" >/dev/null || return 1
    pv_ui_wait '^All languages[|]' 20
}

page_xml() {
    pv_shell rm -f /data/local/tmp/pvl-page.xml >/dev/null 2>&1
    pv_shell uiautomator dump /data/local/tmp/pvl-page.xml >/dev/null 2>&1
    pv_shell cat /data/local/tmp/pvl-page.xml 2>/dev/null
}

# page_checked: the title of the row whose radio button is checked; nothing when none is.
page_checked() {
    page_xml | python3 -c '
import sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit(1)
def rows(node, parents):
    yield node, parents
    for child in node:
        yield from rows(child, parents + [node])
for node, parents in rows(root, []):
    if node.get("checkable") == "true" and node.get("checked") == "true":
        for up in reversed(parents):
            titles = [n.get("text") for n in up.iter("node") if n.get("resource-id", "").endswith(":id/title") and n.get("text")]
            if titles:
                print(titles[0])
                sys.exit(0)
'
}

# page_checked_soon: page_checked, read again for up to about 12 s until a row is checked: the page lists
# the languages first and adds the app's current one to "Suggested" a moment later (on a busy machine the
# device check of R4 read it before that, in a full run of the matrix right after other checks).
page_checked_soon() {
    local i checked
    for i in 1 2 3 4 5 6 7 8; do
        checked=$(page_checked)
        [ -z "$checked" ] || { echo "$checked"; return 0; }
        pv_sleep 1.5
    done
}

# page_lists: every language of the app is on the page: Oʻzbekcha, Ўзбекча, English, Русский.
page_lists() {
    local titles
    titles=$(pv_ui_nodes | awk -F'|' '$3 == "title" { print $1 }')
    pv_log "the page: $(printf '%s' "$titles" | tr '\n' ';')"
    printf '%s\n' "$titles" | grep -q '^O.zbek' && printf '%s\n' "$titles" | grep -q '^Ўзбекча' \
        && printf '%s\n' "$titles" | grep -qx 'English' && printf '%s\n' "$titles" | grep -qx 'Русский' \
        || { pv_log "not every language of the app is listed"; return 1; }
}

# page_marks CHOICE NAME: the app's choice made in the app is marked on the page as NAME.
page_marks() {
    local checked
    pva_relaunch && pva_on Welcome 30 && switch_to "$1" || return 1
    page_open || return 1
    checked=$(page_checked_soon)
    shot "page-$1"
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    [ "$checked" = "$2" ] || { pv_log "the page marks '$checked', not '$2'"; return 1; }
    pv_log "the page marks '$checked'"
}

page_uzbek_cyrillic() {
    local checked first
    pva_relaunch && pva_on Welcome 30 && switch_to UZ_CYRL || return 1
    page_open || return 1
    checked=$(page_checked_soon)
    first=$(pv_ui_nodes | awk -F'|' '$3 == "title" { if (all) { print $1; exit } } $1 == "All languages" { all = 1 }')
    shot "page-UZ_CYRL"
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    if [ -z "$checked" ]; then
        known "page: Ўзбекча chosen in the app is not marked on the phone's page" \
            "Android $(pv_getprop ro.build.version.release)'s page marks no Uzbek Cyrillic tag; it lists '$first' first under All languages"
        return 0
    fi
    [ "$checked" != "System default" ] || { pv_log "the page marks System default for Ўзбекча"; return 1; }
    pv_log "the page marks '$checked'"
}

# Choosing Русский on the phone's page: the page asks for a region (Russian has several; Uzbek
# Cyrillic, which has one, is set at once as uz-Cyrl-UZ), Россия, and the app's language is Russian.
page_chooses() {
    pva_relaunch && pva_on Welcome 30 && switch_to SYSTEM || return 1
    page_open || return 1
    pv_ui_tap '^Русский[|]' >/dev/null || { pv_log "no row Русский on the page"; return 1; }
    if pv_ui_wait '^Choose a region[|]|[|]Choose a region[|]' 10; then
        pv_ui_tap '^Россия[|]' >/dev/null || { pv_log "no region Россия; texts: $(pv_ui_nodes | awk -F'|' '$1 != "" { printf "%s; ", $1 }')"; return 1; }
    fi
    pv_sleep 3
    case "$(pvi_app_locales)" in "[ru"*) ;; *) pv_log "the app's locales are $(pvi_app_locales)"; return 1 ;; esac
    pv_log "the page set $(pvi_app_locales)"
    pva_in_front || pva_relaunch
    pva_on Welcome 30 && pvi_open_language && pvi_dialog_is RU && pvi_cancel Welcome
}

part_page() {
    if [ "$API" -lt 33 ]; then
        pv_report SKIPPED "page: the phone's own language page" "Android 13 (API 33) and later"
        return 0
    fi
    fresh || { pv_report FAIL "page: install and start"; return 1; }
    pv_step "page: it lists Oʻzbekcha, Ўзбекча, English and Русский" -- eval 'page_open && page_lists; rc=$?; pv_shell input keyevent KEYCODE_BACK >/dev/null; [ $rc -eq 0 ]'
    pv_step "page: Русский chosen in the app is marked" -- page_marks RU "Русский"
    pv_step "page: English chosen in the app is marked" -- page_marks EN "English"
    pv_step "page: Oʻzbekcha chosen in the app is marked (O‘zbek (lotin))" -- page_marks UZ_LATN "O‘zbek (lotin)"
    pv_step "page: System language chosen in the app is marked (System default)" -- page_marks SYSTEM "System default"
    pv_step "page: Ўзбекча chosen in the app" -- page_uzbek_cyrillic
    pv_step "page: Русский chosen on the phone's page changes the app" -- page_chooses
    pv_step "page: back to System language" -- eval 'pva_relaunch && pva_on Welcome 30 && switch_to SYSTEM'
}

# ---- mixing: two phone languages ------------------------------------------------------------------
# locale_config_file AAPT2 QUALIFIER: the APK's file of the generated locale config for QUALIFIER ("" or
# v35), as the resource table names it: res/xml-v35/_generated_res_locale_config.xml in the debug build,
# a short name such as res/vY.xml where R8's resource optimisation renamed the files (staging, release).
locale_config_file() {
    "$1" dump resources "$APK" 2>/dev/null | awk -v q="($2)" '
        / resource 0x[0-9a-f]+ xml\/_generated_res_locale_config$/ { on = 1; next }
        on && / resource 0x/ { exit }
        on && $1 == q && $2 == "(file)" { print $3; exit }'
}

no_default_locale() {
    local aapt2 v35 base v35_file base_file
    aapt2=$(ls -d "$PV_SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)
    v35_file=$(locale_config_file "$aapt2" v35)
    base_file=$(locale_config_file "$aapt2" "")
    pv_log "the locale config's files: ${base_file:-none} and, for API 35 and later, ${v35_file:-none}"
    [ -n "$v35_file" ] || { pv_log "no v35 locale config in the APK's resource table"; return 1; }
    v35=$("$aapt2" dump xmltree "$APK" --file "$v35_file" 2>&1) || { pv_log "no $v35_file: $v35"; return 1; }
    base=$("$aapt2" dump xmltree "$APK" --file "${base_file:-res/xml/_generated_res_locale_config.xml}" 2>&1)
    pv_log "the v35 locale config ($v35_file): $(printf '%s\n' "$v35" | grep -o 'name(0x01010003)="[^"]*"' | sed 's/.*="//; s/"$//' | tr '\n' ' ')"
    printf '%s\n' "$v35" | grep -q defaultLocale && { pv_log "the API 35 locale config sets android:defaultLocale"; return 1; }
    printf '%s\n' "$base" | grep -q defaultLocale && pv_log "res/xml (API 33 and 34, where the attribute does nothing) sets defaultLocale"
    return 0
}

# The bottom sheet's drag handle: material3's m3c_bottom_sheet_drag_handle_description, which has
# Russian and Latin Uzbek texts but none in Uzbek Cyrillic; the app has its own Ўзбекча text for it
# (values-b+uz+Cyrl/strings_overrides.xml, tools:override, i18n.md §1.4.6) since the merge of R6.
drag_handle_uzbek_cyrillic() {
    local english russian cyrillic handle
    english=$(pvi_apk_string "$APK" m3c_bottom_sheet_drag_handle_description)
    russian=$(pvi_apk_string "$APK" m3c_bottom_sheet_drag_handle_description ru)
    cyrillic=$(pvi_apk_string "$APK" m3c_bottom_sheet_drag_handle_description b+uz+Cyrl)
    [ -n "$cyrillic" ] || { pv_log "the APK has no Uzbek Cyrillic drag handle (values-b+uz+Cyrl/strings_overrides.xml)"; return 1; }
    pva_tap pv.tab.Passwords && pva_on Passwords || return 1
    if pva_present pv.list.emptyAdd 2; then pva_tap pv.list.emptyAdd; else pva_tap pv.list.add; fi || return 1
    pva_on Edit && pva_tap pv.edit.generateButton && pva_need pv.gen.preview 15 || return 1
    handle=$(pv_ui_nodes | awk -F'|' -v e="$english" -v r="$russian" -v c="$cyrillic" '$2 == e || $2 == r || $2 == c { print $2; exit }')
    shot "mixing-sheet"
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pv_sleep 1
    pva_hide_keyboard
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    if pva_present pv.edit.dialog.discard 5; then pva_tap pv.edit.dialog.discard; fi
    pva_on Passwords 20
    [ -n "$handle" ] || { pv_log "no drag handle '$cyrillic', '$english' or '$russian' on the sheet"; return 1; }
    pv_log "the drag handle says '$handle'"
    [ "$handle" != "$russian" ] || { pv_log "a Russian library text on an Uzbek Cyrillic screen"; return 1; }
    [ "$handle" = "$cyrillic" ] || { pv_log "the drag handle is not the app's Uzbek Cyrillic '$cyrillic'"; return 1; }
}

two_languages_dialog() {
    pva_tap pv.tab.Settings && pva_on Settings && pvi_open_language || return 1
    PVI_PHONE_SHOWN=UZ_CYRL pvi_dialog_is SYSTEM UZ_CYRL || return 1
    shot "mixing-dialog"
    pvi_cancel Settings
}

part_mixing() {
    pv_step "mixing: the API 35 locale config has no android:defaultLocale" -- no_default_locale
    fresh && pva_set_up || { pv_report FAIL "mixing: install, set up a vault"; return 1; }
    pv_step "mixing: the phone set to uz-Cyrl-UZ, then ru-RU" -- pvi_phone_set uz-Cyrl-UZ,ru-RU
    pva_in_front || pva_relaunch
    pv_step "mixing: the app on System language is in Ўзбекча (its dialog)" -- two_languages_dialog
    pv_step "mixing: a library text without Uzbek Cyrillic is the app's own Ўзбекча, not Russian" -- drag_handle_uzbek_cyrillic
    pv_step "mixing: the phone back to en-US" -- pvi_phone_set en-US
}

# ---- formats: the phone's ICU in each language ---------------------------------------------------------
# added_in TAG: on the account's screen in TAG, "Added …" ends with today's date as the phone's ICU
# writes it for TAG, each of its spaces a no-break space (Formatters.unbroken since w5-carry, 7b8d930:
# a date never breaks across two lines; architecture.md 3.5, Formatters).
added_in() {
    local tag=$1 answer expected found deadline icu
    answer=$(pva_debug locale "$tag") || return 1
    pv_log "$answer"
    icu=$(pvi_icu_date yMMMd "$tag") || return 1
    expected=${icu// /$(printf '\302\240')}
    deadline=$((SECONDS + 20))
    while :; do
        found=$(pv_ui_nodes | awk -F'|' -v d="$expected" 'index($1, d) && length($1) >= length(d) && substr($1, length($1) - length(d) + 1) == d { print $1; exit }')
        [ -z "$found" ] || break
        [ $SECONDS -lt $deadline ] || { pv_log "no text ending in '$expected' (ICU, $tag); texts: $(pv_ui_nodes | awk -F'|' '$1 != "" { printf "%s; ", $1 }')"; return 1; }
        pv_sleep 1
    done
    pva_on Entry 5 || return 1
    pv_log "$tag: '$found' (ICU writes '$icu'; the app puts no-break spaces in it)"
    shot "formats-$tag"
}

open_account() {
    pva_debug entry add Bankomat >/dev/null && pva_row_with Bankomat 15 || return 1
    pv_ui_tap '^Bankomat[|]' >/dev/null || return 1
    pva_on Entry 20
}

icu_version() {
    local v
    v=$(pvi_icu_version) || return 1
    pv_log "the phone's ICU: $v"
    if [ "$API" -eq 24 ]; then case "$v" in 56.*) ;; *) pv_log "API 24 should have ICU 56"; return 1 ;; esac; fi
}

part_formats() {
    fresh && pva_set_up || { pv_report FAIL "formats: install, set up a vault"; return 1; }
    pv_step "formats: the phone's ICU version" -- icu_version
    pv_step "formats: an account's screen" -- open_account
    local tag
    for tag in uz-Latn uz-Cyrl ru en; do
        pv_step "formats: the date on the account's screen in $tag is the phone's ICU's" -- added_in "$tag"
    done
    pva_debug locale system >/dev/null
}

# ---- prompt: the fingerprint prompt's texts --------------------------------------------------------------
prompt_set_up() {
    pvi_open_language && pvi_choose EN_XA Welcome || return 1
    pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword && pva_reveal pv.setup.passphrase || return 1
    PVA_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    pva_tap pv.open.WriteDown && pva_on WriteDown && pva_tap pv.open.TypeAgain && pva_on TypeAgain || return 1
    pva_type pv.setup.again "$PVA_PASSWORD" && pva_hide_keyboard && pva_tap pv.action.createVault || return 1
    pva_on CreatePin 120 && pin_digits "$PIN" && pva_on ConfirmPin 20 && pin_digits "$PIN" && pva_on FingerprintOffer 30
}

# pin_digits DIGITS: the digits on the pad shown, taking only their own keys (pv.pin.key.N); app.sh's
# pva_pin wants all ten keys on the screen, and in en-XA the 0 is below it (see PIN).
pin_digits() {
    local nodes line i d
    pva_need "pv.pin.key.${1:0:1}" 20 || return 1
    nodes=$(pv_ui_nodes)
    for ((i = 0; i < ${#1}; i++)); do
        d=${1:$i:1}
        line=$(printf '%s\n' "$nodes" | pv_ui_match "[|]pv\.pin\.key\.$d\$")
        [ -n "$line" ] || { pv_log "no key $d; ids: $(pva_ids)"; return 1; }
        pv_shell input tap "$(printf '%s\n' "$line" | cut -d'|' -f6)" "$(printf '%s\n' "$line" | cut -d'|' -f7)" >/dev/null
        pv_sleep 0.4
    done
}

prompt_texts() {
    local title button english_title english_button nodes deadline
    title=$(pvi_apk_string "$APK" lock_prompt_turn_on_title en-rXA)
    button=$(pvi_apk_string "$APK" common_cancel en-rXA)
    english_title=$(pvi_apk_string "$APK" lock_prompt_turn_on_title)
    english_button=$(pvi_apk_string "$APK" common_cancel)
    [ -n "$title" ] && [ -n "$button" ] || { pv_log "the APK has no en-XA texts for the prompt"; return 1; }
    pva_tap pv.fingerprintOffer.use || return 1
    deadline=$((SECONDS + 20))
    while :; do
        nodes=$(pv_ui_nodes_all)
        printf '%s\n' "$nodes" | grep -qF "$title" && break
        [ $SECONDS -lt $deadline ] || { pv_log "no prompt titled '$title'; texts: $(printf '%s\n' "$nodes" | awk -F'|' '$1 != "" { printf "%s; ", $1 }')"; return 1; }
        pv_sleep 1
    done
    shot "prompt-en-XA"
    pv_log "the prompt: $(printf '%s\n' "$nodes" | awk -F'|' '$1 != "" { printf "%s; ", $1 }')"
    # Android 7's dialogs draw their buttons in capitals: the texts are compared ignoring case.
    printf '%s\n' "$nodes" | has_text "$button" || { pv_log "the prompt has no button '$button'"; return 1; }
    ! printf '%s\n' "$nodes" | has_text "$english_title" && ! printf '%s\n' "$nodes" | has_text "$english_button" \
        || { pv_log "the prompt shows English texts of the app"; return 1; }
}

# has_text TEXT: a node of the dump on stdin has the text TEXT, ignoring case.
has_text() {
    python3 -c 'import sys
want = sys.argv[1].upper()
sys.exit(0 if any(line.split("|")[0].upper() == want for line in sys.stdin) else 1)' "$1"
}

prompt_finger() {
    pv_sleep 2
    pv_adb emu finger touch 1 >/dev/null 2>&1
    pv_sleep 1
    pv_adb emu finger remove 1 >/dev/null 2>&1
    pva_on BackupsExplained 30 && pva_tap pv.action.finish && pva_on Passwords 30
}

part_prompt() {
    fresh || { pv_report FAIL "prompt: install and start"; return 1; }
    pv_step "prompt: English (XA) chosen on Welcome; setup and the PIN steps up to the fingerprint offer" -- prompt_set_up || return 1
    pv_step "prompt: 'Turn on' opens the prompt with the app's en-XA title and button" -- prompt_texts
    pv_step "prompt: finger 1 turns fingerprint unlock on" -- prompt_finger
    pva_debug locale system >/dev/null 2>&1
}

# ---- the run ---------------------------------------------------------------------------------------------
for part in "${PARTS[@]}"; do
    echo "== $part"
    "part_$part" > >(tee -a "$LOGS/matrix.log") 2> >(tee -a "$LOGS/matrix.log" >&2)
done
restore
pv_step "the app did not crash" -- pva_no_crash
[ "$KNOWN" -eq 0 ] || echo "($KNOWN KNOWN: platform behaviour, not failures)"
pv_summary
