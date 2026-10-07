package io.github.dilshodbekmx.passwordvault

import android.app.Application
import io.github.dilshodbekmx.passwordvault.clipboard.ClipboardModule
import io.github.dilshodbekmx.passwordvault.clipboard.SecretClipboard
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterKdf
import io.github.dilshodbekmx.passwordvault.core.lock.flow.DeviceKeys
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinKey
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultPort
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordFiles
import io.github.dilshodbekmx.passwordvault.core.storage.FilePrimitives
import io.github.dilshodbekmx.passwordvault.core.util.Clock
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.vaultfile.BuildInfo
import io.github.dilshodbekmx.passwordvault.data.DataModule
import io.github.dilshodbekmx.passwordvault.data.ListModelSource
import io.github.dilshodbekmx.passwordvault.data.SecurityModule
import io.github.dilshodbekmx.passwordvault.data.SecurityReports
import io.github.dilshodbekmx.passwordvault.data.VaultRepository
import io.github.dilshodbekmx.passwordvault.files.DocumentIo
import io.github.dilshodbekmx.passwordvault.files.FilesModule
import io.github.dilshodbekmx.passwordvault.keystore.KeystoreModule
import io.github.dilshodbekmx.passwordvault.lock.BiometricLauncher
import io.github.dilshodbekmx.passwordvault.lock.LockManager
import io.github.dilshodbekmx.passwordvault.lock.LockModule
import io.github.dilshodbekmx.passwordvault.platform.AppDispatchers
import io.github.dilshodbekmx.passwordvault.platform.PlatformModule
import io.github.dilshodbekmx.passwordvault.session.Session
import io.github.dilshodbekmx.passwordvault.session.SessionModule
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.SettingsModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's one object graph, built by [PasswordVaultApp] when the process starts (architecture.md
 * §1 item 9, §3.6, §5.1): no dependency injection framework. Each area plugs in through its own
 * `…Module.create(graph)`, which returns the fake of `APP/fake/` until the area's package replaces
 * the body; nobody else edits this file. Everything here lives as long as the process, outside the
 * activity, so that a rotation or a language change neither locks nor loses input (lock.md §11).
 */
class AppGraph(val app: Application) {
    val dispatchers: AppDispatchers = AppDispatchers.Default

    /** Saves and record writes that have started always finish here (architecture.md §5.3). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The running app's writer: "PasswordVault " + versionName and versionCode (R-14). */
    val build = BuildInfo("PasswordVault " + BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    /** Files, records, clock, random, the one Argon2id gate, the two roots (build plan 2.7). */
    val platform: Platform by lazy { PlatformModule.create(this) }
    val settings: AppSettings by lazy { SettingsModule.create(this) }

    /** DeviceKeys, PinKey, FingerprintKey, BiometricLauncher (build plan 3.9, 4.1). */
    val keys: Keys by lazy { KeystoreModule.create(this) }

    /** VaultRepository, VaultPort, ListModelSource (build plan 3.1). */
    val data: Data by lazy { DataModule.create(this) }
    val security: SecurityReports by lazy { SecurityModule.create(this) }
    val lock: LockManager by lazy { LockModule.create(this) }
    val session: Session by lazy { SessionModule.create(this) }
    val clipboard: SecretClipboard by lazy { ClipboardModule.create(this) }
    val files: DocumentIo by lazy { FilesModule.create(this) }
}

/**
 * The Android implementations of the `:core` ports (architecture.md §2.2 `platform`, build plan 2.7).
 *
 * @property files the vault generations' file primitives (`OsFiles`).
 * @property records the lock records' files (`OsFiles`).
 * @property kdf the one Argon2id gate of the process (`GatedMasterKdf` over `GuardedArgon2id`, with
 *   the native library or the Java fallback): every derivation of the app goes through it (§5.2).
 * @property vaultsDir `<filesDir>/vaults` (architecture.md §4.7).
 * @property lockRoot `<noBackupFilesDir>/lock` (lock.md §5.2, R-3).
 */
class Platform(
    val files: FilePrimitives,
    val records: RecordFiles,
    val clock: Clock,
    val random: RandomSource,
    val kdf: MasterKdf,
    val vaultsDir: String,
    val lockRoot: String,
)

/** The Keystore and the fingerprint prompt (architecture.md §2.2 `keystore`, `lock`; build plan 3.9, 4.1). */
class Keys(val device: DeviceKeys, val pin: PinKey, val fingerprint: FingerprintKey, val launcher: BiometricLauncher)

/**
 * The vault (architecture.md §2.2 `data`, build plan 3.1): [repository] and [port] are the same
 * `DefaultVaultRepository`, seen by the screens and by the lock.
 */
class Data(val repository: VaultRepository, val port: VaultPort, val lists: ListModelSource)
