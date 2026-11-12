allprojects {
    repositories {
        google()
        mavenCentral()
        // TikTok Business SDK 只发布在 JitPack（tiktok_events_sdk 插件依赖 com.github.tiktok:tiktok-business-android-sdk）。
        // 🛡 用 content 过滤只放行这一个 group：JitPack 直接从 GitHub 构建，不限范围会让任意依赖都可能从这里解析。
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.tiktok") }
        }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

// tiktok_events_sdk 的构建脚本在 AGP ≥ 9 时默认「Kotlin 已内置」而不再 apply kotlin-android，
// 但本工程 gradle.properties 设了 android.builtInKotlin=false ⇒ 它的 Kotlin 源码没人编译，
// GeneratedPluginRegistrant 找不到 TiktokEventsSdkPlugin（编译失败）。这里只对该插件补上 kotlin-android。
// 将来本工程打开 builtInKotlin 时删掉这段。
subprojects {
    if (name == "tiktok_events_sdk") {
        pluginManager.withPlugin("com.android.library") {
            if (!pluginManager.hasPlugin("org.jetbrains.kotlin.android")) {
                apply(plugin = "org.jetbrains.kotlin.android")
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
