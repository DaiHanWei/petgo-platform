import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    // 系统推送（TIMPush/FCM）：读 app/google-services.json（FCM 通道注册在 IM 控制台，证书 ID 9088）。
    id("com.google.gms.google-services")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// 正式发布签名（upload key）：值来自 android/key.properties（gitignored，绝不入库）。
// 文件缺失时（CI / 未配置的机器）release 回退到共享 debug 签名，仍可出内部测试包。
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Google 地图 Android 密钥（V1.3.0 batch-b1 Story 1.4 · AD-3 Rule 4）。
// 🔴 **env 注入、绝不入库**（CLAUDE.md 护栏）。两条来源，按优先级：
//   ① 环境变量 GOOGLE_MAPS_API_KEY_ANDROID（CI / 出包脚本）
//   ② android/maps.properties 的 MAPS_API_KEY（本机开发，gitignored；模板 maps.properties.example）
// 两者都没有 → 空串：**能编译、能装，只是地图是灰的**。刻意不让构建失败 ——
// 绝大多数开发与 CI 任务（analyze / test / 其它页面联调）与地图无关，
// 为一个没配密钥就整包编不出来，代价远大于收益（同 release 签名缺失回退 debug 的取舍）。
// ⚠️ 密钥三套（iOS / Android / 后台 Web）**不要混用**；每把都要加平台限制 + 每日配额上限。
// ⚠️ Google Cloud 项目**未开通结算**时密钥在正式包里不工作 —— 属发版检查单 RC-2，不是代码能解决的。
val mapsProperties = Properties()
val mapsPropertiesFile = rootProject.file("maps.properties")
if (mapsPropertiesFile.exists()) {
    mapsProperties.load(FileInputStream(mapsPropertiesFile))
}
val googleMapsApiKey: String =
    System.getenv("GOOGLE_MAPS_API_KEY_ANDROID")
        ?: (mapsProperties["MAPS_API_KEY"] as String?)
        ?: ""

android {
    namespace = "com.tailtopia.app"
    // posthog_flutter 4.11.0 连带的 androidx（fragment 1.7.1 / activity 1.8.1 / lifecycle 2.7.0 等）
    // 要求 compileSdk ≥ 34；显式提到 36（仅编译期可用 API，不改 targetSdk 运行时行为）。
    compileSdk = maxOf(flutter.compileSdkVersion, 36)
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.tailtopia.app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // 地图密钥经 manifest 占位符注入（AndroidManifest 里是 ${googleMapsApiKey}，不是明文）。
        manifestPlaceholders["googleMapsApiKey"] = googleMapsApiKey
    }

    signingConfigs {
        getByName("debug") {
            // 团队共享 debug keystore：所有人编译 SHA-1 一致，Google 登录免逐机注册 Cloud。
            // 标准密码、非敏感，仅供开发 / 内部测试包；正式发布须另建 release keystore（勿入库）。
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // upload key（Google Play 首次提审 / 上架签名）。值由 key.properties 注入。
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // 有 key.properties → 用 upload key 正式签名（提审/上架）；否则回退 debug（内测/CI 不被卡）。
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // AGP 9 起 release 默认开 R8。TIMPush 依赖链里的 WorkManager/Room 靠反射找
            // 生成类，不加 keep 规则会启动即崩（2026-08-07 真机实测，见 proguard-rules.pro）。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // TIMPush FCM 厂商通道工件（tencent_cloud_chat_push 插件只带 timpush 核心 9.0.7653，
    // 厂商工件按需自选——本项目仅接 FCM，国内厂商通道一律不接）。
    // ⚠️ 版本 9.0.7652 非笔误：mavenCentral 上 fcm 工件只发到 9.0.7652（核心 9.0.7653 无配套 fcm，
    // 腾讯发布侧错位），同小版本兼容。升级插件时先查 repo.maven.apache.org 的 fcm maven-metadata。
    implementation("com.tencent.timpush:fcm:9.0.7652")
}

flutter {
    source = "../.."
}
