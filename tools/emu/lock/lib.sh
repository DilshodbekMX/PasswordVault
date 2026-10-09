# tools/emu/lock/lib.sh - shared by the lock's emulator checks E1 to E17 (lock.md §15.3; build plan
# 4.5). A check sources it and calls pvl_begin:
#
#   source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
#   pvl_begin E1 "$@"          # SERIAL [--apk FILE]: the device and the APK
#   pv_step "..." -- ...       # adb.sh's steps: PASS, FAIL or SKIPPED per step
#   pvl_end                    # the app did not crash, the summary, the exit status
#
# After it the script has adb.sh's pv_* functions, app.sh's pva_* functions, and:
#   SERIAL, APK, API, VARIANT (debug or staging, read from the APK: debuggable or not), PVL_OUT (a
#   directory for what the script saw, under this worktree's build/emu-lock/SERIAL/NAME, never in git)
#   pvl_fresh, pvl_set_up [PIN|-] [fingerprint|-], pvl_pin_steps   the app installed afresh, a vault
#                                                      set up, 4.3's PIN steps walked
#   pvl_lock_now, pvl_pad, pvl_to_pad, pvl_unlock_pin, pvl_to_master_password, pvl_unlock_mp,
#   pvl_wrong_pins, pvl_wait_answer, pvl_open_list    the lock screens
#   pvl_status, pvl_item, pvl_vault_item, pvl_pin KEY, pvl_fp KEY, pvl_state KEY, pvl_policy KEY,
#   pvl_lock_state, pvl_alias_count, pvl_has_canary, pvl_expect_pin, pvl_expect_fp   the lock's state
#   pvl_screen, pvl_wait_screen, pvl_status_line, pvl_nowhere, pvl_notice_lines, pvl_notice_has,
#   pvl_answer                                         what the screen shows
#   pvl_finger N, pvl_prompt_up, pvl_prompt_down, pvl_prompt_shown, pvl_prompt_negative,
#   pvl_touch_prompt, pvl_enrolled_fingers             fingerprint
#   pvl_kill9, pvl_force_stop, pvl_restart             the process
#   pvl_res, pvl_expect, pvl_text_is, pvl_text_shown   the app's own texts (English, as the emulators are set)
#   pvl_record, pvl_record_put, pvl_lock_files         lock records through run-as (debuggable builds)
#   pvl_later WAVE NAME [MARKERS] -- COMMAND...        a step that a later package enables
#   pvl_skip, pvl_note                                 a SKIPPED step; a line for the record
#   pvl_setting_set, pvl_undo                          device changes put back when the script ends
#   pvl_window_lib                                     tools/emu/window/lib.sh (the window helper)
#   pvl_log_mark, pvl_log_since, pvl_screenshot
#
# Every adb call goes through adb.sh (pv_adb, pv_shell), so a script refuses a serial that this
# worktree's tools/emu/boot.sh did not start, and never touches emulator-5554. The screens are found by
# the resource ids the app gives them (architecture.md §3.6: LockTags pv.lock.*, PinPadTags pv.pin.*,
# NoticeTags pv.notice*, the routes pv.route.<Route>); a text is compared with the APK's own English
# string, as the emulators run in English. The lock's state is read with the debug command `status`
# (build plan 4.1, lock.md §15.3), which debug, staging and backupCheck builds carry.
set -u
LOCK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMU="$(cd "$LOCK_DIR/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"

# The PIN the checks choose: allowed by the blocklist (lock.md T1 names it among the PINs that pass).
PVL_PIN=${PV_LOCK_PIN:-582913}
# Wrong PINs, all different from each other and from PVL_PIN (the same wrong PIN twice is not counted,
# lock.md §6.6). Any six digits may be typed on the lock screen; only a new PIN meets the blocklist.
PVL_WRONG=(905127 368014 321299 143637 270419 816352 409728 735061 650394 194873 528164)
# Emulator fingers: 1 and 2 are enrolled on every base (tools/emu/README.md); 5 is not.
PVL_FINGER=1
PVL_SECOND_FINGER=2
PVL_UNKNOWN_FINGER=5

pvl_usage() { pv_usage "$PVL_SCRIPT"; }

# pvl_begin NAME ARGS...: ARGS are the script's own, SERIAL [--apk FILE] (a script with options of its
# own, such as E10's --only, takes them out first). Sets SERIAL, APK, API, VARIANT and PVL_OUT.
pvl_begin() {
    PVL_NAME=$1
    PVL_SCRIPT=${BASH_SOURCE[1]}
    shift
    SERIAL=${1:-}
    [ -n "$SERIAL" ] || pvl_usage
    case "$SERIAL" in -*) pvl_usage ;; esac
    shift
    APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
    while [ $# -gt 0 ]; do
        case "$1" in
            --apk) [ $# -ge 2 ] || pvl_usage; APK=$2; shift ;;
            *) pvl_usage ;;
        esac
        shift
    done
    pv_use "$SERIAL"
    pva_init
    PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
    API=$(pv_api)
    [ -f "$APK" ] || { echo "$PVL_NAME: no APK at $APK (tools/gradle.sh :app:assembleDebug or :app:assembleStaging)" >&2; exit 2; }
    PVL_OUT=$REPO/build/emu-lock/$SERIAL/$PVL_NAME
    mkdir -p "$PVL_OUT"
    PVL_RESOURCES=$PVL_OUT/resources.txt
    VARIANT=$(pvl_apk_variant "$APK")
    PVL_RESTORE=()
    PVL_UNDO=()
    echo "$PVL_NAME on $SERIAL (API $API, $VARIANT build): $APK"
    trap pvl_cleanup EXIT
    trap 'exit 130' INT TERM
}

# pvl_end: the last step of every script, then its exit status.
pvl_end() {
    if pv_shell pm path "$PV_PACKAGE" 2>/dev/null | grep -q '^package:'; then
        pv_step "the app did not crash" -- pva_no_crash
    fi
    pv_summary
    exit $?
}

# Puts the device back as the script found it: the settings it changed (the last one first), the
# commands it left with pvl_undo, portrait.
pvl_cleanup() {
    local i ns key value
    trap - EXIT
    [ -n "${PV_SERIAL:-}" ] || return 0
    PV_ADB_TIMEOUT=60
    for ((i = ${#PVL_RESTORE[@]} - 1; i >= 0; i--)); do
        IFS='|' read -r ns key value <<<"${PVL_RESTORE[$i]}"
        if [ "$value" = null ]; then
            pv_shell settings delete "$ns" "$key" >/dev/null 2>&1
        else
            pv_shell settings put "$ns" "$key" "$value" >/dev/null 2>&1
        fi
    done
    for ((i = ${#PVL_UNDO[@]} - 1; i >= 0; i--)); do
        pv_shell "${PVL_UNDO[$i]}" >/dev/null 2>&1
    done
    case "$(pv_shell settings get system user_rotation 2>/dev/null | tr -d '\r')" in
        0 | null | "") ;;
        *) pv_shell settings put system user_rotation 0 >/dev/null 2>&1 ;;
    esac
}

# pvl_setting_set NAMESPACE KEY VALUE: sets it and remembers what it was, for pvl_cleanup.
pvl_setting_set() {
    local old
    old=$(pv_shell settings get "$1" "$2" 2>/dev/null | tr -d '\r')
    PVL_RESTORE+=("$1|$2|${old:-null}")
    pv_shell settings put "$1" "$2" "$3" >/dev/null || return 1
}

# pvl_undo COMMAND: COMMAND (one string, for the device's shell) runs when the script ends.
pvl_undo() { PVL_UNDO+=("$1"); }

# pvl_window_lib: sources tools/emu/window/lib.sh (the window helper: win_helper_build,
# win_helper_install, win_cmd, win_a11y_*, win_control_start, win_file). That file sources app.sh
# again, which sets the screen's size back to its defaults (1080x1920): pva_init reads it again.
pvl_window_lib() {
    source "$EMU/window/lib.sh"
    pva_init
}

# pvl_note TEXT: a line for the record, not a step: what a check is asked to log or to note.
pvl_note() { echo "NOTE $*"; }

# ---- the APK -----------------------------------------------------------------------------------
pvl_aapt2() { ls -d "$PV_SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1; }

# pvl_apk_variant APK: "debug" when the APK is debuggable (debug and backupCheck builds), otherwise
# "staging" (R8, not debuggable, with the debug receiver: architecture.md §7.2).
pvl_apk_variant() {
    local aapt2
    aapt2=$(pvl_aapt2)
    if [ -n "$aapt2" ] && "$aapt2" dump badging "$1" 2>/dev/null | grep -q '^application-debuggable'; then
        echo debug
    else
        echo staging
    fi
}

# The APK's resource table (aapt2 dump resources), read once per script.
pvl_resources() {
    [ -s "$PVL_RESOURCES" ] || "$(pvl_aapt2)" dump resources "$APK" > "$PVL_RESOURCES" 2>/dev/null
    cat "$PVL_RESOURCES"
}

# pvl_has_strings PREFIX: the APK has a string or plural whose name starts with PREFIX.
pvl_has_strings() { pvl_resources | grep -q -E "^ *resource 0x[0-9a-f]+ (string|plurals)/$1"; }

# pvl_res string NAME | pvl_res plural NAME QUANTITY: the default (English) text of a resource, as the
# APK has it.
pvl_res() {
    local kind=$1 name=$2 quantity=${3:-other}
    case "$kind" in
        string)
            pvl_resources | awk -v name="string/$name" '
                $1 == "resource" && $NF == name { f = 1; next }
                f && /^ *\(\) "/ { sub(/^ *\(\) "/, ""); sub(/"$/, ""); print; found = 1; exit }
                f && $1 == "resource" { exit }
                END { exit found ? 0 : 1 }' ;;
        plural)
            pvl_resources | awk -v name="plurals/$name" -v q="$quantity" '
                $1 == "resource" && $NF == name { f = 1; next }
                f && /^ *\(\) \(plurals\)/ { g = 1; next }
                f && g && /^ *\(/ { exit }
                f && g && index($0, q "=\"") { s = $0; sub(/^[^"]*"/, "", s); sub(/"$/, "", s); print s; found = 1; exit }
                f && $1 == "resource" { exit }
                END { exit found ? 0 : 1 }' ;;
        *) return 2 ;;
    esac
}

# pvl_norm: stdin with every run of white space (the no-break space U+00A0 too) made one space,
# trimmed.
pvl_norm() { sed 's/\xc2\xa0/ /g; s/[[:space:]][[:space:]]*/ /g; s/^ //; s/ $//'; }

# pvl_expect TEXT KIND NAME [N [M...]]: TEXT is the resource's text (KIND string or plural; a plural's
# quantity is "one" for N 1 and "other" otherwise, as English has them), with %d and %1$d read as N,
# %2$d as M (and so on), any other number argument as a number and %1$s (any string argument) as any
# text. Says what differs when it is not.
pvl_expect() {
    local text=$1 kind=$2 name=$3 want quantity=other
    shift 3
    [ "${1:-}" = 1 ] && quantity=one
    want=$(pvl_res "$kind" "$name" "$quantity") || { pv_log "no $kind $name in the APK"; return 1; }
    if printf '%s' "$text" | pvl_norm | python3 -c '
import re, sys
want, args = sys.argv[1], sys.argv[2:]
norm = lambda t: re.sub(r"\s+", " ", t.replace(" ", " ")).strip()
pattern, pos = "", 0
for m in re.finditer(r"%(?:(\d+)\$)?([ds])", norm(want)):
    pattern += re.escape(norm(want)[pos:m.start()])
    index = int(m.group(1) or 1) - 1
    if m.group(2) == "d":
        pattern += re.escape(args[index]) if index < len(args) else r"\d+"
    else:
        pattern += ".*"
    pos = m.end()
pattern += re.escape(norm(want)[pos:])
sys.exit(0 if re.fullmatch(pattern, norm(sys.stdin.read())) else 1)
' "$want" "$@"; then
        return 0
    fi
    pv_log "the text is '$text'; expected $kind $name${1:+ ($*)}: '$want'"
    return 1
}

# pvl_text_shown KIND NAME [SECONDS]: some node on the screen (a dialog, a snackbar, a line) shows that
# resource's text within SECONDS (default 10).
pvl_text_shown() {
    local kind=$1 name=$2 deadline=$((SECONDS + ${3:-10})) text
    while :; do
        while IFS= read -r text; do
            [ -n "$text" ] || continue
            pvl_expect "$text" "$kind" "$name" 2>/dev/null && return 0
        done < <( { pv_ui_nodes; pv_ui_nodes_all 2>/dev/null; } | cut -d'|' -f1)
        [ $SECONDS -lt $deadline ] || { pv_log "no node shows $kind $name ('$(pvl_res "$kind" "$name")'); ids: $(pva_ids)"; return 1; }
        pv_sleep 1
    done
}

# pvl_text_is ID KIND NAME [N]: the node ID shows that text (pvl_expect).
pvl_text_is() {
    local id=$1 text
    shift
    text=$(pva_text "$id") || return 1
    pvl_expect "$text" "$@"
}

# pvl_later WAVE NAME [needs-debuggable] [needs-root] -- COMMAND...: a step that needs a package built
# after 4.5 (build plan 4.5:
# "written now and marked with the wave that enables them"). WAVE is 5.5 (the screens of changing and
# replacing the master password, strings_master.xml) or 6.1 (the language switch,
# strings_language.xml). When the APK has the package's strings the step runs as any other;
# otherwise it prints SKIPPED with the package it waits for. The markers are pv_step's.
pvl_later() {
    local wave=$1 name=$2 prefix what markers=()
    shift 2
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do markers+=("$1"); shift; done
    [ "${1:-}" = "--" ] && shift
    case "$wave" in
        5.5) prefix=master_; what="build plan 5.5, the screens of F8 and F11 (wave 5)" ;;
        6.1) prefix=language_; what="build plan 6.1, the language switch (wave 6)" ;;
        *) pv_die "pvl_later: unknown wave $wave" 2 ;;
    esac
    if pvl_has_strings "$prefix"; then
        pv_step "$name" "${markers[@]}" -- "$@"
    else
        pv_report SKIPPED "$name" "waits for $what: the APK has no ${prefix}* strings"
        return 0
    fi
}

# pvl_skip NAME REASON: a step that cannot run here, as SKIPPED.
pvl_skip() { pv_report SKIPPED "$1" "$2"; }

# ---- the app's state through the debug command `status` ------------------------------------------
# PVL_STATUS: the last answer, one line of items separated by "; " (build plan §12, "4.5").
PVL_STATUS=""

pvl_status() {
    PVL_STATUS=$(pva_debug status) || { PVL_STATUS=""; return 1; }
    printf '%s %s\n' "$(date +%T)" "$PVL_STATUS" >> "$PVL_OUT/status.log"
}

# The items of PVL_STATUS, one per line.
pvl_items() { printf '%s\n' "$PVL_STATUS" | sed 's/; /\n/g' | sed 's/^ *//'; }

# pvl_item PREFIX: the first item that starts with PREFIX ("state:", "policy:", "keystore:", ...).
pvl_item() { pvl_items | awk -v p="$1" 'index($0, p) == 1 { print; exit }'; }

# The current vault's id ("vaults: <id> generation N").
pvl_vault_id() { pvl_item "vaults:" | sed -n 's/^vaults: \([0-9a-f-]\{36\}\) generation.*/\1/p'; }

# pvl_vault_item RECORD [VAULT-ID]: the item "RECORD: ..." (pin.rec, fingerprint.rec, state.rec) of
# the lock directory of VAULT-ID (default: the current vault).
pvl_vault_item() {
    local id=${2:-}
    [ -n "$id" ] || id=$(pvl_vault_id)
    pvl_items | awk -v id="$id" -v rec="$1:" '
        $0 == "lock " id { f = 1; next }
        f && (index($0, "lock ") == 1 || index($0, "aliases:") == 1) { exit }
        f && index($0, rec) == 1 { print; exit }'
}

# pvl_rec_value ITEM KEY: in an item such as "pin.rec: OK, slot set, 1 wrap(s), off none, charged
# false, row 0, total 0, unseen 0", the value after KEY ("row" -> 0, "slot" -> set, "off" -> none).
# KEY "read" gives the read result (OK, MISSING, DAMAGED, NEWER, UNREADABLE).
pvl_rec_value() {
    case "$2" in
        read) printf '%s\n' "$1" | sed -n 's/^[a-z.]*: \([A-Z]*\).*/\1/p' ;;
        *) printf '%s\n' "$1" | sed -n "s/.*[ ,]$2 \([^ ,]*\).*/\1/p" | head -n 1 ;;
    esac
}

# pvl_pin KEY, pvl_fp KEY, pvl_state KEY: a value of the current vault's records in PVL_STATUS
# (call pvl_status first). pin: read slot off charged row total unseen. fp: read slot off. state:
# read practice step, change, pending, replaced ("wrong master passwords" as "wrong").
pvl_pin() { pvl_rec_value "$(pvl_vault_item pin.rec)" "$1"; }
pvl_fp() { pvl_rec_value "$(pvl_vault_item fingerprint.rec)" "$1"; }
pvl_state() {
    local item
    item=$(pvl_vault_item state.rec)
    case "$1" in
        wrong) printf '%s\n' "$item" | sed -n 's/.*wrong master passwords \([0-9]*\).*/\1/p' ;;
        practice) printf '%s\n' "$item" | sed -n 's/.*practice step \([0-9]*\).*/\1/p' ;;
        *) pvl_rec_value "$item" "$1" ;;
    esac
}

# pvl_policy KEY: from "policy: row PIN_PAD, pin USABLE, fingerprint usable true" (only while
# locked): row, pin, fingerprint.
pvl_policy() {
    local item
    item=$(pvl_item "policy:")
    case "$1" in
        row) printf '%s\n' "$item" | sed -n 's/^policy: row \([A-Z_]*\).*/\1/p' ;;
        pin) printf '%s\n' "$item" | sed -n 's/.*, pin \([A-Z_]*\).*/\1/p' ;;
        fingerprint) printf '%s\n' "$item" | sed -n 's/.*fingerprint usable \([a-z]*\).*/\1/p' ;;
    esac
}

# pvl_lock_state: "unlocked", "locked: pad", "locked: master password", ... from "state: ...".
pvl_lock_state() { pvl_item "state:" | sed 's/^state: //; s/, [0-9]* notice(s)$//'; }

# pvl_alias_count pin|fp: how many aliases of that kind the Keystore lists ("keystore: ...", every
# alias of the app), pv_canary aside.
pvl_alias_count() {
    local prefix=pv_pin_
    [ "$1" = fp ] && prefix=pv_fp_
    pvl_item "keystore:" | tr ' ' '\n' | grep -c "^$prefix"
}

pvl_has_canary() { pvl_item "keystore:" | tr ' ' '\n' | grep -qx pv_canary; }

# pvl_show_status: the current vault's records and the keystore, for a failure message.
pvl_show_status() {
    printf '%s | %s | %s | %s | %s | %s\n' "$(pvl_item 'state:')" "$(pvl_item 'policy:')" \
        "$(pvl_vault_item pin.rec)" "$(pvl_vault_item fingerprint.rec)" "$(pvl_item 'keystore:')" "$(pvl_item 'hold:')"
}

# pvl_expect_pin KEY=VALUE...: the current pin.rec has these values (a fresh status is read).
pvl_expect_pin() {
    local kv key want got bad=0
    pvl_status || return 1
    for kv in "$@"; do
        key=${kv%%=*}; want=${kv#*=}
        got=$(pvl_pin "$key")
        [ "$got" = "$want" ] || { pv_log "pin.rec $key is '$got', expected '$want'"; bad=1; }
    done
    [ $bad = 0 ] || pv_log "status: $(pvl_show_status)"
    return $bad
}

# pvl_expect_fp KEY=VALUE...: the same for fingerprint.rec.
pvl_expect_fp() {
    local kv key want got bad=0
    pvl_status || return 1
    for kv in "$@"; do
        key=${kv%%=*}; want=${kv#*=}
        got=$(pvl_fp "$key")
        [ "$got" = "$want" ] || { pv_log "fingerprint.rec $key is '$got', expected '$want'"; bad=1; }
    done
    [ $bad = 0 ] || pv_log "status: $(pvl_show_status)"
    return $bad
}

# pvl_wait_lock_state REGEX [SECONDS]: the lock's state (pvl_lock_state) matches within SECONDS.
pvl_wait_lock_state() {
    local deadline=$((SECONDS + ${2:-20})) now
    while :; do
        pvl_status && now=$(pvl_lock_state) && printf '%s\n' "$now" | grep -q -E -- "$1" && return 0
        [ $SECONDS -lt $deadline ] || { pv_log "the lock is '${now:-?}', not /$1/"; return 1; }
        pv_sleep 1
    done
}

# ---- what the screen shows ---------------------------------------------------------------------------
# pvl_screen [NODES]: the lock screen or the app as shown now: pad, fingerprint-only, master-password,
# older, older-set, unreadable, welcome, list, pin-steps, notice, prompt (a fingerprint prompt over
# everything), or "other <ids>".
pvl_screen() {
    local nodes=${1:-}
    [ -n "$nodes" ] || nodes=$(pv_ui_nodes)
    if pvl_prompt_in "$nodes"; then echo prompt; return; fi
    if pva_in_nodes '[|]pv\.notice$' "$nodes"; then echo notice
    elif pva_in_nodes '[|]pv\.lock\.pad$' "$nodes"; then echo pad
    elif pva_in_nodes '[|]pv\.lock\.fingerprintOnly$' "$nodes"; then echo fingerprint-only
    elif pva_in_nodes '[|]pv\.lock\.olderSet$' "$nodes"; then echo older-set
    elif pva_in_nodes '[|]pv\.lock\.older$' "$nodes"; then echo older
    elif pva_in_nodes '[|]pv\.lock\.unreadable$' "$nodes"; then echo unreadable
    elif pva_in_nodes '[|]pv\.lock\.password$' "$nodes"; then echo master-password
    elif pva_in_nodes '[|]pv\.route\.Welcome$' "$nodes"; then echo welcome
    elif pva_in_nodes "[|]pv\\.route\\.($PVA_PIN_STEPS)\$" "$nodes"; then echo pin-steps
    elif pva_in_nodes '[|]pv\.route\.Passwords$' "$nodes"; then echo list
    else echo "other $(printf '%s\n' "$nodes" | awk -F'|' '$3 ~ /^pv[.]/ { printf "%s ", $3 }')"
    fi
}

# pvl_wait_screen NAME [SECONDS]: pvl_screen says NAME (a regular expression) within SECONDS (default 20).
pvl_wait_screen() {
    local deadline=$((SECONDS + ${2:-20})) now
    while :; do
        now=$(pvl_screen)
        printf '%s\n' "$now" | grep -q -x -E -- "$1" && return 0
        [ $SECONDS -lt $deadline ] || { pv_log "the screen is '$now', not /$1/"; return 1; }
        pv_sleep 1
    done
}

# pvl_nowhere ID: no node ID on the screen, also below the fold: the keyboard closed, and looked for
# again after each of two swipes down (only nodes that are shown are reported).
pvl_nowhere() {
    local i
    pva_hide_keyboard
    for i in 1 2 3; do
        pva_absent "$1" || { pv_log "$1 is on the screen"; return 1; }
        [ "$i" -lt 3 ] && pva_scroll_down
    done
    return 0
}

# pvl_status_line: the pad's status line (pv.lock.status), or nothing when there is none.
pvl_status_line() { pva_node pv.lock.status | cut -d'|' -f1; }

# pvl_notice_lines: the texts of the notice on the screen (its title, then pv.notice.line.N), one per line.
pvl_notice_lines() { pv_ui_nodes | awk -F'|' '$3 ~ /^pv[.]notice[.](title|line[.][0-9]+)$/ { print $1 }'; }

# pvl_notice_has KIND NAME [N]: a notice is shown and one of its texts is that resource's (pvl_expect).
pvl_notice_has() {
    local line lines
    pva_need pv.notice 20 || return 1
    lines=$(pvl_notice_lines)
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        pvl_expect "$line" "$@" 2>/dev/null && return 0
    done <<<"$lines"
    pv_log "no line of the notice is $1 $2${3:+ ($3)}: $(printf '%s' "$lines" | tr '\n' '/')"
    return 1
}

# pvl_answer ANSWER: the notice's button pv.answer.ANSWER (OK, LATER, CHANGE_PIN, ...).
pvl_answer() { pva_tap "pv.answer.$1"; }

# ---- setup and unlocking ---------------------------------------------------------------------------
# pvl_fresh: the APK installed afresh (nothing of an earlier run left) and started: Welcome.
pvl_fresh() {
    pva_install "$APK" || return 1
    pva_start && pva_on Welcome 30
}

# pvl_set_up [PIN|-] [fingerprint|-]: from Welcome, setup with the suggested words (lock.md F1 steps 1
# to 3; their master password becomes PVA_PASSWORD), then the PIN steps that "Choose a PIN" opens (F1
# steps 6 to 8): PIN chosen and typed again (or "Not now" for "-"), fingerprint unlock turned on with
# finger 1 (or "Not now"; on a phone without an enrolled finger the offer is not shown), "Make
# backups" finished; the list, with nothing over it.
pvl_set_up() {
    local pin=${1:--} fingerprint=${2:--}
    pva_on Welcome 30 || return 1
    pva_tap pv.open.CreateMasterPassword && pva_on CreateMasterPassword || return 1
    pva_reveal pv.setup.passphrase || return 1
    PVA_PASSWORD=$(pva_text pv.setup.passphrase) || return 1
    printf '%s\n' "$PVA_PASSWORD" | grep -Eq '^[a-z]{3,9}(-[a-z]{3,9}){5}$' \
        || { pv_log "not six words: '$PVA_PASSWORD'"; return 1; }
    pva_tap pv.open.WriteDown && pva_on WriteDown || return 1
    pva_tap pv.open.TypeAgain && pva_on TypeAgain || return 1
    pva_type pv.setup.again "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.action.createVault || return 1
    pva_unlocked Passwords 120 || return 1
    pvl_pin_steps "$pin" "$fingerprint"
}

# pvl_pin_steps PIN|- fingerprint|- [ROUTE]: walks 4.3's PIN steps as they come (CreatePin, ConfirmPin,
# FingerprintOffer, BackupsExplained) and answers notices "Later" or "OK", until ROUTE (default
# Passwords) is shown alone for 3 s. PVL_NOTICES gets the answers each notice offered ("LATER,OK," per
# notice), so that a caller can say which notices came.
PVL_NOTICES=""
pvl_pin_steps() {
    local pin=$1 fingerprint=$2 route=${3:-Passwords} deadline=$((SECONDS + 180)) nodes answer chosen=0 confirmed=0
    while :; do
        nodes=$(pv_ui_nodes)
        if pvl_prompt_in "$nodes"; then
            # The prompt of "Use fingerprint" on the offer (lock.md F9 step 6).
            pv_sleep 2
            pvl_finger "$PVL_FINGER"
            pv_sleep 2
        elif pva_in_nodes '[|]pv\.notice$' "$nodes"; then
            # What each notice offered, for the caller (PVL_NOTICES: the answers' names, space-separated).
            PVL_NOTICES="$PVL_NOTICES $(printf '%s\n' "$nodes" | awk -F'|' '$3 ~ /^pv[.]answer[.]/ { sub(/^pv[.]answer[.]/, "", $3); printf "%s,", $3 }')"
            answer=""
            for answer in LATER OK; do
                [ -n "$(pva_node "pv.answer.$answer" "$nodes")" ] && break
                answer=""
            done
            [ -n "$answer" ] || { pv_log "a notice without Later or OK; ids: $(pva_ids)"; return 1; }
            pva_tap "pv.answer.$answer" || return 1
        elif pva_in_nodes '[|]pv\.route\.CreatePin$' "$nodes"; then
            if [ "$pin" = - ] || [ $chosen -ge 2 ]; then
                [ $chosen -lt 2 ] || pv_log "CreatePin again after the PIN was typed twice; leaving it with Not now"
                pva_tap pv.pin.notNow || return 1
            else
                pva_pin "$pin" || return 1
                chosen=$((chosen + 1))
                pv_sleep 1
            fi
        elif pva_in_nodes '[|]pv\.route\.ConfirmPin$' "$nodes"; then
            if [ "$pin" = - ]; then
                pv_shell input keyevent KEYCODE_BACK >/dev/null
            else
                pva_pin "$pin" || return 1
                confirmed=$((confirmed + 1))
                pv_sleep 2
            fi
        elif pva_in_nodes '[|]pv\.route\.FingerprintOffer$' "$nodes"; then
            if [ "$fingerprint" = fingerprint ]; then
                pva_tap pv.fingerprintOffer.use || return 1
                pvl_prompt_up 15 || return 1
                pv_sleep 2
                pvl_finger "$PVL_FINGER"
                pvl_prompt_down 15 || return 1
                fingerprint=done
            else
                pva_tap pv.fingerprintOffer.notNow || return 1
            fi
        elif pva_in_nodes '[|]pv\.route\.BackupsExplained$' "$nodes"; then
            pva_tap pv.action.finish || return 1
        elif pva_in_nodes "[|]pv\\.route\\.$route\$" "$nodes"; then
            pv_sleep 3
            nodes=$(pv_ui_nodes)
            if pva_in_nodes "[|]pv\\.route\\.$route\$" "$nodes" \
                && ! pva_in_nodes "[|]pv\\.(notice|route\\.($PVA_PIN_STEPS))\$" "$nodes" && ! pvl_prompt_in "$nodes"; then
                if [ "$fingerprint" = fingerprint ]; then
                    pv_log "the fingerprint offer was not shown (is a finger enrolled?)"
                    return 1
                fi
                return 0
            fi
        fi
        [ $SECONDS -lt $deadline ] || { pv_log "the PIN steps did not end on $route; ids: $(pva_ids)"; return 1; }
        pv_sleep 1
    done
}

# pvl_lock_now: "Lock now" on the list (Session.lockNow): the lock screen, or the fingerprint prompt
# that opens over it by itself (on API 24 to 27 the library's dialog hides the lock screen's nodes).
pvl_lock_now() {
    pva_on Passwords 20 || return 1
    pva_hide_keyboard
    pva_tap pv.list.lockNow || return 1
    pvl_wait_screen 'pad|fingerprint-only|master-password|prompt' 20
}

# pvl_pad [SECONDS]: the pad is shown (an automatic fingerprint prompt over it is closed with its
# negative button, "Use PIN").
pvl_pad() {
    local deadline=$((SECONDS + ${1:-30})) now
    while :; do
        now=$(pvl_screen)
        case "$now" in
            pad) return 0 ;;
            prompt) pvl_prompt_negative ;;
        esac
        [ $SECONDS -lt $deadline ] || { pv_log "no pad: the screen is '$now'"; return 1; }
        pv_sleep 1
    done
}

# pvl_to_pad [SECONDS]: the pad, from the master-password screen ("Use PIN", when the PIN is usable)
# or from under a prompt ("Use PIN" on the prompt).
pvl_to_pad() {
    local now
    now=$(pvl_screen)
    case "$now" in
        master-password) pva_tap pv.lock.usePin || { pv_log "no \"Use PIN\" on the master-password screen"; return 1; } ;;
    esac
    pvl_pad "${1:-20}"
}

# pvl_open_list [SECONDS]: right after an unlock: the list, once what comes over it (notices, "Choose
# a PIN") is answered (pva_settle).
pvl_open_list() { pva_unlocked Passwords "${1:-60}" && pva_settle Passwords; }

# pvl_unlock_pin [PIN]: the PIN on the pad that is shown; then the list.
pvl_unlock_pin() { pvl_pad && pva_pin "${1:-$PVL_PIN}" && pvl_open_list; }

# pvl_to_master_password: from the pad, "Forgot PIN? Use master password"; from the fingerprint-only
# screen, "Use master password": the master-password field.
pvl_to_master_password() {
    local now
    now=$(pvl_screen)
    case "$now" in
        prompt) pvl_prompt_negative; pv_sleep 1; now=$(pvl_screen) ;;
    esac
    case "$now" in
        pad) pva_tap pv.lock.forgotPin || return 1 ;;
        fingerprint-only) pva_tap pv.lock.useMasterPassword || return 1 ;;
        master-password) return 0 ;;
        *) pv_log "no way to the master password from '$now'"; return 1 ;;
    esac
    pva_need pv.lock.password 15
}

# pvl_unlock_mp: the master password (PVA_PASSWORD) on the lock screen, through "Forgot PIN?" or "Use
# master password" when needed; then the list once what comes over it is answered.
pvl_unlock_mp() { pvl_to_master_password && pva_type pv.lock.password "$PVA_PASSWORD" && pva_submit_open pv.lock.unlock Passwords 90; }

# pvl_wrong_pins N [FIRST]: N wrong PINs on the pad (PVL_WRONG from index FIRST, default 0), each
# answered before the next ("Wrong PIN" on the status line, or the pad gone at a limit).
pvl_wrong_pins() {
    local n=$1 first=${2:-0} i line
    for ((i = first; i < first + n; i++)); do
        pvl_pad 20 || return 1
        pva_pin "${PVL_WRONG[$i]}" || return 1
        pvl_wait_answer || return 1
    done
}

# pvl_wait_answer [SECONDS]: after the sixth digit, until the check has answered: the dots are empty
# again with a status line, or the pad is gone.
pvl_wait_answer() {
    local deadline=$((SECONDS + ${1:-20})) nodes
    pv_sleep 1.2
    while :; do
        nodes=$(pv_ui_nodes)
        if ! pva_in_nodes '[|]pv\.lock\.pad$' "$nodes"; then return 0; fi
        if pva_in_nodes '[|]pv\.lock\.status$' "$nodes" && ! pva_in_nodes '[|]pv\.lock\.opening$' "$nodes" \
            && printf '%s\n' "$nodes" | grep -q '[|]pv\.pin\.dots[|]' \
            && ! printf '%s\n' "$nodes" | grep '[|]pv\.pin\.dots[|]' | grep -q -E ' [1-6] of '; then
            return 0
        fi
        [ $SECONDS -lt $deadline ] || { pv_log "no answer to the PIN within ${1:-20} s; ids: $(pva_ids)"; return 1; }
        pv_sleep 0.5
    done
}

# ---- fingerprint ---------------------------------------------------------------------------------
# pvl_finger N: one touch of emulator finger N, then "remove", so that touches close together are not
# merged into one (docs/lab/biometric.md, Experiment 1).
pvl_finger() {
    pv_adb emu finger touch "$1" >/dev/null || return 1
    pv_sleep 0.5
    pv_adb emu finger remove >/dev/null
}

# pvl_prompt_in NODES: a fingerprint prompt is on the screen. API 28 and newer: the system's
# BiometricPrompt window (its icon biometric_icon, or on Android 9 the dialog's fingerprint icon); API
# 24 to 27: androidx.biometric's own dialog inside the app (FingerprintDialogFragment, its icon
# fingerprint_icon).
pvl_prompt_in() {
    printf '%s\n' "$1" | grep -q -E '[|](biometric_icon|fingerprint_icon|fingerprint_description|biometric_title)[|]'
}

# pvl_prompt_nodes: the nodes of the active window and of every window a user can touch: the system's
# prompt is a window of its own on API 28 and newer (the active one on API 28, but not on API 37).
pvl_prompt_nodes() { pv_ui_nodes; pv_ui_nodes_all 2>/dev/null | cut -d'|' -f1-7; }

# pvl_prompt_shown: the prompt is on the screen now.
pvl_prompt_shown() {
    case "$(pv_focus)" in *BiometricPrompt* | *BiometricDialog* | *biometric*) return 0 ;; esac
    pvl_prompt_in "$(pvl_prompt_nodes)"
}

# pvl_prompt_up [SECONDS]: a prompt appears within SECONDS (default 15).
pvl_prompt_up() {
    local deadline=$((SECONDS + ${1:-15}))
    until pvl_prompt_shown; do
        [ $SECONDS -lt $deadline ] || { pv_log "no fingerprint prompt within ${1:-15} s (focus $(pv_focus))"; return 1; }
        pv_sleep 0.5
    done
}

# pvl_prompt_down [SECONDS]: no prompt is on the screen within SECONDS (default 15).
pvl_prompt_down() {
    local deadline=$((SECONDS + ${1:-15}))
    while pvl_prompt_shown; do
        [ $SECONDS -lt $deadline ] || { pv_log "the fingerprint prompt is still open after ${1:-15} s"; return 1; }
        pv_sleep 0.5
    done
}

# pvl_prompt_negative: the prompt's negative button ("Use PIN", "Use master password", "Cancel"): the
# system's button_negative, or the library's own dialog's button (API 24 to 27).
pvl_prompt_negative() {
    local nodes
    nodes=$(pvl_prompt_nodes)
    pv_ui_tap '[|](button_negative|negative_button|button2)$' "$nodes" >/dev/null && return 0
    pv_ui_tap '^(Use PIN|USE PIN|Use master password|USE MASTER PASSWORD|Cancel|CANCEL|Not now|NOT NOW)[|]' "$nodes" >/dev/null && return 0
    pv_log "no negative button on the prompt"
    return 1
}

# pvl_touch_prompt N [SECONDS]: once a prompt is up, 2 s later (a touch made before the system's
# prompt is drawn is lost on API 28 and 37: build plan §12, "4.5"), finger N.
pvl_touch_prompt() {
    pvl_prompt_up "${2:-15}" || return 1
    pv_sleep 2
    pvl_finger "$1"
}

# pvl_enrolled_fingers: how many fingerprints the phone has enrolled (dumpsys fingerprint), -1 if
# unknown.
pvl_enrolled_fingers() {
    pv_shell dumpsys fingerprint 2>/dev/null | tr -d '\r' | python3 -c '
import json, sys
for line in sys.stdin:
    line = line.strip()
    if line.startswith("{") and "\"prints\"" in line:
        print(sum(p.get("count", 0) for p in json.loads(line)["prints"]))
        break
else:
    print(-1)'
}

# ---- the process -----------------------------------------------------------------------------------
# pvl_kill9: the app's process ends without warning: kill -9 through run-as (debuggable builds), or
# su on the userdebug images (API 24 and 28); where neither works (staging on API 37), am force-stop,
# as build plan §12 ("4.5") has it. Prints which.
pvl_kill9() {
    local pid
    pid=$(pva_pid)
    [ -n "$pid" ] || { pv_log "the app has no process"; return 1; }
    if pv_is_debuggable; then
        pv_shell run-as "$PV_PACKAGE" kill -9 "$pid" >/dev/null 2>&1
        echo "kill -9 (run-as)"
    elif pv_has_root; then
        pv_shell su 0 kill -9 "$pid" >/dev/null 2>&1
        echo "kill -9 (su)"
    else
        pv_shell am force-stop "$PV_PACKAGE" >/dev/null 2>&1
        echo "am force-stop (neither run-as nor root here)"
    fi
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
        if [ "$(pva_pid)" != "$pid" ]; then
            # The system learns of the death a moment later ("has died", "app died, no saved state");
            # a launcher intent sent before that goes to the dying activity and is lost.
            pv_sleep 2
            return 0
        fi
        pv_sleep 0.5
    done
    pv_log "the process $pid is still there"
    return 1
}

# pvl_force_stop: am force-stop, and the process is gone.
pvl_force_stop() {
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null 2>&1
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
        [ -z "$(pva_pid)" ] && return 0
        pv_sleep 0.5
    done
    pv_log "the app's process is still there after force-stop"
    return 1
}

# pvl_restart: the launcher's intent starts the app again (a new process if it had none): the lock
# screen (or a prompt over it). Once more when nothing of the app shows within 15 s: an intent that
# reached a process in the middle of dying is lost.
pvl_restart() {
    pva_relaunch || return 1
    pvl_wait_screen 'pad|fingerprint-only|master-password|prompt|older|older-set|unreadable' 15 2>/dev/null && return 0
    pv_log "the lock screen did not show; the launcher's intent once more"
    pva_relaunch && pvl_wait_screen 'pad|fingerprint-only|master-password|prompt|older|older-set|unreadable' 30
}

# ---- the app's files (debuggable builds: run-as) -------------------------------------------------
# pvl_lock_files: the files of the app's lock directories (no_backup/lock/*/), one per line.
pvl_lock_files() { pv_shell "run-as $PV_PACKAGE sh -c 'ls -1 no_backup/lock/*/ 2>/dev/null; ls -1d no_backup/lock/* 2>/dev/null'" 2>/dev/null | tr -d '\r'; }

# pvl_record VAULT-ID NAME: the JSON of the record NAME (pin.rec, fingerprint.rec, state.rec) of that
# vault, framing checked (lock.md §5.3); nothing and status 1 when it is missing or does not frame.
pvl_record() {
    pv_shell "run-as $PV_PACKAGE cat no_backup/lock/$1/$2" 2>/dev/null | python3 -c '
import hashlib, struct, sys
data = sys.stdin.buffer.read()
if len(data) < 44 or data[:8] != b"PVLOCK01":
    sys.exit(1)
n = struct.unpack(">I", data[8:12])[0]
body, digest = data[12:12 + n], data[12 + n:]
if len(body) != n or hashlib.sha256(data[:12 + n]).digest() != digest:
    sys.exit(1)
sys.stdout.write(body.decode("utf-8") + "\n")'
}

# pvl_record_put VAULT-ID NAME JSON: writes JSON as that record, framed as lock.md §5.3 has it (magic,
# length, JSON, SHA-256), through run-as: a test of what the app does with a record it did not write.
# The app's process must not run (it keeps the records it means to have in memory).
pvl_record_put() {
    local tmp=$PVL_OUT/record.bin
    printf '%s' "$3" | python3 -c '
import hashlib, struct, sys
body = sys.stdin.buffer.read()
head = b"PVLOCK01" + struct.pack(">I", len(body))
sys.stdout.buffer.write(head + body + hashlib.sha256(head + body).digest())' > "$tmp" || return 1
    pv_adb push "$tmp" /data/local/tmp/pvl-record.bin >/dev/null 2>&1 || return 1
    pv_shell "run-as $PV_PACKAGE sh -c 'cat /data/local/tmp/pvl-record.bin > no_backup/lock/$1/$2'" >/dev/null 2>&1
    pv_shell rm -f /data/local/tmp/pvl-record.bin >/dev/null 2>&1
    [ "$(pvl_record "$1" "$2")" = "$3" ] || { pv_log "the record $2 of $1 did not take"; return 1; }
}

# ---- logcat ----------------------------------------------------------------------------------------
# pvl_log_mark: from here on pvl_log_since reads the device's log.
pvl_log_mark() { PVL_LOG_SINCE=$(pv_shell date '+%m-%d %H:%M:%S.000' 2>/dev/null | tr -d '\r'); }

# pvl_log_since [TAG:PRIORITY...]: the log lines since pvl_log_mark (all tags when none is given).
pvl_log_since() {
    if [ $# -gt 0 ]; then
        pv_shell logcat -d -v time -T "'${PVL_LOG_SINCE:-01-01 00:00:00.000}'" -s "$@" 2>/dev/null | tr -d '\r' | grep -v '^--------- beginning'
    else
        pv_shell logcat -d -v time -T "'${PVL_LOG_SINCE:-01-01 00:00:00.000}'" 2>/dev/null | tr -d '\r' | grep -v '^--------- beginning'
    fi
}

# pvl_screenshot NAME: the screen into PVL_OUT/NAME.png (debug builds; a secure window gives black or
# nothing, so the emulator's own screenshot is taken on staging builds).
pvl_screenshot() {
    if [ "$VARIANT" = debug ]; then
        pva_screenshot "$PVL_OUT/$1.png" 2>/dev/null
    else
        pv_adb emu screenrecord screenshot "$PVL_OUT" >/dev/null 2>&1
    fi
    return 0
}
