# 为什么 vendor 这份插件（2026-08-07）

`tencent_cloud_chat_push 9.0.7652`（pub.dev 原版）的 `android/build.gradle` 仍调用 `jcenter()`，
而 Gradle 9 已**彻底移除**该方法 → 本项目（Gradle 9.1.0 / AGP 9.0.1）构建即失败。
对照 `tencent_cloud_chat_sdk` 同结构脚本（无 jcenter）可正常构建，故唯一改动是：

- `android/build.gradle`：删除 buildscript 与 rootProject.allprojects 两处 `jcenter()` 行。
- `android/build.gradle`：`compileSdkVersion 33` → `36`（chat_sdk 9.x 与 androidx.fragment 1.7.1
  要求依赖方 compileSdk ≥34，项目基线 36；仅编译期 API 面，不改运行时行为）。

其余文件与 pub.dev 原版 **逐字节一致**（未拷贝 example/test 目录）。
`pubspec.yaml` 经 `dependency_overrides` 指向本目录。

**移除时机**：上游发布去掉 jcenter 的新版后，升级 `dependencies` 里的版本号、
删掉 `dependency_overrides` 项与本目录即可（注意插件版本须与 `tencent_cloud_chat_sdk` 同号配套）。
