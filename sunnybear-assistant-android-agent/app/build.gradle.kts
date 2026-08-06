plugins {
    id("com.android.application")
}

android {
    namespace = "com.fishsunny.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fishsunny.agent"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // WebSocket client — OkHttp already includes WebSocket support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON parsing — lightweight, no reflection
    implementation("com.google.code.gson:gson:2.11.0")
    // AndroidX core
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
