plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.zhufucdev.ws_plugin"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.zhufucdev.ws_plugin"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.2.5-api102"
    }
    buildFeatures { buildConfig = true }
    // Same multi-ABI packaging as the manager app: per-ABI APKs plus a universal one.
    // The plugin itself is pure Kotlin today, so the universal APK is what the release ships,
    // while per-ABI splits stay available as CI artifacts for devices/JNI later on.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.corektx)
    implementation(libs.kotlinx.coroutine)
    compileOnly(libs.libxposed)
    // Build the fixed SDK sources in this repository, never the old Maven binaries.
    implementation(project(":stub"))
    implementation(project(":plugin"))
    implementation(project(":xposed"))
}

