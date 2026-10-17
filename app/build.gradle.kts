plugins {
    alias(libs.plugins.agp)
    // AGP 9.x đã tích hợp sẵn Kotlin support — KHÔNG apply org.jetbrains.kotlin.android
    // (apply thêm sẽ clash: "Cannot add extension with name 'kotlin'" — gặp ở CI run #4)
}

android {
    namespace = "one.lethanhdong.vpnpocket"
    // compileSdk 36: androidx.core 1.18.0 bắt buộc compile API 36+ (AAR metadata check).
    // targetSdk giữ 34 theo spec (quyết định runtime behavior, không liên quan lỗi build).
    compileSdk = 36

    defaultConfig {
        applicationId = "one.lethanhdong.vpnpocket"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // (không có kotlinOptions — đi theo AGP 9.x integrated Kotlin như DAC)
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime)
    implementation(libs.security.crypto)
    // vpntunnel.aar: CI build từ vpntunnel/ (gomobile bind) rồi copy vào app/libs.
    implementation(files("libs/vpntunnel.aar"))
}
