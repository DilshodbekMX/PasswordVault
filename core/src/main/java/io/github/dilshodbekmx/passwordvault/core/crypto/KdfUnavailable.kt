package io.github.dilshodbekmx.passwordvault.core.crypto

/**
 * Argon2id cannot run on this phone now: the native library did not load and the Java fallback
 * would not fit in the free heap, or memory ran out (architecture.md §3.2, [V-C2]). The screens
 * say "not enough memory". It is never a wrong password and is never counted (lock.md §6.8).
 */
class KdfUnavailable(cause: Throwable?) : Exception(cause)
