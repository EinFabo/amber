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
# The user's own cache, not a shared /tmp: these jars are run (the compiler) and trusted (the
# classpath), so nobody else may get to put files there first.
WORK="${XDG_CACHE_HOME:-$HOME/.cache}/amber-typecheck"
KOTLIN_VERSION="2.1.20"
# Android 15 == API 35 == compileSdk of this project.
ANDROID_ALL="15-robolectric-13954326"
CENTRAL="https://repo1.maven.org/maven2"

mkdir -p "$WORK/jars"

# Pinned by SHA-256 (taken from downloads whose SHA-1 matched Maven Central's own), checked
# every run - a cached copy too. A new version means new pins.
fetch() { # group/path artifact sha256
  local path="$1" file="$2" sha="$3"
  if [ ! -f "$WORK/jars/$file" ]; then
    echo "==> $file"
    curl -fsSL -o "$WORK/jars/$file.part" "$CENTRAL/$path/$file"
    mv "$WORK/jars/$file.part" "$WORK/jars/$file"
  fi
  echo "$sha  $WORK/jars/$file" | sha256sum -c --quiet - \
    || { rm -f "$WORK/jars/$file"; echo "$file does not match its pinned SHA-256" >&2; exit 1; }
}

fetch "org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VERSION" "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar" \
  c54a00718c8c0e3ee858bf42771c7f401c5e3c1738861e18e9536371042fa1b3
fetch "org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION" "kotlin-stdlib-$KOTLIN_VERSION.jar" \
  1bcc74e8ce84e2c25eaafde10f1248349cce3062b6e36978cbeec610db1e930a
fetch "org/jetbrains/kotlin/kotlin-script-runtime/$KOTLIN_VERSION" "kotlin-script-runtime-$KOTLIN_VERSION.jar" \
  ae4397fbb3aa2a1ada09290e753bba99a4114548977c3115526b9958f4cb1a04
fetch "org/jetbrains/kotlin/kotlin-daemon-embeddable/$KOTLIN_VERSION" "kotlin-daemon-embeddable-$KOTLIN_VERSION.jar" \
  d9e83df1d847a201ba3c016a786ced091be9503997d09f6a9cf1796ee489f374
fetch "org/jetbrains/intellij/deps/trove4j/1.0.20200330" "trove4j-1.0.20200330.jar" \
  c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d
fetch "org/jetbrains/annotations/13.0" "annotations-13.0.jar" \
  ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478
# A runtime dependency of the compiler itself (see its pom), not of the checked sources.
fetch "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0" "kotlinx-coroutines-core-jvm-1.8.0.jar" \
  9860906a1937490bf5f3b06d2f0e10ef451e65b95b269f22daf68a3d1f5065c5
fetch "org/robolectric/android-all/$ANDROID_ALL" "android-all-$ANDROID_ALL.jar" \
  e17105f6c432357e6bb278a527f8e848d01fc56ccee01be77725bea23d871990

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
