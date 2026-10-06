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
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}
