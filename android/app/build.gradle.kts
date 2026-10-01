plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.xi.ridemeter"
    compileSdk = 34

    defaultConfig {
        applicationId = "kr.xi.ridemeter"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("verCode") as String?)?.toInt() ?: 1
        // CI 에서 -PverName=v1.2 로 태그를 주입한다. Releases 태그와 같아야 업데이트 판정이 맞는다.
        versionName = (project.findProperty("verName") as String?) ?: "dev"
    }

    signingConfigs {
        create("shared") {
            // 고정 서명 키 — 빌드마다 서명이 바뀌지 않아야 기존 설치 위에 업데이트가 된다.
            // 사내 배포용이며 Play 스토어 업로드용이 아니다.
            storeFile = file("keystore/ridemeter.p12")
            storePassword = "ridemeter"
            keyAlias = "ridemeter"
            keyPassword = "ridemeter"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
