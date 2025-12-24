plugins {
    // Use plugin IDs without explicit versions here because the Android Gradle Plugin
    // is already on the classpath (configured at the root level).
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ai.assistance.showerclient"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        // Shower client does not use Compose directly; only Binder/IPC and coroutines.
        compose = false
        aidl = true
        buildConfig = false
    }
}

dependencies {
    // Keep this module portable when copied into another project that may not use a version catalog.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
