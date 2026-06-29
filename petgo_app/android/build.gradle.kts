allprojects {
    repositories {
        google()
        mavenCentral()
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
