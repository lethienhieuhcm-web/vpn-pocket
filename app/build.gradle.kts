plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "one.lethanhdong.vpnpocket"
    compileSdk = 34

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
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime)
    implementation(libs.security.crypto)
    // P2: implementation(files("libs/vpntunnel.aar")) — CI build AAR từ vpntunnel/ rồi copy vào app/libs
}
