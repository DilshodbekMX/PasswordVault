# Private emulators (package 0.2, in progress)

Work in progress. Done so far:

- `/home/asus/pvlab/sysimg/android-24/default/x86_64/` and `android-28/default/x86_64/`: the AOSP
  images `x86_64-24_r08.zip` and `x86_64-28_r04.zip`, downloaded into `/home/asus/pvlab/dl/` and
  checked against the sha1 sums of `docs/lab/old-android.md` §0 (`dl/expected.sha1`).
- `/home/asus/pvlab/sysimg/android-37.0/google_apis_playstore/x86_64/`: a copy of the SDK's API 37
  image (sha1 of every file in `dl/sysimg-37.sha1`).

Next: the base AVDs in `/home/asus/pvlab/avd/`, their provisioning, then `boot.sh`, `adb.sh`,
`stop.sh`, `smoke.sh`.
