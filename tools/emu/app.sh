# tools/emu/app.sh - helpers that drive PasswordVault's own screens on a private emulator, for
# smoke.sh, the integration checks under checks/ and any other script of tools/emu/. Source it after
# adb.sh, then name the device:
#
#   source tools/emu/adb.sh; source tools/emu/app.sh
#   pv_use "$SERIAL"; pva_init
#
# Screens are found by the resource ids the app gives them (architecture.md §3.6), never by their
# texts, so nothing depends on the language: pv.route.<Route> on every screen, pv.open.<Route> and
# pv.action.* on the shell's buttons, and the tags of the screens of build plan 3.3 to 3.6
# (SetupTags pv.setup.*, LockTags pv.lock.*, ListTags pv.list.*, EditTags pv.edit.*, GenTags pv.gen.*).
# Every function returns 0 on success and logs (pv_log) why it failed. The functions are named pva_*
# so that a script can keep its own short helpers.
#
# Made by the integrator of sub-wave 3A for the skeleton check (build plan 3A, checks/3A.sh) and the
# skeleton stage of smoke.sh.

# The master password the last pva_set_up chose (the suggested words), and pva_unlock's default.
PVA_PASSWORD=${PVA_PASSWORD:-}
PVA_SCREEN_H=1920
PVA_SCREEN_W=1080

# pva_init: reads the screen's size (portrait). Call it once after pv_use.
pva_init() {
    local size
    size=$(pv_shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Physical size: *\([0-9]*x[0-9]*\)$/\1/p' | head -n 1)
    if [ -n "$size" ]; then
        PVA_SCREEN_W=${size%x*}
        PVA_SCREEN_H=${size#*x}
    fi
}

pva_re() { printf '%s' "$1" | sed 's/[.]/\\./g'; }

# The pv.* ids on the screen now, for messages.
pva_ids() { pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]/ { printf "%s ", $3 }'; }

# pva_node ID [NODES]: the enabled node whose resource id is ID.
pva_node() {
    local nodes=${2:-}
    [ -n "$nodes" ] || nodes=$(pv_ui_nodes)
    printf '%s\n' "$nodes" | pv_ui_match "[|]$(pva_re "$1")\$"
}

# pva_present ID [SECONDS]: a node with id ID appears within SECONDS (default 10).
pva_present() { pv_ui_wait "[|]$(pva_re "$1")\$" "${2:-10}"; }

# pva_need ID [SECONDS]: like pva_present, and says what is shown when ID is not.
pva_need() { pva_present "$@" || { pv_log "no $1; ids: $(pva_ids)"; return 1; }; }

pva_absent() { [ -z "$(pva_node "$1")" ]; }

# pva_on ROUTE [SECONDS]: the screen of ROUTE is shown within SECONDS (default 20).
pva_on() {
    pv_ui_wait "[|]pv\.route\.$1\$" "${2:-20}" || { pv_log "not on $1; ids: $(pva_ids)"; return 1; }
}

# pva_text ID: the text of the node with id ID.
pva_text() {
    local line
    line=$(pva_node "$1")
    [ -n "$line" ] || { pv_log "no node $1; ids: $(pva_ids)"; return 1; }
    printf '%s\n' "$line" | cut -d'|' -f1
}

pva_scroll_down() { # the content moves up, so that what is below comes into view
    pv_shell input swipe $((PVA_SCREEN_W / 2)) $(($(pva_height) * 55 / 100)) $((PVA_SCREEN_W / 2)) $(($(pva_height) * 25 / 100)) 400 >/dev/null
    pv_sleep 1
}

pva_scroll_up() {
    pv_shell input swipe $((PVA_SCREEN_W / 2)) $(($(pva_height) * 25 / 100)) $((PVA_SCREEN_W / 2)) $(($(pva_height) * 55 / 100)) 400 >/dev/null
    pv_sleep 1
}

# The height of the screen as the app is shown now (landscape swaps width and height).
pva_height() {
    case "$(pv_shell settings get system user_rotation 2>/dev/null | tr -d '\r')" in
        1 | 3) echo "$PVA_SCREEN_W" ;;
        *) echo "$PVA_SCREEN_H" ;;
    esac
}

# pva_reveal ID: ID is on the screen, after scrolling down for it (up to five swipes): on a short
# screen a node can be below the fold, and only nodes that are shown are reported.
pva_reveal() {
    local i
    for i in 1 2 3 4 5 6; do
        pva_present "$1" 2 && return 0
        [ "$i" -lt 6 ] || break
        pva_hide_keyboard
        pva_scroll_down
    done
    pv_log "no $1 after scrolling; ids: $(pva_ids)"
    return 1
}

# pva_tap ID: taps the node whose id is ID, after scrolling it into view and above the bottom band
# where the system's navigation bar is (at most 160 px: 48 dp at 420 dpi is 126), up to five swipes.
# A screen that is not laid out around the keyboard (the list: its Add button stays where it is) has
# nodes behind the keyboard that are still reported as visible, and a tap there would type a letter:
# so with the keyboard open, it is closed before a node in the lower two thirds is tapped, and before
# every swipe.
pva_tap() {
    local line y i bottom height
    height=$(pva_height)
    bottom=$((height - 160))
    for i in 1 2 3 4 5 6 7; do
        line=$(pva_node "$1")
        if [ -n "$line" ]; then
            y=$(printf '%s\n' "$line" | cut -d'|' -f7)
            if [ "$y" -gt 0 ] && [ "$y" -lt "$bottom" ]; then
                if [ "$y" -gt $((height * 30 / 100)) ] && pva_keyboard_shown; then
                    pva_hide_keyboard || return 1
                    continue
                fi
                pv_shell input tap "$(printf '%s\n' "$line" | cut -d'|' -f6)" "$y" >/dev/null
                return
            fi
        fi
        [ "$i" -lt 7 ] || break
        pva_hide_keyboard
        pva_scroll_down
    done
    pv_log "cannot tap $1 (${line:+at y $y of $height}${line:-not on the screen}); ids: $(pva_ids)"
    return 1
}

# pva_type ID TEXT: taps the field ID and types TEXT at its end. TEXT may hold letters, digits and
# . _ - @ : / (adb's `input text`); a space is typed as %s. Nothing is dumped between the tap and
# the typing, so the focus stays in the field.
pva_type() {
    pva_tap "$1" || return 1
    pv_sleep 1
    pv_shell input keyevent KEYCODE_MOVE_END >/dev/null
    pv_shell input text "$(printf '%s' "$2" | sed 's/ /%s/g')" >/dev/null
    pv_sleep 1
}

pva_keyboard_shown() { pv_shell dumpsys input_method 2>/dev/null | tr -d '\r' | grep -q 'mInputShown=true'; }

# pva_wait_keyboard [SECONDS]: the keyboard is shown within SECONDS (default 10); dumpsys only, so
# the focus is not moved (pv_ui_nodes may move it with Tab).
pva_wait_keyboard() {
    local deadline=$((SECONDS + ${1:-10}))
    until pva_keyboard_shown; do
        [ $SECONDS -lt $deadline ] || { pv_log "no keyboard within ${1:-10} s"; return 1; }
        pv_sleep 1
    done
}

# pva_hide_keyboard: closes the keyboard with Back, which the keyboard takes.
pva_hide_keyboard() {
    pva_keyboard_shown || return 0
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    local i
    for i in 1 2 3 4 5; do
        pv_sleep 1
        pva_keyboard_shown || return 0
    done
    pv_log "the keyboard stays open"
    return 1
}

# pva_submit BUTTON-ID THEN-ID [SECONDS]: the keyboard closed (Back, which the keyboard takes) and the
# button tapped, as a finger would; then THEN-ID appears within SECONDS (default 30). Not the Enter
# key: a key event ends touch mode, and the first field of the next screen (the list's search field)
# would then take the focus and keep the keyboard open over the screen.
pva_submit() {
    pva_hide_keyboard
    pva_tap "$1" || return 1
    pva_need "$2" "${3:-30}"
}

# pva_rotate 0|1: portrait or landscape (the user's rotation, with auto-rotate off); waits 3 s.
pva_rotate() {
    pv_shell settings put system accelerometer_rotation 0 >/dev/null
    pv_shell settings put system user_rotation "$1" >/dev/null
    pv_sleep 3
}

pva_in_front() { case "$(pv_focus)" in *"$PV_PACKAGE"/*) return 0 ;; *) return 1 ;; esac; }

pva_pid() {
    local p
    p=$(pv_shell pidof "$PV_PACKAGE" 2>/dev/null | tr -d '\r')
    [ -n "$p" ] || p=$( { pv_shell ps -A 2>/dev/null || pv_shell ps; } | tr -d '\r' | awk -v p="$PV_PACKAGE" '$NF == p { print $2; exit }')
    echo "$p"
}

# pva_relaunch: the launcher's intent; brings the task back, or starts the app.
pva_relaunch() {
    local out
    out=$(pv_shell am start -W -n "$PV_PACKAGE/.MainActivity" 2>&1 | tr -d '\r')
    echo "$out" | grep -q '^Status: ok' || { pv_log "am start: $out"; return 1; }
}

# pva_install APK: removes an installed copy and installs APK (without streaming: pv_adb). Each try
# gets 90 s, and up to three tries are made, should an install still not answer.
pva_install() {
    [ -f "$1" ] || { pv_log "no APK at $1"; return 1; }
    local out i
    pv_adb uninstall "$PV_PACKAGE" >/dev/null 2>&1
    for i in 1 2 3; do
        out=$(PV_ADB_TIMEOUT=90 pv_adb install -r "$1" 2>&1)
        echo "$out" | grep -q '^Success' && return 0
        pv_log "adb install, try $i: ${out:-no answer within 90 s}"
        pv_sleep 5
    done
    return 1
}

# pva_start: the keyguard dismissed, portrait, the logs cleared, the app started.
pva_start() {
    pv_unlock >/dev/null 2>&1
    pva_rotate 0
    pv_shell logcat -b crash -c >/dev/null 2>&1
    pv_shell logcat -c >/dev/null 2>&1
    pva_relaunch
}

# pva_screen_off [SECONDS]: the screen off for SECONDS (default 3; dumpsys power proves it), on
# again, the keyguard dismissed, and the app in front again.
pva_screen_off() {
    local power
    pv_shell input keyevent KEYCODE_SLEEP >/dev/null || return 1
    pv_sleep "${1:-3}"
    power=$(pv_shell dumpsys power 2>/dev/null | tr -d '\r' | grep -m 1 -o 'mWakefulness=[A-Za-z]*')
    pv_shell input keyevent KEYCODE_WAKEUP >/dev/null
    case "$power" in mWakefulness=Asleep | mWakefulness=Dozing) ;; *) pv_log "the screen did not go off: $power"; return 1 ;; esac
    pv_sleep 1
    pv_unlock || return 1
    pv_sleep 1
    pva_in_front || pva_relaunch
}

# pva_away SECONDS: Home; SECONDS from the moment the app is no longer in front; then the launcher's
# intent brings the task back.
pva_away() {
    local i
    pv_shell input keyevent KEYCODE_HOME >/dev/null || return 1
    for i in 1 2 3 4 5 6 7 8 9 10; do pva_in_front || break; pv_sleep 0.5; done
    pva_in_front && { pv_log "the app is still in front after Home: $(pv_focus)"; return 1; }
    pv_sleep "$1"
    pva_relaunch
}

# pva_kill_in_background: Home, then the app's process ends: `am kill`, or kill -9 through run-as
# (debug builds) or su (userdebug images, API 24 and 28) where am kill is refused (API 24). True
# when the process is gone; 2 when nothing here could end it.
pva_kill_in_background() {
    local pid
    pv_shell input keyevent KEYCODE_HOME >/dev/null
    pv_sleep 2
    pv_shell am kill "$PV_PACKAGE" >/dev/null 2>&1
    pv_sleep 2
    pid=$(pva_pid)
    if [ -n "$pid" ] && pv_is_debuggable; then
        pv_shell run-as "$PV_PACKAGE" kill -9 "$pid" >/dev/null 2>&1
        pv_sleep 2
        pid=$(pva_pid)
    fi
    if [ -n "$pid" ] && pv_has_root; then
        pv_shell su 0 kill -9 "$pid" >/dev/null 2>&1
        pv_sleep 2
        pid=$(pva_pid)
    fi
    [ -z "$pid" ] && return 0
    pv_log "the app's process $pid is still there"
    return 2
}

# pva_set_up: setup with the suggested words (ux.md §5.3 to §5.5; lock.md F1 steps 1 to 3), from
# Welcome to the list: Start, the six words read from the screen into PVA_PASSWORD, "Use these words",
# "Write it down on paper", Next, the words typed again, Next. The vault is made then.
pva_set_up() {
    pva_on Welcome 30 || return 1
    pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword || return 1
    pva_reveal pv.setup.passphrase || return 1
    PVA_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    printf '%s\n' "$PVA_PASSWORD" | grep -Eq '^[a-z]{3,9}(-[a-z]{3,9}){5}$' \
        || { pv_log "not six words: '$PVA_PASSWORD'"; return 1; }
    pva_tap pv.open.WriteDown && pva_on WriteDown || return 1
    pva_tap pv.open.TypeAgain && pva_on TypeAgain || return 1
    pva_type pv.setup.again "$PVA_PASSWORD" || return 1
    pva_submit pv.action.createVault pv.route.Passwords 120
}

# pva_unlock [ROUTE] [PASSWORD]: the master password on the lock screen; then ROUTE (default
# Passwords) is shown.
pva_unlock() {
    local route=${1:-Passwords} password=${2:-$PVA_PASSWORD}
    pva_on Locked 30 && pva_need pv.lock.password 10 || return 1
    pva_type pv.lock.password "$password" || return 1
    pva_submit pv.lock.unlock "pv.route.$route" 90
}

# pva_add_account NAME LOGIN PASSWORD WEBSITE NOTE: from the list, Add, the five fields, Save; the
# account's own screen (Entry) is shown then.
pva_add_account() {
    pva_on Passwords || return 1
    if pva_present pv.list.emptyAdd 2; then pva_tap pv.list.emptyAdd; else pva_tap pv.list.add; fi || return 1
    pva_on Edit || return 1
    pva_type pv.edit.name "$1" && pva_type pv.edit.username "$2" && pva_type pv.edit.password "$3" \
        && pva_type pv.edit.website.0 "$4" && pva_type pv.edit.notes "$5" || return 1
    pva_hide_keyboard
    pva_tap pv.edit.save || return 1
    pva_on Entry 30
}

# pva_row_titles: the titles of the list's rows as shown, top to bottom, one per line: the first text
# under each pv.list.row.<id> node (its title; the login follows it).
pva_row_titles() {
    pv_ui_nodes | awk -F'|' '
        $3 ~ /^pv[.]list[.]row[.]/ { y = $7; pending = 1; next }
        pending && $3 ~ /^pv[.]/ { pending = 0 }
        pending && $1 != "" { print y "|" $1; pending = 0 }
    ' | sort -t'|' -k1,1n | cut -d'|' -f2-
}

# pva_row_with TEXT [SECONDS]: a row of the list shows TEXT.
pva_row_with() {
    local deadline=$((SECONDS + ${2:-10}))
    while :; do
        pva_row_titles | grep -qF -- "$1" && return 0
        [ $SECONDS -lt $deadline ] || { pv_log "no row with '$1'; rows: $(pva_row_titles | tr '\n' ';')"; return 1; }
        pv_sleep 1
    done
}

# pva_debug COMMAND...: a debug command (DebugCommandReceiver, debug and staging builds); prints its
# answer, the data of `am broadcast`; fails unless the result code is 1 (done).
pva_debug() {
    local out
    out=$(pv_shell am broadcast -a "$PV_PACKAGE.debug.CMD" -p "$PV_PACKAGE" --es cmd "'$*'" 2>&1 | tr -d '\r' | grep 'result=')
    printf '%s\n' "$out" | sed -n 's/.*data="\(.*\)"$/\1/p'
    case "$out" in *'result=1,'*) return 0 ;; *) pv_log "debug command '$*': ${out:-no answer}"; return 1 ;; esac
}

# pva_screenshot FILE: the screen as PNG (debug builds; the secure window of staging gives black).
pva_screenshot() {
    mkdir -p "$(dirname "$1")"
    pv_adb exec-out screencap -p > "$1" 2>/dev/null && [ -s "$1" ] || { pv_log "no screenshot in $1"; return 1; }
}

# pva_no_crash: no FATAL EXCEPTION of the app in the crash buffer or AndroidRuntime's log.
pva_no_crash() {
    local crash
    crash=$( { pv_shell logcat -b crash -d 2>/dev/null; pv_shell logcat -d -s AndroidRuntime:E 2>/dev/null; } \
        | tr -d '\r' | grep -A 3 'FATAL EXCEPTION' | grep -m 1 -B 1 -A 2 "$PV_PACKAGE")
    [ -z "$crash" ] || { pv_log "the app crashed: $crash"; return 1; }
}
