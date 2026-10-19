import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Navigation 3 route keys are @Serializable (architecture.md §2.2 `nav`).
    alias(libs.plugins.kotlin.serialization)
}

/** The app ID in every build type, with no suffix. It must never change (build check R-14). */
val appId = "io.github.dilshodbekmx.passwordvault"

/**
 * Release signing (architecture.md §7.2): `keystore.properties` (storeFile, storePassword, keyAlias,
 * keyPassword; storeFile an absolute path, or a path relative to the project root) and the keystore
 * stay outside git. Without them the release build gives an unsigned APK.
 */
val releaseKeys: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = appId
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeys != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    // The four build types of architecture.md §7.2. The build checks at the end of this file compare
    // every variant with that table again, so a change here that breaks it fails the build.
    buildTypes {
        debug {
            isDebuggable = true
            isPseudoLocalesEnabled = true // en-XA and ar-XB (i18n.md §1.1)
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            buildConfigField("boolean", "ALLOW_SOFTWARE_KEYSTORE", "true")
            buildConfigField("boolean", "SECURE_WINDOW", "false")
        }
        release {
            // Full R8. The template's packageScope setting produced a release build that
            // crashed at startup (IllegalAccessError in androidx.startup) with these libraries.
            optimization {
                enable = true
            }
            isDebuggable = false
            // Phones only: an APK with arm libraries alone does not install on the x86_64
            // emulators, which is why staging exists [V-S9].
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            buildConfigField("boolean", "ALLOW_SOFTWARE_KEYSTORE", "false") // lock.md §4.5
            buildConfigField("boolean", "SECURE_WINDOW", "true")
            signingConfig = if (releaseKeys != null) signingConfigs.getByName("release") else null
        }
        // The emulator checks against the optimised code: R8 on and not debuggable like release, but
        // signed with the debug key, with the x86_64 libraries, software keys allowed and the debug
        // receiver (lock.md §15.3). Never given to anyone.
        create("staging") {
            initWith(getByName("release"))
            optimization {
                enable = true
            }
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
            buildConfigField("boolean", "ALLOW_SOFTWARE_KEYSTORE", "true")
            buildConfigField("boolean", "SECURE_WINDOW", "true")
            matchingFallbacks += listOf("release")
        }
        // Built only by lock.md check E15: debug with a manifest overlay that allows Android backup.
        create("backupCheck") {
            initWith(getByName("debug"))
            isDebuggable = true
            isPseudoLocalesEnabled = true
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
            buildConfigField("boolean", "ALLOW_SOFTWARE_KEYSTORE", "true")
            buildConfigField("boolean", "SECURE_WINDOW", "false")
            matchingFallbacks += listOf("debug")
        }
    }

    sourceSets {
        // The test hooks of src/debug (DebugCommandReceiver and its commands) are also in staging
        // and backupCheck; each of those has its own manifest that declares the receiver. Built-in
        // Kotlin compiles only the kotlin directories it is given, hence both sets. Build check
        // R-2 fails if the receiver's class is missing from a variant that declares it.
        for (set in listOf("staging", "backupCheck")) {
            named(set) {
                java.directories += "src/debug/java"
                kotlin.directories += "src/debug/java"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The four languages (i18n.md §1.1, §1.4.2). res/resources.properties sets unqualifiedResLocale=en.
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "uz", "b+uz+Cyrl", "ru")
    }
    bundle {
        language {
            enableSplit = false // only matters if an App Bundle is ever made (i18n.md §1.4.6)
        }
    }
    // AGP otherwise puts a list of the app's libraries into the signing block of every signed APK,
    // encrypted so that only Google Play can read it, and new at every packaging. The app never goes
    // through Play: the user's APK carries no such block, and two builds of one commit signed with
    // one key are the same file.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    lint {
        // i18n.md §1.1 makes these errors. MissingTranslation was a warning from round R3 until the
        // merge of round R6 (build-plan.md §8, "Before 6A starts" and "Integration check for wave
        // 6"), while the translations came; it ignores values-b+uz+Cyrl, which ResourcesTest covers.
        error += listOf(
            "MissingTranslation", "ExtraTranslation", "MissingQuantity",
            "StringFormatMatches", "StringFormatInvalid", "TypographyEllipsis",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.argon2kt)
    // Previews and Compose tests; staging and release have neither (build check R-9).
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "backupCheckImplementation"(libs.androidx.compose.ui.tooling)
    "backupCheckImplementation"(libs.androidx.compose.ui.test.manifest)
    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":testing"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// The source-rule tests (app/src/test/.../arch) read every source set the app is built from, and the
// main sources of :core and :testing, so a change to any of them runs the tests again instead of
// taking a cached result.
tasks.withType<Test>().configureEach {
    val sets = listOf("main", "debug", "staging", "backupCheck", "release").map { "app/src/$it" } +
        listOf("core/src/main", "testing/src/main")
    inputs.files(sets.map { dir -> fileTree(rootDir.resolve(dir)) { include("**/*.kt", "**/*.java") } })
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("scannedSources")
    // ResourcesTest (build plan 2.9) reads the string files themselves. A change to a text alone leaves
    // R unchanged, so without these inputs the test task would stay up to date (or come from the build
    // cache) and pass on a stale result.
    inputs.files(fileTree(rootDir.resolve("app/src/main/res")) { include("values*/*.xml") })
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("stringResources")
}

// ---------------------------------------------------------------------------------------------
// Build checks (architecture.md §8.5, lock.md §15.5). Each variant's `assemble` runs its checks:
// buildCheck<Variant>Manifest (R-1, R-5, R-7, R-9, R-14, allowBackup and debuggable), ...Apk (R-2,
// R-8, R-17), ...BuildConfig (R-2), ...R8 (R-16, where R8 runs) and buildCheckNoTrackedBuildOutput
// (R-12); `buildCheck<Variant>Manifest` also runs on its own (tools/check.sh offline). The source
// scans R-4, R-6, R-10, R-11, R-13 and R-15 are unit tests (arch/ in the tests of :core and :app);
// R-3 is a device test.
// ---------------------------------------------------------------------------------------------

/**
 * What architecture.md §7.2 and §8.5 require of one variant, written out here a second time on
 * purpose: the checks compare what was built with this table, not with the DSL above.
 */
class VariantSpec(
    val allowSoftwareKeystore: Boolean,
    val secureWindow: Boolean,
    val abis: Set<String>,
    /** R-9: exactly these components are exported. */
    val exported: Set<String>,
    /** R-2: whether the debug receiver's class is in the APK. */
    val hasDebugReceiver: Boolean,
    val allowBackup: Boolean,
    /**
     * R8 runs (§7.2), so R-16 reads the variant's mapping, and R-17 reads the APK's classes through
     * it. These are the optimised builds, which also leave out every JVM class file (R-17).
     */
    val r8: Boolean,
    /** The merged manifest's `android:debuggable` (§7.2, "Debuggable"). */
    val debuggable: Boolean,
    /**
     * R-17: src/debug is one of the variant's source sets, so classes of the app's `debug` and
     * `fake` packages may be in its APK. Where it is not (release), none may.
     */
    val debugSources: Boolean,
)

val mainActivity = "$appId.MainActivity"
val profileInstallReceiver = "androidx.profileinstaller.ProfileInstallReceiver"
val debugReceiver = "$appId.debug.DebugCommandReceiver"
val releaseExported = setOf(mainActivity, profileInstallReceiver)
val stagingExported = releaseExported + debugReceiver
val debugExported = stagingExported + setOf(
    "androidx.compose.ui.tooling.PreviewActivity", // ui-tooling
    "androidx.activity.ComponentActivity", // ui-test-manifest
)
val phoneAbis = setOf("arm64-v8a", "armeabi-v7a")
val emulatorAbis = phoneAbis + "x86_64"

val variantSpecs = mapOf(
    "debug" to VariantSpec(
        true, false, emulatorAbis, debugExported, true, false, r8 = false, debuggable = true, debugSources = true,
    ),
    "staging" to VariantSpec(
        true, true, emulatorAbis, stagingExported, true, false, r8 = true, debuggable = false, debugSources = true,
    ),
    "release" to VariantSpec(
        false, true, phoneAbis, releaseExported, false, false, r8 = true, debuggable = false, debugSources = false,
    ),
    "backupCheck" to VariantSpec(
        true, false, emulatorAbis, debugExported, true, true, r8 = false, debuggable = true, debugSources = true,
    ),
)

/**
 * R-17: the app's test hooks (src/debug: the debug commands and the fakes of the app's interfaces)
 * and the fakes of `:testing`, which is only ever a test dependency. A package here includes its
 * sub-packages.
 */
val debugPackages = setOf("$appId.debug", "$appId.fake")
val testingPackage = "$appId.testing"

/**
 * R-16: the members that R8 must keep under their own names, by class, as `mapping.txt` writes
 * them. androidx.biometric's FingerprintManager callback (Android 6 to 8.1, and Samsung phones with
 * Android 9): compileSdk 37's android.jar has no FingerprintManager, so R8 does not see that these
 * override the platform's methods, and only the rule in src/main/keepRules/rules.keep keeps them
 * (lock.md §9.1 item 7).
 */
val r8MustKeep = mapOf(
    "androidx.biometric.internal.FingerprintManagerCompat\$Api23Impl\$1" to listOf(
        "void onAuthenticationError(int,java.lang.CharSequence)",
        "void onAuthenticationHelp(int,java.lang.CharSequence)",
        "void onAuthenticationSucceeded(android.hardware.fingerprint.FingerprintManager\$AuthenticationResult)",
        "void onAuthenticationFailed()",
    ),
)

/** R-9: the only permissions the app may hold. R-7: HIDE_OVERLAY_WINDOWS must be one of them. */
val allowedPermissions = setOf(
    "android.permission.USE_BIOMETRIC",
    "android.permission.USE_FINGERPRINT",
    "android.permission.HIDE_OVERLAY_WINDOWS",
    "$appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)

/**
 * R-1 (no INTERNET), R-5 (FingerprintDialogActivity not exported), R-7 (HIDE_OVERLAY_WINDOWS),
 * R-9 (permissions and exported components), R-14 (package and generator string), and the
 * variant's allowBackup and debuggable, read from the merged manifest, which includes everything
 * libraries add.
 */
abstract class CheckMergedManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val appId: Property<String>
    @get:Input abstract val allowedPermissions: SetProperty<String>
    @get:Input abstract val exported: SetProperty<String>
    @get:Input abstract val allowBackup: Property<Boolean>
    @get:Input abstract val debuggable: Property<Boolean>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val android = "http://schemas.android.com/apk/res/android"
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val manifest = factory.newDocumentBuilder().parse(mergedManifest.get().asFile).documentElement
        fun Element.children(vararg tags: String): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName in tags }
        fun Element.android(attr: String): String = getAttributeNS(android, attr)

        val pkg = manifest.getAttribute("package")
        fun fullName(name: String) = when {
            name.startsWith(".") -> pkg + name
            '.' !in name -> "$pkg.$name"
            else -> name
        }
        val problems = mutableListOf<String>()

        // R-14
        if (pkg != appId.get()) problems += "R-14: the merged manifest's package is '$pkg', not '${appId.get()}'"
        val generator = "PasswordVault " + manifest.android("versionName")
        if (!Regex("[A-Za-z0-9 ._()-]{1,64}").matches(generator)) {
            problems += "R-14: the generator string '$generator' does not match [A-Za-z0-9 ._()-]{1,64}"
        }

        // R-1, R-7, R-9 (permissions)
        val uses = manifest.children("uses-permission", "uses-permission-sdk-23").map { it.android("name") }.toSortedSet()
        if ("android.permission.INTERNET" in uses) {
            problems += "R-1: the app requests android.permission.INTERNET, but it must stay offline. Find the " +
                "library that adds it (see the manifest merger report) and remove it with tools:node=\"remove\""
        }
        if ("android.permission.HIDE_OVERLAY_WINDOWS" !in uses) problems += "R-7: HIDE_OVERLAY_WINDOWS is not declared"
        val extra = uses - allowedPermissions.get()
        if (extra.isNotEmpty()) problems += "R-9: permissions outside the allowed list: $extra"
        val declared = manifest.children("permission", "permission-group", "permission-tree").map { it.android("name") }.toSortedSet()
        val ownDeclared = declared - setOf("${appId.get()}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        if (ownDeclared.isNotEmpty()) problems += "R-9: the app declares unexpected permissions: $ownDeclared"

        // R-5, R-9 (exported components and their guards)
        val application = manifest.children("application").single()
        val exportedNow = sortedSetOf<String>()
        for (component in application.children("activity", "activity-alias", "service", "receiver", "provider")) {
            val name = fullName(component.android("name"))
            val exportedAttr = component.android("exported")
            val isExported = when (exportedAttr) {
                "true" -> true
                "false" -> false
                else -> component.tagName != "provider" && component.children("intent-filter").isNotEmpty()
            }
            if (name.endsWith(".FingerprintDialogActivity") && isExported) {
                problems += "R-5: $name is exported"
            }
            if (!isExported) continue
            exportedNow += name
            val guardedByDump = name == "${appId.get()}.debug.DebugCommandReceiver" ||
                name == "androidx.profileinstaller.ProfileInstallReceiver"
            if (guardedByDump && component.android("permission") != "android.permission.DUMP") {
                problems += "R-9: $name is exported without android:permission=\"android.permission.DUMP\""
            }
        }
        val expected = exported.get().toSortedSet()
        if (exportedNow != expected) {
            problems += "R-9: exported components are $exportedNow; expected exactly $expected " +
                "(missing ${expected - exportedNow}, unexpected ${exportedNow - expected})"
        }

        // allowBackup: false everywhere except backupCheck (architecture.md §7.2, [V-P6])
        val backup = application.android("allowBackup") == "true"
        if (backup != allowBackup.get()) problems += "allowBackup is $backup, expected ${allowBackup.get()}"

        // debuggable: only debug and backupCheck (architecture.md §7.2). A debuggable build lets
        // `run-as` read the vault and the lock records, and a debugger attach.
        val isDebuggable = application.android("debuggable") == "true"
        if (isDebuggable != debuggable.get()) {
            problems += "android:debuggable is $isDebuggable, expected ${debuggable.get()}"
        }

        val v = variantName.get()
        if (problems.isNotEmpty()) {
            throw GradleException("Build checks failed for the merged manifest of '$v':\n- " + problems.joinToString("\n- "))
        }
        report.get().asFile.writeText(
            "Merged manifest of '$v': package $pkg, generator '$generator'\n" +
                "permissions: $uses\nexported: $exportedNow\nallowBackup: $backup\ndebuggable: $isDebuggable\n" +
                "R-1, R-5, R-7, R-9 and R-14 pass.\n",
        )
    }
}

/**
 * R-8: the APK's native libraries are exactly the variant's ABIs (release: arm64-v8a and
 * armeabi-v7a only). R-2: the debug receiver's class is in the APK exactly where the variant
 * declares the receiver, and never in release.
 *
 * R-17: no class of [forbiddenPackages] is in the APK: in release, none of the app's `debug` and
 * `fake` packages (src/debug, which release does not compile), and in no variant any of `:testing`.
 * The classes are those the APK's dex files define, under their original names: where R8 ran, its
 * mapping gives them, since R8 renames and moves almost every class (release's are a00, b01, ...,
 * whatever their packages were). Where R8 ran, its mapping also names no code of those packages,
 * so nothing of them was inlined into or merged with another class; no dex file names one of their
 * types; and the APK holds no JVM class file, which ART would never load (kotlinx-coroutines'
 * DebugProbesKt.bin is left out of these builds), and no dex, jar or APK file other than its own
 * classes*.dex, whose classes this check could not see. The report counts the classes of
 * [countedPackages].
 */
abstract class CheckApk : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkFolder: DirectoryProperty

    /** R8's mapping of the variant, only where R8 runs. */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:Internal abstract val artifactsLoader: Property<BuiltArtifactsLoader>
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val abis: SetProperty<String>
    @get:Input abstract val debugReceiver: Property<String>
    @get:Input abstract val hasDebugReceiver: Property<Boolean>
    @get:Input abstract val forbiddenPackages: SetProperty<String>
    @get:Input abstract val countedPackages: SetProperty<String>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val v = variantName.get()
        val apks = artifactsLoader.get().load(apkFolder.get())?.elements?.map { File(it.outputFile) }.orEmpty()
        if (apks.isEmpty()) throw GradleException("No APK found for '$v' in ${apkFolder.get().asFile}")
        val descriptor = "L" + debugReceiver.get().replace('.', '/') + ";"
        val forbidden = forbiddenPackages.get().toSortedSet()
        val counted = (countedPackages.get() + forbidden).toSortedSet()
        val r8 = mapping.isPresent
        val map = if (r8) readMapping(mapping.get().asFile, forbidden) else null
        val problems = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                val found = names.filter { it.startsWith("lib/") && it.count { c -> c == '/' } >= 2 }
                    .map { it.split('/')[1] }.toSortedSet()
                if (found != abis.get().toSortedSet()) {
                    problems += "R-8: ${apk.name} has native libraries for $found; expected exactly ${abis.get().toSortedSet()}"
                }
                val dexNames = names.filter { Regex("classes\\d*\\.dex").matches(it) }
                if (dexNames.isEmpty()) problems += "R-17: ${apk.name} has no classes.dex"
                var inDex = false
                val classes = mutableListOf<String>()
                val typesNamed = forbidden.associateWith { mutableListOf<String>() }
                for (dex in dexNames) {
                    val bytes = zip.getInputStream(zip.getEntry(dex)).use { it.readBytes() }
                    val text = String(bytes, Charsets.ISO_8859_1)
                    if (text.contains(descriptor)) inDex = true
                    for (p in forbidden) {
                        val typePrefix = "L" + p.replace('.', '/') + "/"
                        if (text.contains(typePrefix)) typesNamed.getValue(p) += dex
                    }
                    classes += dexClassNames(bytes, "${apk.name}!$dex")
                }
                if (inDex != hasDebugReceiver.get()) {
                    problems += "R-2: ${debugReceiver.get()} is ${if (inDex) "" else "not "}in ${apk.name}; " +
                        "expected ${if (hasDebugReceiver.get()) "in" else "not in"} '$v'"
                }

                // R-17. A class that the mapping does not list kept its name.
                val original = classes.map { map?.originalOf?.get(it) ?: it }
                val unlisted = if (map == null) 0 else classes.count { it !in map.originalOf }
                val counts = counted.associateWith { p -> original.filter { it == p || it.startsWith("$p.") }.sorted() }
                for (p in forbidden) {
                    val hits = counts.getValue(p)
                    if (hits.isNotEmpty()) {
                        problems += "R-17: ${apk.name} holds classes of $p (${hits.size}), for example ${hits.take(3)}"
                    }
                    if (typesNamed.getValue(p).isNotEmpty()) {
                        problems += "R-17: ${typesNamed.getValue(p)} of ${apk.name} name types of $p"
                    }
                    val mentions = map?.mentions?.getValue(p)
                    if (mentions != null && mentions.count > 0) {
                        problems += "R-17: R8's mapping of '$v' names code of $p (a class, or code inlined into or merged " +
                            "with another) on ${mentions.count} lines, for example ${mentions.examples}"
                    }
                }
                val classFiles = if (r8) jvmClassFiles(zip, names) else emptyList()
                if (classFiles.isNotEmpty()) {
                    problems += "R-17: ${apk.name} holds JVM class files, which ART never loads: $classFiles"
                }
                // Files that would hold code outside the APK's own classes*.dex.
                val otherCodeFile = Regex("(?i).*\\.(dex|jar|apk|odex|vdex|oat)")
                val otherCode = if (r8) names.filter { it.matches(otherCodeFile) && it !in dexNames } else emptyList()
                if (otherCode.isNotEmpty()) {
                    problems += "R-17: ${apk.name} holds code besides its classes*.dex, which this check cannot " +
                        "read: $otherCode"
                }

                lines += "${apk.name}: lib/ $found, debug receiver ${if (inDex) "present" else "absent"}"
                lines += "${apk.name}: ${classes.size} classes (dex files: ${dexNames.size})" +
                    if (map == null) {
                        ", under their own names (no R8)"
                    } else {
                        ", named back through R8's mapping (${map.originalOf.size} class lines; " +
                            "$unlisted classes not listed there, so not renamed)"
                    }
                for ((p, hits) in counts) {
                    lines += "  $p: ${hits.size} classes" + if (p in forbidden) " (must be 0)" else ""
                }
                if (map != null) {
                    lines += "  R8's mapping names code of " +
                        forbidden.joinToString(", ") { p -> "$p on ${map.mentions.getValue(p).count} lines" }
                    lines += "  JVM class files in the APK: ${classFiles.size}; " +
                        "other dex, jar or APK files: ${otherCode.size}"
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException("Build checks failed for the APK of '$v':\n- " + problems.joinToString("\n- "))
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\nR-2 (APK part), R-8 and R-17 pass.\n"))
    }

    /** How often, and on which lines, a mapping names code of one package. */
    class Mentions {
        var count = 0
        val examples = mutableListOf<String>()
    }

    /** R8's mapping: each class line as R8's name to the original one, and the lines naming code of watched packages. */
    class Mapping(val originalOf: Map<String, String>, val mentions: Map<String, Mentions>)

    /**
     * Reads `mapping.txt`, where a class is a line `original -> renamed:` and its members are indented
     * lines below it; an inlined method's frame names its own class (`1:2:void a.B.c():3:4 -> d`), and
     * comment lines (`#`) carry R8's notes in JSON, some with type descriptors.
     */
    private fun readMapping(file: File, watched: Set<String>): Mapping {
        val originalOf = HashMap<String, String>()
        val mentions = watched.associateWith { Mentions() }
        val spellings = watched.associateWith { listOf("$it.", it.replace('.', '/') + "/") }
        val classLine = Regex("(\\S+) -> (\\S+):")
        file.useLines { lines ->
            for (line in lines) {
                for ((p, forms) in spellings) {
                    if (forms.any { line.contains(it) }) {
                        val m = mentions.getValue(p)
                        m.count++
                        if (m.examples.size < 3) m.examples += line.trim()
                    }
                }
                if (line.isEmpty() || line[0] == '#' || line[0].isWhitespace()) continue
                classLine.matchEntire(line)?.let { originalOf[it.groupValues[2]] = it.groupValues[1] }
            }
        }
        return Mapping(originalOf, mentions)
    }

    /**
     * The classes a dex file defines, from its `class_defs`, as `a.b.C`. Dex 035 to 040 hold one dex
     * per file; a later version may hold several in one container, which this does not read, so it
     * fails rather than check part of the file.
     */
    private fun dexClassNames(dex: ByteArray, where: String): List<String> {
        fun fail(why: String): Nothing = throw GradleException("R-17: cannot read $where: $why")
        if (dex.size < 0x70) fail("${dex.size} bytes, less than a dex header")
        val magic = String(dex, 0, 8, Charsets.ISO_8859_1)
        if (!Regex("dex\n0(3[5-9]|40)\u0000").matches(magic)) fail("not a dex file of version 035 to 040")
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        fun u4(at: Int): Int {
            if (at < 0 || at > dex.size - 4) fail("offset $at is outside the file")
            return buf.getInt(at).also { if (it < 0) fail("value at $at is out of range") }
        }
        val stringIdsSize = u4(0x38)
        val stringIdsOff = u4(0x3C)
        val typeIdsSize = u4(0x40)
        val typeIdsOff = u4(0x44)
        val classDefsSize = u4(0x60)
        val classDefsOff = u4(0x64)
        return List(classDefsSize) { i ->
            // class_def_item: 8 uints, the first the index of the class's type_id.
            val type = u4(classDefsOff + 32 * i)
            if (type >= typeIdsSize) fail("class_def $i names type $type of $typeIdsSize")
            val string = u4(typeIdsOff + 4 * type)
            if (string >= stringIdsSize) fail("type $type names string $string of $stringIdsSize")
            // string_data_item: the length as ULEB128, then MUTF-8 bytes up to a 0 byte.
            var at = u4(stringIdsOff + 4 * string)
            while (at < dex.size && dex[at] < 0) at++
            at++
            var end = at
            while (end < dex.size && dex[end] != 0.toByte()) end++
            if (end >= dex.size) fail("string $string runs past the end of the file")
            val name = String(dex, at, end - at, Charsets.UTF_8)
            if (name.length < 3 || name[0] != 'L' || !name.endsWith(";")) fail("class_def $i is '$name', not a class type")
            name.substring(1, name.length - 1).replace('/', '.')
        }
    }

    /** Entries that are JVM class files: named `.class`, or starting with the class file magic 0xCAFEBABE. */
    private fun jvmClassFiles(zip: ZipFile, names: List<String>): List<String> = names.filter { name ->
        if (name.endsWith("/")) return@filter false
        if (name.endsWith(".class")) return@filter true
        val head = zip.getInputStream(zip.getEntry(name)).use { it.readNBytes(4) }
        head.size == 4 && ByteBuffer.wrap(head).int == 0xCAFEBABE.toInt()
    }
}

/** R-2 and architecture.md §7.2: the variant's BuildConfig switches. */
abstract class CheckBuildConfig : DefaultTask() {
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val actual: MapProperty<String, String>
    @get:Input abstract val expected: MapProperty<String, String>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val v = variantName.get()
        val wrong = expected.get().filter { (name, value) -> actual.get()[name] != value }
            .map { (name, value) -> "BuildConfig.$name is ${actual.get()[name]}, expected $value" }
        if (wrong.isNotEmpty()) {
            throw GradleException("Build checks failed for the BuildConfig of '$v' (R-2, architecture.md §7.2):\n- " + wrong.joinToString("\n- "))
        }
        report.get().asFile.writeText("BuildConfig of '$v': ${expected.get()}\nR-2 (BuildConfig part) passes.\n")
    }
}

/**
 * R-16: R8 kept every member of [r8MustKeep] under its own name. Read from the variant's
 * `mapping.txt`, where a class is a line `original -> renamed:` followed by its members, each
 * indented, as `[lines:]type name(arguments)[:lines] -> name in the APK`; a member that R8 removed
 * is not listed there.
 */
abstract class CheckR8Keeps : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val mustKeep: MapProperty<String, List<String>>
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val v = variantName.get()
        val wanted = mustKeep.get()
        // For each class of [wanted]: the members listed under it, as "signature -> name".
        val found = wanted.keys.associateWith { mutableSetOf<String>() }
        var current: MutableSet<String>? = null
        val memberLine = Regex("^\\s+(?:\\d+:\\d+:)?(\\S+ [^\\s(]+\\([^)]*\\))(?::\\d+(?::\\d+)?)? -> (\\S+)$")
        mapping.get().asFile.useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                if (!line[0].isWhitespace()) {
                    current = found[line.substringBefore(" -> ")]
                    continue
                }
                val members = current ?: continue
                memberLine.find(line)?.let { members += "${it.groupValues[1]} -> ${it.groupValues[2]}" }
            }
        }
        val problems = mutableListOf<String>()
        for ((cls, signatures) in wanted) {
            for (signature in signatures) {
                val name = signature.substringBefore('(').substringAfterLast(' ')
                if ("$signature -> $name" !in found.getValue(cls)) {
                    problems += "R-16: R8 did not keep \"$signature\" of $cls under its own name (src/main/keepRules/rules.keep)"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Build checks failed for the R8 output of '$v':\n- " + problems.joinToString("\n- "))
        }
        report.get().asFile.writeText("R8 mapping of '$v': ${wanted.values.sumOf { it.size }} members kept under their own names.\nR-16 passes.\n")
    }
}

/** R-12: no build output is tracked by git. Skipped, with a warning, outside a git checkout. */
abstract class CheckNoTrackedBuildOutput : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:Internal abstract val repoDir: DirectoryProperty

    @TaskAction
    fun check() {
        val out = ByteArrayOutputStream()
        val status = try {
            exec.exec {
                workingDir = repoDir.get().asFile
                commandLine("git", "ls-files", "-z")
                standardOutput = out
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }.exitValue
        } catch (e: Exception) {
            -1
        }
        if (status != 0) {
            logger.warn("R-12 skipped: `git ls-files` did not run here (not a git checkout, or no git).")
            return
        }
        val tracked = out.toString(Charsets.UTF_8).split('\u0000').filter { Regex("(^|/)build/").containsMatchIn(it) }
        if (tracked.isNotEmpty()) {
            throw GradleException(
                "R-12: git tracks ${tracked.size} files of build output, for example ${tracked.take(5)}. " +
                    "Remove them with `git rm -r --cached <dir>`; .gitignore ignores every build/ directory.",
            )
        }
    }
}

val noTrackedBuildOutput = tasks.register<CheckNoTrackedBuildOutput>("buildCheckNoTrackedBuildOutput") {
    repoDir.set(rootProject.layout.projectDirectory)
}

androidComponents {
    onVariants { variant ->
        val spec = variantSpecs[variant.name]
            ?: throw GradleException("Variant '${variant.name}' has no entry in variantSpecs (app/build.gradle.kts)")
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val reports = layout.buildDirectory.dir("reports/build-checks/${variant.name}")
        // kotlinx-coroutines-core ships DebugProbesKt.bin, a JVM class file of its debug agent
        // (kotlinx.coroutines.debug.internal) that ART never loads; the library's documentation says
        // to leave it out of Android apps, at no loss of function. The optimised builds, staging and
        // release, leave it out, and their APK check (R-17) fails on any JVM class file in the APK.
        if (spec.r8) variant.packaging.resources.excludes.add("DebugProbesKt.bin")
        // Local names: inside the task blocks below, appId, allowedPermissions and debugReceiver
        // mean the tasks' own properties.
        val expectedAppId = appId
        val expectedPermissions = allowedPermissions
        val receiverClass = debugReceiver

        val manifestCheck = tasks.register<CheckMergedManifest>("buildCheck${cap}Manifest") {
            description = "R-1, R-5, R-7, R-9 and R-14 on the merged manifest of ${variant.name}."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            variantName.set(variant.name)
            appId.set(expectedAppId)
            allowedPermissions.set(expectedPermissions)
            exported.set(spec.exported)
            allowBackup.set(spec.allowBackup)
            debuggable.set(spec.debuggable)
            report.set(reports.map { it.file("manifest.txt") })
        }
        val apkCheck = tasks.register<CheckApk>("buildCheck${cap}Apk") {
            description = "R-2, R-8 and R-17 on the APK of ${variant.name}."
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            if (spec.r8) mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            variantName.set(variant.name)
            abis.set(spec.abis)
            debugReceiver.set(receiverClass)
            hasDebugReceiver.set(spec.hasDebugReceiver)
            forbiddenPackages.set(if (spec.debugSources) setOf(testingPackage) else debugPackages + testingPackage)
            countedPackages.set(debugPackages + testingPackage)
            report.set(reports.map { it.file("apk.txt") })
        }
        val buildConfigCheck = tasks.register<CheckBuildConfig>("buildCheck${cap}BuildConfig") {
            description = "R-2 and the BuildConfig switches of architecture.md §7.2 for ${variant.name}."
            variantName.set(variant.name)
            val fields = variant.buildConfigFields
                ?: throw GradleException("BuildConfig is off for '${variant.name}'; the build checks need it")
            actual.set(fields.map { all -> all.mapValues { (_, field) -> field.value.toString() } })
            expected.set(
                mapOf(
                    "ALLOW_SOFTWARE_KEYSTORE" to spec.allowSoftwareKeystore.toString(),
                    "SECURE_WINDOW" to spec.secureWindow.toString(),
                ),
            )
            report.set(reports.map { it.file("buildconfig.txt") })
        }
        val r8Check = if (spec.r8) {
            tasks.register<CheckR8Keeps>("buildCheck${cap}R8") {
                description = "R-16 on the R8 mapping of ${variant.name}."
                mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                variantName.set(variant.name)
                mustKeep.set(r8MustKeep)
                report.set(reports.map { it.file("r8.txt") })
            }
        } else {
            null
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach {
            dependsOn(manifestCheck, apkCheck, buildConfigCheck, noTrackedBuildOutput)
            r8Check?.let { dependsOn(it) }
        }
    }
}
