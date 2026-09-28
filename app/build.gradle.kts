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
        create("spike") {
            storeFile = rootProject.file("keystore/spike.jks")
            storePassword = "dropspike"
            keyAlias = "spike"
            keyPassword = "dropspike"
        }
        // Your private key, when the build provides one (CI decodes the SIGNING_KEYSTORE_B64
        // secret to a file; see README "Signing"). Falls back to the throwaway key otherwise.
        val privateStore = System.getenv("SIGNING_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
        if (privateStore != null) {
            create("private") {
                storeFile = privateStore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }
    val releaseSigning = signingConfigs.findByName("private") ?: signingConfigs.getByName("spike")

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("spike")
        }
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
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
        buildConfig = true
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
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
