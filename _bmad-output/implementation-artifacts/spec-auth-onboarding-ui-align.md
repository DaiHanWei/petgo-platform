---
title: '认证 & 引导流 UI 对齐原型 V1.0.0（补 3 缺口）'
type: 'feature'
created: '2026-06-16'
status: 'done'
baseline_commit: '7e688b60b9ab465c1f72f228fcb31d3800174797'
context:
  - '{project-root}/_bmad-output/core-pages-UX.md'
  - '{project-root}/_bmad-output/pages/splash.html'
  - '{project-root}/_bmad-output/pages/notif-gate.html'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 认证 & 引导流 8 屏中 7 屏已对齐薄荷绿，但缺 3 处真实缺口：① 无 Splash 启动屏（冷启动直进 `/home`）；② 通知权限只有「我的」页被动引导卡，缺建档/首次问诊后主动弹的「前置说明」bottom sheet（原型 P-09）；③ Mint 引导页文案硬编码印尼语 + 残留 `'PETGO'` 字标（改名漏网）。

**Approach:** 新建 SplashPage 作为启动初始路由（动画后转 `/home`）；新建通知权限前置 bottom sheet（pre-prompt rationale）接入既有 `PushPermissionGate` 触发点；把 Mint 引导页硬编码文案迁入 arb 并修字标为 TailTopia。三处一律复用 app 现有 **mint + cream** token，**不引入原型的紫色主色**。

## Boundaries & Constraints

**Always:** 复用 `core/theme` 既有 token（mint 主色 / cream 底）；用户可见文案走 `lib/l10n` arb（en 为模板 + id）；保持单账号单宠物、portrait-only；推送沿用 `PushPermissionGate` 既有语义——绝不 App 首启请求、双时机（首次问诊 / 建档且从未问诊）取最早、仅触发一次、拒绝后不再主动弹。

**Ask First:** 若某处不引入原型紫色 `#845EC9` 就无法忠实还原（典型为 Splash 品牌辉光色），HALT 确认用 mint 还是破例。

**Never:** 不改全局主题、不把主色由 mint 换成原型 violet；不改 cream 基底；不动后端契约 / DB / 路由 `redirect` 门控语义；不碰 `PETGO_MOCK`、目录名、DB 默认等**故意保留**的 petgo 标识（仅改用户可见的 `'PETGO'` 字标）。

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 冷启动过场 | 无 deeplink | 显示 Splash → ~2.2s 动画后 `go('/home')`（再由 redirect 决定登录/引导） | init 异常也按时转场，不卡死 |
| 建档触发权限 | 建档完成 & 从未问诊 & `pushPermissionAsked=false` | 弹 P-09 前置 sheet；点 Aktifkan → 请求系统权限；任一结果置 asked=true | 系统权限异常仍置 asked |
| 问诊触发权限 | 首次问诊完成 & `asked=false` | 同上（双时机取最早，仅一次） | 同上 |
| 拒绝前置说明 | P-09 点「Tidak, terima kasih」 | 关 sheet、不调系统弹窗、置 asked=true | N/A |
| 已问过 | `pushPermissionAsked=true` | 不弹 sheet | N/A |

</frozen-after-approval>

## Code Map

- `lib/core/router/app_router.dart` -- `initialLocation:'/home'`(line55) + `redirect`(line56)；加 `/splash` 路由并设为初始
- `lib/features/onboarding/presentation/splash_page.dart` -- 新建 Splash（P-01）
- `lib/features/notify/presentation/push_permission_sheet.dart` -- 新建 P-09 前置 sheet
- `lib/features/notify/domain/push_permission_gate.dart` -- `_maybeRequest`：请求系统权限前先弹 sheet 确认
- `lib/features/notify/data/push_permission_providers.dart` -- gate 触发点接线（注入「弹 sheet」回调）
- `lib/features/onboarding/presentation/mint_onboarding_page.dart` -- 文案迁 arb + `'PETGO'`→TailTopia
- `lib/l10n/app_en.arb` / `app_id.arb` -- 新增 splash / pushPrompt / onboarding 文案键
- `lib/core/theme/colors.dart` -- 如需，加 Splash 品牌暗色常量（mint 系）

## Tasks & Acceptance

**Execution:**
- [x] `lib/features/onboarding/presentation/splash_page.dart` -- 新建 SplashPage：mint 品牌暗底 + 脉冲环/图标弹入/字标淡入/三点 loader + 版本号「v 1.0.0」+ 副标「Komunitas Pecinta Hewan Peliharaan Indonesia」；`initState` 起 ~2.2s 定时后 `context.go('/home')`；系统 reduce-motion 时降级为静态
- [x] `lib/core/router/app_router.dart` -- 加 `GoRoute('/splash')`，`initialLocation` 改 `'/splash'`；`redirect` 放行 `'/splash'`（由 Splash 自行转场，不被 redirect 抢跳）
- [x] `lib/features/notify/presentation/push_permission_sheet.dart` -- 新建 `showModalBottomSheet`：拖拽条 + 铃铛图标 + 标题/副文 + 3 条好处(🎂生日/💬兽医回复/🏅里程碑) +「Aktifkan Notifikasi」/「Tidak, terima kasih」；返回 `bool`（同意/拒绝）
- [x] `lib/features/notify/domain/push_permission_gate.dart` + `data/push_permission_providers.dart` -- 触发时先弹 sheet：同意→`requestSystemPermission`；拒绝→跳过系统弹窗；任一分支置 `asked=true`；保持双时机/仅一次语义（通过注入 sheet 回调，保持 gate 可测）
- [x] `lib/features/onboarding/presentation/mint_onboarding_page.dart` -- 硬编码印尼语全部改 `AppLocalizations` 取值；`'PETGO'` 字标 → TailTopia（或品牌常量）
- [x] `lib/l10n/app_en.arb` + `app_id.arb` -- 增 `splashTagline`、`pushPrompt*`（title/subtitle/benefit×3 双语/cta/dismiss）、`onboarding*` 键；`flutter gen-l10n`
- [x] 单元/Widget 测试 -- 覆盖 I/O 矩阵：gate 在 `asked=true` 不弹、双时机各仅一次、sheet 拒绝置 asked；Splash 定时转场到 `/home`

**Acceptance Criteria:**
- Given 冷启动无 deeplink, when 启动 app, then 先见 Splash 动画约 2.2s 再自动进 `/home`，期间不卡死
- Given 已登录新用户建档完成且从未问诊, when 落地, then 弹 P-09 sheet；点 Aktifkan 触发系统权限请求
- Given `pushPermissionAsked=true`, when 任一时机, then 不再弹 P-09
- Given 设备语言 id 或 en, when 打开 Splash 与 Mint 引导, then 文案随 arb 切换、无硬编码印尼语、无 `'PETGO'` 残留
- Given 本地运行 `flutter analyze`/`flutter test`, when 执行, then L0 全绿

## Design Notes

- **品牌色取舍（关键）**：原型主色为 violet `#845EC9`，但 app 已确立 mint(`#5BCBBB`)+cream 体系且 7 屏在用。本次一律用 mint，**不引入 violet**（见 Boundaries）。Splash 暗底（原型 `#141019`）用 mint 系辉光替代原型紫辉光；爪印图标形状可复用原型。
- **Splash 非路由门控**：`redirect` 仍负责登录/引导分流；Splash 仅做品牌过场后 `go('/home')`，避免与 redirect 抢路由。
- **P-09 是系统权限前置说明**（pre-prompt rationale），不替代系统弹窗；在调用 `requestSystemPermission` 前出现以提高授予率。
- arb 模板是 `app_en.arb`（`template-arb-file`），先加英文键再补 id。

## Verification

**Commands:**
- `cd petgo_app && flutter gen-l10n` -- expected: 无错误，生成 AppLocalizations
- `cd petgo_app && flutter analyze` -- expected: No issues found
- `cd petgo_app && flutter test` -- expected: 全绿（含新增 gate/sheet/splash 测试）

**Manual checks (L2 本地，云端跳过):**
- 模拟器冷启见 Splash 动画 → 自动进 /home；新用户建档后弹 P-09；切 id/en 文案正确无 'PETGO'

## Suggested Review Order

**启动屏 Splash（P-01）**

- 入口：启动改从 `/splash` 起，先看 boot 流如何改
  [`app_router.dart:56`](../../petgo_app/lib/core/router/app_router.dart#L56)
- redirect 放行 `/splash`——不被门控抢跳，由 splash 自行转场
  [`app_router.dart:61`](../../petgo_app/lib/core/router/app_router.dart#L61)
- Splash 过场：~2.2s 后 `go('/home')`（onComplete 可注入；reduce-motion 降级）
  [`splash_page.dart:58`](../../petgo_app/lib/features/onboarding/presentation/splash_page.dart#L58)
- 新增品牌暗底 token（mint 系，非原型紫）
  [`colors.dart:58`](../../petgo_app/lib/core/theme/colors.dart#L58)

**通知权限前置说明（P-09）**

- 门控：系统弹窗前先弹 rationale；同意才请求，拒绝/异常跳过、仍置 asked
  [`push_permission_gate.dart:67`](../../petgo_app/lib/features/notify/domain/push_permission_gate.dart#L67)
- 接线：用 `rootNavigatorKey` 弹 sheet；无 context/异常取安全侧 false
  [`push_permission_providers.dart:22`](../../petgo_app/lib/features/notify/data/push_permission_providers.dart#L22)
- sheet 本体：拖拽条/铃铛/3 条好处/两按钮，返回 bool
  [`push_permission_sheet.dart:14`](../../petgo_app/lib/features/notify/presentation/push_permission_sheet.dart#L14)

**i18n 迁移 + 改名**

- Mint 引导页文案改 arb；`'PETGO'` 字标 → `l10n.appTitle`
  [`mint_onboarding_page.dart:134`](../../petgo_app/lib/features/onboarding/presentation/mint_onboarding_page.dart#L134)
- 新增 31 键（en 模板含 `{name}` placeholder 元数据）
  [`app_en.arb`](../../petgo_app/lib/l10n/app_en.arb)

**测试（外围）**

- gate rationale 路径：同意→请求、拒绝→跳过仍置 asked
  [`push_permission_gate_test.dart:110`](../../petgo_app/test/notify/push_permission_gate_test.dart#L110)
- Splash 过场回调 + mint 引导改 l10n 包裹
  [`splash_test.dart`](../../petgo_app/test/onboarding/splash_test.dart)
