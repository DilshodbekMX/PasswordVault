# tools/emu/i18n/lib.sh - the phone's languages and the app's language switch on a private emulator,
# for the scripts of tools/emu/i18n/ (build plan 6.1). Source it after adb.sh and app.sh, then name
# the device:
#
#   source tools/emu/adb.sh; source tools/emu/app.sh; source tools/emu/i18n/lib.sh
#   pv_use "$SERIAL"; pva_init
#
# The phone's side runs PvLocale.java (this directory) as the shell user: see that file for what
# each command does. The jar is built once per worktree into build/emu/pv-locale.jar (the JDK's
# javac, the SDK's d8) and pushed to /data/local/tmp/ when needed, as clipboard/lib.sh does.
#
#   pvi_phone_get             the phone's languages now ("uz-Latn-UZ,en-US")
#   pvi_phone_set TAGS        makes TAGS the phone's languages, as Settings › Languages does
#   pvi_icu_date SKEL TAG     today's date as the phone's ICU writes it for TAG with SKEL
#   pvi_icu_version           the phone's ICU version ("56.1.0.0")
#   pvi_app_locales           the app's own languages as the system keeps them (Android 13 and
#                             later: cmd locale get-app-locales; "[]" for the system language)
#   pvi_open_language         opens the language dialog from the screen shown (Welcome's globe or
#                             Settings' language row) and waits for it
#   pvi_choose CHOICE         taps the dialog's row CHOICE (SYSTEM, UZ_LATN, UZ_CYRL, EN, RU, EN_XA,
#                             AR_XB) and waits until the dialog has closed
#   pvi_dialog_texts          the dialog's title and its System row, one per line ("Til", "Tizim tili",
#                             "Oʻzbekcha": the title, the row's name, the language under it)
#   pvi_selected              the CHOICE whose row is checked
# Every function returns 0 on success.

PVI_SRC=$PV_EMU_DIR/i18n/PvLocale.java
PVI_JAR=${PVI_JAR:-$PV_EMU_DIR/../../build/emu/pv-locale.jar}
PVI_ON_DEVICE=/data/local/tmp/pv-locale.jar

# Builds the jar when it is missing or older than its source; into a directory of its own first, so
# that scripts running at once do not collide.
pvi_build() {
    [ -f "$PVI_JAR" ] && [ ! "$PVI_SRC" -nt "$PVI_JAR" ] && return 0
    local java_home=${PV_JAVA_HOME:-/home/asus/.jdks/jdk-25} d8 android_jar tmp
    d8=$(ls -d "$PV_SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -n 1)
    android_jar=$(ls -d "$PV_SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1)
    [ -x "$java_home/bin/javac" ] && [ -n "$d8" ] && [ -n "$android_jar" ] || { pv_log "no javac, d8 or android.jar to build ${PVI_JAR##*/}"; return 1; }
    mkdir -p "$(dirname "$PVI_JAR")" || return 1
    tmp=$(mktemp -d "$(dirname "$PVI_JAR")/locale.XXXXXX") || return 1
    if "$java_home/bin/javac" -nowarn -Xlint:-options -source 8 -target 8 -cp "$android_jar" -d "$tmp" "$PVI_SRC" > "$tmp/javac.log" 2>&1 \
        && JAVA_HOME=$java_home PATH=$java_home/bin:$PATH "$d8" --min-api 24 --output "$tmp/pv-locale.jar" "$tmp"/PvLocale*.class > "$tmp/d8.log" 2>&1; then
        mv -f "$tmp/pv-locale.jar" "$PVI_JAR"
        rm -rf "$tmp"
        return 0
    fi
    pv_log "could not build ${PVI_JAR##*/}: $(cat "$tmp/javac.log" "$tmp/d8.log" 2>/dev/null | grep -v '^Note:' | tail -n 3)"
    rm -rf "$tmp"
    return 1
}

# One run of the tool on the device: its lines without the last, and 0 when the last was "PVLOCALE OK".
pvi_once() { # COMMAND [ARG...]
    local out cmd="CLASSPATH=$PVI_ON_DEVICE app_process /system/bin PvLocale" a
    for a in "$@"; do cmd="$cmd '$a'"; done
    out=$(pv_adb shell "$cmd" 2>/dev/null | tr -d '\r')
    PVI_LAST=$(printf '%s\n' "$out" | tail -n 1)
    [ "$PVI_LAST" = "PVLOCALE OK" ] || return 1
    printf '%s\n' "$out" | sed '$d'
}

# The tool, built when its source changed and pushed when the device has not got this build of it (a
# marker per serial under $PV_RUN/locale/; a device restarted since has no jar, and the failed run
# pushes it again).
pvi_run() { # COMMAND [ARG...]
    local marker=$PV_RUN/locale/$PV_SERIAL a
    for a in "$@"; do
        case "$a" in *[!A-Za-z0-9._,\\\ -]*) pv_log "pvl: '$a' holds more than letters, digits, spaces, \\ and . _ , -"; return 2 ;; esac
    done
    if [ -f "$PVI_JAR" ] && [ ! "$PVI_SRC" -nt "$PVI_JAR" ] && [ -f "$marker" ] && [ ! "$PVI_JAR" -nt "$marker" ]; then
        pvi_once "$@" && return 0
        case "$PVI_LAST" in "PVLOCALE ERROR"*) pv_log "pvl $1: $PVI_LAST"; return 1 ;; esac
    fi
    pvi_build || return 1
    pv_adb push "$PVI_JAR" "$PVI_ON_DEVICE" > /dev/null 2>&1 || { pv_log "could not push ${PVI_JAR##*/} to $PV_SERIAL"; return 1; }
    mkdir -p "$PV_RUN/locale" && touch "$marker"
    pvi_once "$@" || { pv_log "pvl $1: ${PVI_LAST:-no answer}"; return 1; }
}

pvi_phone_get() { pvi_run get; }
pvi_phone_set() { pvi_run set "$1" > /dev/null; }
pvi_icu_date() { pvi_run date "$1" "$2"; }
pvi_icu_version() { pvi_run icu; }

# The app's own languages as the system keeps them, Android 13 and later: "[uz-Cyrl]", "[]".
pvi_app_locales() {
    pv_shell cmd locale get-app-locales "$PV_PACKAGE" 2>/dev/null | tr -d '\r' | sed -n 's/^Locales for .* are \(\[.*\]\)$/\1/p' | head -n 1
}

# ---- the app's language dialog (LanguageTags: pv.language.dialog, pv.language.<CHOICE>, pv.language.cancel)

# The title and the System row of each language (strings_language.xml), and the language's own name
# (donottranslate.xml), by choice; the pseudo-locales' titles come from the APK (pvi_apk_string).
pvi_title() {
    case "$1" in
        UZ_LATN) echo "Til" ;;
        UZ_CYRL) echo "Тил" ;;
        RU) echo "Язык" ;;
        *) echo "Language" ;;
    esac
}
pvi_system_name() {
    case "$1" in
        UZ_LATN) echo "Tizim tili" ;;
        UZ_CYRL) echo "Тизим тили" ;;
        RU) echo "Язык системы" ;;
        *) echo "System language" ;;
    esac
}
pvi_own_name() {
    case "$1" in
        UZ_LATN) echo "Oʻzbekcha" ;;
        UZ_CYRL) echo "Ўзбекча" ;;
        RU) echo "Русский" ;;
        *) echo "English" ;;
    esac
}

# pvi_apk_string APK NAME [QUALIFIER]: the text of string NAME in APK for QUALIFIER (aapt2's spelling:
# en-rXA, b+uz+Cyrl, ru; default the default), read with the SDK's aapt2 on this computer.
pvi_apk_string() {
    local aapt2 q=${3:-}
    aapt2=$(ls -d "$PV_SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)
    [ -n "$aapt2" ] || { pv_log "no aapt2 under $PV_SDK/build-tools"; return 1; }
    "$aapt2" dump resources "$1" 2>/dev/null | awk -v n="string/$2" -v q="($q)" '
        $1 == "resource" && $3 == n { f = 1; next }
        f && $1 == "resource" { exit }
        f && $1 == q { sub(/^[^"]*"/, ""); sub(/"$/, ""); print; exit }
    '
}

# pvi_open_language: the language dialog from the screen shown (Welcome's globe button or the
# language row of Settings, both pv.open.Language), waited for in its own window.
pvi_open_language() {
    pva_tap pv.open.Language || return 1
    pva_need pv.language.dialog 15
}

# pvi_settled ROUTE [SECONDS]: ROUTE is shown with no dialog over it, the app's window has the focus,
# and that stays so for 2 s: a language change recreates the activity a moment after the dialog
# closes (default deadline 30 s).
pvi_settled() {
    local deadline=$((SECONDS + ${2:-30})) nodes
    while :; do
        nodes=$(pv_ui_nodes)
        if pva_in_nodes "[|]pv\\.route\\.$1\$" "$nodes" && ! pva_in_nodes '[|]pv\.language\.dialog$' "$nodes" && pva_in_front; then
            pv_sleep 2
            nodes=$(pv_ui_nodes)
            pva_in_nodes "[|]pv\\.route\\.$1\$" "$nodes" && ! pva_in_nodes '[|]pv\.language\.dialog$' "$nodes" && return 0
        fi
        [ $SECONDS -lt $deadline ] || { pv_log "not settled on $1; ids: $(pva_ids)"; return 1; }
        pv_sleep 1
    done
}

# pvi_choose CHOICE ROUTE: in the open dialog, taps CHOICE's row; the dialog closes and ROUTE (the
# screen it was opened from) is shown again, settled after the recreation.
pvi_choose() {
    pva_tap "pv.language.$1" || return 1
    pvi_settled "$2"
}

# pvi_cancel ROUTE: "Cancel"; the dialog closes and ROUTE is shown.
pvi_cancel() {
    pva_tap pv.language.cancel || return 1
    pvi_settled "$1" 15
}

# pvi_dialog_texts: the open dialog's title, its System row's name and the language under it, one
# per line (from one dump).
pvi_dialog_texts() {
    pv_ui_nodes | awk -F'|' '
        $3 == "pv.language.dialog" { d = 1; next }
        d && !t && $1 != "" { print $1; t = 1; next }
        $3 == "pv.language.SYSTEM" { s = 1; n = 0; next }
        s && $3 ~ /^pv[.]/ { s = 0 }
        s && $1 != "" { print $1; n++; if (n == 2) s = 0 }
    '
}

# pvi_selected: the CHOICE whose row is checked. Compose drops the click action of a selected radio
# button from its accessibility node, so the one row that is not clickable is the checked one.
pvi_selected() {
    pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]language[.][A-Z_]+$/ && $4 == "false" { sub(/^pv[.]language[.]/, "", $3); print $3 }'
}

# pvi_dialog_is CHOICE [SHOWN]: the open dialog has CHOICE checked and is written in SHOWN (default
# CHOICE; for SYSTEM the language the phone's languages give, EN by default): its title, its System
# row's name, and under it the own name of the language the app shows with the phone's languages
# (PVI_PHONE_SHOWN, default EN).
pvi_dialog_is() {
    local choice=$1 shown=${2:-$1} texts want selected
    [ "$shown" = SYSTEM ] && shown=${PVI_PHONE_SHOWN:-EN}
    texts=$(pvi_dialog_texts)
    want="$(pvi_title "$shown")
$(pvi_system_name "$shown")
$(pvi_own_name "${PVI_PHONE_SHOWN:-EN}")"
    [ "$texts" = "$want" ] || { pv_log "the dialog says '$(printf '%s' "$texts" | tr '\n' ';')', not '$(printf '%s' "$want" | tr '\n' ';')'"; return 1; }
    selected=$(pvi_selected)
    [ "$selected" = "$choice" ] || { pv_log "the checked row is '$selected', not $choice"; return 1; }
}

# pvi_record_file: AppCompat's record of the app's language on Android 7 to 12 (i18n.md §1.4.3),
# read with run-as (debug builds); nothing when it does not exist.
PVI_RECORD=files/androidx.appcompat.app.AppCompatDelegate.application_locales_record_file
pvi_record_file() { pv_shell run-as "$PV_PACKAGE" cat "$PVI_RECORD" 2>/dev/null | tr -d '\r'; }

# pvi_quiet_network: on Android 11 and later, airplane mode on. The API 37 base is a Google Play
# image with network: left on, the Play Store updates Play services, Gboard, Chrome and more within
# the first twenty minutes and fills the 6 GB data partition to 96 % (seen on 2026-10-09: installs
# then fail with "Requested internal only, but not enough space"). The app needs no network.
pvi_quiet_network() {
    [ "$(pv_api)" -ge 30 ] || return 0
    pv_shell cmd connectivity airplane-mode enable >/dev/null 2>&1
    [ "$(pv_shell settings get global airplane_mode_on 2>/dev/null | tr -d '\r')" = 1 ] \
        || { pv_log "airplane mode did not turn on"; return 1; }
    pv_log "airplane mode on (free on /data: $(pv_shell df -h /data/user/0 2>/dev/null | tr -d '\r' | awk 'NR == 2 { print $4 }'))"
}
