import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
}

// The app compiles with Java 11 source/target; compiling against the JDK 11 API
// (-Xjdk-release) keeps newer JDK methods out of code that must move into the app.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Locale and encoding must not matter: run the tests under Turkish defaults.
    jvmArgs("-Duser.language=tr", "-Duser.country=TR", "-Dfile.encoding=UTF-8")
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Benchmark: ./gradlew bench
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Measures search time over 5,000 entries per keystroke."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.dilshodbekmx.passwordvault.search.SearchBenchmarkKt")
    workingDir = projectDir
    jvmArgs(listOf("-Xms512m", "-Xmx512m") + (project.findProperty("benchJvmArgs") as String? ?: "").split(" ").filter { it.isNotBlank() })
}

// Draft Uzbek Cyrillic strings: ./gradlew uzCyrl -PuzArgs="in/values-uz/strings.xml out/values-b+uz+Cyrl/strings.xml"
tasks.register<JavaExec>("uzCyrl") {
    group = "i18n"
    description = "Drafts values-b+uz+Cyrl/strings.xml from values-uz/strings.xml for human review."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.dilshodbekmx.passwordvault.text.UzbekCyrillicStringsKt")
    workingDir = projectDir
    args = (project.findProperty("uzArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}

// Converter accuracy on lex.uz parallel texts: ./gradlew evalConverters
tasks.register<JavaExec>("evalConverters") {
    group = "verification"
    description = "Word-by-word accuracy of the Uzbek script converters on parallel texts."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.dilshodbekmx.passwordvault.text.ConverterEvalKt")
    workingDir = projectDir
    args = (project.findProperty("evalArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}
