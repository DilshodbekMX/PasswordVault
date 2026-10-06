#!/bin/bash
# Compiles the prototype and its test with the Kotlin compiler bundled in Android Studio.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=/home/asus/.jdks/jdk-25
KC=/snap/android-studio/current/plugins/Kotlin/kotlinc/bin/kotlinc
G=~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx
CP=$(ls $G/kotlinx-serialization-core-jvm/1.11.0/*/kotlinx-serialization-core-jvm-1.11.0.jar):$(ls $G/kotlinx-serialization-json-jvm/1.11.0/*/kotlinx-serialization-json-jvm-1.11.0.jar)
rm -rf classes && mkdir -p classes
$KC -jvm-target 17 -cp "$CP" src/pv/formats/*.kt test/*.kt -d classes 2>&1 | grep -v "^warning: " || true
echo "$CP:$PWD/classes:/snap/android-studio/current/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar" > classpath.txt
