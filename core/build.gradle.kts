plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The vault's logic with no screens and no Android framework calls: cryptography, the vault
// file format, storage, search, import and export. It is an Android library, not a plain JVM
// one, so that lint checks every API it uses against minSdk 24; its tests run on the JVM.
android {
    namespace = "io.github.dilshodbekmx.passwordvault.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle)
    implementation(libs.zxcvbn)
    // :testing depends on :core; Gradle accepts the edge because only the tests of :core use it.
    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The source-rule tests (core/src/test/.../core/arch) read these files, so a change to any of them,
// even one that compiles to the same classes, runs the tests again instead of taking a cached result.
tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree("src/main") { include("**/*.kt", "**/*.java") },
        fileTree(rootDir.resolve("testing/src/main")) { include("**/*.kt", "**/*.java") },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("scannedSources")
}
