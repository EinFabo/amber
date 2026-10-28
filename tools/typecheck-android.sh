#!/usr/bin/env bash
# Type-checks the non-Compose part of :app against the REAL Android framework, without an
# Android SDK.
#
# Why this exists: dl.google.com is unreachable in some sandboxes, so neither the SDK nor
# AndroidX can be resolved and :app cannot be built at all. But Robolectric publishes real
# Android framework jars to Maven Central, which IS reachable - so the android.* layer can be
# checked for real. That is the layer where platform semantics live, and where every serious
# defect in this project has been found so far.
#
# What it proves: the 15 non-Compose sources resolve and type-check against the actual
# framework classes for API 35 - method names, signatures, overrides, nullability.
# What it does NOT prove: anything about the Compose UI (androidx.* lives only on Google's
# Maven), and the androidx.* touch points are only as accurate as tools/typecheck-stubs.
# A real ./gradlew :app:assembleDebug remains the final word.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${TMPDIR:-/tmp}/amethyst-typecheck"
KOTLIN_VERSION="2.1.20"
# Android 15 == API 35 == compileSdk of this project.
ANDROID_ALL="15-robolectric-13954326"
CENTRAL="https://repo1.maven.org/maven2"

mkdir -p "$WORK/jars"

fetch() { # group/path artifact version
  local path="$1" file="$2"
  [ -f "$WORK/jars/$file" ] && return 0
  echo "==> $file"
  curl -fsSL -o "$WORK/jars/$file" "$CENTRAL/$path/$file"
}

fetch "org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VERSION" "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION" "kotlin-stdlib-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlin/kotlin-script-runtime/$KOTLIN_VERSION" "kotlin-script-runtime-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/kotlin/kotlin-daemon-embeddable/$KOTLIN_VERSION" "kotlin-daemon-embeddable-$KOTLIN_VERSION.jar"
fetch "org/jetbrains/intellij/deps/trove4j/1.0.20200330" "trove4j-1.0.20200330.jar"
fetch "org/jetbrains/annotations/13.0" "annotations-13.0.jar"
# A runtime dependency of the compiler itself (see its pom), not of the checked sources.
fetch "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0" "kotlinx-coroutines-core-jvm-1.8.0.jar"
fetch "org/robolectric/android-all/$ANDROID_ALL" "android-all-$ANDROID_ALL.jar"

echo "==> building :core so its classes are on the classpath"
(cd "$ROOT" && ./gradlew --quiet :core:jar)

CORE_JAR="$(find "$ROOT/core/build/libs" -name '*.jar' | head -1)"
DEPS="$(find "$HOME/.gradle/caches/modules-2" \
  -name 'kotlinx-coroutines-core-jvm-*.jar' -o \
  -name 'kotlinx-serialization-json-jvm-*.jar' -o \
  -name 'kotlinx-serialization-core-jvm-*.jar' -o \
  -name 'okhttp-*.jar' -o -name 'okio-jvm-*.jar' 2>/dev/null \
  | grep -v sources | tr '\n' ':')"

CLASSPATH="$CORE_JAR:$WORK/jars/kotlin-stdlib-$KOTLIN_VERSION.jar:$DEPS$WORK/jars/android-all-$ANDROID_ALL.jar"
COMPILER_CP="$(find "$WORK/jars" -name '*.jar' | tr '\n' ':')"

mapfile -t SOURCES < <(find "$ROOT/app/src/main/kotlin" -name '*.kt' -not -path '*/ui/*')

echo "==> type-checking ${#SOURCES[@]} sources against android-all $ANDROID_ALL"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -no-reflect -no-stdlib \
  -classpath "$CLASSPATH" \
  -d "$WORK/classes" \
  "${SOURCES[@]}" "$ROOT"/tools/typecheck-stubs/*.kt
echo "==> clean"
