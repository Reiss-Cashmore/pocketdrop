plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.dropspike"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dropspike"
        minSdk = 29
        targetSdk = 37
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
