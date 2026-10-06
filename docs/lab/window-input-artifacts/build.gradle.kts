plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.pvlab.helper"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.pvlab.helper"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
}
