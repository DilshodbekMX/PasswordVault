// The helper app of tools/emu/window (build plan 3.12): a logging autofill service, an accessibility
// service that is not an accessibility tool and one that is, an overlay drawn over other apps, a
// control screen made of platform views, and an instrumentation that reads the saved-instance state
// of the app under test. Plain Java and no library at all: the instrumentation's APK is put in front
// of the app's own on the class path of the app's process, so it must not bring a class the app has
// too.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.dilshodbekmx.pvlab.window"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.dilshodbekmx.pvlab.window"
        // 26: the autofill framework and TYPE_APPLICATION_OVERLAY start there (architecture.md §8.4).
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // Never shipped; nothing here runs lint.
        checkReleaseBuilds = false
    }
}

dependencies {
    // The system API classes of content capture, for LabCaptureService: compile time only.
    compileOnly(project(":stubs"))
}
