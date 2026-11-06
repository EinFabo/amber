import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pin bytecode to 17 without requesting a toolchain: jvmToolchain(17) or a java{}
// compatibility block makes Gradle hunt for a JDK 17 installation and try to download one,
// which fails wherever the network is restricted. --release uses the running JDK instead.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// The cleanup eval (tools/cleanup-eval): its own source set, so it runs the real ChatCleanup
// against real models only when asked - never as part of :core:test or a build.
val eval: SourceSet by sourceSets.creating
configurations[eval.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[eval.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
kotlin.target.compilations.getByName("eval").associateWith(kotlin.target.compilations.getByName("main"))

tasks.register<JavaExec>("cleanupEval") {
    description = "Runs tools/cleanup-eval/cases.jsonl through chat models; keys from the environment only."
    group = "verification"
    classpath = eval.runtimeClasspath
    mainClass.set("app.amethyst.eval.CleanupEvalKt")
    workingDir = rootDir
}
