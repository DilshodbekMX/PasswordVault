package io.github.dilshodbekmx.passwordvault.core.lock.flow

/** What making a Keystore key came to: the NOT NOW rows of lock.md §14.3 (architecture.md §3.4). */
sealed interface KeyCreate {
    /** Made, and in secure hardware (lock.md §4.5), or the build allows software keys (`ALLOW_SOFTWARE_KEYSTORE`). */
    data object Ok : KeyCreate

    /** Any passing failure, the 50,000-key limit of API 37 among them: the half-made alias was deleted. */
    data object NotNow : KeyCreate

    /** Fingerprint only: no biometric is enrolled. "Add a fingerprint in your phone's settings first." */
    data object NoneEnrolled : KeyCreate

    /** `KeyInfo` says software, in a release build: for good (lock.md F18). The key was deleted. */
    data object NoSecureHardware : KeyCreate
}
