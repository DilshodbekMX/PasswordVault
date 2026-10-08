package io.github.dilshodbekmx.passwordvault.debug.lock

import android.content.Context
import android.util.Log
import io.github.dilshodbekmx.passwordvault.PasswordVaultApp
import io.github.dilshodbekmx.passwordvault.debug.DebugCommand
import io.github.dilshodbekmx.passwordvault.lock.DefaultLockManager
import io.github.dilshodbekmx.passwordvault.lock.LockDebugImpl

/**
 * The lock's debug commands (lock.md §15.3; architecture.md §3.6 "Debug commands"; build plan 4.1),
 * for the emulator checks E1 to E17 (`tools/emu/lock/`, build plan 4.5). Debug, staging and
 * backupCheck builds only; a release build has neither the receiver nor a lock that these could
 * reach (build check R-2).
 *
 * ```
 * adb -s <serial> shell am broadcast -a io.github.dilshodbekmx.passwordvault.debug.CMD \
 *     -p io.github.dilshodbekmx.passwordvault --es cmd 'ks-fault getkey-null'
 * ```
 *
 * | `cmd` | What it does | Answer (`data` of the broadcast's result) |
 * |---|---|---|
 * | `status` | Nothing; it reads | One line of items separated by `; `, in this order: the lock engine's own description (`state: …`, `visible: …, visit: …, launch outstanding: …`, `canAuthenticate: …, no secure hardware: …`, `vaults: …, put aside: …`, while locked `policy: row …, pin …, fingerprint usable …`, then for every lock directory `lock <vault_id>`, `pin.rec: …` with its slot, `off`, `charged`, `row`, `total` and `unseen`, `fingerprint.rec: …`, `state.rec: …`, and `aliases: …` with the `pv_pin_…` and `pv_fp_…` aliases); then `hold: …`, `fail-next-write: …`, `ks-fault: …`, `keystore: …` (every alias, `pv_canary` among them), `prompt: …, may open by itself: …` and `last PIN unlock: …` (milliseconds from the sixth digit to the unlocked state). While a hold is reached the first item is `engine: held at …, not asked` instead of the engine's description |
 * | `hold-after-charge` | The next PIN entry stops when its charge is on disk (lock.md §6.3 C), before anything is checked | `hold-after-charge: armed; …` |
 * | `hold-before-hmac` | The next PIN check stops just before it asks the Keystore (§6.3 E.3) | `hold-before-hmac: armed; …` |
 * | `release` | Lets a held check go on; disarms a hold that was not reached | `release: released after-charge (reached)` (or `before-hmac`, or `(armed)`), or `release: nothing was held` |
 * | `fail-next-write` | The next write of a lock record fails, with nothing written | `fail-next-write: armed; …` |
 * | `ks-fault ROW` | Arms the Keystore fault ROW for the next call that meets it: a name of `KeystoreFaults.rows`, or `prompt-error-<code>`; `none` disarms; without ROW it only reads | `ks-fault: ROW`, `ks-fault: none`, or without ROW `ks-fault: <armed or none>; rows: <every name>` |
 * | `drop-keys` | Deletes every alias of the app in the Keystore, the canary too | `drop-keys: N of M aliases deleted` and, when some stayed, `; left: …` |
 *
 * Every answer is also logged under the tag [TAG], the items of `status` one per line (the receiver
 * logs the answer under `PVDEBUG` as well). A name with words it does not take, or a `ks-fault` row that no fault has,
 * ends as the receiver's "failed" (result 3). No answer holds a secret: no digit, password, key or
 * entry text.
 *
 * Each command needs its line in `DebugCommands.all`, which the integrator keeps: [all] is the seven
 * of them by name.
 */
object LockCommands {
    /** The log tag of the lock's own lines (lock.md §15.3). */
    const val TAG = "PVLOCK"

    val Status: DebugCommand = LockCommand(LockDebugImpl.STATUS)
    val HoldAfterCharge: DebugCommand = LockCommand(LockDebugImpl.HOLD_AFTER_CHARGE)
    val HoldBeforeHmac: DebugCommand = LockCommand(LockDebugImpl.HOLD_BEFORE_HMAC)
    val Release: DebugCommand = LockCommand(LockDebugImpl.RELEASE)
    val FailNextWrite: DebugCommand = LockCommand(LockDebugImpl.FAIL_NEXT_WRITE)
    val KsFault: DebugCommand = LockCommand(LockDebugImpl.KS_FAULT)
    val DropKeys: DebugCommand = LockCommand(LockDebugImpl.DROP_KEYS)

    /** The seven commands by the name `cmd` starts with: what `DebugCommands.all` lists. */
    val all: Map<String, DebugCommand> = mapOf(
        LockDebugImpl.STATUS to Status,
        LockDebugImpl.HOLD_AFTER_CHARGE to HoldAfterCharge,
        LockDebugImpl.HOLD_BEFORE_HMAC to HoldBeforeHmac,
        LockDebugImpl.RELEASE to Release,
        LockDebugImpl.FAIL_NEXT_WRITE to FailNextWrite,
        LockDebugImpl.KS_FAULT to KsFault,
        LockDebugImpl.DROP_KEYS to DropKeys,
    )

    /** The command [name] of the lock's test hooks, run on the app's own lock. */
    private class LockCommand(private val name: String) : DebugCommand {
        override suspend fun run(context: Context, args: List<String>): String {
            val lock = (context.applicationContext as PasswordVaultApp).graph.lock
            val hooks = (lock as? DefaultLockManager)?.debug as? LockDebugImpl ?: return "$name: this build's lock has no test hooks"
            val answer = try {
                hooks.command(name, args)
            } catch (e: IllegalArgumentException) {
                Log.i(TAG, "$name: refused (" + e.message + ")") // the message is this code's own text
                throw e
            }
            if (name == LockDebugImpl.STATUS) answer.split(LockDebugImpl.LINE_SEPARATOR).forEach { Log.i(TAG, it) } else Log.i(TAG, answer)
            return answer
        }
    }
}
