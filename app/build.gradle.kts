plugins {
    alias(libs.plugins.android.application)
}

// 제출용 release 설정은 저장소 밖에서 받는다(docs/deploy.md 5장).
// - 서버 주소: -Pgildongmu.serverUrl=https://…/v1 (또는 ~/.gradle/gradle.properties). 없으면 앱이 "서버 주소 없음"으로 뜬다.
// - 서명 키: 키 파일 경로와 비밀번호는 ~/.gradle/gradle.properties에만 둔다. 없으면 서명하지 않은 APK가 나온다.
val releaseServerUrl = providers.gradleProperty("gildongmu.serverUrl").orElse("").get().trim()
if (releaseServerUrl.isNotEmpty() && !Regex("^https://[^\\s\"]+/v1$").matches(releaseServerUrl)) {
    throw GradleException("gildongmu.serverUrl은 https://…/v1 형태여야 합니다: $releaseServerUrl")
}
val releaseKeystore = providers.gradleProperty("gildongmu.keystore").orNull

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

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("gildongmu.keystorePassword").get()
                keyAlias = providers.gradleProperty("gildongmu.keyAlias").get()
                keyPassword = providers.gradleProperty("gildongmu.keyPassword").get()
            }
        }
    }

    buildTypes {
        debug {
            // 에뮬레이터에서 본 내 Mac의 로컬 서버
            buildConfigField("String", "SERVER_URL", "\"http://10.0.2.2:8080/v1\"")
        }
        release {
            isMinifyEnabled = false
            // 배포한 서버 주소(Cloud Run). 위의 gildongmu.serverUrl에서 받는다.
            buildConfigField("String", "SERVER_URL", "\"$releaseServerUrl\"")
            signingConfig = signingConfigs.findByName("release")
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
    implementation(libs.pleos.texttospeech)
    implementation(libs.pleos.speechtotext)

    testImplementation(libs.junit)
}
