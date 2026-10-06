#!/usr/bin/env bash
# tools/check.sh [unit|lint|build|offline|all]... [-- <gradle arguments>]
#
# The project's check (build plan §1, "The check script"). "The full check" is `tools/check.sh all`.
#
#   unit     :core:testDebugUnitTest under tr-TR and again under fa-IR, then :app:testDebugUnitTest.
#            The source scans of architecture.md §8.5 (R-4, R-6, R-10, R-11, R-13) are among them.
#   lint     :core:lintDebug :app:lintDebug; the checks of i18n.md §1.1 are errors.
#   build    :app:assembleDebug :app:assembleStaging :app:assembleRelease; each runs the build checks
#            that are Gradle tasks (R-1, R-2, R-5, R-7, R-8, R-9, R-12, R-14).
#   offline  the merged-manifest check of all four variants (no INTERNET, R-9); `aapt2 dump
#            permissions` on the debug, staging and release APKs (no permission outside R-9's list);
#            the offline class scan of the sources.
#   all      the four parts, in that order.
#
# Arguments after `--` go to every Gradle call, for example `tools/check.sh all -- --offline`.
# Every Gradle call goes through tools/gradle.sh. Every part asked for runs, even after a failure;
# the full output of each step is kept in build/check/. Exit status: 0 when every step passed,
# 1 when one failed, 2 for a usage error. Device checks are not here: they are the [device] and
# [script] lines of the build plan and the integration checks.
set -u

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
gradle="$root/tools/gradle.sh"
app_id="io.github.dilshodbekmx.passwordvault"
android_home="${PV_ANDROID_HOME:-/home/asus/Android/Sdk}"

usage() { # the comment at the top of this file
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
    exit 2
}

parts=()
gradle_args=()
while (($#)); do
    case "$1" in
        unit | lint | build | offline) parts+=("$1") ;;
        all) parts+=(unit lint build offline) ;;
        --) shift; gradle_args=("$@"); break ;;
        -h | --help) usage ;;
        *) echo "check.sh: unknown part '$1'" >&2; usage ;;
    esac
    shift
done
((${#parts[@]})) || usage

logs="$root/build/check"
rm -rf "$logs"
mkdir -p "$logs"
step=0
results=()
failed=0
assembled=0

# record <name> <status>
record() {
    if (($2 == 0)); then results+=("PASS  $1"); else results+=("FAIL  $1"); failed=1; fi
}

# run_gradle <name> <gradle arguments...>: one step; the console gets the output without task lines.
run_gradle() {
    local name="$1"
    shift
    step=$((step + 1))
    local log
    log="$logs/$(printf '%02d' "$step")-$(echo "$name" | tr -c 'A-Za-z0-9' '-' | tr -s '-' | sed 's/-$//').log"
    echo
    echo "== $name"
    echo "   tools/gradle.sh $* ${gradle_args[*]:-}"
    "$gradle" "$@" ${gradle_args[@]+"${gradle_args[@]}"} 2>&1 | tee "$log" | grep --line-buffered -v -E '^> Task |^$'
    local status=${PIPESTATUS[0]}
    record "$name" "$status"
    ((status == 0)) || echo "   FAILED; the full output is in ${log#"$root"/}"
    return "$status"
}

apk_of() { # the APK last built for variant $1, as AGP's output-metadata.json names it
    local dir="$root/app/build/outputs/apk/$1"
    local name
    name="$(sed -n 's/.*"outputFile": *"\([^"]*\)".*/\1/p' "$dir/output-metadata.json" 2>/dev/null | head -n 1)"
    if [[ -n "$name" && -f "$dir/$name" ]]; then echo "$dir/$name"; fi
}

part_unit() {
    run_gradle "unit: :core tests under tr-TR" :core:testDebugUnitTest -Ppv.testLocale=tr-TR
    run_gradle "unit: :core tests under fa-IR" :core:testDebugUnitTest -Ppv.testLocale=fa-IR
    run_gradle "unit: :app tests" :app:testDebugUnitTest
}

part_lint() {
    run_gradle "lint: :core and :app" :core:lintDebug :app:lintDebug
}

part_build() {
    run_gradle "build: debug, staging and release APKs with the build checks" \
        :app:assembleDebug :app:assembleStaging :app:assembleRelease && assembled=1
}

# The permissions of R-9, the only ones an APK may request.
allowed_permissions=(
    android.permission.USE_BIOMETRIC
    android.permission.USE_FINGERPRINT
    android.permission.HIDE_OVERLAY_WINDOWS
    "$app_id.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)

check_apk_permissions() {
    step=$((step + 1))
    local name="offline: aapt2 dump permissions on the debug, staging and release APKs"
    local log="$logs/$(printf '%02d' "$step")-offline-aapt2.log"
    echo
    echo "== $name"
    local aapt2
    aapt2="$(find "$android_home/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -n 1)"
    local status=0
    : >"$log"
    if [[ -z "$aapt2" ]]; then
        echo "   no aapt2 under $android_home/build-tools" | tee -a "$log"
        record "$name" 1
        return 1
    fi
    for variant in debug staging release; do
        local apk
        apk="$(apk_of "$variant")"
        if [[ -z "$apk" ]]; then
            echo "   $variant: no APK in app/build/outputs/apk/$variant" | tee -a "$log"
            status=1
            continue
        fi
        local dump
        dump="$("$aapt2" dump permissions "$apk" 2>&1)" || {
            echo "   $variant: aapt2 failed: $dump" | tee -a "$log"
            status=1
            continue
        }
        echo "$dump" >>"$log"
        local package
        package="$(sed -n 's/^package: //p' <<<"$dump")"
        if [[ "$package" != "$app_id" ]]; then
            echo "   $variant: package is '$package', not $app_id" | tee -a "$log"
            status=1
        fi
        local bad=()
        local permission
        while read -r permission; do
            [[ -z "$permission" ]] && continue
            local ok=0
            local allowed
            for allowed in "${allowed_permissions[@]}"; do [[ "$permission" == "$allowed" ]] && ok=1; done
            ((ok)) || bad+=("$permission")
        done < <(sed -n "s/^uses-permission[^:]*: name='\([^']*\)'.*/\1/p" <<<"$dump")
        if ((${#bad[@]})); then
            echo "   $variant ($(basename "$apk")): permissions outside R-9's list: ${bad[*]}" | tee -a "$log"
            status=1
        else
            echo "   $variant ($(basename "$apk")): $(grep -c '^uses-permission' <<<"$dump") permissions, all within R-9's list; no INTERNET" | tee -a "$log"
        fi
    done
    record "$name" "$status"
    return "$status"
}

part_offline() {
    run_gradle "offline: merged manifests of debug, staging, release and backupCheck (R-1, R-9)" \
        :app:buildCheckDebugManifest :app:buildCheckStagingManifest \
        :app:buildCheckReleaseManifest :app:buildCheckBackupCheckManifest
    if ((!assembled)); then
        run_gradle "offline: the APKs to inspect" :app:assembleDebug :app:assembleStaging :app:assembleRelease &&
            assembled=1
    fi
    check_apk_permissions
    run_gradle "offline: class scan of the sources" \
        :app:testDebugUnitTest --tests 'io.github.dilshodbekmx.passwordvault.arch.OfflineSourceScanTest'
}

for part in "${parts[@]}"; do
    "part_$part"
done

echo
echo "== Summary (logs in build/check/)"
printf '   %s\n' "${results[@]}"
if ((failed)); then
    echo "   The check FAILED."
    exit 1
fi
echo "   The check passed."
