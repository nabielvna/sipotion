plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(libs.exifinterface)
    val cameraxVersion = "1.3.4"

    // -- Android UI & Core --
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // -- Networking --
    implementation(libs.okhttp)
    implementation(libs.gson)

    // -- CameraX --
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")

    // -- Architecture Components --
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.1")
    implementation("androidx.lifecycle:lifecycle-livedata:2.8.1")

    // --
    implementation("javax.inject:javax.inject:1")

    // -- Dependency Injection - Hilt --
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")

    // -- Testing --
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}