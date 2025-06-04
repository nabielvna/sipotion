plugins {
    alias(libs.plugins.android.application)
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
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.okhttp)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    val cameraxVersion = "1.3.0" // Gunakan versi stabil terbaru atau versi yang Anda inginkan

    implementation("androidx.core:core-ktx:1.9.0") // Contoh versi, sesuaikan
    implementation("androidx.appcompat:appcompat:1.6.1") // Contoh versi, sesuaikan
    implementation("com.google.android.material:material:1.10.0") // Contoh versi, sesuaikan
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // Contoh versi, sesuaikan

    // Dependensi CameraX
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    implementation("androidx.camera:camera-extensions:${cameraxVersion}") // Opsional untuk efek ekstensi

    // Dependensi lain yang Anda gunakan (OkHttp, Gson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Ganti dengan versi terbaru
    implementation("com.google.code.gson:gson:2.10.1")
}