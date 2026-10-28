import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.amethyst"
    compileSdk = 35
    // Pinned so CI and local builds use the same toolchain for whisper.cpp.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        // The app is Amber; the code keeps its old namespace (app.amethyst), which nobody sees.
        // Changed with the first release build, which is a fresh install anyway.
        applicationId = "app.amber"
        minSdk = 28
        targetSdk = 35
        versionCode = 27
        versionName = "0.15.0"

        ndk {
            // Phones only. Every arm64 device is covered, and leaving out x86/armv7 keeps the
            // APK and the native build time down.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    /**
     * Release builds come out unsigned and are signed with the release key by
     * tools/sign-release.sh. A local keystore.properties (gitignored: keystore file, alias,
     * both passwords) still signs here, should one ever be wanted.
     */
    val releaseKey = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Not debuggable, compiled ahead of time, shrunk by R8: faster, smaller, and no
            // way to attach a debugger to a process holding microphone and accessibility.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // Compressed instead of stored: the APK is installed by hand after a download, where
        // download size matters more than the few MB of extra space the unpacked copy takes
        // on the device.
        dex.useLegacyPackaging = true
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        // Robolectric renders with the real resources and native graphics.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Stage 1 is verified by assembling and running on a device; lint findings are
        // reported in CI but must not block producing an installable APK.
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * Tinfoil's attestation verifier, bound from tinfoil-go by tools/build-tinfoil-aar.sh. Not
 * checked in (a binary of several MB, reproducible from a pinned commit), so a fresh checkout
 * has to build it once before the app compiles.
 */
val tinfoilAar = file("libs/tinfoil-mobile.aar")

/** sherpa-onnx, the runtime of the local Parakeet engine; fetched by tools/fetch-sherpa-aar.sh. */
val sherpaAar = file("libs/sherpa-onnx.aar")

// Checked when the app is built, not when Gradle configures - :core's tests need no AAR.
tasks.named("preBuild") {
    // A plain File, so the check holds no reference to the build script (configuration cache).
    val aar = tinfoilAar
    val sherpa = sherpaAar
    doFirst {
        if (!aar.exists()) {
            throw GradleException(
                "app/libs/tinfoil-mobile.aar is missing. Build it once with: tools/build-tinfoil-aar.sh " +
                    "(needs Go and the Android NDK).",
            )
        }
        if (!sherpa.exists()) {
            throw GradleException("app/libs/sherpa-onnx.aar is missing. Fetch it with: tools/fetch-sherpa-aar.sh")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files(tinfoilAar))
    implementation(files(sherpaAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
