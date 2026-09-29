buildscript {
    // Lets CI write buildscript-gradle.lockfile (the Gradle plugins' own dependencies) for the
    // vulnerability scan, alongside the app's.
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    // AGP 9.4 depends on library versions with published advisories (found by the OSV scan and
    // Dependabot). They run only in the build, never in the APK, but AGP signs the APK with
    // Bouncy Castle, so lift them to patched versions. Drop once AGP ships newer ones.
    dependencies {
        constraints {
            classpath(libs.bouncycastle.bcprov)
            classpath(libs.bouncycastle.bcpkix)
            classpath(libs.bouncycastle.bcutil)
            classpath(libs.commons.lang3)
            classpath(libs.jdom2)
            classpath(libs.jose4j)
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
