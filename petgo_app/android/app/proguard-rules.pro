# R8 / ProGuard 规则（release 构建）。
#
# 背景（2026-08-07 真机实测）：AGP 9 起 release 默认开启 R8 混淆。接入 TIMPush 后其依赖链
# 带入 WorkManager + Room，二者靠**反射**按类名拼 `<类名>_Impl` 找生成实现，被混淆改名后
# 启动即崩：`Failed to create an instance of class androidx.work.impl.WorkDatabase.canonicalName`。
# debug 包不混淆，故只在 release 包（含上架包）复现——属发版阻塞级，规则不可删。

# --- Room：保留数据库类与其生成的 _Impl 实现（反射按名查找） ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# --- WorkManager：内部实现与 Worker 子类均经反射实例化 ---
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-dontwarn androidx.work.**

# --- 腾讯 IM / TIMPush：SDK 内部大量反射 + JNI 回调，整体保留 ---
-keep class com.tencent.** { *; }
-dontwarn com.tencent.**

# --- FCM / Google Play services：消息服务经清单反射拉起 ---
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
