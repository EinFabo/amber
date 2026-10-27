pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // mavenCentral first: it is the only one reachable from restricted CI/dev sandboxes,
        // so pure-JVM modules keep resolving even when google() is unreachable.
        mavenCentral()
        google()
    }
}

rootProject.name = "nocturne"

include(":core")

// :app needs the Android SDK. Including it unconditionally would break every Gradle
// invocation on machines without one (AGP resolution + sdk.dir check), which would make
// the pure-JVM :core module untestable there. So it is opt-in by SDK presence.
val androidSdkPresent = sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
    .any { !System.getenv(it).isNullOrBlank() } ||
    file("local.properties").let { it.isFile && it.readText().contains("sdk.dir") }

if (androidSdkPresent || providers.gradleProperty("nocturne.withApp").orNull == "true") {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle("[nocturne] No Android SDK found - :app excluded. :core builds and tests normally.")
    }
}
