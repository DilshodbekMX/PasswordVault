import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.io.ByteArrayOutputStream
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
 * keyPassword; storeFile relative to the project root) and the keystore stay outside git. Without
 * them the release build gives an unsigned APK.
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
    lint {
        // i18n.md §1.1 makes these errors. MissingTranslation is a warning from round R3 until the
        // integration check of wave 6 (build-plan.md §8, "Before 6A starts"): 6.1 brings the first
        // files under values-uz and values-ru, and the other translations come only in 6B.
        error += listOf(
            "ExtraTranslation", "MissingQuantity",
            "StringFormatMatches", "StringFormatInvalid", "TypographyEllipsis",
        )
        warning += "MissingTranslation"
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
// Build checks (architecture.md §8.5, lock.md §15.5). Each variant's `assemble` runs its checks;
// `buildCheck<Variant>Manifest` also runs on its own (tools/check.sh offline). The source scans R-4,
// R-6, R-10, R-11 and R-13 are unit tests (arch/ in the tests of :core and :app); R-3 is a device
// test.
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
    "debug" to VariantSpec(true, false, emulatorAbis, debugExported, true, false),
    "staging" to VariantSpec(true, true, emulatorAbis, stagingExported, true, false),
    "release" to VariantSpec(false, true, phoneAbis, releaseExported, false, false),
    "backupCheck" to VariantSpec(true, false, emulatorAbis, debugExported, true, true),
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
 * variant's allowBackup, read from the merged manifest, which includes everything libraries add.
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

        val v = variantName.get()
        if (problems.isNotEmpty()) {
            throw GradleException("Build checks failed for the merged manifest of '$v':\n- " + problems.joinToString("\n- "))
        }
        report.get().asFile.writeText(
            "Merged manifest of '$v': package $pkg, generator '$generator'\n" +
                "permissions: $uses\nexported: $exportedNow\nallowBackup: $backup\n" +
                "R-1, R-5, R-7, R-9 and R-14 pass.\n",
        )
    }
}

/**
 * R-8: the APK's native libraries are exactly the variant's ABIs (release: arm64-v8a and
 * armeabi-v7a only). R-2: the debug receiver's class is in the APK exactly where the variant
 * declares the receiver, and never in release.
 */
abstract class CheckApk : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkFolder: DirectoryProperty

    @get:Internal abstract val artifactsLoader: Property<BuiltArtifactsLoader>
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val abis: SetProperty<String>
    @get:Input abstract val debugReceiver: Property<String>
    @get:Input abstract val hasDebugReceiver: Property<Boolean>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val v = variantName.get()
        val apks = artifactsLoader.get().load(apkFolder.get())?.elements?.map { File(it.outputFile) }.orEmpty()
        if (apks.isEmpty()) throw GradleException("No APK found for '$v' in ${apkFolder.get().asFile}")
        val descriptor = "L" + debugReceiver.get().replace('.', '/') + ";"
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
                val inDex = names.filter { Regex("classes\\d*\\.dex").matches(it) }.any { dex ->
                    val bytes = zip.getInputStream(zip.getEntry(dex)).use { it.readBytes() }
                    String(bytes, Charsets.ISO_8859_1).contains(descriptor)
                }
                if (inDex != hasDebugReceiver.get()) {
                    problems += "R-2: ${debugReceiver.get()} is ${if (inDex) "" else "not "}in ${apk.name}; " +
                        "expected ${if (hasDebugReceiver.get()) "in" else "not in"} '$v'"
                }
                lines += "${apk.name}: lib/ $found, debug receiver ${if (inDex) "present" else "absent"}"
            }
        }
        if (problems.isNotEmpty()) throw GradleException("Build checks failed for the APK of '$v':\n- " + problems.joinToString("\n- "))
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\nR-2 (APK part) and R-8 pass.\n"))
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
            report.set(reports.map { it.file("manifest.txt") })
        }
        val apkCheck = tasks.register<CheckApk>("buildCheck${cap}Apk") {
            description = "R-2 and R-8 on the APK of ${variant.name}."
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            variantName.set(variant.name)
            abis.set(spec.abis)
            debugReceiver.set(receiverClass)
            hasDebugReceiver.set(spec.hasDebugReceiver)
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
        tasks.matching { it.name == "assemble$cap" }.configureEach {
            dependsOn(manifestCheck, apkCheck, buildConfigCheck, noTrackedBuildOutput)
        }
    }
}
