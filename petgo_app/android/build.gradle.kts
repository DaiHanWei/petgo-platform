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

// posthog_flutter 4.11.0 发布配置陈旧，与本项目工具链冲突，定向修正该子工程（不动插件版本与 Dart 代码）：
//  1) Kotlin languageVersion/apiVersion 钉死 "1.6"，Kotlin 2.3.20 编译器拒编 → 抬到 2.0。
//  2) 插件 compileSdk=33，但其连带的 androidx(fragment/activity/lifecycle) 要求 ≥34 → 抬到 36。
subprojects {
    if (project.name == "posthog_flutter") {
        project.afterEvaluate {
            project.tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
                compilerOptions {
                    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
                    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
                }
            }
            (project.extensions.findByName("android") as? com.android.build.gradle.BaseExtension)
                ?.compileSdkVersion(36)
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
