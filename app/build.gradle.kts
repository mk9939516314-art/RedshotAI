plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.redshot"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.redshot"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.5.0"
        // Only 64-bit phones (all modern ones); keeps the app small.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One fixed key so every new build installs over the old one (permissions stay).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
}
