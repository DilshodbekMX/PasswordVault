#!/usr/bin/env bash
# tools/emu/window/state.sh - build plan 3.12, [script]: nothing that was typed is in the
# saved-instance state of the real setup, list, edit and lock screens (architecture.md §5.6, §8.4;
# lock.md §5.8; ux.md §3; [V-C11]). The method is the window lab's e5_state.py
# (docs/lab/window-input.md, Experiment 5), whose lab app logged every leaf of the Bundle in
# onSaveInstanceState. PasswordVault has no such log, so the helper app's StateProbe, an
# instrumentation, runs in the app's own process (both APKs are signed with the debug key) and writes
# each Bundle that Android takes from the activity to logcat, complete, with the androidx saved-state
# registry where Compose keeps its saved state: its leaves, and its bytes as a Parcel.
#
#   tools/emu/window/state.sh SERIAL [--apk FILE]
#       SERIAL comes from tools/emu/boot.sh (API 28 or 37; the helper app needs API 26). The APK
#       defaults to this worktree's app/build/outputs/apk/debug/app-debug.apk; the staging APK is
#       checked the same way.
#
# The app is started under the probe and used through its own screens. With a text typed (or a secret
# shown), the device is turned to landscape and back, which makes Android save the state twice and
# build the screen again from it; once, with the whole form typed, Home does it instead. Each saved
# Bundle must name the screen's route (so it is the app's own, complete state) and must hold none of
# the texts typed so far, neither among its leaves nor in its bytes, in UTF-16 or UTF-8:
#   setup: the own master password in its field, shown on "Write it down", and typed again;
#   the list: a search; the form: name, username, password, website, notes; its dialog: a label and a
#   secret value; the generator's sheet: the suggested password; the lock screen: the master password;
#   since the device check of 4A the PIN steps ("Set a PIN"): the master password, and five digits of
#   the new PIN on their pad.
# The control is the helper app's own screen under the same probe: a platform text field saves its
# text, and the probe must find it, or a clean result would mean nothing.
# Prints PASS or FAIL per step and a summary; exit 0 when no step failed, 1 otherwise, 2 for a usage
# error, 3 for a refused serial. The leaves of every Bundle are kept under build/window/SERIAL/.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
win_begin state.sh "$@"

CONTROL_TEXT=ctrlSavedLLL
CONTROL_SECRET=CtrlSavedMMM4
PROBE_PID=""
LAST_SEQ=0

# probe_start CLASS PACKAGE: the process of PACKAGE starts under the probe; nothing of it is shown yet.
probe_start() {
    local out i pid
    out=$(pv_shell am instrument "$WIN_HELPER/$1" 2>&1 | tr -d '\r')
    case "$out" in
        *FAILED* | *Error* | *Exception*) pv_log "am instrument $1: $out"; return 1 ;;
    esac
    WIN_INSTRUMENTED=1
    for i in $(seq 1 30); do
        pid=$(pva_pid_of "$2")
        if [ -n "$pid" ] && pv_shell logcat -d -s PvWinState:I 2>/dev/null | tr -d '\r' | grep -q "READY pid=$pid target=$2\$"; then
            PROBE_PID=$pid
            LAST_SEQ=0
            return 0
        fi
        pv_sleep 0.5
    done
    pv_log "the probe did not start in $2: ${out:-no output from am instrument}"
    return 1
}

pva_pid_of() { pv_shell pidof "$1" 2>/dev/null | tr -d '\r' | awk '{ print $1 }'; }

# What was typed so far, and what each text is called in the messages (the texts themselves are not
# printed).
NEEDLES=()
NAMES=()
typed_text() { NEEDLES+=("$1"); NAMES+=("$2"); }

# records TAG ROUTE NEEDLE...: the Bundles the probe wrote since the last call, one line each:
# "SEQ LEAVES BYTES ACTIVITY ROUTE-NAMED FOUND": ROUTE-NAMED is yes when the Bundle names the route
# ROUTE, FOUND the positions (from 0) of the NEEDLEs it holds, comma-separated, or "-". A needle is
# looked for among the leaves and in the bytes, in UTF-16 and in UTF-8. The leaves go to
# $WIN_OUT/state-$VARIANT-TAG-SEQ.txt. The caller moves LAST_SEQ on (this runs in a subshell).
records() {
    local tag=$1 out
    shift
    out=$(pv_shell logcat -d -v threadtime -s PvWinState:I 2>/dev/null | tr -d '\r' \
        | python3 -c '
import base64, re, sys
pid, last, prefix, route, needles = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5:]
recs = {}
for line in sys.stdin:
    m = re.match(r"\S+ \S+ +(\d+) +\d+ I PvWinState: (SAVE|L64|B64|END) (\d+) ?(.*)$", line.rstrip("\n"))
    if not m or m.group(1) != pid:
        continue
    kind, seq, rest = m.group(2), int(m.group(3)), m.group(4)
    r = recs.setdefault(seq, {"L64": [], "B64": [], "SAVE": None, "END": False})
    if kind in ("L64", "B64"):
        r[kind].append(rest.split(" ", 1)[1])
    elif kind == "SAVE":
        r["SAVE"] = rest
    else:
        r["END"] = True
def held(text, leaves, data):
    return text in leaves or text.encode("utf-16-le") in data or text.encode("utf-8") in data
for seq in sorted(recs):
    r = recs[seq]
    if seq <= last or not r["END"] or r["SAVE"] is None:
        continue
    leaves = base64.b64decode("".join(r["L64"])).decode("utf-8", "replace")
    data = base64.b64decode("".join(r["B64"]))
    found = [str(i) for i, n in enumerate(needles) if n and held(n, leaves, data)]
    with open("%s-%d.txt" % (prefix, seq), "w") as f:
        f.write(leaves)
    activity = re.search(r"activity=(\S+)", r["SAVE"])
    print(seq, leaves.count("\n"), len(data) if r["B64"] else -1, activity.group(1) if activity else "?",
          "yes" if route and held("nav.Route." + route, leaves, data) else "no", ",".join(found) or "-")
' "$PROBE_PID" "$LAST_SEQ" "$WIN_OUT/state-$VARIANT-$tag" "$@")
    printf '%s\n' "$out"
}

# names POSITIONS: "0,2" as the names of those texts.
names() {
    local i out=""
    for i in $(printf '%s' "$1" | tr ',' ' '); do out="$out${out:+, }${NAMES[$i]}"; done
    printf '%s' "$out"
}

# turn: to landscape and back; Android saves the activity's state each time.
turn() {
    pva_rotate 1
    pva_rotate 0
}

# clean TAG ROUTE WHAT: after WHAT (turn, or home) the probe wrote at least one Bundle, and each
# names the route ROUTE and holds none of the texts typed so far.
clean() {
    local tag=$1 route=$2 out line seq found rc=0 count=0
    case "$3" in
        turn) turn ;;
        home)
            pv_shell input keyevent KEYCODE_HOME >/dev/null
            pv_sleep 3 ;;
    esac
    out=$(records "$tag" "$route" "${NEEDLES[@]}")
    while read -r line; do
        [ -n "$line" ] || continue
        count=$((count + 1))
        seq=$(printf '%s' "$line" | cut -d' ' -f1)
        LAST_SEQ=$seq
        found=$(printf '%s' "$line" | cut -d' ' -f6)
        echo "  saved state $seq: $(printf '%s' "$line" | cut -d' ' -f2) leaves, $(printf '%s' "$line" | cut -d' ' -f3) bytes, names the route $route: $(printf '%s' "$line" | cut -d' ' -f5), of the ${#NEEDLES[@]} texts typed so far it holds: $([ "$found" = - ] && echo none || names "$found")"
        [ "$(printf '%s' "$line" | cut -d' ' -f5)" = yes ] || { pv_log "saved state $seq does not name the route $route: it is not the screen's whole state"; rc=1; }
        [ "$found" = "-" ] || { pv_log "saved state $seq holds what was typed: $(names "$found") ($WIN_OUT/state-$VARIANT-$tag-$seq.txt)"; rc=1; }
    done <<<"$out"
    [ "$count" -ge 1 ] || { pv_log "the probe wrote no saved state"; rc=1; }
    return $rc
}

# typed ID TEXT: TEXT typed into the field ID, which then holds exactly TEXT (read as TEXT, or as
# that many mask characters): the keyboard did not change it.
typed() {
    local text mask
    pva_type "$1" "$2" || return 1
    text=$(pva_text "$1") || return 1
    mask=$(printf '%*s' "${#2}" '' | sed 's/ /•/g')
    [ "$text" = "$2" ] || [ "$text" = "$mask" ] || { pv_log "$1 does not hold what was typed (${#text} characters read, ${#2} typed)"; return 1; }
}

# generated: GENERATED becomes the password the generator's sheet shows, one without "/" (the
# reading of the screen writes a "|" as "/").
generated() {
    local i
    for i in 1 2 3 4 5 6; do
        GENERATED=$(pva_text pv.gen.preview) || return 1
        case "$GENERATED" in
            */* | "") pva_tap pv.gen.new || return 1; pv_sleep 1 ;;
            *) [ ${#GENERATED} -ge 8 ] && return 0 ;;
        esac
    done
    pv_log "no usable suggestion in the generator's sheet"
    return 1
}

# ---- the control: the helper's own screen under the same probe -------------------------------------
control() {
    local out line found rc=1
    probe_start .StateProbeSelf "$WIN_HELPER" || return 1
    win_control_start || return 1
    pva_type control_text "$CONTROL_TEXT" && pva_type control_password "$CONTROL_SECRET" || return 1
    turn
    out=$(VARIANT=control records control "" "$CONTROL_TEXT" "$CONTROL_SECRET")
    while read -r line; do
        [ -n "$line" ] || continue
        found=$(printf '%s' "$line" | cut -d' ' -f6)
        echo "  the control's saved state $(printf '%s' "$line" | cut -d' ' -f1): $(printf '%s' "$line" | cut -d' ' -f2) leaves, $(printf '%s' "$line" | cut -d' ' -f3) bytes, of the 2 texts typed it holds: $([ "$found" = "0,1" ] && echo both || echo "$found")"
        [ "$found" = "0,1" ] && rc=0
    done <<<"$out"
    [ $rc -eq 0 ] || pv_log "the probe did not find the control's typed texts in a saved state: ${out:-no saved state}"
    pv_shell am force-stop "$WIN_HELPER" >/dev/null 2>&1
    return $rc
}

app_under_probe() {
    pva_install "$APK" || return 1
    if pv_is_debuggable; then VARIANT=debug; else VARIANT=staging; fi
    pv_unlock >/dev/null 2>&1
    pva_rotate 0
    pv_shell logcat -b crash -c >/dev/null 2>&1
    probe_start .StateProbe "$PV_PACKAGE" || return 1
    pva_relaunch && pva_on Welcome 30 || return 1
    [ "$(pva_pid_of "$PV_PACKAGE")" = "$PROBE_PID" ] || { pv_log "the app is not running in the probe's process"; return 1; }
}

pv_step "control: the probe finds a platform field's typed text in the saved state of the helper's own screen" -- control || win_end
pv_step "the app installed afresh and started under the probe: Welcome" -- app_under_probe || win_end
echo "state.sh: the $VARIANT build"

# ---- setup -------------------------------------------------------------------------------------
typed_text "$WIN_MASTER" "the master password"
WIN_PIN_DIGITS=58291
typed_text "$WIN_PIN_DIGITS" "the PIN's digits"
pv_step "setup: the own master password typed" -- eval 'win_to_own && typed pv.setup.own "$WIN_MASTER"' || win_end
pv_step "state: setup, pv.setup.own typed, turned twice: the saved states hold nothing typed" -- clean own CreateMasterPassword turn
pv_step "setup: the password used: \"Write it down on paper\" shows it" -- eval 'win_use_own && win_reuse_no' || win_end
pv_step "state: setup, the password shown (pv.setup.chosen), turned twice: the saved states hold nothing typed" -- clean chosen WriteDown turn
pv_step "setup: the master password typed again" -- eval 'win_to_again && typed pv.setup.again "$WIN_MASTER"' || win_end
pv_step "state: setup, pv.setup.again typed, turned twice: the saved states hold nothing typed" -- clean again TypeAgain turn
pv_step "setup: the vault is made" -- win_create_vault || win_end

# ---- the list and the form ---------------------------------------------------------------------
typed_text "$WIN_QUERY" "the search"
pv_step "the list: a search typed" -- typed pv.list.search "$WIN_QUERY" || win_end
pv_step "state: the list, pv.list.search typed, turned twice: the saved states hold nothing typed" -- clean search Passwords turn
typed_text "$WIN_TITLE" "the form's name"
typed_text "$WIN_USER" "the form's username"
typed_text "$WIN_PASS" "the form's password"
typed_text "$WIN_SITE" "the form's website"
typed_text "$WIN_NOTE" "the form's notes"
pv_step "the form: name, username, password, website and notes typed" -- eval 'pva_hide_keyboard; pva_tap pv.list.searchClear && win_open_form && typed pv.edit.name "$WIN_TITLE" && typed pv.edit.username "$WIN_USER" && typed pv.edit.password "$WIN_PASS" && typed pv.edit.website.0 "$WIN_SITE" && typed pv.edit.notes "$WIN_NOTE"' || win_end
pv_step "state: the form, five fields typed, turned twice: the saved states hold nothing typed" -- clean form Edit turn
typed_text "$WIN_LABEL" "the dialog's label"
typed_text "$WIN_VALUE" "the dialog's secret value"
pv_step "the form's dialog: a label and a secret value typed" -- eval 'win_field_dialog && typed pv.edit.addField.label "$WIN_LABEL" && typed pv.edit.addField.value "$WIN_VALUE"' || win_end
pv_step "state: the form's dialog, both fields typed, turned twice: the saved states hold nothing typed" -- clean dialog Edit turn
GENERATED=""
pv_step "the field added; the generator's sheet shows a password" -- eval 'win_field_add && win_generator && generated' || win_end
typed_text "$GENERATED" "the generator's suggestion"
pv_step "state: the generator's sheet, a suggested password shown, turned twice: the saved states hold nothing typed" -- clean generator Edit turn
pv_step "state: the form with everything typed, left with Home: the saved state holds nothing typed" -- clean home Edit home

# ---- the lock screen ---------------------------------------------------------------------------
pv_step "the app in front again, the sheet and the form closed, locked, the master password typed" -- eval 'pva_relaunch && pva_need pv.gen.preview 20 && pv_shell input keyevent KEYCODE_BACK >/dev/null && win_close_form && win_lock && typed pv.lock.password "$WIN_MASTER"' || win_end
pv_step "state: the lock screen, pv.lock.password typed, turned twice: the saved states hold nothing typed" -- clean lock Locked turn

# ---- the PIN steps (since the device check of 4A) ----------------------------------------------
pv_step "the master password opens the app; Settings > \"Set a PIN\" asks for it again: typed" -- eval 'win_unlock && win_set_pin_prompt && typed pv.pin.mp.field "$WIN_MASTER"' || win_end
pv_step "state: the PIN steps' master password, pv.pin.mp.field typed, turned twice: the saved states hold nothing typed" -- clean pinmp ChangePin turn
pv_step "the master password accepted: the PIN steps' pad, five digits of a PIN typed" -- eval 'pva_hide_keyboard; pva_tap pv.pin.mp.next && pva_need pv.pin.key.0 60 && pva_pin "$WIN_PIN_DIGITS"' || win_end
pv_step "state: the PIN steps' pad, five digits typed, turned twice: the saved states hold nothing typed" -- clean pindigits ChangePin turn
win_end
