# Durable atomic write lab: Android 17 (API 37) emulator

Measured on 2026-10-06 on the `Medium_Phone_API_37.0` emulator image (Android 17, x86_64, `/data` on ext4), with a test app that ran the planned save procedure in `filesDir/vault` and `noBackupFilesDir/vault`. Three full runs were recorded before the lab's agent was interrupted; the clipboard half of the same lab did not finish and is reported separately. The numbers come from the raw log (`io-all-runs.jsonl`, 138 records). Android 7.0 and 9 were checked for the same calls in [old-android.md](old-android.md) (they succeed there too). No real phone and no f2fs file system was measured.

## The procedure tested

1. Write the bytes to `NAME.tmp`, flush, `FileOutputStream.fd.sync()`, close.
2. `Os.rename(tmp, dst)`.
3. `Os.open(directory, O_RDONLY | O_CLOEXEC, 0)`, `Os.fsync(fd)`, `Os.close(fd)`.

## Results

Every call succeeded in both directories, including the fsync on the directory descriptor (no `ErrnoException`, no SELinux denial). All 360 written files read back identical, and no temporary file was left behind.

| Directory | Payload | Median commit | Slowest of 60 | File fsync (median) | Rename | Directory fsync |
|---|---|---|---|---|---|---|
| `filesDir` | 100 KB | 0.7 to 2.5 ms | 26.4 ms | 0.4 to 1.1 ms | 0.02 to 0.05 ms | 0.13 to 0.24 ms |
| `filesDir` | 1 MB | 1.7 to 4.9 ms | 17.9 ms | 1.1 to 3.8 ms | 0.02 to 0.06 ms | 0.15 to 0.47 ms |
| `filesDir` | 5 MB | 5.1 to 5.7 ms | 11.1 ms | 3.0 to 3.4 ms | 0.04 to 0.05 ms | 0.22 to 0.26 ms |
| `noBackupFilesDir` | 100 KB | 0.4 to 0.6 ms | 5.3 ms | 0.2 to 0.4 ms | 0.01 to 0.02 ms | 0.13 to 0.14 ms |
| `noBackupFilesDir` | 1 MB | 1.3 to 1.9 ms | 7.6 ms | 0.8 to 1.1 ms | 0.02 to 0.03 ms | 0.12 to 0.20 ms |
| `noBackupFilesDir` | 5 MB | 4.1 to 4.9 ms | 9.3 ms | 2.5 to 2.9 ms | 0.03 to 0.05 ms | 0.19 to 0.23 ms |

Each cell is the range of medians over the three runs (20 commits per run and size). The host is a fast desktop with an NVMe drive, so a phone will be slower; the result that matters is that the procedure works and the directory fsync is cheap on ext4. On f2fs a directory fsync forces a checkpoint and may cost more ([VERIFICATION.md](../research/VERIFICATION.md), V-S8).

## Failures surface as exceptions

Each of these threw, in all three runs and in both directories, instead of failing silently:

- `Os.rename` into a directory that does not exist; the whole commit into a missing directory. `File.renameTo` into a missing directory was reported as a failure too.
- The commit into a read-only directory; `Os.rename` inside a read-only directory; `Os.rename` from a writable directory into a read-only one. The temporary file stayed in place after the failed rename.
- `fd.sync()` after the stream was closed; `Os.fsync` on a closed descriptor; `Os.fsync` on a directory descriptor after `Os.close`; closing a directory descriptor twice.

These succeeded, as expected: writing and syncing a file that was opened before its directory became read-only, and fsync on a read-only directory.

## The one result that changes the design

`Os.rename` onto an existing target **succeeded and replaced it** (test R1, all runs). The save procedure therefore cannot rely on "the target name never exists": it must check under the vault lock that the target is absent and refuse otherwise (V-S1).
