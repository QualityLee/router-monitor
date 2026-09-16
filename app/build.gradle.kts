plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.workbuddy.routermon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.workbuddy.routermon"
        minSdk = 21          // 工控机常见 Android 5+
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        // Android 5.1(API 22) 上没有 java.util.stream / java.time，
        // 打开 core library desugaring，避免 jsoup 等库在运行时缺类。
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // HTML 解析
    implementation("org.jsoup:jsoup:1.17.1")
}
