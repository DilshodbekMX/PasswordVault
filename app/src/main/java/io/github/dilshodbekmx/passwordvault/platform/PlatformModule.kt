package io.github.dilshodbekmx.passwordvault.platform

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.Platform

/**
 * Builds [Platform]: `OsFiles`, `AndroidClock`, `SystemRandom`, `NativeArgon2id` behind the gate,
 * and the two roots. A stub until build plan 2.7 replaces the body; nothing the shell runs needs it.
 */
object PlatformModule {
    fun create(graph: AppGraph): Platform = throw NotImplementedError("PlatformModule: the Android adapters come with build plan 2.7")
}
