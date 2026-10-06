plugins {
    alias(libs.plugins.android.library)
}

// The fakes of every :core port (FakeClock, FakeRandom, FakeFiles, …) and the source scanner of the
// build checks, shared by the tests of :core and :app (architecture.md §2.1). Only ever a test
// dependency. Like :core it makes no Android framework call (build check R-10).
android {
    namespace = "io.github.dilshodbekmx.passwordvault.testing"
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
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
}
