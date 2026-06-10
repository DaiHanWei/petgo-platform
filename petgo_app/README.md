# petgo_app

TailTopia 移动端 —— Flutter 3.44.x / Dart 3.12，Riverpod + go_router，feature-first 结构，portrait-only，V1 仅浅色，双语（id / en）。

## 前置

- Flutter **3.44.x**（stable）/ Dart 3.12
- iOS：Xcode + 模拟器；Android：Android SDK（apk 构建）

## 本地起步

```bash
cd petgo_app
flutter pub get
flutter gen-l10n          # 生成 AppLocalizations（i18n）
flutter run               # 出空白首页（HomePage 占位，显示本地化 appTitle）
```

切换设备语言 id ↔ en，首页文案随之切换（验证 i18n 接通）。

## 质量门槛（L0）

```bash
flutter analyze          # 零警告
flutter test             # 冒烟：App 启动并渲染本地化首页
```

## 结构

- `lib/core/{network,router,theme,l10n,storage}` · `lib/shared/{widgets,utils}`
- `lib/features/<feature>/{data,domain,presentation}`，feature ∈ `auth,triage,consult,content,profile,notify,me,vet`
- `lib/l10n/*.arb`（id/en 文案）+ 生成的 `app_localizations*.dart`

## 已知约束

- `riverpod_lint` + `custom_lint` 暂缓：`custom_lint` 当前最高仅支持 analyzer 8，而 Flutter 3.44 / riverpod 3.x 需 analyzer 9+。待生态跟进后在 `analysis_options.yaml` 启用（已留 TODO）。
- `flutter build apk` 需较大的 Gradle 发行包下载；受限网络可能截断，CI（干净网络）正常。
