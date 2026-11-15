import java.util.Properties

// local.properties에 넣은 Pleos 키 읽기 (GitHub에는 안 올라감)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.project1"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nbbang.drive"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Gleo AI 음성 인식(SpeechToText) 앱 등록용 키. 없으면 빈 값 → 음성 입력만 꺼지고 나머지는 그대로 동작
        debug {
            // 개발용: local.properties의 내 키를 넣어 음성 입력까지 테스트
            buildConfigField("String", "PLEOS_CLIENT_ID", "\"${localProps.getProperty("PLEOS_CLIENT_ID", "")}\"")
            buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${localProps.getProperty("PLEOS_CLIENT_SECRET", "")}\"")
        }
        release {
            // 제출·공개용 APK: 키를 넣지 않음 (공개 저장소에 올려도 키가 새지 않게)
            buildConfigField("String", "PLEOS_CLIENT_ID", "\"\"")
            buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"\"")
            // 심사용 APK는 별도 키스토어 없이 디버그 서명으로 설치 가능하게
            signingConfig = signingConfigs.getByName("debug")
            // 코드 축소·난독화는 끔 (에뮬레이터에서 검증한 동작 그대로 내보내기)
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.google.zxing:core:3.5.3")   // 정산 QR 만들기
    implementation("ai.pleos.playground:Vehicle:2.3.2")   // Pleos Vehicle SDK (주행거리·기어·배터리·연비·좌석)
    implementation("ai.pleos.playground:SpeechToText:2.1.3.2")   // Gleo AI 음성 인식 (이름 말해서 넣기)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}