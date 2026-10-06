// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no kotlin-android plugin.
    // Declaring the Compose compiler plugin at libs.versions.kotlin also sets the Kotlin version.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
