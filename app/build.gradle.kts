plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dropspike"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.dropspike"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${versionCode}"
    }

    signingConfigs {
        // A fixed, throwaway key committed to the repo so every build (local or CI)
        // is signed identically and installs as an update over the previous one.
        // Replace with a private key before distributing to anyone else.
        create("spike") {
            storeFile = rootProject.file("keystore/spike.jks")
            storePassword = "dropspike"
            keyAlias = "spike"
            keyPassword = "dropspike"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("spike")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("spike")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Sideloaded spike: don't let Play-Store policy checks (e.g. ExpiredTargetSdkVersion) fail CI.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
