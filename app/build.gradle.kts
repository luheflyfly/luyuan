plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
}

android {
    namespace = "com.luyuan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luyuan"
        minSdk = 24
        targetSdk = 34
        versionCode = 104
        versionName = "1.33.1"
        // 离线识别（sherpa-onnx）jniLib 只带 arm64（用户真机为 arm64，控制 APK 体积）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    androidResources {
        // onnx 模型文件禁止 aapt 压缩：JNI 侧按未压缩资产读取
        noCompress += "onnx"
    }

    signingConfigs {
        getByName("debug") {
            // 固定签名：keystore 提交进仓库，保证每次 CI 编出的 APK 签名一致，
            // 手机端才能覆盖安装（此前每次编译临时密钥 → 签名不同 → 无法覆盖装）
            storeFile = rootProject.file("keystore/luyuan-debug.p12")
            storePassword = "luyuan2026"
            keyAlias = "luyuan-debug"
            keyPassword = "luyuan2026"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    val composeBom = "2024.06.00"
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("com.alphacephei:vosk-android:0.3.47")
    // 日记配图加载（本地文件 + SVG 贴纸）
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-svg:2.6.0")
    // 分享网址抓正文（微信文章等）：HTML 解析
    implementation("org.jsoup:jsoup:1.17.2")
}
