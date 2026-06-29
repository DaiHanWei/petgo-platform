---
title: 'PostHog 分析基建接入（Flutter 端，基建层）'
type: 'feature'
created: '2026-06-29'
status: 'done'
baseline_commit: '107a122'
context: ['{project-root}/CLAUDE.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** TailTopia 当前没有任何前端行为分析能力，无法观测用户启动/页面跳转/登录等关键路径。已拿到 PostHog 项目凭证与交接文档，需在 Flutter 端把分析基建铺起来。

**Approach:** 接入 `posthog_flutter` SDK，token 走 dart-define 注入；`main()` 完成初始化、`GoRouter` 挂 `PosthogObserver` 自动采集页面跳转、app 根用单个 `ref.listen` 在登录/登出时 identify/reset；封装一个脱敏的 `Analytics` 上报门面。本期只铺基建，不批量埋业务事件。

## Boundaries & Constraints

**Always:**
- token/host 经 `String.fromEnvironment` 注入，带默认值，对齐 `dio_client.dart` 既有模式；不硬编码进 `main.dart` 逻辑分支。
- identify 的 distinctId 用 `sha256('tailtopia-user-' + userId)` 的十六进制串——既不外露自增 id（CLAUDE.md 护栏），又稳定可跨会话关联。
- identify/reset 在 app 根用 `ref.listen(authControllerProvider)` 收口，**不**修改 `AuthController`（保持其「仅持不可变态、副作用在外」约定）、**不**逐个改 9 处登录/登出 call-site。
- `config.debug = kDebugMode`（release 自动关）。

**Ask First:**
- 若要把 email/nickname/petStatus 等任何用户属性作为 userProperties 上传。
- 若要开启 `sessionReplay`、或把 PostHog 接到后端/Web 端。
- 若要超出基建、批量埋具体业务事件（本期不做）。

**Never:**
- 绝不向 PostHog 传 PII（email/昵称/真实姓名）或健康数据（宠物症状/分诊结果文本）。
- 绝不把 Personal API Key 放进客户端；只用 write-only Project Token。
- 不引入新中间件、不动后端、不开 sessionReplay。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 用户登录成功 | authState → authenticated 且 `profile.id != null` | 调用 `Posthog().identify(distinctId = sha256(id))`，无 userProperties | identify 抛错则吞掉并 log，不阻断登录 |
| 登出/续期失败 | authState → guest | 调用 `Posthog().reset()` | reset 抛错则吞掉，不阻断登出 |
| 已登录但无 id（如 VET，state 无 profile） | authenticated 且 `profile?.id == null` | 不 identify（保持匿名）；本期不覆盖 VET | N/A |
| 页面跳转 | GoRouter push/pop 命名路由 | `PosthogObserver` 自动发 `$screen` | N/A |
| `Analytics.capture` 传入含 PII 的 key | properties 含 email/name/phone 等敏感键 | 门面剥离这些键后再上报 | N/A |

</frozen-after-approval>

## Code Map

- `petgo_app/pubspec.yaml` -- 加 `posthog_flutter` 依赖（`crypto` 已在）。
- `petgo_app/lib/core/analytics/analytics.dart` -- 新增：上报门面（init 配置、identify/reset、脱敏 capture、distinctId 哈希）。
- `petgo_app/lib/main.dart` -- `runApp` 前调用 `Analytics.init()`。
- `petgo_app/lib/app.dart` -- `TailTopiaApp` 内 `ref.listen(authControllerProvider)` 触发 identify/reset。
- `petgo_app/lib/core/router/app_router.dart` -- `GoRouter(... observers: [PosthogObserver()])`。
- `petgo_app/lib/core/network/dio_client.dart` -- 参照其 `fromEnvironment` 默认值写法（不改动，仅对齐）。

## Tasks & Acceptance

**Execution:**
- [x] `petgo_app/pubspec.yaml` -- 加 `posthog_flutter: ^4.0.0`；`flutter pub get` 解析为 **4.11.0**（^4.0.0 约束内最新），成功。
- [x] `petgo_app/lib/core/analytics/analytics.dart` -- 门面已建：`init()` 用 dart-define `POSTHOG_KEY`/`POSTHOG_HOST`（默认生产 token / `https://us.i.posthog.com`）建 `PostHogConfig`（debug=kDebugMode、captureApplicationLifecycleEvents=true、sessionReplay=false）；`identifyUser(int)` sha256 distinctId；`reset()`；`capture()` 经 `scrub` 剥离敏感键。全 try/catch 吞错。
- [x] `petgo_app/lib/main.dart` -- `runApp` 前 `await Analytics.init()`。
- [x] `petgo_app/lib/app.dart` -- `build` 内 `ref.listen(authControllerProvider)`：authenticated 且 `profile?.id != null` → identify；guest → reset。
- [x] `petgo_app/lib/core/router/app_router.dart` -- `GoRouter(... observers: [PosthogObserver()])`。
- [x] `petgo_app/test/core/analytics/analytics_scrub_test.dart` -- 6 个单测覆盖 scrub（大小写/不改原 map）+ distinctId（确定性/非明文 64hex），全绿。

**Acceptance Criteria:**
- Given release 构建（无 dart-define 覆盖），when app 启动，then PostHog 用默认生产 token + host 初始化且 debug=false。
- Given 用户成功登录，when authState 变 authenticated，then 上报的 distinctId 是哈希串、不含明文 id/email。
- Given 调用 `Analytics.capture` 且 props 含 `email`，when 上报，then 该键已被剥离。
- Given `flutter analyze` 与 `flutter test`，when 运行，then 全绿（L0）。

## Spec Change Log

**2026-06-29 — review patches（无 spec loopback，均为代码层 patch）：** 三路对抗式 review 后做防御性加固，未改 frozen intent：
- 监听器条件由 `== authenticated` 放宽到 `!= guest && id != null`（覆盖 newUserPendingOnboarding 引导期 identify）；加「id 变化才触发」防改资料重复 identify；换用户先 reset；guest 仅在曾有身份时 reset。
- `scrub` 改键归一化（去 `_`/`-`）+ 递归嵌套 map；黑名单扩健康/凭证/精确位置类键；新增对应单测。
- `init` 加 3s timeout 防 setup 挂起阻塞首帧。
- distinctId 维持 sha256 方案，但注释不再过度声称「不可枚举」，记录威胁模型与后端 token 后续路径。
- 延后项记入 `deferred-work.md`：主 Tab `$screen` 缺失、冷启动登出残留身份、id 类键导出策略、自由文本值 PII 等。

## Design Notes

distinctId 同时满足两条约束（无 PII + 不外露自增 id），用已在依赖里的 `crypto`：

```dart
String _distinctId(int userId) =>
    sha256.convert(utf8.encode('tailtopia-user-$userId')).toString();
```

identify 收口在 app 根（而非 9 处 call-site，也不入 `AuthController`），保持控制器纯净：

```dart
ref.listen<AuthState>(authControllerProvider, (prev, next) {
  final id = next.profile?.id;
  if (next.status == AuthStatus.authenticated && id != null) {
    Analytics.identifyUser(id);
  } else if (next.status == AuthStatus.guest) {
    Analytics.reset();
  }
});
```

`_scrub` 用敏感键黑名单（email/name/nickname/phone/displayName 等，大小写不敏感）做防御性兜底，确保即便误传 PII 也不出端。VET 登录态 state 不含 profile.id，本期保持匿名，后续如需再议。

## Verification

**Commands:**
- `cd petgo_app && flutter pub get` -- expected: 依赖解析成功（含 posthog_flutter）。
- `cd petgo_app && flutter analyze` -- expected: No issues found（L0）。
- `cd petgo_app && flutter test test/core/analytics/analytics_scrub_test.dart` -- expected: 全绿。
- `cd petgo_app && flutter gen-l10n && flutter test` -- expected: 既有测试不回归。

**Manual checks (L2，待本地/真机):**
- `config.debug=true` 跑 debug 包，控制台见事件打印；PostHog 控制台 Activity → Live events 见 `$screen`、`$identify` 实时流入；distinctId 为哈希串非明文。

## Suggested Review Order

**身份绑定（最高杠杆，先看这里）**

- 入口：登录/登出 → identify/reset 的单点收口，含换用户/防重复/引导期逻辑
  [`app.dart:77`](../../petgo_app/lib/app.dart#L77)
- distinctId 哈希方案 + 威胁模型说明（为何 V1 接受无盐 sha256）
  [`analytics.dart:95`](../../petgo_app/lib/core/analytics/analytics.dart#L95)

**PII 脱敏安全网**

- `scrub`：键归一化 + 递归嵌套 map 剥离敏感键
  [`analytics.dart:99`](../../petgo_app/lib/core/analytics/analytics.dart#L99)
- 敏感键黑名单（身份/健康/凭证/精确位置）
  [`analytics.dart:33`](../../petgo_app/lib/core/analytics/analytics.dart#L33)

**初始化与自动追踪**

- `init`：dart-define 注入 token/host + 3s timeout 防阻塞首帧
  [`analytics.dart:46`](../../petgo_app/lib/core/analytics/analytics.dart#L46)
- `runApp` 前初始化
  [`main.dart:20`](../../petgo_app/lib/main.dart#L20)
- GoRouter 挂 `PosthogObserver` 自动页面追踪（注意 deferred：主 Tab 用 IndexedStack 不发 `$screen`）
  [`app_router.dart:90`](../../petgo_app/lib/core/router/app_router.dart#L90)

**支撑改动**

- 依赖（解析为 4.11.0）
  [`pubspec.yaml:70`](../../petgo_app/pubspec.yaml#L70)
- scrub / distinctId 单测（8 例）
  [`analytics_scrub_test.dart:5`](../../petgo_app/test/core/analytics/analytics_scrub_test.dart#L5)
