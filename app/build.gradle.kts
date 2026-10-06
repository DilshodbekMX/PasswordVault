import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.dilshodbekmx.passwordvault"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.dilshodbekmx.passwordvault"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Full R8. The template's packageScope setting produced a release build that
            // crashed at startup (IllegalAccessError in androidx.startup) with these libraries.
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
    }
}

/**
 * The app must never be able to go online. Fails the build if the merged manifest of a variant,
 * which includes everything libraries add, requests the INTERNET permission.
 */
abstract class CheckNoInternetPermission : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val result: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile.readText()
        if (manifest.contains("\"android.permission.INTERNET\"")) {
            throw GradleException(
                "The merged manifest requests android.permission.INTERNET, but this app must stay " +
                    "offline. Find the library that adds it (see the manifest merger report) and " +
                    "remove it with tools:node=\"remove\".",
            )
        }
        result.get().asFile.writeText("No INTERNET permission in the merged manifest.\n")
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val noInternet = tasks.register<CheckNoInternetPermission>("check${variantName}NoInternetPermission") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            result.set(layout.buildDirectory.file("reports/no-internet/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach { dependsOn(noInternet) }
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
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
}
