plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val biometricVersion: String = providers.gradleProperty("biometricVersion").getOrElse("1.1.0")
val largeHeap: String = providers.gradleProperty("largeHeap").getOrElse("false")
// Optional: -PcoreVersion=1.17.0 forces androidx.core (and core-ktx) to that version.
val coreVersion: String? = providers.gradleProperty("coreVersion").orNull

if (coreVersion != null) {
    configurations.configureEach {
        resolutionStrategy.force("androidx.core:core:$coreVersion", "androidx.core:core-ktx:$coreVersion")
    }
}

android {
    namespace = "com.example.pvlab.old"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.pvlab.old"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Lab switches: ./gradlew assembleDebug -PlargeHeap=true -PbiometricVersion=1.4.0-alpha07
        manifestPlaceholders["largeHeap"] = largeHeap
        buildConfigField("String", "BIOMETRIC_VERSION", "\"$biometricVersion\"")
        buildConfigField("boolean", "LARGE_HEAP", largeHeap)
        buildConfigField("String", "CORE_VERSION", "\"${coreVersion ?: "catalog (1.19.1)"}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation("androidx.biometric:biometric:$biometricVersion")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.argon2kt)
    implementation(libs.bouncycastle)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
