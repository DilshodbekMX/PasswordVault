#!/usr/bin/env bash
# tools/emu/change/guided.sh SERIAL [--apk FILE] [--test-apk FILE] [--shots DIR] [--retries N]
#
# Build plan 7.2's [script] line (ux.md §5.17, §12 check 5): on the emulator the guided change of one
# password survives a lock and `am force-stop` at every step, and both passwords can be copied from
# the list and from the account's screen meanwhile.
#
# The accounts. On a fresh install of the APK (default the debug build) the test APK's
# GuidedChangeSeed (APP-AT/ui/security/change/) makes a vault with a master password of its own and
# four accounts with fixed ids: Gmail, Facebook and Kapitalbank share the password Toshkent2020!,
# Uzum Market has its own. The test APK is removed again at once. Gmail's id is the one the Security
# tab's placeholder opens until build plan 7.1 is merged; after the merge the script goes the real
# way, the reuse card of the Security tab and "Change" on Gmail's row of the group list.
#
# The unlocks. At the first unlock the lock's "Choose a PIN" is answered with a PIN, as the user has
# one (a banking-style PIN, with the master password as the root of recovery), and every later unlock
# is with that PIN. Unlocked with the master password and no PIN, every unlock would run "Choose a
# PIN" again, whose end ("Make backups", Finish) opens the Passwords tab in place of the route the
# app was on (4.3's PinSetupNav: ChoosePinReason.NONE_HERE is its setup flow).
#
# The steps, each checked after a lock (the screen off: the app locks at once, and the PIN brings the
# same route back) and after `am force-stop` (the app starts again on the list; the change is opened
# again from the Security tab): before anything is saved; after "Create new password";
# after each of N more tries (default 8: the fourth through "Password rules" and its "Use this
# password", the other seven by "Create another password", so that ux.md §12 check 5's seven taps
# are made); "I'll finish later"; "No, put back the old password", which restores the
# original; and "Yes, it's changed", whose end ("Gmail now has its own password." with "Next:
# Facebook") comes back after a lock. Meanwhile both passwords are copied from the change's own
# buttons, from the list row's menu and from the account's two rows, and read from outside the app
# (clipboard/lib.sh): each copy holds the right password and is flagged sensitive. Last, "Next" opens
# Facebook's change and, after it, Kapitalbank's with "Last one!".
#
# It works by resource id (GuidedChangeTags pv.change.*, ListTags, EntryTags, the shell's and 7.1's),
# except the two items of the list row's copy menu, which have no id and are found by their English
# words in every window (pv_ui_nodes_all). The passwords never reach the log: a mismatch is reported
# with lengths only. With --shots DIR (debug builds; default /home/asus/pvwork/shots-7 on API 37)
# pictures of the steps are saved as DIR/7.2-NN-name.png.
#
# Exit 0 when no step failed. About 15 minutes on API 37.
set -u

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
# shellcheck source=../adb.sh
source "$REPO/tools/emu/adb.sh"
# shellcheck source=../app.sh
source "$REPO/tools/emu/app.sh"
# shellcheck source=../clipboard/lib.sh
source "$REPO/tools/emu/clipboard/lib.sh"

usage() {
    echo "usage: $0 SERIAL [--apk FILE] [--test-apk FILE] [--shots DIR] [--retries N]" >&2
    exit 2
}

[ $# -ge 1 ] || usage
SERIAL=$1
shift
APK=$REPO/app/build/outputs/apk/debug/app-debug.apk
TEST_APK=$REPO/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
SHOTS=""
SHOTS_SET=0
RETRIES=8
while [ $# -gt 0 ]; do
    case "$1" in
        --apk) [ $# -ge 2 ] || usage; APK=$2; shift 2 ;;
        --test-apk) [ $# -ge 2 ] || usage; TEST_APK=$2; shift 2 ;;
        --shots) [ $# -ge 2 ] || usage; SHOTS=$2; SHOTS_SET=1; shift 2 ;;
        --retries) [ $# -ge 2 ] || usage; RETRIES=$2; shift 2 ;;
        *) usage ;;
    esac
done

pv_use "$SERIAL"
pva_init
PV_ADB_TIMEOUT=${PV_ADB_TIMEOUT:-120}
API=$(pv_api)
[ "$SHOTS_SET" = 1 ] || { [ "$API" = 37 ] && SHOTS=/home/asus/pvwork/shots-7; }
echo "change/guided.sh on $SERIAL (API $API): $APK"

TEST_PACKAGE=$PV_PACKAGE.test
SEED_CLASS=$PV_PACKAGE.ui.security.change.GuidedChangeSeed
# The seeded accounts (GuidedChangeSeed) and the master password of their vault.
GMAIL=00000000-0000-4000-8000-00000000e001
FACEBOOK=00000000-0000-4000-8000-00000000e002
BANK=00000000-0000-4000-8000-00000000e003
SHARED='Toshkent2020!'
PVA_PASSWORD=Change-seven-two-seed-vault
# The PIN chosen at the first "Choose a PIN": the lock screen's pad from then on, as the user has it.
PIN=${PV_CHANGE_PIN:-582913}

# The passwords seen so far: the latest new one, every new one made, and Gmail's at its end.
NEW=""
MADE=""
NEW_GMAIL=""

# ---- helpers ----------------------------------------------------------------------------------

# shot NAME: a picture of the screen as DIR/7.2-NAME.png, when pictures are kept (debug builds only:
# the secure window of staging gives nothing).
shot() {
    [ -n "$1" ] && [ -n "$SHOTS" ] && pv_is_debuggable || return 0
    pv_sleep 1
    pva_screenshot "$SHOTS/7.2-$1.png" || true
}

# The line of the node with id ID on the screen now, enabled or not (pva_node finds enabled ones only).
node_any() { pv_ui_nodes | awk -F'|' -v id="$1" '$3 == id { print; exit }'; }

# enabled_of ID: "true" or "false" for the node with id ID on the screen now; empty when not shown.
enabled_of() { node_any "$1" | cut -d'|' -f5; }

# words_of ID: the words of the button ID. A Compose button's node has no text of its own in the
# dump: its words are on the first node with a text after it (before the next node with an id).
words_of() {
    pv_ui_nodes | awk -F'|' -v id="$1" '
        $3 == id { on = 1; if ($1 != "") { print $1; exit } next }
        on && $3 ~ /^pv[.]/ { exit }
        on && $1 != "" { print $1; exit }'
}

# Slow swipes: a fast one leaves the list flinging, and Compose spends the next touch on stopping the
# fling instead of the tap it was meant to be (seen under a machine load of 30).
swipe_down() { # the content moves up, so that what is below comes into view
    local h
    h=$(pva_height)
    pv_shell input swipe $((PVA_SCREEN_W / 2)) $((h * 60 / 100)) $((PVA_SCREEN_W / 2)) $((h * 30 / 100)) 900 >/dev/null
    pv_sleep 1.5
}

swipe_up() {
    local h
    h=$(pva_height)
    pv_shell input swipe $((PVA_SCREEN_W / 2)) $((h * 30 / 100)) $((PVA_SCREEN_W / 2)) $((h * 60 / 100)) 900 >/dev/null
    pv_sleep 1.5
}

# to_top: the scrolling screen back at its top.
to_top() {
    local i
    for i in 1 2 3 4; do swipe_up; done
}

# seek ID: the node ID, enabled or not, is on the screen: from the top of the scrolling screen down
# (the steps take more than one screen, and pva_reveal only scrolls down).
seek() {
    [ -n "$(node_any "$1")" ] && return 0
    to_top
    local i
    for i in 1 2 3 4 5 6 7 8; do
        [ -n "$(node_any "$1")" ] && return 0
        swipe_down
    done
    pv_log "no $1 after scrolling; ids: $(pva_ids)"
    return 1
}

# tap ID: ID sought and tapped (pva_tap, which keeps away from the bottom band of the system's
# navigation bar; a node that lies there is first moved up with a slow swipe). From Android 13 on the
# system shows its own preview of a copy at the bottom left for a few seconds, and a tap there is the
# preview's: a node in the lower half waits until COPY_PREVIEW_S have passed since the last copy.
tap() {
    seek "$1" || return 1
    local y height wait
    height=$(pva_height)
    y=$(node_any "$1" | cut -d'|' -f7)
    if [ -n "$y" ] && [ "$y" -ge $((height - 200)) ]; then
        swipe_down
        y=$(node_any "$1" | cut -d'|' -f7)
    fi
    if [ "$API" -ge 33 ] && [ -n "$y" ] && [ "$y" -gt $((height / 2)) ] && [ -n "$LAST_COPY" ]; then
        wait=$((LAST_COPY + COPY_PREVIEW_S - SECONDS))
        [ "$wait" -le 0 ] || pv_sleep "$wait"
    fi
    pva_tap "$1"
}
LAST_COPY=""
COPY_PREVIEW_S=8

# gone ID [SECONDS]: no node ID within SECONDS (default 10).
gone() {
    local deadline=$((SECONDS + ${2:-10}))
    while [ -n "$(node_any "$1")" ]; do
        [ $SECONDS -lt $deadline ] || { pv_log "$1 is still shown"; return 1; }
        pv_sleep 1
    done
}

# tab NAME: the bottom bar's tab NAME (Passwords, Security, Settings), tapped where it is; it lies in
# the bottom band that pva_tap keeps away from.
tab() {
    local line
    line=$(pva_node "pv.tab.$1")
    [ -n "$line" ] || { pv_log "no tab $1; ids: $(pva_ids)"; return 1; }
    pv_shell input tap "$(printf '%s\n' "$line" | cut -d'|' -f6)" "$(printf '%s\n' "$line" | cut -d'|' -f7)" >/dev/null
}

# expect_clip EXPECTED WHAT: the clipboard, read from outside the app, holds EXPECTED, flagged
# sensitive (ux.md §6). The text is never logged.
expect_clip() {
    local d="" text="" sensitive="" i
    for i in 1 2 3 4 5; do
        pv_sleep 1
        d=$(pvc_get) || continue
        text=$(printf '%s\n' "$d" | pvc_field text)
        [ "$text" = "$1" ] && break
    done
    [ "$text" = "$1" ] || { pv_log "$2: the clipboard holds ${#text} characters, not the ${#1} expected"; return 1; }
    LAST_COPY=$SECONDS
    sensitive=$(printf '%s\n' "$d" | pvc_field sensitive)
    [ "$sensitive" = true ] || { pv_log "$2: the clip is not flagged sensitive ($sensitive)"; return 1; }
}

# copy_on_change ID EXPECTED WHAT: the change's copy button ID gives EXPECTED. When no clip comes
# at all, the tap is made once more (a tap that met a moving list is spent on stopping it).
copy_on_change() {
    pvc_clear >/dev/null 2>&1
    tap "$1" || return 1
    if ! clip_came; then
        pv_log "$3: no clip after the tap; tapping again"
        tap "$1" || return 1
    fi
    expect_clip "$2" "$3"
}

# clip_came: the clipboard holds a clip within about 5 s (after pvc_clear: a new one).
clip_came() {
    local i
    for i in 1 2 3 4 5; do
        pv_sleep 1
        [ "$(pvc_describe 2>/dev/null | pvc_field has)" = true ] && return 0
    done
    return 1
}

# ---- the screens ------------------------------------------------------------------------------

# open_change: Gmail's guided change, from the Security tab (a tab's root: the stack is new). Through
# 7.1's reuse card and group list when they are there, otherwise through the shell's placeholders,
# which open GuidedChange(PLACEHOLDER_ENTRY_ID), Gmail's id.
open_change() {
    tab Security && pva_on Security 20 || return 1
    if pva_present pv.open.Group 5; then
        pva_tap pv.open.Group && pva_on Group && pva_tap pv.open.GuidedChange || return 1
    else
        # The check runs in the background after the unlock ("Checking 3 of 4…").
        pva_present pv.security.card.reused.open 60 || { pv_log "no reuse card; ids: $(pva_ids)"; return 1; }
        tap pv.security.card.reused.open && pva_on Group 20 || return 1
        tap "pv.group.action.$GMAIL" || return 1
    fi
    pva_on GuidedChange 20 || return 1
    account_is Gmail
}

# account_is TITLE: the change shown is that of TITLE.
account_is() {
    local shown
    seek pv.change.account || return 1
    shown=$(node_any pv.change.account | cut -d'|' -f1)
    [ "$shown" = "$1" ] || { pv_log "the change of '$shown' is shown, not of '$1'"; return 1; }
}

# The states of the steps, by the buttons that show them.
not_started() {
    seek pv.change.create || return 1
    [ -z "$(node_any pv.change.createAnother)" ] || { pv_log "Create another password is shown"; return 1; }
    seek pv.change.copyNew || return 1
    [ "$(enabled_of pv.change.copyNew)" = false ] || { pv_log "Copy new password before a new one is made"; return 1; }
    seek pv.change.yes || return 1
    [ "$(enabled_of pv.change.yes)" = false ] || { pv_log "Yes does not wait for step 3"; return 1; }
}

started() {
    seek pv.change.createAnother || return 1
    seek pv.change.saved || return 1
    [ -z "$(node_any pv.change.create)" ] || { pv_log "Create new password is shown"; return 1; }
    seek pv.change.yes || return 1
    [ "$(enabled_of pv.change.yes)" = true ] || { pv_log "Yes is not enabled"; return 1; }
}

ended() {
    pva_need pv.change.done 20 || return 1
    pva_present pv.change.next 5 || { pv_log "no Next; ids: $(pva_ids)"; return 1; }
}

# lock_back ROUTE: the screen off (the app locks at once, lock.md §10.2), on again, the PIN; then
# ROUTE is shown again.
lock_back() {
    pva_screen_off 3 || return 1
    pva_pin_unlock "$1" "$PIN"
}

# force_stop_back: `am force-stop`, the app started again (on the lock screen), the PIN; the app
# opens on the list, since a force-stop ends the saved back stack too.
force_stop_back() {
    pv_shell am force-stop "$PV_PACKAGE" >/dev/null || return 1
    pv_sleep 2
    [ -z "$(pva_pid)" ] || { pv_log "the app still runs after am force-stop"; return 1; }
    pva_relaunch || return 1
    pva_pin_unlock Passwords "$PIN"
}

# list_copies OLD NEW: on the list, Gmail's copy button offers both passwords in its menu while the
# change waits, and each item gives its password. With NEW empty (no change waits) it copies OLD at
# once. The menu's items have no ids: they are found by their English words in every window.
list_copies() {
    local copy="pv.list.copy.e.$GMAIL"
    tab Passwords
    pva_on Passwords 20 && pva_need "$copy" 10 || return 1
    pvc_clear >/dev/null 2>&1
    pva_tap "$copy" || return 1
    if [ -z "$2" ]; then
        expect_clip "$1" "the row's copy"
        return
    fi
    menu_item "Copy old password" || return 1
    shot "$LIST_SHOT"
    pv_ui_tap "^Copy old password" "$(pv_ui_nodes_all)" >/dev/null || return 1
    expect_clip "$1" "the row's Copy old password" || return 1
    pvc_clear >/dev/null 2>&1
    pva_tap "$copy" && menu_item "Copy new password" || return 1
    pv_ui_tap "^Copy new password" "$(pv_ui_nodes_all)" >/dev/null || return 1
    expect_clip "$2" "the row's Copy new password"
}
LIST_SHOT=04-list-row-menu

# menu_item WORDS: a window shows an item whose text starts with WORDS (the row's copy menu).
menu_item() {
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
        [ -n "$(pv_ui_nodes_all | pv_ui_match "^$1")" ] && return 0
        pv_sleep 1
    done
    pv_log "no menu item '$1'"
    return 1
}

# detail_copies OLD NEW: Gmail's screen, from the list, has two passwords and copies each; with NEW
# empty, one password, OLD. Back on the list after.
detail_copies() {
    pva_on Passwords 20 || return 1
    pva_tap "pv.list.row.$GMAIL" && pva_on Entry 20 || return 1
    if [ -z "$2" ]; then
        pva_need pv.entry.password.copy 10 || return 1
        [ -z "$(node_any pv.entry.oldPassword.copy)" ] || { pv_log "an old password row with no change waiting"; return 1; }
        pvc_clear >/dev/null 2>&1
        pva_tap pv.entry.password.copy && expect_clip "$1" "the account's password" || return 1
    else
        pva_need pv.entry.oldPassword.copy 10 || return 1
        shot "$DETAIL_SHOT"
        pvc_clear >/dev/null 2>&1
        pva_tap pv.entry.oldPassword.copy && expect_clip "$1" "the account's old password" || return 1
        pvc_clear >/dev/null 2>&1
        pva_tap pv.entry.password.copy && expect_clip "$2" "the account's new password" || return 1
    fi
    pva_tap pv.entry.back && pva_on Passwords 20
}
DETAIL_SHOT=05-account-two-passwords

# The change's own buttons give the website's password (steps 1 and 4) and, once made, the new one.
screen_copies_old() {
    copy_on_change pv.change.step1.copyOld "$SHARED" "step 1's Copy old password" || return 1
    copy_on_change pv.change.step4.copyOld "$SHARED" "step 4's Copy old password"
}

screen_copies() {
    screen_copies_old && copy_on_change pv.change.copyNew "$NEW" "step 4's Copy new password"
}

# read_new: the new password, through step 4's "Copy new password": not the original, and not one made
# before.
read_new() {
    local got
    pvc_clear >/dev/null 2>&1
    tap pv.change.copyNew || return 1
    pv_sleep 1
    got=$(pvc_get | pvc_field text) || return 1
    [ ${#got} -ge 4 ] || { pv_log "no new password on the clipboard"; return 1; }
    LAST_COPY=$SECONDS
    [ "$got" != "$SHARED" ] || { pv_log "the new password is the old one"; return 1; }
    case " $MADE " in *" $got "*) pv_log "a new password came again"; return 1 ;; esac
    NEW=$got
    MADE="$MADE $got"
}

# ---- the steps --------------------------------------------------------------------------------

seed() {
    [ -f "$TEST_APK" ] || { pv_log "no test APK at $TEST_APK (tools/gradle.sh :app:assembleDebugAndroidTest)"; return 1; }
    pva_install "$APK" || return 1
    local out
    out=$(PV_ADB_TIMEOUT=180 pv_adb install -r -t "$TEST_APK" 2>&1)
    echo "$out" | grep -q '^Success' || { pv_log "test APK: $out"; return 1; }
    out=$(PV_ADB_TIMEOUT=180 pv_shell am instrument -w -e pvChangeSeed "$PVA_PASSWORD" -e class "$SEED_CLASS" \
        "$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tr -d '\r')
    pv_adb uninstall "$TEST_PACKAGE" >/dev/null 2>&1
    echo "$out" | grep -q '^OK (1 test)' || { pv_log "the seed: $(echo "$out" | tail -n 8)"; return 1; }
}

# start_unlock: the app started, the master password typed, and "Choose a PIN" answered with $PIN (its
# second step, the fingerprint offer "Not now", "Make backups" Finish); a notice on the way ("Make a
# backup now?") is answered "Later". The list then shows the seed's accounts.
start_unlock() {
    pva_start || return 1
    pva_on Locked 30 && pva_need pv.lock.password 10 || return 1
    pva_type pv.lock.password "$PVA_PASSWORD" || return 1
    pva_hide_keyboard
    pva_tap pv.lock.unlock || return 1
    local deadline=$((SECONDS + 180)) nodes chosen=0 quiet=0 answer
    while :; do
        nodes=$(pv_ui_nodes)
        if pva_in_nodes '[|]pv\.notice$' "$nodes"; then
            answer=""
            for answer in LATER OK; do
                [ -n "$(pva_node "pv.answer.$answer" "$nodes")" ] && break
                answer=""
            done
            [ -n "$answer" ] && pva_tap "pv.answer.$answer" || { pv_log "a notice without Later or OK; ids: $(pva_ids)"; return 1; }
            quiet=0
        elif pva_in_nodes '[|]pv\.route\.CreatePin$' "$nodes"; then
            pva_pin "$PIN" && pva_on ConfirmPin 30 && pva_pin "$PIN" || return 1
            chosen=1
            quiet=0
        elif pva_in_nodes '[|]pv\.route\.FingerprintOffer$' "$nodes"; then
            pva_tap pv.fingerprintOffer.notNow || return 1
            quiet=0
        elif pva_in_nodes '[|]pv\.route\.BackupsExplained$' "$nodes"; then
            pva_tap pv.action.finish || return 1
            quiet=0
        elif [ "$chosen" = 1 ] && pva_in_nodes '[|]pv\.route\.Passwords$' "$nodes"; then
            # Shown alone for a while: nothing more comes.
            quiet=$((quiet + 1))
            [ "$quiet" -lt 3 ] || break
        fi
        [ $SECONDS -lt $deadline ] || { pv_log "the first unlock did not end on the list with a PIN; ids: $(pva_ids)"; return 1; }
        pv_sleep 2
    done
    pva_row_with Gmail && pva_row_with Facebook && pva_row_with Kapitalbank
}

before_step3() {
    shot 01-steps-top
    not_started || return 1
    shot 02-steps-4-and-5-wait
    screen_copies_old
}

before_step3_after_lock() {
    lock_back GuidedChange && account_is Gmail && not_started && screen_copies_old
}

before_step3_after_force_stop() {
    force_stop_back && list_copies "$SHARED" "" && detail_copies "$SHARED" "" && open_change && not_started && screen_copies_old
}

create_first() {
    tap pv.change.create || return 1
    started || return 1
    shot 03-step3-saved
    read_new
}

started_after_lock() {
    lock_back GuidedChange && account_is Gmail && started && screen_copies
}

started_after_force_stop() {
    force_stop_back && list_copies "$SHARED" "$NEW" && detail_copies "$SHARED" "$NEW" && open_change && started && screen_copies
}

# retry N: "Create another password" (try 4 through "Password rules" and the generator sheet), the
# new password read; then a lock (odd N) or am force-stop (even N), after which both passwords are
# copied again: from the change, and after a force-stop from the list and the account too.
retry() {
    local before=$NEW
    if [ "$1" = 4 ]; then
        tap pv.change.rules || return 1
        pva_need pv.gen.use 20 || return 1
        shot 07-password-rules
        pva_tap pv.gen.use && gone pv.gen.use 10 || return 1
    else
        tap pv.change.createAnother || return 1
        if [ "$1" = 1 ] && pva_present pv.change.created 5; then shot 06-another-password-saved; fi
    fi
    started && read_new || return 1
    [ "$NEW" != "$before" ] || { pv_log "try $1 left the same password"; return 1; }
    if [ $(($1 % 2)) = 1 ]; then
        started_after_lock
    else
        LIST_SHOT="" DETAIL_SHOT="" started_after_force_stop
    fi
}

later() {
    tap pv.change.later && pva_on Group 20 || return 1
    force_stop_back && LIST_SHOT="" DETAIL_SHOT="" list_copies "$SHARED" "$NEW" && DETAIL_SHOT="" detail_copies "$SHARED" "$NEW"
}

revert() {
    open_change && started || return 1
    tap pv.change.no || return 1
    not_started || return 1
    shot 08-old-password-back
    # "The old password is back." lies over the bottom of the screen for a few seconds.
    gone pv.change.message 15 || return 1
    screen_copies_old || return 1
    force_stop_back && list_copies "$SHARED" "" && detail_copies "$SHARED" ""
}

confirm() {
    open_change && not_started || return 1
    tap pv.change.create && started && read_new || return 1
    lock_back GuidedChange && started && screen_copies || return 1
    tap pv.change.yes && ended || return 1
    local next
    next=$(words_of pv.change.next)
    [ "$next" = "Next: Facebook" ] || { pv_log "the next button reads '$next'"; return 1; }
    pva_present pv.change.backupLine 5 || { pv_log "no backup line, the first end of the session"; return 1; }
    shot 09-gmail-done-next-facebook
    NEW_GMAIL=$NEW
}

ended_after_lock() {
    lock_back GuidedChange && ended
}

next_accounts() {
    pva_tap pv.change.next || return 1
    pva_on GuidedChange 20 && account_is Facebook && not_started || return 1
    shot 10-next-facebook
    tap pv.change.create && started || return 1
    tap pv.change.yes && ended || return 1
    local next
    next=$(words_of pv.change.next)
    [ "$next" = "Next: Kapitalbank" ] || { pv_log "Facebook's next button reads '$next'"; return 1; }
    # The step before locked the app: a new session, whose first end shows the backup line again.
    pva_present pv.change.backupLine 5 || { pv_log "no backup line at the first end of a new session"; return 1; }
    pva_tap pv.change.next || return 1
    pva_on GuidedChange 20 && account_is Kapitalbank || return 1
    to_top
    pva_need pv.change.lastOne 10 || return 1
    shot 11-kapitalbank-last-one
    tap pv.change.create && started || return 1
    tap pv.change.yes || return 1
    # The last one: nobody has the old password any more, and the session showed its backup line.
    pva_need pv.change.done 20 && pva_need pv.change.finish 10 || return 1
    [ -z "$(node_any pv.change.next)" ] || { pv_log "a Next after the last one"; return 1; }
    [ -z "$(node_any pv.change.backupLine)" ] || { pv_log "the backup line came twice in one session"; return 1; }
    shot 12-kapitalbank-done-for-now
    pva_tap pv.change.finish && pva_on Group 20
}

# Gmail's change is over: one password, its new one, on the list and on its screen.
ended_after_force_stop() {
    [ -n "$NEW_GMAIL" ] || { pv_log "Gmail's change did not end"; return 1; }
    force_stop_back && list_copies "$NEW_GMAIL" "" && detail_copies "$NEW_GMAIL" ""
}

pv_step "fresh install, the seed's vault and accounts (test APK removed again)" -- seed
pv_step "the app opens with the master password; Choose a PIN answered with a PIN" -- start_unlock
pv_step "Gmail's guided change opens from the Security tab" -- open_change
pv_step "steps 1 and 2: nothing saved; Copy old password gives the website's password" -- before_step3
pv_step "before step 3: survives a lock" -- before_step3_after_lock
pv_step "before step 3: survives am force-stop (list, account, change)" -- before_step3_after_force_stop
pv_step "step 3: Create new password saves it at once" -- create_first
pv_step "step 4: both passwords from the change's buttons" -- screen_copies
pv_step "after step 3: survives a lock" -- started_after_lock
pv_step "after step 3: survives am force-stop; both passwords from the list row's menu and the account's rows" -- started_after_force_stop
for n in $(seq 1 "$RETRIES"); do
    how="Create another password"
    [ "$n" != 4 ] || how="Password rules, Use this password"
    after="a lock"
    [ $((n % 2)) = 1 ] || after="am force-stop"
    pv_step "try $((n + 1)) ($how), then $after: the old password stays, both passwords copied" -- retry "$n"
done
pv_step "I'll finish later, then am force-stop: both passwords from the list and the account" -- later
pv_step "No, put back the old password restores the original, also after am force-stop" -- revert
pv_step "a new change survives a lock; Yes, it's changed ends it with Next: Facebook and the backup line" -- confirm
pv_step "the end of the change survives a lock" -- ended_after_lock
pv_step "Next: Facebook (a new session: the backup line again), then Kapitalbank with Last one!, ended with Done for now and no second backup line" -- next_accounts
pv_step "Gmail's new password survives am force-stop (list and account, one password)" -- ended_after_force_stop
pv_step "the app did not crash" -- pva_no_crash
pv_summary
