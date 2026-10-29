pluginManagement {
    repositories {
        // Evaluated before the rest of this file, so googleOnly() below is out of reach here.
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android\\..*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        googleOnly()
        // mavenCentral first: it is the only one reachable from restricted CI/dev sandboxes,
        // so pure-JVM modules keep resolving even when google() is unreachable.
        mavenCentral()
        google()
    }
}

/**
 * androidx and the Android Gradle plugin come from Google's repository and nowhere else. Asked
 * first, Maven Central would serve whoever registered those names there - nobody today, but
 * the build's safety should not hang on that.
 */
fun RepositoryHandler.googleOnly() {
    exclusiveContent {
        forRepository { google() }
        filter {
            includeGroupByRegex("androidx\\..*")
            includeGroupByRegex("com\\.android(\\..*)?")
            includeGroupByRegex("com\\.google\\.android\\..*")
        }
    }
}

rootProject.name = "amethyst"

include(":core")

// :app needs the Android SDK. Including it unconditionally would break every Gradle
// invocation on machines without one (AGP resolution + sdk.dir check), which would make
// the pure-JVM :core module untestable there. So it is opt-in by SDK presence.
val androidSdkPresent = sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
    .any { !System.getenv(it).isNullOrBlank() } ||
    file("local.properties").let { it.isFile && it.readText().contains("sdk.dir") }

if (androidSdkPresent || providers.gradleProperty("amethyst.withApp").orNull == "true") {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle("[amethyst] No Android SDK found - :app excluded. :core builds and tests normally.")
    }
}
