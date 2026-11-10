import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * ONNX Runtime for sherpa-onnx's native build: headers and libonnxruntime.so from the same Maven
 * AAR that puts the library into the APK, so what sherpa-onnx is compiled against and what ships
 * cannot drift apart. Unpacked before any CMake task runs.
 */
val onnxRuntimeAar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val onnxRuntimeDir = layout.buildDirectory.dir("onnxruntime")
val extractOnnxRuntime by tasks.registering(Sync::class) {
    from({ zipTree(onnxRuntimeAar.singleFile) }) {
        include("headers/**", "jni/arm64-v8a/libonnxruntime.so")
    }
    into(onnxRuntimeDir)
}
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(extractOnnxRuntime) }

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
        versionCode = 59
        versionName = "0.31.0"

        ndk {
            // Phones only. Every arm64 device is covered, and leaving out x86/armv7 keeps the
            // APK and the native build time down.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // Reproducible: no absolute checkout path in __FILE__ strings (CMakeLists.txt
                // maps its build-tree copies the same way).
                val repoRoot = rootProject.layout.projectDirectory.asFile.absolutePath
                cFlags += "-ffile-prefix-map=$repoRoot=."
                cppFlags += "-ffile-prefix-map=$repoRoot=."
                arguments += listOf(
                    // The native sources are git submodules; CMake downloads nothing.
                    "-DTHIRD_PARTY_DIR=${rootProject.file("third_party").absolutePath}",
                    // ONNX Runtime's headers and libonnxruntime.so, unpacked from the Maven AAR
                    // by extractOnnxRuntime (top of this file).
                    "-DORT_DIR=${onnxRuntimeDir.get().asFile.absolutePath}",
                    // The C++ runtime linked into each library, as the ONNX Runtime AAR does:
                    // no libc++_shared.so.
                    "-DANDROID_STL=c++_static",
                    // 16 KB segment alignment for every library, and no GNU build-id: the NDK
                    // derives it from the build, so it would differ between build machines.
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--build-id=none",
                )
                // Only what ships; sherpa-onnx would otherwise also build libraries nobody loads.
                targets += listOf("amethyst_whisper", "sherpa-onnx-jni")
            }
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
            // PNGs as they are in res/: crunching them is a known source of builds that differ
            // between machines, and a release has to come out the same bytes wherever it is
            // rebuilt (README, "Reproducible builds"; tools/check-reproducible.sh).
            isCrunchPngs = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // No dependency metadata in the APK: AGP otherwise adds a block encrypted with Google's key
    // that only Play can read. F-Droid and IzzyOnDroid flag it, and it would stand between our
    // APK and a reproducible build.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        // The app follows the phone's language: English, and German from values-de. Android 13+
        // also offers this list per app in its settings (the locale config generated from res/,
        // with res/resources.properties naming the default). Only these two: a library's
        // French text next to Amber's English would be a mix nobody asked for.
        generateLocaleConfig = true
        localeFilters += listOf("en", "de")
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG keeps user data out of a release build's logcat (DebugLog).
        buildConfig = true
    }

    // -Pamber.insertTestHook=true puts the debug build's adb insertion hook (src/debug) into a
    // release build, to test on a phone where the accessibility grant belongs to the release
    // app. Such a build is for one device and one test session - never published.
    if (providers.gradleProperty("amber.insertTestHook").orNull == "true") {
        sourceSets.getByName("release") {
            kotlin.srcDir("src/debug/kotlin")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
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
        // libonnxruntime.so reaches the build twice: from the ONNX Runtime AAR (the copy that
        // ships) and as an imported library of the sherpa-onnx CMake project. Both are the same
        // file from the same Maven artifact.
        jniLibs.pickFirsts += "lib/*/libonnxruntime.so"
        // Shipped byte for byte as Microsoft published it (AGP would strip its symbol table), so
        // the APK's copy can be checked against the AAR on Maven Central.
        jniLibs.keepDebugSymbols += "**/libonnxruntime.so"
        // ONNX Runtime's Java binding: Amber reaches ONNX Runtime only through sherpa-onnx.
        jniLibs.excludes += "lib/*/libonnxruntime4j_jni.so"
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
 * has to build it before the app compiles, and again whenever that script changes: an older AAR
 * stays here and gives other bytes. tools/sign-release.sh rebuilds it for every release.
 */
val tinfoilAar = file("libs/tinfoil-mobile.aar")

/** LICENSE and NOTICE from the repository root, shipped as assets for the Licenses page. */
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val copyLicenses by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
    into(licenseAssets)
}
android.sourceSets.getByName("main").assets.srcDir(licenseAssets)

/**
 * sherpa-onnx's Kotlin API, compiled from the submodule: only the files the offline recognizer
 * needs (SherpaRuntime). The rest of the directory brings in text-to-speech, diarization and
 * other classes whose native side this build leaves out. Its JNI code finds these classes and
 * their fields by name (see proguard-rules.pro). tools/typecheck-android.sh keeps the same list:
 * change both.
 */
val sherpaKotlinApi = rootProject.file("third_party/sherpa-onnx/sherpa-onnx/kotlin-api")
val sherpaKotlinSources = listOf(
    "FeatureConfig.kt",
    "HomophoneReplacerConfig.kt",
    "OfflineRecognizer.kt",
    "OfflineStream.kt",
    "QnnConfig.kt",
).map { File(sherpaKotlinApi, it) }

/** Copies [sources] into a generated source directory: a source set takes directories only. */
abstract class CopyKotlinSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        for (file in sources) {
            if (!file.isFile) {
                throw GradleException("$file is missing. Run: git submodule update --init --recursive")
            }
            file.copyTo(File(out, file.name))
        }
    }
}
val copySherpaKotlinApi by tasks.registering(CopyKotlinSources::class) {
    sources.from(sherpaKotlinSources)
    outputDir.set(layout.buildDirectory.dir("generated/sherpaKotlinApi"))
}
androidComponents.onVariants { variant ->
    // The java sources, not sources.kotlin: the Kotlin plugin compiles .kt files from AGP's java
    // source directories, generated ones included, but ignores generated kotlin ones.
    variant.sources.java?.addGeneratedSourceDirectory(copySherpaKotlinApi, CopyKotlinSources::outputDir)
}

// Checked when the app is built, not when Gradle configures - :core's tests need no AAR.
tasks.named("preBuild") {
    dependsOn(copyLicenses)
    // A plain File, so the check holds no reference to the build script (configuration cache).
    val aar = tinfoilAar
    doFirst {
        if (!aar.exists()) {
            throw GradleException(
                "app/libs/tinfoil-mobile.aar is missing. Build it with: tools/build-tinfoil-aar.sh " +
                    "(needs Go and the Android NDK).",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    // Used directly (TinfoilVerifier, model downloads), not only through :core's api.
    implementation(libs.okhttp)
    implementation(files(tinfoilAar))
    // libonnxruntime.so for sherpa-onnx; the same AAR feeds its native build (onnxRuntimeAar).
    implementation(libs.onnxruntime.android)
    onnxRuntimeAar(variantOf(libs.onnxruntime.android) { artifactType("aar") })

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

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
