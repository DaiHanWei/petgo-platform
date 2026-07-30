# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 这是 PetGo 移动端子工程的工作约定。**Monorepo 级纪律**（版本基线、双产物布局、story / BMad 流程、Flyway 约定、`/me` 命名、ProblemDetail、enforcement 护栏、headless 云端能力）见根目录 `../CLAUDE.md` —— 本文件不重复。

## 常用命令

```bash
flutter pub get
flutter gen-l10n                    # 生成 lib/l10n/app_localizations*.dart（改 ARB 后必须重跑）
flutter analyze                     # 零警告为门槛
flutter test                        # 全量 widget/unit
flutter test test/auth/auth_logic_test.dart                # 单文件
flutter test --plain-name 'AC3: id device locale'          # 单用例（按 test 名匹配）
flutter build apk --debug           # L0 构建烟测
flutter run --dart-define=PETGO_API_BASE_URL=http://10.0.2.2:8080
```

build_runner（riverpod_generator 用；当前仓库少量代码生成）：
```bash
dart run build_runner build --delete-conflicting-outputs
```

运行期配置注入（**值必须 `--dart-define` 传入，不要硬编码到源**）：
- `PETGO_API_BASE_URL`（默认 `http://localhost:8080`，Android 模拟器用 `http://10.0.2.2:8080`）
- `GOOGLE_SERVER_CLIENT_ID`（L2 真实 Google 登录需要；空 → 走平台默认配置）

## 高层架构

### 分层 & feature-first
```
lib/
  main.dart            # 锁竖屏 + 启动期从 prefs 注水 ProviderScope overrides
  app.dart             # MaterialApp.router + 仅浅色 + textScaler clamp ≤ 1.3（NFR-13）
  core/
    network/           # dio_client（providers）+ auth_interceptor + api_paths + problem_detail
    router/            # app_router（GoRouter Provider）+ deep_link_routes + route_intent
    storage/           # SecureTokenStore（JWT）+ AppPrefs（shared_preferences 薄包装）
    theme/             # colors/spacing/typography/elevation/motion/rounded（设计 token）
    l10n/              # locale_controller（手动选择 + 持久化覆盖）
    media/             # media_scope（媒体调用范围/权限上下文）
  l10n/                # app_en.arb / app_id.arb（生成产物已在 analyzer.exclude）
  features/<f>/        # f ∈ {auth, triage, consult, content, profile, notify, me, vet, media}
    data/              # repositories + 外部 client（如 google_auth_client）
    domain/            # state、controller、纯逻辑（headless 可单测）
    presentation/      # *_page.dart + 局部 widget
  shared/{widgets,utils}
test/<feature>/        # 与 features/ 同名分目录；widget_test.dart = 根冒烟
```
新功能严格按 `features/<f>/{data,domain,presentation}` 落位；跨 feature 复用走 `shared/`。

### 状态管理：Riverpod 3.x（无 codegen 也可手写 Provider）
- **入口注水**：`main.dart` 异步读 prefs 后用 `ProviderScope(overrides: [...])` 注入启动态（`profilePromptBootstrapProvider`、`localeOverrideProvider`）—— 不要把启动期 IO 放进 Provider 构造期。
- **懒依赖避循环**：`dioProvider` 通过闭包 `() => ref.read(authRepositoryProvider).refresh()` 拿 `authRepository`，**不在构造期 read**（否则 dio↔auth 互依赖会死锁）。新增类似双向依赖时沿用这个模式。

### 路由：go_router + Provider redirect
- `routerProvider`（`core/router/app_router.dart`）：所有受控路由的**唯一**门控入口在顶层 `redirect`，不要在叶子页里复写守卫。
- 受控前缀 `_controlledLocations = {/profile, /triage, /me, /consult, /notifications}`：游客深链命中 → redirect `/home`。
- 角色隔离：`auth.isVet`（兽医）和用户/游客**互斥**；任一方走对方路由都被 redirect 回各自首页。`/vet/login` 是兽医登录入口，登录后停留也会被收口到 `/vet/workbench`。
- `StatefulShellRoute.indexedStack` 承载 4 个用户 Tab（home/profile/triage/me）；**详情页、引导流、`/vet/*` 走 shell 外顶层路由**（push 时隐藏 Tab Bar）。
- Tab 点击门控由 `AppShell` 单一入口处理（不要把"未登录 → 弹登录"散到各 Tab 页里）。
- `rootNavigatorKey`：拦截器在 401 续期彻底失败后用它定位全局 Navigator 弹强登录引导（`LoginGuideController` 单例守卫，防并发 401 叠多窗）。

### 网络：dio + AuthInterceptor
- 所有 API 路径**集中在 `core/network/api_paths.dart`**（`/api/v1` + 资源小写复数连字符；当前用户主体统一 `/me`，**不要写 `/users/me`** —— 决策 C1）。新端点先在 `ApiPaths` 注册，再被 repository 引用。
- 401 → 单飞 `refresh()` → 重放一次原请求；refresh 失败 → `toGuest()` + 弹强登录引导。Repository **不要自己处理 401**。
- `Accept-Language` 由拦截器按 `platformDispatcher.locale` 实时注入（`id` / 其余 → `en`）。
- 错误体走后端 RFC 9457 ProblemDetail（`problem_detail.dart` 解析）；UI 文案用本地化字符串映射，**不要直接展示 `detail` 原文**。

### 存储
- `SecureTokenStore`（`flutter_secure_storage`）：access/refresh JWT。
- `AppPrefs`（`shared_preferences`）：非敏感偏好（语言、档案提示条计数 / 永久关闭 / 完成态）。键全部在 `AppPrefs.k*` 静态常量集中。

### 主题 & 无障碍
- V1 **仅浅色 + portrait-only**（`main.dart` 锁向、`AppTheme.light` 直接传，**不要**加 dark/auto 分支）。
- `app.dart` 的 `MediaQuery` builder 把 `textScaler` clamp 到 `maxTextScale = 1.3`（NFR-13），所有页面均受此约束 —— **不要在子树里重置 MediaQuery 把它放开**，否则超大字号会破布局。

### 输入 / 键盘避让（强制标准）
**任何获得焦点的输入框必须完整显示在软键盘上方，不被遮挡。** 新增含输入框的页面/弹层必须按其「形态」套用对应机制（共享件 `lib/shared/widgets/keyboard_safe_area.dart`）：

| 形态 | 机制 |
|---|---|
| **可滑动表单页**（body 已是 `ListView`/`SingleChildScrollView`） | 零改动，聚焦自动 `ensureVisible`；仅核实 Scaffold `resizeToAvoidBottomInset` 未被设 `false` |
| **不可滑动表单页**（`Column` + `Spacer`/`Expanded` 沉底按钮） | 用 `KeyboardSafeArea` 包 `Column`（`Spacer` 仍生效）；依赖 Scaffold `resizeToAvoidBottomInset: true`（默认） |
| **不可滑动、输入在顶部** | 零动作：`resizeToAvoidBottomInset: true` 从底部压缩、顶部不动 → 顶部框天然在键盘上方 |
| **底部弹层 bottom sheet** | `showModalBottomSheet(isScrollControlled: true)` + 内容套 `KeyboardInset`（或在滚动内容底部加 `viewInsets.bottom` padding）；**固定高度**（`FractionallySizedBox`）sheet 必须套 `KeyboardInset`，否则不随键盘自适应 |
| **底部贴附输入栏**（评论/聊天） | 宿主 Scaffold `resizeToAvoidBottomInset: true`（body 内 `Column` 底栏自动上移）；浮层挂载则套 `KeyboardInset` |
| **dialog** | Flutter 默认居中 + viewInsets 顶起，通常无需改，仅核实 |

- **点非输入区收起键盘**：全局已在 `app.dart` 的 `builder` 套 `GestureDetector(translucent, onTap → FocusManager.instance.primaryFocus?.unfocus())`——点到非交互空白处自动收键盘，按钮/输入框不受影响。新页无需再各自处理，勿在子树重复叠加抢焦点逻辑。
- 共享件二选一：`KeyboardSafeArea`（表单页体，填满视口 + 键盘弹出可上滚）／`KeyboardInset`（底部弹层 / 浮层贴附栏，动画补 `viewInsets.bottom` 内边距）。
- ⚠️ `app_shell.dart` 有意设 `resizeToAvoidBottomInset: false`（底栏 + 「＋」不被键盘顶起）；Tab 根页因此**不得内联输入框**，文字编辑一律走 modal sheet 让位。
- 标准只保证「焦点框」在键盘上方；输入框下方的非焦点按钮被挡属更强诉求，需按页单独提出。测试见 `test/shared/keyboard_safe_area_test.dart`。

### i18n
- ARB → `flutter gen-l10n` → `lib/l10n/app_localizations*.dart`（已在 analyzer.exclude；改完别忘记跑 gen-l10n，否则编译失败）。
- 语言决策优先级：`localeControllerProvider`（手动选择，持久化在 prefs）→ `localeResolutionCallback`（设备 `id` 直通，其余回退英语）。新增文案两份 ARB 同步写，**严禁源里硬编码用户可见字符串**。

### 测试
- 目录与 features 平行：`test/<feature>/`。冒烟 `test/widget_test.dart` 覆盖 Shell + Tab Bar + i18n 默认/印尼语切换。
- `test/l10n/microcopy_rules_test.dart` 类的契约测试在改 ARB / 微文案规则时会触发，**先跑 `flutter test` 再提交**，别绕过。
- 模拟设备语言：`tester.platformDispatcher.localesTestValue = const [Locale('id')]`（参考 `widget_test.dart`）。

## 本仓库特有的"易踩点"

- `riverpod_lint` / `custom_lint` **暂缓**：`custom_lint` 仅到 analyzer 8，本仓 analyzer 9 → 不要为了开 lint 而降版本；`analysis_options.yaml` 已留 TODO，等生态跟上。
- 生成代码（`**/*.g.dart` 与 `app_localizations*.dart`）已在 analyzer.exclude，**不要手改生成产物**；改 schema/ARB 后重跑生成命令。
- 改完 `pubspec.yaml` 务必 `flutter pub get` + 重跑 `gen-l10n`（pub upgrade 可能动 intl 链）。
- Android 模拟器访问宿主后端：`PETGO_API_BASE_URL=http://10.0.2.2:8080`（`localhost` 在模拟器里是它自己）。
- 验证层级（详见根 CLAUDE.md）：本子工程默认 L0 在云端跑，**模拟器/真机视觉（L2）必须本地** —— 云端 headless 不要尝试 `flutter run` 截屏。
