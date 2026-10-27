// Intentionally free of a plugins block: declaring the Android plugins here (even with
// `apply false`) would pull AGP onto the root buildscript classpath and break builds on
// machines without access to Google's Maven repo. Each module declares what it needs.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
