package io.github.dilshodbekmx.passwordvault.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The three dispatchers of architecture.md §5.3, wrapped so that a test passes one test dispatcher
 * for all of them ([single]).
 *
 * - [main]: `Dispatchers.Main.immediate`. Compose, ViewModels, `LockManager.send` and the
 *   publication of lock state, `BiometricPrompt`, setting and clearing the clipboard, `AutoLock`
 *   callbacks, navigation.
 * - [io]: `Dispatchers.IO`. `OsFiles` (vault generations, lock records), the file picker's streams,
 *   `SharedPreferences.commit()`, Keystore calls (they are binder calls),
 *   `FingerprintKey.availability()`.
 * - [default]: `Dispatchers.Default`. Argon2id, sealing and opening bodies, JSON, sorting, building
 *   and querying the `SearchIndex`, zxcvbn, import parsing, export writing.
 *
 * Nothing blocks the main thread: no `runBlocking`, no file or Keystore call on [main].
 */
class AppDispatchers(val main: CoroutineDispatcher, val io: CoroutineDispatcher, val default: CoroutineDispatcher) {
    companion object {
        /**
         * The app's own, made on first use: `Dispatchers.Main` needs Android's main looper, which a
         * JVM test does not have.
         */
        val Default: AppDispatchers by lazy { AppDispatchers(Dispatchers.Main.immediate, Dispatchers.IO, Dispatchers.Default) }

        /** One dispatcher for all three, for tests. */
        fun single(dispatcher: CoroutineDispatcher): AppDispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher)
    }
}
