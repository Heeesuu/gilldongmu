plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gildongmu.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gildongmu.app"
        // Pleos Connect 필수 조건: API 28 이상
        minSdk = 28
        // Pleos Connect 에뮬레이터(Android 14)에 맞춤
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AAOS 표준 Car API(차량 속도, 주행 제한 상태)
    useLibrary("android.car")
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
}
