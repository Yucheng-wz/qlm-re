// ============================================================================
// 「取了吗」 - 取件码与待办管理工具
// 作者：焱川 (by 焱川)
// ============================================================================
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional: if keystore.properties is absent (e.g. on another
// machine) the build still works and simply produces an unsigned release APK.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.hnu.pickupassistant"
    compileSdk = 34

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.hnu.pickupassistant"
        minSdk = 29
        targetSdk = 34
        // 【版本匹配铁律】versionCode 由 versionName 直接拼接：
        // X.Y.Z → XYZ（三位整数）。禁止自动 +1 / 累加；versionName 与 versionCode 同行同步修改，
        // 且必须与线上 Gitee version.json 的 versionCode 完全相等。
        // （注意：X/Y/Z 各位只能 0~9，2.8.10 这类会拼成四位、破坏 XYZ 映射。）
        // 本轮：分发渠道迁到 GitHub Releases + 国内加速镜像——
        //       UpdateConfig 改为「GitHub owner/repo + 多源列表」：version.json 依次尝试
        //       ghproxy.net / ghfast.top / gh-proxy.com / jsDelivr / raw / Gitee 旧地址；
        //       APK 下载按 apkMirrors → 加速前缀+直链 → 直链 逐个回退；
        //       移除 Gitee 专用 Referer，统一 UA。3.0.8 / 308 → 3.0.9 / 309。
        versionCode = 309
        versionName = "3.0.9"

        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // 离线 OCR 的 native 模型库约 11 MB / 架构，只保留 64 位 ARM 真机架构
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        // Shizuku UserService 需要 AIDL 接口
        aidl = true
        // 自动更新模块读取 BuildConfig.VERSION_CODE 作为本地版本号（AGP 8 默认关闭）
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Jetpack Compose & Material 3
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 本地离线 OCR：中文+拉丁文字识别 bundled 模型，模型随 APK 打包，无需联网/Play 服务
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Shizuku：借 shell 权限免弹窗抓屏（「看见没」无授权弹窗路径）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 自建 Gitee 静态 JSON 自动更新：OkHttp 请求 version.json 与下载 APK
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 纯 Kotlin 工具类的可执行单元测试（JVM 本地跑，不需要真机）
    testImplementation("junit:junit:4.13.2")
}
