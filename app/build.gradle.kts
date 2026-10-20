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
        versionCode = (project.findProperty("buildNum") as String?)?.toIntOrNull() ?: 15
        versionName = "0.9." + ((project.findProperty("buildNum") as String?) ?: "0")
        // Only 64-bit phones (all modern ones); keeps the app small.
        // (the emulator test job also needs x86_64)
        ndk { abiFilters += if (project.hasProperty("testAbi")) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
