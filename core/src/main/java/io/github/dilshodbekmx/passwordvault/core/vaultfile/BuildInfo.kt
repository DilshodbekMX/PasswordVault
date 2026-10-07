package io.github.dilshodbekmx.passwordvault.core.vaultfile

/**
 * What a writer puts into every header it seals (architecture.md §4.2, §4.5 rule 3): [generator] is
 * `"PasswordVault " + versionName` and must match `[A-Za-z0-9 ._()-]{1,64}` (a build check, R-14);
 * [writerVersion] is the build's `versionCode`. `AppGraph` makes the one instance from `BuildConfig`.
 */
data class BuildInfo(val generator: String, val writerVersion: Int)
