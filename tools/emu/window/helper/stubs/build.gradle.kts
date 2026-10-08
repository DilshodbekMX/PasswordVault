// tools/emu/window/helper/stubs: compile-time stand-ins for the three system-API classes of content
// capture (Android 10, API 29) that the helper's LabCaptureService needs and the SDK's android.jar
// leaves out: ContentCaptureService, ContentCaptureEvent and ViewNode. The helper compiles against
// them (compileOnly); none of them is ever in its APK, where the device's own classes are used.
plugins {
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

dependencies {
    // android.app.Service and the public android.view classes the stand-ins name.
    val sdk = providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    compileOnly(files(sdk.map { "$it/platforms/android-37.0/android.jar" }))
}
