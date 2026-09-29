// Lets CI write buildscript-gradle.lockfile (the Gradle plugins' own dependencies) for the
// vulnerability scan, alongside the app's.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
