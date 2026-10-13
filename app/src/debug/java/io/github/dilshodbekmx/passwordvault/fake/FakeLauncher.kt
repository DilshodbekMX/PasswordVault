package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.core.lock.flow.PromptUse
import io.github.dilshodbekmx.passwordvault.lock.BiometricLauncher
import io.github.dilshodbekmx.passwordvault.lock.PromptOutcome
import javax.crypto.Cipher

/**
 * A [BiometricLauncher] with no prompt (architecture.md §2.2 `fake`; build plan 3.9 tests the
 * Keystore adapters with it). Each call takes the next of [answers], made with [succeed], [error]
 * or [notShown]; when there is none the prompt is [PromptOutcome.NotShown]. [requests] records
 * every call, [cancels] counts [cancel]. Single-threaded.
 */
class FakeLauncher : BiometricLauncher {
    /** One call: what the prompt was for. */
    data class Request(val use: PromptUse, val launch: Int, val pinUsable: Boolean)

    /** The answers of the next prompts; each receives the cipher it was given. */
    val answers = ArrayDeque<(Cipher) -> PromptOutcome>()

    val requests = mutableListOf<Request>()

    var cancels: Int = 0
        private set

    /** The next prompt succeeds and hands back the cipher it was given, as the library does. */
    fun succeed() {
        answers += { cipher -> PromptOutcome.Succeeded(cipher) }
    }

    /** The next prompt ends with the error [code]. */
    fun error(code: Int) {
        answers += { PromptOutcome.Error(code) }
    }

    /** The next prompt cannot be shown. */
    fun notShown() {
        answers += { PromptOutcome.NotShown }
    }

    override suspend fun authenticate(cipher: Cipher, use: PromptUse, launch: Int, pinUsable: Boolean): PromptOutcome {
        requests += Request(use, launch, pinUsable)
        return answers.removeFirstOrNull()?.invoke(cipher) ?: PromptOutcome.NotShown
    }

    override fun cancel() {
        cancels++
    }
}
