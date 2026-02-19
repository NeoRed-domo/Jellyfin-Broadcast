plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.jellyfinbroadcast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jellyfinbroadcast"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = "jellyfinbroadcast"
            keyAlias = "jellyfin"
            keyPassword = "jellyfinbroadcast"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    // Jellyfin SDK
    implementation("org.jellyfin.sdk:jellyfin-core:1.5.5")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.15.0")

    // Leanback for Android TV
    implementation("androidx.leanback:leanback:1.0.0")

    // QR Code generation (ZXing)
    implementation("com.google.zxing:core:3.5.3")

    // QR Code scanning (ML Kit)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // CameraX for QR scanning
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}
