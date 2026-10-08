#!/usr/bin/env bash
# tools/emu/checks/3B.sh - the device check of sub-wave 3B (build-plan.md section 5, "Integration check
# for 3B"; section 12, "The integration in two steps", step 2): everything that check lists but the
# merge and tools/check.sh all, which the merge did before it tagged wave-3B-merged.
#
#   tools/emu/checks/3B.sh [PART...] [--shots DIR] [--keep] [-- GRADLE-ARGS...]
#   tools/emu/checks/3B.sh clip SERIAL [--apk FILE] [--shots DIR]
#   tools/emu/checks/3B.sh secure SERIAL [--apk FILE]
#
# The parts run in this order, all of them when none is named, on the integrator's devices (5584 is
# API 24, 5582 API 28, 5580 API 37): a device of this worktree that already runs on its port is used as
# it is, the others are booted together (boot.sh --integrator). The debug and staging APKs are built
# first (tools/gradle.sh). The three devices are stopped at the end unless --keep is given; the part
# "stop" does nothing else.
#   merged     the seven branches of round R1 (w3B-3.7 to w3B-3.12, w3B-carry) are in HEAD.
#   connected  adb.sh --connected on the three devices: every connected test, and every serial has a
#              passing report (BUILD SUCCESSFUL alone does not count). A class that failed is run once
#              more, alone, on that device; it counts as passed when it passes alone, and the step names
#              it as a flake. Then every device-test class of packages 2.5, 2.7 to 2.9, 3.3 to 3.9 and
#              w3B-carry ran on each device with no failure and at least the tests it has there
#              (@SdkSuppress leaves some out on API 24 and 28): 308 on API 24, 309 on 28, 313 on 37 at
#              wave-3B-merged.
#   skeleton   the skeleton steps 1 to 9 of checks/3A.sh (its skeleton mode) with the debug build on API
#              24, 28 and 37 at once, then with the staging build on API 24 and 37 at once. Step 9 is
#              smoke.sh, which has had the copy and the clear since sub-wave 3B.
#   clip       "clip SERIAL" (below) with the debug build on API 24, 28 and 37 at once; on API 37 the
#              screenshots go to --shots DIR (default /home/asus/pvwork/shots-3B; files named 3B-*.png,
#              nothing else there is touched).
#   smoke      smoke.sh staging on API 24 and 37 at once: set up, add, rotate, kill and come back,
#              lock, unlock; and since 3B the account opened, its password shown and copied (the clip
#              flagged, with a token; the status row), and the clip gone at its time with the app in
#              the background (setup's 1-minute delay).
#   secure     "secure SERIAL" with the staging build on API 24, 28 and 37 at once.
#   window     tools/emu/window/all.sh S24 S28 S37 with the debug and the staging APK: package 3.12's
#              checks on API 28 and 37 (keyboard, autofill, overlay, state, a11y; a KNOWN line on API 28
#              is a shown secret that lock.md 2.3 accepts before Android 14), a11y-old.sh on API 24.
#   extras     beyond the plan's list, the packages' own scripts on the merged app, with the debug build
#              on API 24, 28 and 37 at once: clipboard/clear.sh (3.8), shell/walk.sh (2.5; the account's
#              screen since iface-64), setup/create.sh --vault-file (3.3; the masked fields since 3.12),
#              session/autolock.sh (3.2); then walk.sh with the staging build on API 37.
#
#   clip SERIAL     (a debug build: it needs run-as and screencap) installs afresh, sets up a vault, adds
#                   an account with a password and a note, and sets the clipboard delay to 30 s in
#                   pv_settings as clipboard/clear.sh does (the Settings screen comes with 4.3). Then:
#                   1. the account opened from the list: its password drawn as dots; no node holds it;
#                   2. "Show password": the value is the password;
#                   3. "Show in big letters": one box per character, each with its description;
#                   4. Copy: the clip carries the sensitive flag, a 32-digit token, an empty label and
#                      the password; its clear is planned 30 s after the copy (pv_settings); the status
#                      row tells TalkBack "Password copied" and 30 seconds, and its countdown changes
#                      from second to second (three screenshots 1.1 s apart);
#                   5. Back: the list shows the row, still counting down;
#                   6. Home: the clip is still there 2.5 s before its time and gone within 3 s after it;
#                   7. a word of the note selected with a long press and copied with the floating
#                      toolbar's Copy: the clip carries the flag and a token and holds the word, and the
#                      row says "Copied" (selected text, not a password);
#                   8. iface-66: in the edit form's "Add field" dialog with "Hide this value" off, a value
#                      selected and copied with the toolbar's Copy goes through SecretClipboard: flagged,
#                      with a token; the field still holds it; after Cancel the row says "Copied";
#                   and the app did not crash. The floating text toolbar is in no accessibility tree on
#                   these images (Android 7 and 9 report no window for it; on Android 17 the system draws
#                   it into a SurfaceView), so its words are found in screenshots: the band that changes
#                   when the toolbar appears, and the words drawn in it. Copy is the first word of a
#                   read-only selection's toolbar; in a text field's toolbar it is the word drawn like it.
#   secure SERIAL   (a staging build) installs afresh, sets up a vault, adds an account and shows its
#                   password; the app's window has FLAG_SECURE; `adb exec-out screencap -p` gives a
#                   picture whose app area is black, while the emulator's own screenshot (`emu
#                   screenrecord screenshot`) of the same screen is not (the control; kept in the logs).
#
# Prints PASS, FAIL or SKIPPED per step and a summary; logs in build/emu-checks/3B/. Exit 0 when no step
# failed, 1 otherwise, 2 for a usage error, 3 for a refused serial. Written by the device check of
# sub-wave 3B (round R2 of build-plan.md section 12).
set -u
EMU="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$EMU/../.." && pwd)"
source "$EMU/adb.sh"
source "$EMU/app.sh"
source "$EMU/clipboard/lib.sh"

SHOTS=/home/asus/pvwork/shots-3B
LOGS_ROOT=$REPO/build/emu-checks/3B
DEBUG_APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
STAGING_APK=$REPO/app/build/outputs/apk/staging/app-staging.apk
RESULTS=$REPO/app/build/outputs/androidTest-results/connected/debug

# A string of the app's English resources: FILE NAME.
res_string() { sed -n "s:.*<string name=\"$2\">\(.*\)</string>.*:\1:p" "$REPO/app/src/main/res/values/$1.xml" | head -n 1; }
COPIED_PASSWORD=$(res_string strings_clipboard clipboard_copied_password) # Password copied
COPIED_OTHER=$(res_string strings_clipboard clipboard_copied_other)       # Copied

note() { echo "     $*"; }

# ---- the clipboard, read from outside the app (clipboard/lib.sh) ----------------------------------
clip_read() {
    local d
    d=$(pvc_describe) || { pv_log "the clipboard could not be read"; return 1; }
    CLIP_NOW=$(printf '%s\n' "$d" | pvc_field now)
    CLIP_HAS=$(printf '%s\n' "$d" | pvc_field has)
    CLIP_TOKEN=$(printf '%s\n' "$d" | pvc_field token)
    CLIP_LABEL=$(printf '%s\n' "$d" | pvc_field label)
    CLIP_SENSITIVE=$(printf '%s\n' "$d" | pvc_field sensitive)
    [ -n "$CLIP_NOW" ] && [ -n "$CLIP_HAS" ]
}

clip_says() { echo "has=$CLIP_HAS token=${CLIP_TOKEN:-none} sensitive=${CLIP_SENSITIVE:-absent} label='$CLIP_LABEL'"; }

clip_text() { pvc_get | pvc_field text; }

# A new clip of the app's after the tap that began after BEFORE (the token before it): sets TOKEN and
# COPIED_AT (the device's clock at the first reading that saw it).
await_new_clip() { # BEFORE-TOKEN
    local i
    for i in $(seq 1 20); do
        clip_read || return 1
        if [ -n "$CLIP_TOKEN" ] && [ "$CLIP_TOKEN" != "$1" ]; then
            TOKEN=$CLIP_TOKEN
            COPIED_AT=$CLIP_NOW
            return 0
        fi
        pv_sleep 0.2
    done
    pv_log "no new clip of the app's: $(clip_says)"
    return 1
}

# The clip just made is the app's secret clip: the sensitive flag and a 32-digit token.
clip_is_flagged() {
    printf '%s' "$TOKEN" | grep -Eq '^[0-9a-f]{32}$' || { pv_log "the token is not 32 hex digits: '$TOKEN'"; return 1; }
    [ "$CLIP_SENSITIVE" = true ] || { pv_log "the sensitive flag is '${CLIP_SENSITIVE:-absent}'"; return 1; }
    note "the clip: sensitive=$CLIP_SENSITIVE token=$TOKEN label='$CLIP_LABEL'"
}

# Nothing is on the clipboard: no clip at all from API 28 on; below it, where a clear can only put an
# empty clip, a clip without a label, a token or a text (as clipboard/clear.sh).
cleared() {
    local text
    clip_read || return 1
    if [ "$(pv_api)" -ge 28 ]; then
        [ "$CLIP_HAS" = false ] || { pv_log "the clipboard is not empty: $(clip_says)"; return 1; }
        return 0
    fi
    text=$(clip_text) || return 1
    [ -z "$CLIP_TOKEN" ] && [ -z "$CLIP_LABEL" ] && [ -z "$text" ] || { pv_log "the clipboard is not empty: $(clip_says), ${#text} characters"; return 1; }
}

until_device() { # MS: returns when the device's clock reads MS
    local left
    clip_read || return 1
    left=$(($1 - CLIP_NOW))
    [ "$left" -le 0 ] || pv_sleep "$((left / 1000)).$(printf '%03d' $((left % 1000)))"
}

# ---- one device: the account's screen and the clipboard (clip SERIAL) -----------------------------
ACCOUNT=Clipbank
LOGIN=dilnoza.clip@example.uz
SECRET=Qm7Xc3Lp9Vt2Hs5
WEBSITE=https://clip.example.uz/login
NOTE="Branch near Chorsu bazaar"
FIELD_VALUE=Dialogvalue7
PREFS=shared_prefs/pv_settings.xml
TOKEN=""
COPIED_AT=0
DEADLINE=0

prefs() { pv_shell run-as "$PV_PACKAGE" cat "$PREFS" 2>/dev/null | tr -d '\r'; }
prefs_token() { prefs | sed -n 's/.*<string name="pending_clip_token">\([^<]*\)<.*/\1/p'; }
prefs_deadline() { prefs | sed -n 's/.*<long name="pending_clip_deadline_ms" value="\([0-9]*\)".*/\1/p'; }

# node_bounds ID: "left top right bottom" of the node whose id is ID, in any window (pv_ui_nodes_all);
# waits up to 5 s for it.
node_bounds() {
    local deadline=$((SECONDS + 5)) line
    while :; do
        line=$(pv_ui_nodes_all | awk -F'|' -v id="$1" '$3 == id { print; exit }')
        if [ -n "$line" ]; then
            printf '%s\n' "$line" | cut -d'|' -f8-11 | tr '|' ' '
            return 0
        fi
        [ $SECONDS -lt $deadline ] || { pv_log "no node $1 in any window; ids: $(pva_ids)"; return 1; }
        pv_sleep 0.5
    done
}

shot() { # NAME: the screen as SHOT_DIR/NAME.png (API 37 only; nothing without --shots)
    [ -n "$SHOT_DIR" ] || return 0
    pva_screenshot "$SHOT_DIR/$1.png" && note "screenshot $SHOT_DIR/$1.png"
}

capture() { pv_adb exec-out screencap -p > "$1" 2>/dev/null && [ -s "$1" ]; }

clip_prepare() {
    pva_install "$APK" || return 1
    pvc_clear
    pva_start && pva_set_up || return 1
    pva_add_account "$ACCOUNT" "$LOGIN" "$SECRET" "$WEBSITE" "$NOTE" || return 1
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords 20
}

# "Clear copied text after" through the settings file, as clipboard/clear.sh's set_delay: the app
# reads it once per process, so it is stopped, edited and started again (then locked: unlocked here).
delay_30s() {
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null
    pv_shell "run-as $PV_PACKAGE sed -i 's#<string name=\"clip_delay\">[^<]*</string>#<string name=\"clip_delay\">30s</string>#' $PREFS" || return 1
    prefs | grep -q '<string name="clip_delay">30s</string>' || { pv_log "$PREFS: $(prefs | tr -d '\n')"; return 1; }
    pva_relaunch && pva_unlock Passwords
}

# to_entry: the app in front, unlocked, on the account's screen.
to_entry() {
    local i nodes row
    pv_unlock >/dev/null 2>&1
    pva_in_front || pva_relaunch || return 1
    for i in 1 2 3 4 5 6 7 8 9 10; do
        nodes=$(pv_ui_nodes)
        if printf '%s\n' "$nodes" | awk -F'|' '$3 == "pv.route.Locked" { f = 1 } END { exit !f }'; then
            pva_unlock Passwords || return 1
            continue
        fi
        if printf '%s\n' "$nodes" | awk -F'|' '$3 == "pv.route.Entry" { f = 1 } END { exit !f }'; then
            return 0
        fi
        row=$(printf '%s\n' "$nodes" | awk -F'|' '$3 ~ /^pv[.]list[.]row[.]/ { print $3; exit }')
        if [ -n "$row" ]; then
            pva_tap "$row" && pva_on Entry 20 && return 0
        fi
        pv_sleep 1
    done
    pv_log "the account's screen is not shown; ids: $(pva_ids)"
    return 1
}

open_account() {
    local title
    to_entry || return 1
    title=$(pva_text pv.entry.title) || return 1
    [ "$title" = "$ACCOUNT" ] || { pv_log "the screen's title is '$title', not '$ACCOUNT'"; return 1; }
}

password_hidden() {
    local nodes value
    nodes=$(pv_ui_nodes)
    value=$(printf '%s\n' "$nodes" | awk -F'|' '$3 == "pv.entry.password.value"')
    [ -n "$value" ] || { pv_log "no pv.entry.password.value; ids: $(pva_ids)"; return 1; }
    note "the hidden value's node: $(printf '%s' "$value" | cut -d'|' -f1-3)"
    case "$(printf '%s' "$value" | cut -d'|' -f1)" in
        "" | •*) ;;
        *) pv_log "the hidden value's node has a text"; return 1 ;;
    esac
    if printf '%s\n' "$nodes" | grep -qF -- "$SECRET"; then pv_log "a node holds the password while it is hidden"; return 1; fi
    shot 3B-entry-password-hidden
}

password_shown() {
    local deadline=$((SECONDS + 10)) text=""
    pva_tap pv.entry.password.show || return 1
    while :; do
        text=$(pva_text pv.entry.password.value 2>/dev/null)
        [ "$text" = "$SECRET" ] && break
        [ $SECONDS -lt $deadline ] || { pv_log "pv.entry.password.value reads '$text'"; return 1; }
        pv_sleep 0.5
    done
    shot 3B-entry-password-shown
}

big_letters() {
    local nodes n described
    pva_tap pv.entry.password.big && pva_on BigLetters 20 || return 1
    nodes=$(pv_ui_nodes)
    n=$(printf '%s\n' "$nodes" | awk -F'|' '$3 ~ /^pv[.]big[.]cell[.][0-9]+$/' | wc -l)
    described=$(printf '%s\n' "$nodes" | awk -F'|' '$3 ~ /^pv[.]big[.]cell[.][0-9]+$/ && $2 != ""' | wc -l)
    note "$n boxes, $described with a description; the first: $(printf '%s\n' "$nodes" | awk -F'|' '$3 == "pv.big.cell.0" { print $2 }')"
    [ "$n" -eq ${#SECRET} ] && [ "$described" -eq ${#SECRET} ] || { pv_log "${#SECRET} characters, $n boxes, $described described"; return 1; }
    shot 3B-big-letters
    pva_tap pv.big.close && pva_on Entry 20
}

# row_counts_down WHAT NAME: the status row is shown; it tells TalkBack WHAT ("Password copied") and
# the 30-second delay; and its pixels change from one screenshot to the next, 1.1 s apart, three times
# (the countdown; the row's text is for the eyes only, TalkBack hears the announcement). From Android
# 13 on the system shows its own clipboard overlay for a few seconds after a copy, over the left of the
# row, where a short text such as "Copied text" ends with its countdown: a round without a change is
# tried again 3 s later, up to three rounds. The screenshots are kept as LOGS/SERIAL-NAME-N.png, the
# row cut out as ...-row-N.png.
row_counts_down() {
    local what=$1 name=$2 nodes line desc box round
    nodes=$(pv_ui_nodes_all) || { pv_log "the windows could not be read"; return 1; }
    line=$(printf '%s\n' "$nodes" | awk -F'|' '$3 == "pv.clip.row" { print; exit }')
    [ -n "$line" ] || { pv_log "no status row (pv.clip.row); ids: $(pva_ids)"; return 1; }
    desc=$(printf '%s\n' "$nodes" | awk -F'|' '$3 == "pv.clip.row" { on = 1; next } on && $2 != "" { print $2; exit }')
    note "the row tells TalkBack: $desc"
    case "$desc" in "$what. "*) ;; *) pv_log "the row's announcement does not begin with '$what. '"; return 1 ;; esac
    case "$desc" in *30*second*) ;; *) pv_log "the row's announcement does not name 30 seconds"; return 1 ;; esac
    box=$(printf '%s' "$line" | cut -d'|' -f8-11 | tr '|' ' ')
    for round in 1 2 3; do
        [ "$round" = 1 ] || pv_sleep 3
        # shellcheck disable=SC2086
        three_pictures_differ "$LOGS/$PV_SERIAL-$name" $box && return 0
    done
    pv_log "the row did not change from second to second in three rounds"
    return 1
}

three_pictures_differ() { # BASE LEFT TOP RIGHT BOTTOM
    local i
    for i in 1 2 3; do
        capture "$1-$i.png" || { pv_log "no screenshot"; return 1; }
        [ "$i" = 3 ] || pv_sleep 1.1
    done
    python3 - "$@" <<'EOF'
import sys
from PIL import Image, ImageChops
base, box = sys.argv[1], tuple(int(v) for v in sys.argv[2:6])
crops = [Image.open(f"{base}-{i}.png").convert("RGB").crop(box) for i in (1, 2, 3)]
for i, c in enumerate(crops, 1):
    c.save(f"{base}-row-{i}.png")
changed = [ImageChops.difference(crops[i], crops[i + 1]).getbbox() for i in (0, 1)]
print(f"     the row {box}: changed between the screenshots 1 and 2 in {changed[0]}, 2 and 3 in {changed[1]}")
sys.exit(0 if all(changed) else 1)
EOF
}

copy_password() {
    local before deadline="" i delay
    clip_read || return 1
    before=$CLIP_TOKEN
    pva_tap pv.entry.password.copy || return 1
    await_new_clip "$before" && clip_is_flagged || return 1
    [ -z "$CLIP_LABEL" ] || { pv_log "the clip's label is '$CLIP_LABEL', not empty"; return 1; }
    for i in 1 2 3 4 5 6 7 8 9 10; do
        if [ "$(prefs_token)" = "$TOKEN" ]; then deadline=$(prefs_deadline); [ -z "$deadline" ] || break; fi
        pv_sleep 0.3
    done
    [ -n "$deadline" ] || { pv_log "pv_settings has not the clip's token and deadline: $(prefs | grep pending_clip | tr -d '\n')"; return 1; }
    DEADLINE=$deadline
    delay=$((DEADLINE - COPIED_AT))
    note "the clear is planned $delay ms after the first reading that saw the clip"
    [ "$delay" -ge 28500 ] && [ "$delay" -le 30500 ] || { pv_log "the clear is not planned 30 s after the copy"; return 1; }
    row_counts_down "$COPIED_PASSWORD" entry-copied || return 1
    shot 3B-entry-password-copied
}

row_on_the_list() {
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    pva_on Passwords 20 || return 1
    row_counts_down "$COPIED_PASSWORD" list-row || return 1
    if [ -n "$SHOT_DIR" ]; then
        cp "$LOGS/$PV_SERIAL-list-row-1.png" "$SHOT_DIR/3B-list-copy-row-1.png" && cp "$LOGS/$PV_SERIAL-list-row-3.png" "$SHOT_DIR/3B-list-copy-row-2.png" \
            && note "screenshots $SHOT_DIR/3B-list-copy-row-1.png and -2.png, 2.2 s apart"
    fi
}

clip_holds_the_password() {
    local text
    text=$(clip_text) || return 1
    [ "$text" = "$SECRET" ] || { pv_log "the clip does not hold the password (${#text} characters)"; return 1; }
}

gone_in_the_background() {
    local i
    pv_shell input keyevent KEYCODE_HOME >/dev/null || return 1
    for i in 1 2 3 4 5 6 7 8 9 10; do pva_in_front || break; pv_sleep 0.3; done
    pva_in_front && { pv_log "the app is still in front after Home"; return 1; }
    until_device $((DEADLINE - 2500)) || return 1
    clip_read || return 1
    [ "$CLIP_NOW" -lt $((DEADLINE - 500)) ] || { pv_log "too late to look before the clear"; return 1; }
    [ "$CLIP_TOKEN" = "$TOKEN" ] || { pv_log "$((DEADLINE - CLIP_NOW)) ms before its time the clip is gone already: $(clip_says)"; return 1; }
    while :; do
        clip_read || return 1
        [ "$CLIP_TOKEN" = "$TOKEN" ] || break
        [ "$CLIP_NOW" -lt $((DEADLINE + 3000)) ] || { pv_log "the clip is still there $((CLIP_NOW - DEADLINE)) ms after its time"; return 1; }
        pv_sleep 0.1
    done
    note "the clip was gone at the reading $((CLIP_NOW - DEADLINE)) ms after its planned clear, $((CLIP_NOW - COPIED_AT)) ms after the copy was first seen"
    cleared
}

# toolbar_word MODE BEFORE AFTER Y DEBUG [MASK]: the floating text toolbar that appeared between the
# screenshots BEFORE and AFTER over or under the line at Y; prints "TAP x y" for its word: with MODE
# "first" the first word (its ink saved as MASK), otherwise MODE is a MASK file and the word drawn
# like it is taken. DEBUG gets the band with the words framed.
toolbar_word() {
    python3 - "$@" <<'EOF'
import sys
from PIL import Image, ImageDraw

def lum(p):
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]

def differ(p, q):
    return abs(p[0] - q[0]) > 4 or abs(p[1] - q[1]) > 4 or abs(p[2] - q[2]) > 4

mode, before, after, line_y, debug = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
mask_out = sys.argv[6] if len(sys.argv) > 6 else ""
b_img, a_img = Image.open(before).convert("RGB"), Image.open(after).convert("RGB")
W, H = a_img.size
bp, ap = b_img.load(), a_img.load()

def changed_in_row(y):
    return sum(2 for x in range(0, W, 2) if differ(bp[x, y], ap[x, y]))

def bands(rows):
    out, cur, gap = [], None, 0
    for y in rows:
        if changed_in_row(y) >= 160:
            cur = [min(cur[0], y), max(cur[1], y)] if cur else [y, y]
            gap = 0
        elif cur:
            gap += 1
            if gap > 3:
                out.append(cur); cur = None; gap = 0
    if cur:
        out.append(cur)
    return [r for r in out if r[1] - r[0] >= 70]

# The toolbar stands above the selected line (which reaches about 30 px above Y), or below its
# handles when there is no room above.
found = bands(range(line_y - 35, max(0, line_y - 420), -1)) or bands(range(min(H - 1, line_y + 110), min(H, line_y + 520)))
if not found:
    print("ERROR no toolbar appeared near the line"); sys.exit(1)
y0, y1 = found[0]
need = (y1 - y0 + 1) * 0.5
cols = [sum(2 for y in range(y0, y1 + 1, 2) if differ(bp[x, y], ap[x, y])) >= need for x in range(W)]
pill, cur = None, None
for x, on in enumerate(cols + [False]):
    if on:
        cur = [cur[0], x] if cur else [x, x]
    elif cur:
        if not pill or cur[1] - cur[0] > pill[1] - pill[0]:
            pill = cur
        cur = None
if not pill:
    print("ERROR no toolbar columns"); sys.exit(1)
x0, x1 = pill
h = y1 - y0
ry0, ry1 = y0 + h // 5, y1 - h // 5

def ink(x, y):  # a dark pixel the toolbar brought
    return lum(ap[x, y]) < 120 and differ(ap[x, y], bp[x, y])

spans, cur, gap = [], None, 0
for x in range(x0, x1 + 1):
    if any(ink(x, y) for y in range(ry0, ry1 + 1)):
        if cur and gap <= 22:
            cur[1] = x
        else:
            if cur:
                spans.append(cur)
            cur = [x, x]
        gap = 0
    else:
        gap += 1
if cur:
    spans.append(cur)
words = []
for sx0, sx1 in spans:
    if sx1 - sx0 < 14 or sx0 - x0 < 10 or x1 - sx1 < 10:
        continue  # the pill's rounded ends and shadow, not a word
    ys = [y for y in range(ry0, ry1 + 1) if any(ink(x, y) for x in range(sx0, sx1 + 1))]
    words.append((sx0, min(ys), sx1, max(ys)))
im = a_img.copy()
d = ImageDraw.Draw(im)
d.rectangle((x0, y0, x1, y1), outline=(255, 0, 0), width=3)
for w in words:
    d.rectangle(w, outline=(0, 160, 0), width=2)
im.crop((0, max(0, y0 - 60), W, min(H, y1 + 60))).save(debug)
print(f"toolbar rows {y0}-{y1}, columns {x0}-{x1}, words at " + " ".join(f"{w[0]}-{w[2]}" for w in words))
if not words:
    print("ERROR no word in the toolbar"); sys.exit(1)

def mask(box):
    bx0, by0, bx1, by1 = box
    return [[1 if ink(x, y) else 0 for x in range(bx0, bx1 + 1)] for y in range(by0, by1 + 1)]

def iou(a, b):
    hh, ww = max(len(a), len(b)), max(len(a[0]), len(b[0]))
    inter = union = 0
    for y in range(hh):
        for x in range(ww):
            va = a[y][x] if y < len(a) and x < len(a[0]) else 0
            vb = b[y][x] if y < len(b) and x < len(b[0]) else 0
            inter += va & vb
            union += va | vb
    return inter / union if union else 0.0

if mode == "first":
    word = words[0]
    if mask_out:
        m = mask(word)
        out = Image.new("L", (len(m[0]), len(m)))
        out.putdata([255 * v for row in m for v in row])
        out.save(mask_out)
else:
    t_img = Image.open(mode).convert("L")
    t = [[1 if t_img.getpixel((x, y)) > 127 else 0 for x in range(t_img.size[0])] for y in range(t_img.size[1])]
    word, score = None, 0.0
    for w in words:
        m = mask(w)
        if abs(len(m[0]) - len(t[0])) > 6 or abs(len(m) - len(t)) > 6:
            continue
        s = iou(m, t)
        if s > score:
            word, score = w, s
    print(f"the best match has {score:.2f} of its ink in common with the template")
    if not word or score < 0.6:
        print("ERROR no word is drawn like the template"); sys.exit(1)
print(f"TAP {(word[0] + word[2]) // 2} {(word[1] + word[3]) // 2}")
EOF
}

# press_and_copy NAME X Y MODE: a long press at X Y selects a word; the toolbar's word for MODE
# (toolbar_word) is tapped; a new clip of the app's must follow (await_new_clip).
press_and_copy() {
    local name=$1 x=$2 y=$3 mode=$4 out tap before
    clip_read || return 1
    before=$CLIP_TOKEN
    capture "$LOGS/$PV_SERIAL-$name-before.png" || return 1
    pv_shell input swipe "$x" "$y" "$x" "$y" 1200 >/dev/null
    pv_sleep 1.5
    capture "$LOGS/$PV_SERIAL-$name-toolbar.png" || return 1
    out=$(toolbar_word "$mode" "$LOGS/$PV_SERIAL-$name-before.png" "$LOGS/$PV_SERIAL-$name-toolbar.png" "$y" \
        "$LOGS/$PV_SERIAL-$name-toolbar-words.png" "$LOGS/$PV_SERIAL-copy-word.png" 2>&1)
    printf '%s\n' "$out" | grep -v '^TAP ' | sed 's/^/     /'
    tap=$(printf '%s\n' "$out" | sed -n 's/^TAP //p')
    [ -n "$tap" ] || { pv_log "the toolbar's Copy was not found (see $LOGS/$PV_SERIAL-$name-toolbar.png)"; return 1; }
    # shellcheck disable=SC2086
    pv_shell input tap $tap >/dev/null
    await_new_clip "$before" && clip_is_flagged
}

note_copied() {
    local bounds l t r b text
    to_entry || return 1
    bounds=$(node_bounds pv.entry.notes.value) || return 1
    read -r l t r b <<< "$bounds"
    # The first word: a read-only selection's toolbar begins with Copy on every API level.
    press_and_copy note $((l + 40)) $(((t + b) / 2)) first || return 1
    # The row first: reading the clip's text makes Android 12 and later show a notice over it.
    row_counts_down "$COPIED_OTHER" note-copied || return 1
    shot 3B-note-copied
    text=$(clip_text) || return 1
    note "the clip holds '$text'"
    [ -n "$text" ] && case " $NOTE " in *" $text "*) true ;; *) false ;; esac || { pv_log "the clip does not hold a word of the note"; return 1; }
}

# iface-66: a copy in one of the app's dialogs goes through SecretClipboard (VaultWindow wraps every
# dialog's content in ClipboardWindow).
dialog_copied() {
    local bounds l t r b text
    [ -f "$LOGS/$PV_SERIAL-copy-word.png" ] || { pv_log "no picture of the word Copy from the note's toolbar"; return 1; }
    to_entry || return 1
    pva_tap pv.entry.edit && pva_on Edit 20 || return 1
    pva_tap pv.edit.addField && pva_need pv.edit.addField.hide 10 || return 1
    pva_tap pv.edit.addField.hide || return 1
    pv_sleep 1
    pva_type pv.edit.addField.value "$FIELD_VALUE" || return 1
    text=$(pva_text pv.edit.addField.value) || return 1
    [ "$text" = "$FIELD_VALUE" ] || { pv_log "the value field reads '$text' (still hidden?)"; return 1; }
    bounds=$(node_bounds pv.edit.addField.value) || return 1
    read -r l t r b <<< "$bounds"
    press_and_copy dialog $((l + 100)) $(((t + b) / 2 + 10)) "$LOGS/$PV_SERIAL-copy-word.png" || return 1
    text=$(pva_text pv.edit.addField.value) || return 1
    [ "$text" = "$FIELD_VALUE" ] || { pv_log "after Copy the field reads '$text'"; return 1; }
    pva_hide_keyboard
    pva_tap pv.edit.dialog.no || return 1
    pv_sleep 1
    pva_absent pv.edit.addField.hide || { pv_log "the dialog is still open"; return 1; }
    row_counts_down "$COPIED_OTHER" dialog-copied || return 1
    shot 3B-dialog-copied-row
    text=$(clip_text) || return 1
    note "the clip holds '$text'"
    [ "$text" = "$FIELD_VALUE" ] || { pv_log "the clip does not hold the dialog's value"; return 1; }
    pv_shell input keyevent KEYCODE_BACK >/dev/null
    if pva_present pv.edit.dialog.discard 3; then pva_tap pv.edit.dialog.discard || return 1; fi
    pva_on Entry 20
}

run_clip() {
    pv_use "$1"
    pva_init
    PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
    echo "clip: $PV_SERIAL (API $(pv_api)): $APK"
    pv_step "install afresh; set up; an account with a password and a note" -- clip_prepare || { pv_summary; return 1; }
    pv_step "the clipboard delay set to 30 s (pv_settings; a new process, unlocked)" needs-debuggable -- delay_30s || { pv_summary; return 1; }
    pv_step "1. the account from the list: its password drawn as dots, in no node" -- eval 'open_account && password_hidden'
    pv_step "2. Show password: the value is the password" -- password_shown
    pv_step "3. Show in big letters: one described box per character; Close" -- big_letters
    pv_step "4. Copy: flagged, a 32-digit token, cleared 30 s later as planned; the row says Password copied and counts down" -- copy_password || { pv_summary; return 1; }
    pv_step "5. Back: the list shows the row, counting down" -- row_on_the_list
    pv_step "4. the clip holds the password and has no label" -- clip_holds_the_password
    pv_step "6. Home: the clip is there 2.5 s before its time and gone within 3 s after it" -- gone_in_the_background
    pv_step "7. a word of the note selected and copied with the toolbar's Copy: flagged, with a token; the row says Copied" -- note_copied
    pv_step "8. iface-66: the Add field dialog's value copied with the toolbar's Copy: flagged, with a token; not cut" -- dialog_copied
    pv_step "the app did not crash" -- pva_no_crash
    pv_shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    pv_summary
}

# ---- one device: the staging build's secure window (secure SERIAL) --------------------------------
SECURE_PASSWORD=Vb6Np2Kx8Rz4Qt7

secure_prepare() {
    pva_install "$APK" && pva_start && pva_set_up || return 1
    pva_add_account Securebank secure.user@example.uz "$SECURE_PASSWORD" https://secure.example.uz "Secure note" || return 1
    pva_tap pv.entry.password.show || return 1
    local deadline=$((SECONDS + 10))
    until [ "$(pva_text pv.entry.password.value 2>/dev/null)" = "$SECURE_PASSWORD" ]; do
        [ $SECONDS -lt $deadline ] || { pv_log "the password is not shown"; return 1; }
        pv_sleep 0.5
    done
}

# The app's window has FLAG_SECURE (0x2000): its flags (fl=) are named from Android 9 on, a hex mask
# on Android 7.
window_is_secure() {
    local flags mask
    flags=$(pv_shell dumpsys window windows 2>/dev/null | tr -d '\r' \
        | awk -v w="$PV_PACKAGE/$PV_PACKAGE.MainActivity" '
            / Window #[0-9]+ Window\{/ { on = index($0, w) > 0; next }
            on && / fl=/ { sub(/.* fl=/, ""); print; exit }')
    [ -n "$flags" ] || { pv_log "no flags of the app's window in dumpsys window"; return 1; }
    note "the app's window: fl=$(printf '%s' "$flags" | cut -c1-120)"
    case " $flags " in *" SECURE "*) return 0 ;; esac
    mask=$(printf '%s' "$flags" | sed -n 's/^#\([0-9a-f]*\).*/\1/p')
    [ -n "$mask" ] && [ $((0x$mask & 0x2000)) -ne 0 ] || { pv_log "no FLAG_SECURE on the app's window"; return 1; }
}

# black_share FILE: the share of black pixels between 10 % and 90 % of the picture's height (the app's
# area, without the system's bars).
black_share() {
    python3 - "$1" <<'EOF'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
w, h = im.size
px = im.load()
total = black = 0
for y in range(h // 10, h * 9 // 10, 3):
    for x in range(0, w, 3):
        r, g, b = px[x, y]
        total += 1
        black += r <= 8 and g <= 8 and b <= 8
print(f"{black / total:.4f}")
EOF
}

# screencap shows nothing of the app: on Android 7 and 17 its picture is black where the app is; on
# Android 9 screencap refuses while a secure window is shown (exit 1, no picture).
screencap_is_black() {
    local file=$LOGS/$PV_SERIAL-staging-screencap.png share
    pva_in_front || { pv_log "the app is not in front: $(pv_focus)"; return 1; }
    pv_adb exec-out screencap -p > "$file" 2> /dev/null
    if [ ! -s "$file" ]; then
        note "screencap wrote no picture: $(pv_shell 'screencap -p /data/local/tmp/pv-secure.png > /dev/null 2>&1; echo exit $?; rm -f /data/local/tmp/pv-secure.png' | tr -d '\r' | tr '\n' ' ')"
        return 0
    fi
    share=$(black_share "$file") || return 1
    note "screencap: $share of the app's area is black ($file)"
    python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) >= 0.999 else 1)" "$share"
}

emulator_shot_is_not_black() {
    local dir=$LOGS/$PV_SERIAL-emulator-shot file share
    rm -rf "$dir"
    mkdir -p "$dir"
    pv_adb emu screenrecord screenshot "$dir" > /dev/null 2>&1
    file=$(ls -t "$dir"/*.png 2>/dev/null | head -n 1)
    [ -n "$file" ] || { pv_log "emu screenrecord screenshot wrote no picture"; return 1; }
    share=$(black_share "$file") || return 1
    note "the emulator's screenshot: $share of the app's area is black ($file)"
    python3 -c "import sys; sys.exit(0 if float(sys.argv[1]) < 0.5 else 1)" "$share"
}

run_secure() {
    pv_use "$1"
    pva_init
    PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
    echo "secure: $PV_SERIAL (API $(pv_api)): $APK"
    pv_step "install afresh; set up; an account, its password shown" -- secure_prepare || { pv_summary; return 1; }
    pv_step "the app's window has FLAG_SECURE" -- window_is_secure
    pv_step "adb exec-out screencap -p: nothing of the app (its area black, or no picture)" -- screencap_is_black
    pv_step "the control: the emulator's own screenshot of the same screen is not black" -- emulator_shot_is_not_black
    pv_step "the app did not crash" -- pva_no_crash
    pv_shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    pv_summary
}

# ---- the whole check ---------------------------------------------------------------------------------
BRANCHES=(w3B-3.7 w3B-3.8 w3B-3.9 w3B-3.10 w3B-3.11 w3B-3.12 w3B-carry)
APIS=(24 28 37)
declare -A SER=()

branches_merged() {
    local b bad=0
    for b in "${BRANCHES[@]}"; do
        if ! git -C "$REPO" rev-parse --verify -q "refs/heads/$b" > /dev/null; then
            echo "   $b: no such branch"
            bad=1
        elif git -C "$REPO" merge-base --is-ancestor "$b" HEAD; then
            echo "   $b: $(git -C "$REPO" rev-parse --short "$b") is in HEAD"
        else
            echo "   $b: $(git -C "$REPO" rev-parse --short "$b") is not merged"
            bad=1
        fi
    done
    return $bad
}

build_apks() {
    "$REPO/tools/gradle.sh" :app:assembleDebug :app:assembleStaging ${gradle_args[@]+"${gradle_args[@]}"} > "$LOGS_ROOT/assemble.log" 2>&1
    local rc=$?
    grep -E '^BUILD' "$LOGS_ROOT/assemble.log" | sed 's/^/   /'
    return $rc
}

# The integrator's devices: one of this worktree that runs on its port is used, the others booted.
devices() {
    local api port entry rc=0 status
    declare -A pids=()
    for api in "${APIS[@]}"; do
        port=$(pv_integrator_port "$api")
        entry=$PV_REG/emulator-$port
        if [ -f "$entry" ] && [ "$(pv_entry_get "$entry" owner)" = "$(pv_owner)" ] && pv_entry_alive "$entry" \
            && PV_SERIAL=emulator-$port pv_wait_device "emulator-$port" 30; then
            SER[$api]=emulator-$port
            echo "   API $api: emulator-$port, running already"
            continue
        fi
        "$EMU/boot.sh" --integrator "$api" > "$LOGS_ROOT/serial-$api" 2> "$LOGS_ROOT/boot-$api.log" &
        pids[$api]=$!
    done
    for api in "${!pids[@]}"; do
        wait "${pids[$api]}"
        status=$?
        SER[$api]=$(cat "$LOGS_ROOT/serial-$api" 2> /dev/null)
        echo "   API $api: ${SER[$api]:-no device} (boot.sh exit $status)"
        [ $status -eq 0 ] && [ "${SER[$api]}" = "emulator-$(pv_integrator_port "$api")" ] || rc=1
    done
    for api in "${APIS[@]}"; do [ -n "${SER[$api]:-}" ] || rc=1; done
    return $rc
}

# failed_classes DIR: "SERIAL CLASS" for each class with a failure or error in DIR's JUnit reports.
failed_classes() {
    python3 - "$1" <<'EOF'
import glob, sys, xml.etree.ElementTree as ET
out = set()
for f in sorted(glob.glob(sys.argv[1] + "/TEST-*.xml")):
    for suite in ET.parse(f).getroot().iter("testsuite"):
        devs = [p.get("value") for p in suite.iter("property") if p.get("name") == "device"]
        for case in suite.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                for dev in devs:
                    out.add((dev, case.get("classname", "")))
for dev, cls in sorted(out):
    print(dev, cls)
EOF
}

connected_tests() {
    local dir=$LOGS_ROOT/connected rc s classes again=0
    rm -rf "$dir"
    mkdir -p "$dir/full"
    "$EMU/adb.sh" --connected "${SER[24]}" "${SER[28]}" "${SER[37]}" ${gradle_args[@]+-- "${gradle_args[@]}"} > "$dir/full.log" 2>&1
    rc=$?
    cp -r "$RESULTS/." "$dir/full/" 2> /dev/null
    grep -E '^(PASS|FAIL) connected|^BUILD' "$dir/full.log" | sed 's/^/   /'
    [ $rc -ne 0 ] || return 0
    failed_classes "$dir/full" > "$dir/failed"
    # A device without a failing test failed otherwise (no report: an install, a lost device).
    for s in "${SER[24]}" "${SER[28]}" "${SER[37]}"; do
        if grep -q "^FAIL connected tests on $s " "$dir/full.log" && ! grep -q "^$s " "$dir/failed"; then
            echo "   $s: no failing test to run again; the run itself failed there"
            return 1
        fi
    done
    [ -s "$dir/failed" ] || return 1
    for s in "${SER[24]}" "${SER[28]}" "${SER[37]}"; do
        classes=$(awk -v s="$s" '$1 == s { print $2 }' "$dir/failed" | paste -sd, -)
        [ -n "$classes" ] || continue
        echo "   $s: failed in the full run: $classes; run again alone there"
        mkdir -p "$dir/again-$s"
        "$EMU/adb.sh" --connected "$s" -- -Pandroid.testInstrumentationRunnerArguments.class="$classes" ${gradle_args[@]+"${gradle_args[@]}"} > "$dir/again-$s.log" 2>&1 || again=1
        cp -r "$RESULTS/." "$dir/again-$s/" 2> /dev/null
        grep -E '^(PASS|FAIL) connected' "$dir/again-$s.log" | sed 's/^/     alone: /'
    done
    [ $again -eq 0 ] || return 1
    echo "   FLAKY: each class that failed in the full run passed alone: $(awk '{ printf "%s on %s; ", $2, $1 }' "$dir/failed")"
}

# Each device-test class, by package, with the tests it has on API 24, 28 and 37 at wave-3B-merged; a
# class run again alone (connected_tests) counts with that run's result.
device_classes() {
    python3 - "$LOGS_ROOT/connected" "${SER[24]}" "${SER[28]}" "${SER[37]}" <<'EOF'
import collections, glob, os, sys, xml.etree.ElementTree as ET
d, s24, s28, s37 = sys.argv[1:5]
prefix = "io.github.dilshodbekmx.passwordvault."
expected = {
    "nav.NavLockTest": ("2.5", 1, 1, 1),
    "nav.NavRecreationTest": ("2.5", 1, 1, 1),
    "platform.AndroidClockTest": ("2.7", 5, 5, 5),
    "platform.MemoryLimiterTest": ("2.7", 0, 0, 1),
    "platform.NativeArgon2idTest": ("2.7", 10, 10, 11),
    "platform.OsFilesTest": ("2.7", 19, 19, 19),
    "platform.OsRecordFilesTest": ("2.7", 11, 11, 11),
    "platform.PlatformModuleTest": ("2.7", 7, 7, 7),
    "platform.SystemRandomTest": ("2.7", 3, 3, 3),
    "platform.VaultRoundTripDeviceTest": ("2.7", 1, 1, 1),
    "vectors.LockVectorsDeviceTest": ("2.7", 3, 3, 3),
    "vectors.MasterPasswordBytesDeviceTest": ("2.7", 5, 5, 5),
    "vectors.VaultVectorsDeviceTest": ("2.7", 9, 9, 9),
    "window.ObscuredTouchNoticeTest": ("2.8", 2, 2, 2),
    "window.SecureWindowTest": ("2.8", 2, 2, 2),
    "window.WindowPolicyTest": ("2.8", 2, 2, 2),
    "ui.common.CommonStringsPseudoLocaleTest": ("2.8", 2, 2, 2),
    "ui.common.DialogSheetPolicyTest": ("2.8", 4, 4, 4),
    "ui.common.FieldPolicyTest": ("2.8", 4, 4, 4),
    "ui.common.MaskedValueNodesTest": ("2.8", 2, 2, 2),
    "ui.common.PinPadNodesTest": ("2.8", 5, 5, 5),
    "ui.common.SecureFieldSavedStateTest": ("2.8", 1, 1, 1),
    "i18n.FormattersTest": ("2.9", 6, 6, 6),
    "i18n.LocaleCommandTest": ("2.9", 2, 2, 2),
    "i18n.SortKeysTest": ("2.9", 8, 8, 8),
    "ui.setup.TypeAgainRotationTest": ("3.3", 1, 1, 1),
    "ui.lock.LockPseudoLocaleTest": ("3.4", 2, 2, 2),
    "ui.lock.MasterPasswordRotationTest": ("3.4", 1, 1, 1),
    "ui.lock.MasterPasswordScreenTest": ("3.4", 6, 6, 6),
    "ui.list.ListPerformanceTest": ("3.5", 1, 1, 2),
    "ui.list.ListPseudoLocaleTest": ("3.5", 5, 5, 5),
    "ui.list.PasswordsScreenTest": ("3.5", 9, 9, 9),
    "ui.edit.EditKeyboardRotationTest": ("3.6", 3, 4, 4),
    "ui.edit.EditScreenTest": ("3.6", 4, 4, 4),
    "ui.entry.BigLettersScreenTest": ("3.7", 10, 10, 10),
    "ui.entry.EntryPseudoLocaleTest": ("3.7", 6, 6, 6),
    "ui.entry.EntrySavedStateTest": ("3.7", 2, 2, 2),
    "ui.entry.EntryScreenTest": ("3.7", 16, 16, 16),
    "clipboard.AndroidSecretClipboardTest": ("3.8", 34, 34, 34),
    "clipboard.AndroidSystemClipTest": ("3.8", 6, 6, 7),
    "clipboard.ClearAlarmDeviceTest": ("3.8", 4, 4, 4),
    "clipboard.ClipStatusRowTest": ("3.8", 11, 11, 11),
    "clipboard.ClipboardPseudoLocaleTest": ("3.8", 2, 2, 2),
    "clipboard.SecretClipboardBridgeTest": ("3.8", 8, 8, 8),
    "keystore.FakeKeysParityTest": ("3.9", 1, 1, 1),
    "keystore.KeystoreAccessTest": ("3.9", 12, 12, 12),
    "keystore.KeystoreErrorRowsTest": ("3.9", 11, 11, 11),
    "keystore.KeystoreFailureAdapterTest": ("3.9", 5, 5, 5),
    "keystore.KeystoreFingerprintKeyTest": ("3.9", 17, 17, 17),
    "keystore.KeystoreModuleTest": ("3.9", 5, 5, 5),
    "keystore.KeystorePinKeyTest": ("3.9", 8, 8, 8),
    "ui.list.ListFocusTest": ("w3B-carry", 2, 2, 2),
    "data.StoredTextsTest": ("w3B-carry", 1, 1, 1),
}

def read(path):
    seen = collections.defaultdict(lambda: [0, 0, 0])  # (device, class) -> [ran, failed, skipped]
    for f in sorted(glob.glob(path + "/TEST-*.xml")):
        for suite in ET.parse(f).getroot().iter("testsuite"):
            devs = [p.get("value") for p in suite.iter("property") if p.get("name") == "device"]
            for case in suite.iter("testcase"):
                cls = case.get("classname", "")
                cls = cls[len(prefix):] if cls.startswith(prefix) else cls
                for dev in devs:
                    entry = seen[(dev, cls)]
                    if case.find("skipped") is not None:
                        entry[2] += 1
                        continue
                    entry[0] += 1
                    if case.find("failure") is not None or case.find("error") is not None:
                        entry[1] += 1
    return seen

seen = read(d + "/full")
again = set()
for path in sorted(glob.glob(d + "/again-*")):
    serial = os.path.basename(path)[len("again-"):]
    for (dev, cls), entry in read(path).items():
        if dev == serial:
            seen[(dev, cls)] = entry
            again.add((dev, cls))
bad = 0
for api, serial, column in ((24, s24, 1), (28, s28, 2), (37, s37, 3)):
    by_package = collections.defaultdict(lambda: [0, 0])
    for cls, spec in expected.items():
        package, want = spec[0], spec[column]
        ran, failed, skipped = seen.get((serial, cls), (0, 0, 0))
        by_package[package][0] += ran
        by_package[package][1] += want
        if failed or ran < want:
            print(f"   API {api} ({serial}): {cls}: {ran} ran, {failed} failed, {skipped} skipped; expected at least {want}")
            bad = 1
    total = sum(v[0] for (dev, _), v in seen.items() if dev == serial)
    failed = sum(v[1] for (dev, _), v in seen.items() if dev == serial)
    want_total = sum(spec[column] for spec in expected.values())
    if failed or total < want_total:
        bad = 1
    others = sorted(cls for (dev, cls) in seen if dev == serial and cls not in expected)
    rerun = sorted(cls for (dev, cls) in again if dev == serial)
    print(f"   API {api} ({serial}): {total} tests ran (the table: {want_total}), {failed} failed; "
          + ", ".join(f"{p} {r}/{w}" for p, (r, w) in sorted(by_package.items()))
          + (f"; run again alone: {', '.join(rerun)}" if rerun else "")
          + (f"; other classes: {', '.join(others)}" if others else ""))
sys.exit(bad)
EOF
}

# in_parallel NAME COMMAND-WORDS... -- API...: COMMAND SERIAL on each device at once, its output in
# LOGS/NAME-API.log; prints each device's step lines.
in_parallel() {
    local name=$1 api rc=0 status cmd=()
    shift
    while [ $# -gt 0 ] && [ "$1" != "--" ]; do cmd+=("$1"); shift; done
    shift
    declare -A pids=()
    for api in "$@"; do
        "${cmd[@]}" "${SER[$api]}" > "$LOGS/$name-$api.log" 2>&1 &
        pids[$api]=$!
    done
    for api in "$@"; do
        wait "${pids[$api]}"
        status=$?
        echo "   $name on API $api (${SER[$api]}): exit $status"
        grep -E '^(PASS|FAIL|SKIPPED|SUMMARY|   )' "$LOGS/$name-$api.log" | sed 's/^/     /'
        [ $status -eq 0 ] || rc=1
    done
    return $rc
}

# skeleton VARIANT SERIAL through checks/3A.sh; clip and secure through this script.
skeleton_debug() { "$EMU/checks/3A.sh" skeleton debug "$1"; }
skeleton_staging() { "$EMU/checks/3A.sh" skeleton staging "$1"; }
clip_debug() {
    local shots=()
    [ "$1" != "${SER[37]}" ] || shots=(--shots "$SHOTS")
    "${BASH_SOURCE[0]}" clip "$1" --apk "$DEBUG_APK" ${shots[@]+"${shots[@]}"}
}
secure_staging() { "${BASH_SOURCE[0]}" secure "$1" --apk "$STAGING_APK"; }
smoke_staging() { "$EMU/smoke.sh" staging "$1" --apk "$STAGING_APK"; }

window_checks() {
    "$EMU/window/all.sh" "${SER[24]}" "${SER[28]}" "${SER[37]}" > "$LOGS/all.log" 2>&1
    local rc=$?
    grep -E '^(PASS|FAIL) .* \(exit|^SUMMARY window' "$LOGS/all.log" | sed 's/^/   /'
    grep -E '^KNOWN ' "$LOGS/all.log" | sort | uniq -c | sed 's/^/     /'
    return $rc
}

# The packages' own scripts, one after another on one device (the debug build).
extras_one() {
    local s=$1 bad=0 api
    api=$(PV_SERIAL=$s pv_api)
    "$EMU/clipboard/clear.sh" "$s" > "$LOGS/clear-$s.log" 2>&1 || bad=1
    "$EMU/shell/walk.sh" "$s" > "$LOGS/walk-$s.log" 2>&1 || bad=1
    "$EMU/setup/create.sh" "$s" --vault-file > "$LOGS/create-$s.log" 2>&1 || bad=1
    "$EMU/session/autolock.sh" "$s" > "$LOGS/autolock-$s.log" 2>&1 || bad=1
    if [ "$api" = 37 ]; then
        "$EMU/shell/walk.sh" "$s" --apk "$STAGING_APK" > "$LOGS/walk-staging-$s.log" 2>&1 || bad=1
    fi
    for script in clear walk create autolock walk-staging; do
        [ -f "$LOGS/$script-$s.log" ] || continue
        echo "   $script.sh: $(grep -E '^SUMMARY' "$LOGS/$script-$s.log" | tail -n 1)"
        grep -E '^(FAIL|SKIPPED)' "$LOGS/$script-$s.log" | sed 's/^/     /'
    done
    return $bad
}

stop_devices() {
    local api rc=0
    for api in "${APIS[@]}"; do
        [ -n "${SER[$api]:-}" ] && [ -f "$PV_REG/${SER[$api]}" ] || continue
        "$EMU/stop.sh" "${SER[$api]}" >> "$LOGS_ROOT/stop.log" 2>&1 || rc=1
    done
    for api in "${APIS[@]}"; do
        if [ -f "$PV_REG/emulator-$(pv_integrator_port "$api")" ]; then
            echo "   emulator-$(pv_integrator_port "$api") is still registered"
            rc=1
        fi
    done
    return $rc
}

# ---- arguments ------------------------------------------------------------------------------------
if [ "${1:-}" = clip ] || [ "${1:-}" = secure ]; then
    mode=$1
    [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"
    serial=$2
    shift 2
    if [ "$mode" = clip ]; then APK=$DEBUG_APK; else APK=$STAGING_APK; fi
    SHOT_DIR=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --apk) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; APK=$2; shift ;;
            --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOT_DIR=$2; shift ;;
            *) pv_usage "${BASH_SOURCE[0]}" ;;
        esac
        shift
    done
    [ -f "$APK" ] || { echo "3B.sh: no APK at $APK (tools/gradle.sh :app:assembleDebug :app:assembleStaging)" >&2; exit 2; }
    LOGS=$LOGS_ROOT/$mode
    mkdir -p "$LOGS"
    [ -z "$SHOT_DIR" ] || mkdir -p "$SHOT_DIR"
    if [ "$mode" = clip ]; then run_clip "$serial"; else run_secure "$serial"; fi
    exit $?
fi

ALL_PARTS=(merged connected skeleton clip smoke secure window extras)
parts=()
gradle_args=()
keep=0
while [ $# -gt 0 ]; do
    case "$1" in
        merged | connected | skeleton | clip | smoke | secure | window | extras | stop) parts+=("$1") ;;
        --shots) [ $# -ge 2 ] || pv_usage "${BASH_SOURCE[0]}"; SHOTS=$2; shift ;;
        --keep) keep=1 ;;
        --) shift; gradle_args=("$@"); break ;;
        *) pv_usage "${BASH_SOURCE[0]}" ;;
    esac
    shift
done
[ ${#parts[@]} -gt 0 ] || parts=("${ALL_PARTS[@]}")
wants() { [[ " ${parts[*]} " == *" $1 "* ]]; }
needs_devices() { local p; for p in connected skeleton clip smoke secure window extras; do wants "$p" && return 0; done; return 1; }

mkdir -p "$LOGS_ROOT"
echo "sub-wave 3B device check in $REPO at $(git -C "$REPO" rev-parse --short HEAD) ($(date '+%F %T')): ${parts[*]}; logs in ${LOGS_ROOT#"$REPO"/}"

if wants merged; then
    echo
    echo "== merged: round R1"
    pv_step "the seven branches of round R1 are in HEAD" -- branches_merged
fi
if needs_devices; then
    echo
    echo "== devices: the integrator's API 24, 28 and 37"
    if ! pv_step "build the debug and staging APKs" -- build_apks || ! pv_step "the integrator's devices run (boot.sh --integrator)" -- devices; then
        parts=(stop)
    fi
fi
for part in connected skeleton clip smoke secure window extras; do
    wants "$part" || continue
    LOGS=$LOGS_ROOT/$part
    rm -rf "$LOGS"
    mkdir -p "$LOGS"
    echo
    echo "== $part ($(date +%T))"
    case $part in
        connected)
            pv_step "connectedDebugAndroidTest on API 24, 28 and 37 (adb.sh --connected; a failed class once more alone)" -- connected_tests
            pv_step "every device-test class of 2.5, 2.7 to 2.9, 3.3 to 3.9 and w3B-carry passed on each device" -- device_classes ;;
        skeleton)
            pv_step "the skeleton steps 1 to 9, debug build, on API 24, 28 and 37" -- in_parallel skeleton-debug skeleton_debug -- 24 28 37
            pv_step "the skeleton steps 1 to 9, staging build, on API 24 and 37" -- in_parallel skeleton-staging skeleton_staging -- 24 37 ;;
        clip)
            pv_step "the account, its password shown and copied: flagged, the row counts down, gone 30 s later in the background; a note's and a dialog's copies flagged (debug, API 24, 28, 37)" -- in_parallel clip clip_debug -- 24 28 37 ;;
        smoke)
            pv_step "smoke.sh staging with the copy and the clear, on API 24 and 37" -- in_parallel smoke smoke_staging -- 24 37 ;;
        secure)
            pv_step "staging: screencap of the app is black (FLAG_SECURE), the emulator's own screenshot is not, on API 24, 28 and 37" -- in_parallel secure secure_staging -- 24 28 37 ;;
        window)
            pv_step "tools/emu/window/all.sh: debug and staging on API 28 and 37, a11y-old on API 24" -- window_checks ;;
        extras)
            pv_step "extra: clear.sh (3.8), walk.sh (2.5), create.sh --vault-file (3.3), autolock.sh (3.2), debug, API 24, 28, 37; walk.sh staging on API 37" -- in_parallel extras extras_one -- 24 28 37 ;;
    esac
done
if needs_devices || wants stop; then
    if [ $keep = 1 ]; then
        echo
        echo "== the devices keep running (--keep): ${SER[*]:-}"
    else
        [ ${#SER[@]} -gt 0 ] || for api in "${APIS[@]}"; do SER[$api]=emulator-$(pv_integrator_port "$api"); done
        echo
        pv_step "the devices are stopped" -- stop_devices
    fi
fi
echo
PV_SERIAL="" pv_summary
