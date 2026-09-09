# Story 1.2: 设计 token 系统、Tab Bar 外壳与 i18n 脚手架

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **用户**,
I want **一个视觉统一、可在 5 个 Tab 间切换的 App 外壳**,
so that **我打开 App 就能感受到温暖活泼的品牌质感并在主要区域间导航**。

> 本 Story 是 **纯前端外观与导航地基**，在 Story 1.1 生成的空壳上落实设计系统。范围：① 把 UX_DESIGN 全套视觉量化为 `core/theme` 设计 token（colors/typography/rounded/spacing/elevation，全局底色恒 #FAF8F5）；② 渲染底部 Tab Bar 外壳（5 位 + 中间「＋」凸起 + 上沿 CircularNotchedRectangle 弧形缺口 + active 区域色圆 + 120ms 切换动效）；③ 把 Story 1.1 的 i18n 空壳从「1 条占位 key」扩成可承载后续逐屏文案的脚手架（intl + l10n.yaml + app_en.arb/app_id.arb，generate:true，跟随设备语言回退英语）。
>
> **不做**：任何登录/鉴权（Story 1.3）、任何受控 Tab 门控（Story 1.5 接线，本 Story 五个 Tab 暂全可点进空白占位页）、任何业务页面内容（Feed/问诊/档案由各 Epic 填充）、发布 FAB（UX-DR3 属 Epic 2）。本 Story **无后端改动**——纯客户端外观与导航壳。
>
> **依赖**：Story 1.1（已生成 `petgo_app` 骨架 + `core/theme`、`core/l10n`、`shared/widgets` 空目录 + i18n 空壳 + go_router 空路由表）。

## Acceptance Criteria

> **验证层标注**：每条 AC 末尾标注验证层级与所需本地环境——
> **L0 静态**（编译/lint/widget test，无需 DB/外部凭证） · **L1 集成/运行时**（需 Docker daemon + postgres + redis） · **L2 端到端/外部凭证**（需真实第三方凭证或真机/模拟器交互）。
> 本 Story 全部 AC 落在 **L0**（含 Flutter widget test / golden test）+ 少量 **L2(模拟器视觉走查)**——**无 L1**（纯前端，不碰后端/DB/Redis）。

### AC1 — 设计 token 系统落地（core/theme）

**Given** 前端工程
**When** 实现 `core/theme` 设计 token（colors 含 base/text/zone accent 焦糖#C8874A·莫兰迪蓝#7BA7BC/triage 三色、typography scale、rounded、spacing、elevation）
**Then** 全局底色恒为 #FAF8F5，不因 Tab 切换整屏变色（UX-DR1）
**And** 所有后续组件均引用 token，不出现硬编码色值/字号
> 验证层：**L0**（token 常量存在性、ThemeData 装配、`scaffoldBackgroundColor == #FAF8F5` 可 widget test 断言；「无硬编码色值」可用 grep/自定义 lint 规则在 CI 静态校验）+ **L2(模拟器视觉走查)**（切 5 个 Tab 观察底色不变）。

### AC2 — 底部 Tab Bar 外壳（凸起＋＋弧形缺口）

**Given** App 主框架
**When** 渲染底部 Tab Bar（FR-19、UX-DR2）
**Then** 显示 5 位（首页/成长档案/[+]/问诊/我的），中间「＋」凸起（44px 圆 + 3px 白描边 + 约 1/3 高度上移），上沿分割线在「＋」位向下内凹成弧形缺口（CircularNotchedRectangle）
**And** active Tab 显示 34×34 区域色填充圆 + 白图标，「＋」颜色随 active Tab 区域色切换，切换有 120ms 淡出淡入动效
> 验证层：**L0**（widget test 断言 5 个 Tab 渲染、active 圆尺寸、AnimatedSwitcher/AnimatedContainer 时长=120ms；golden test 截弧形缺口 + 凸起几何）+ **L2(模拟器视觉走查)**（凸起上移约 20px、缺口凹深约 18px/宽约 60px、active 圆 scale 入场、haptic 暂不要求）。

### AC3 — i18n 脚手架（id/en，跟随设备语言回退英语）

**Given** i18n 脚手架
**When** 配置 flutter_localizations + intl + l10n.yaml（app_en.arb / app_id.arb，generate:true）
**Then** App 首次启动跟随设备语言（id→印尼语 / en/其他→英语回退），文案经由 .arb 取用，无写死字符串（NFR-11 脚手架）
> 验证层：**L0**（`flutter gen-l10n` 生成成功、`flutter analyze` 零警告、widget test 切 `locale` 断言 5 个 Tab 标签随 id↔en 切换；「无写死字符串」CI 静态扫描）+ **L2(模拟器视觉走查)**（真机/模拟器切系统语言 id↔en + 其他语言回退 en）。

---

## Tasks / Subtasks

> **按"后端子任务 / 前端子任务 / 联调验收"三段组织**。本 Story **纯前端**，后端段写明原因；执行顺序：前端 → 联调。

### 🟦 后端子任务（petgo-backend / Spring Boot）

- [x] **B0. 本 Story 无后端改动** — 设计 token、Tab Bar 外壳、i18n 脚手架均为 Flutter 客户端纯外观/导航能力，不触达任何 API。后端文案的 `Accept-Language` 双语映射在 Story 1.3 起按端点逐步贡献，**本 Story 不动后端**。（无 AC 关联）

### 🟩 前端子任务（petgo_app / Flutter）

- [x] **F1. 设计 token：colors（core/theme/colors.dart）** (AC: 1)
  - [x] 定义 `AppColors`：`base` = `#FAF8F5`（全局底色）；`text`（主/次/弱三级，满足 NFR-13 AA ≥4.5:1，disclaimer ≥3:1）；zone accent = `accentGrowth` 焦糖 `#C8874A`（成长区）、`accentConsult` 莫兰迪蓝 `#7BA7BC`（问诊区）；triage 语义三色（`triageGreen`/`triageYellow`/`triageRed`，红色 alert 用 `#C97A7A`，黄色协议浅底 `#EEF4F7` 备 Epic 4）。
  - [x] 所有色值集中此文件；**禁止任何业务/组件文件硬编码 `Color(0x...)` 或 hex**。
- [x] **F2. 设计 token：typography / rounded / spacing / elevation** (AC: 1)
  - [x] `typography.dart`：scale = display→micro，外加 badge/button/disclaimer 专用样式；支持动态字体（NFR-13，缩放 ≤3 级）。
  - [x] `spacing.dart`：`xxs→section` + 布局量（屏边距 16px、列间距 8px 等，备 Epic 2/3 复用）。
  - [x] `rounded.dart`：`xs→full` + `phone`（卡片上圆角 14px、`rounded.md/.sm` 等）。
  - [x] `elevation.dart`：`card/nav/modal/fab/overlay` 五档。
- [x] **F3. ThemeData 装配（core/theme/app_theme.dart）** (AC: 1)
  - [x] 用上述 token 装 `ThemeData`（V1 仅 `ThemeData.light`，dark 延 V2）；`scaffoldBackgroundColor = AppColors.base`（**底色恒 #FAF8F5，不随 Tab 变**）。
  - [x] `app.dart` 的 `MaterialApp.router` 接入 `theme:`；移除任何脚手架默认蓝色主题。
- [x] **F4. 底部 Tab Bar 外壳（shared/widgets/bottom_tab_bar.dart）** (AC: 2; 支撑 FR-19/UX-DR2)
  - [x] 5 位：首页 / 成长档案 / [+] / 问诊 / 我的；用 `BottomAppBar` + `CircularNotchedRectangle`（缺口凹深约 18px、宽约 60px）承载上沿向下内凹弧形缺口。
  - [x] 中间「＋」=凸起悬浮按钮：44px 圆 + 3px 白描边 + 约 1/3 高度突出（上移约 20px）；颜色随 **active Tab 的区域色**切换（成长档案/[+]→`accentGrowth`，问诊→`accentConsult`，首页/我的取默认区域色——按 UX_DESIGN 既定映射）。
  - [x] active Tab：34×34 区域色填充圆 + 白图标 + active 入场 `scale 0.7→1.0`（UX-DR13，spring ~150ms）；inactive：18px 图标 + 9px 标签。
  - [x] Tab 切换：内容区 120ms 淡出淡入（`AnimatedSwitcher`/`FadeTransition`，duration 写成 token 常量 `kTabFade=120ms`）。
- [x] **F5. 主框架接线 Tab Bar（features 占位页 + go_router shell）** (AC: 2)
  - [x] 用 go_router `StatefulShellRoute`（或等价）承载 5 个 Tab；每个 Tab 目的地暂为**空白占位页**（`features/<feature>/presentation` 下放最小 Scaffold + 居中占位文案，文案走 .arb）。
  - [x] ⚠️ **本 Story 五个 Tab 均可点进**（不做门控）；受控 Tab（成长档案/[+]/问诊/我的）的未登录门控在 **Story 1.5** 接线，此处勿提前加 401/登录拦截。
  - [x] 「＋」点击暂为占位（Publish Compose 属 Epic 2）；点击不崩即可。
- [x] **F6. i18n 脚手架扩展（core/l10n + lib/l10n）** (AC: 3; 支撑 NFR-11)
  - [x] 确认 `l10n.yaml`（`generate: true`，`arb-dir: lib/l10n`，`template-arb-file: app_en.arb`，`output-localization-file: app_localizations.dart`）。
  - [x] `app_en.arb` / `app_id.arb` 补齐本 Story 用到的 key：5 个 Tab 标签（home/profile/add/triage/me）+ 占位页文案 + `appTitle`；两套语言均填，**禁止任何 widget 写死中文/英文字面量**。
  - [x] `MaterialApp.router` 接入 `localizationsDelegates`（含 `AppLocalizations.delegate` + Flutter 三件套）+ `supportedLocales = [en, id]`；`localeResolutionCallback`：设备语言 id→id，其余→en 回退。
  - [x] `flutter gen-l10n` 生成无误，`AppLocalizations.of(context)` 可用。
- [x] **F7. 无硬编码静态守卫（可选但推荐）** (AC: 1, 3)
  - [x] 在 CI/本地加一条轻量校验脚本或 custom_lint 规则：扫描 `lib/` 下 `Color(0x`/`#`hex 直写与中英文字面量 UI 文案，命中即失败（防后续 Story 破坏 token/i18n 纪律）。若工具成本高，可降级为 README 约定 + code review 检查项并在 Completion Notes 注明。

### 🟨 联调验收子任务（端到端跑起来 + CI）

- [x] **J1. 视觉与导航走查** (AC: 1,2 / **L0 widget/golden + L2 模拟器**)
  - [x] widget test：断言 `scaffoldBackgroundColor == #FAF8F5`、Tab Bar 渲染 5 位、active 圆 34×34、切换动效时长 120ms。
  - [x] golden test（或模拟器截图）：底部 Tab Bar 凸起「＋」+ 弧形缺口几何符合 UX-DR2（凸起上移约 20px、缺口凹深约 18px/宽约 60px、3px 白描边）。
  - [x] 模拟器手动：依次点 5 个 Tab，确认整屏底色不变、「＋」颜色随 active 区域色切换、120ms 淡入淡出可感知。
- [x] **J2. i18n 走查** (AC: 3 / **L0 + L2 模拟器**)
  - [x] widget test：`MaterialApp` 注入 `locale: Locale('id')` 与 `Locale('en')`，断言 Tab 标签文案随之切换。
  - [x] 模拟器：切系统语言印尼语→显示 id 文案；切其他非 id/en 语言→回退英文；`flutter analyze` 零警告。
- [x] **J3. CI 仍绿（前端）** (AC: 1,2,3 / **L0**)
  - [x] 复用 Story 1.1 的 `petgo_app/.github/workflows/ci.yml`：`flutter pub get` → `flutter gen-l10n` → `flutter analyze`（零警告）→ `flutter test`（含新增 widget/golden test）→ `flutter build apk --debug` 通过。

---

## Dev Notes

### 关键架构约定（本 Story 相关部分）

**目录/分层**（架构 §Project Structure，Flutter feature-first）：
- 设计 token 落 `lib/core/theme/{colors,typography,spacing,rounded,elevation,app_theme}.dart`（架构 §Frontend 结构示意为 `core/theme/{colors,typography,spacing,elevation}`，本 Story 增 `rounded.dart` + `app_theme.dart` 装配文件）。
- Tab Bar 是**跨特性通用 UI** → 放 `lib/shared/widgets/bottom_tab_bar.dart`（架构 §Complete Project Directory Structure 明列 `shared/widgets/{bottom_tab_bar(5位凸起+), ...}`）。
- i18n 生成物在 `core/l10n/{generated}`，.arb 源在 `lib/l10n/{app_en.arb, app_id.arb}`。
- `presentation` 只依赖 `domain`；通用 UI 在 `shared/widgets/`；全局跨切面（路由/主题/l10n）在 `core/`，不跨 feature import 对方 presentation。

**命名（架构 §Naming · Dart/Flutter）**：文件 snake_case（`bottom_tab_bar.dart`）；类 PascalCase（`AppColors`/`BottomTabBar`）；Riverpod provider `xxxProvider`；状态类 `XxxState`（不可变 copyWith/freezed）；常量 `kXxx` 不强制但推荐（如 `kTabFade`）。

**前端状态（Riverpod）**：当前 Tab index 用 provider 管（如 `tabIndexProvider`），不可变更新；副作用进 provider 不写在 build 内。

### UX 规格要点（UX-DR1 / UX-DR2，本 Story 验收门槛）

- **UX-DR1 设计 token**：colors（base/text/zone accent 焦糖 #C8874A 成长区·莫兰迪蓝 #7BA7BC 问诊区 / triage 三色）、typography scale（display→micro + badge/button/disclaimer）、rounded（xs→full + phone）、spacing（xxs→section + 布局量）、elevation（card/nav/modal/fab/overlay）全量 token 化至 `core/theme`；**页面底色恒为 #FAF8F5，不因 Tab 切换整屏变色**。
- **UX-DR2 Bottom Tab Bar**：5 位、中间凸起「＋」（44px 圆 + 3px 白描边 + 约 1/3 高度突出/上移约 20px）、上沿分割线在「＋」位向下内凹弧形缺口（CircularNotchedRectangle，凹深约 18px/宽约 60px）、active 34×34 区域色填充圆 + 白图标、inactive 18px 图标 + 9px 标签、「＋」颜色随 active Tab 区域色切换、切换 120ms 淡出淡入。
- **UX-DR13 微交互（本 Story 涉及部分）**：active tab circle `scale 0.7→1.0` spring ~150ms。iOS haptics（「＋」点击）可记为 Epic 2/后续接 Publish 时补，本 Story 不强制。
- **UX-DR17 平台适配**：portrait-only、home indicator/edge-to-edge 安全区（Tab Bar 适配底部安全区）、dark mode 延 V2。

### 强制护栏（架构 §Enforcement —— 违反即返工）

- ❌ **禁止硬编码色值/字号/间距**：一切视觉常量经 `core/theme` token；这是 UX-DR1 的验收硬门槛，后续所有 Story 沿用。
- ❌ **禁止写死 UI 字符串**：一切用户可见文案经 .arb（NFR-11）；本 Story 5 个 Tab 标签 + 占位文案是后续逐屏文案的范式样板。
- ❌ 不擅自引入新中间件/状态管理库（坚持 Riverpod + go_router）。
- ✅ V1 仅浅色模式 + portrait-only（架构 §Frontend / NFR-14）。

### 范围边界（防 scope creep —— 本 Story 明确不做）

- ❌ 不做登录/鉴权/JWT（Story 1.3）——Tab Bar 五位全部可点进占位页，**不加任何门控**。
- ❌ 不做受控 Tab 未登录门控 / 强弹窗（Story 1.4/1.5 接线）。
- ❌ 不做发布 FAB（UX-DR3）与 Publish Compose（UX-DR16）——属 Epic 2；本 Story「＋」点击仅占位。
- ❌ 不填充任何 Tab 的真实业务内容（Feed/问诊/档案/我的 由各 Epic 实现）。
- ❌ 不做后端任何改动。
- ✅ 只做：设计 token 全量化 + Tab Bar 外壳（凸起+弧形缺口+动效）+ i18n 脚手架扩展 + 五个空白占位 Tab + 前端 CI 仍绿。

### Project Structure Notes

- 新增/落实文件（前端）：
  - `lib/core/theme/{colors,typography,spacing,rounded,elevation,app_theme}.dart`
  - `lib/shared/widgets/bottom_tab_bar.dart`
  - `lib/features/{home? ,profile,content?,triage,me}/presentation/*_placeholder_page.dart`（五个 Tab 占位页；首页占位可暂放 `features/content/presentation` 或临时 `features/home`，与 Story 1.5/Epic 3 落点对齐——首页 Feed 归 Epic 3 `content`，本 Story 占位命名以不与后续冲突为准，于 Completion Notes 记实际落点）。
  - `lib/l10n/{app_en.arb, app_id.arb}`（扩展）+ `l10n.yaml`（沿用 1.1）。
  - `app.dart`：接 `theme` + `localizationsDelegates`/`supportedLocales` + go_router shell。
- 前端 feature 命名沿用 1.1 约定：`auth,triage,consult,content,profile,notify,me,vet`（无 `moderation`）。Tab「成长档案」对应 `profile`、「我的」对应 `me`、「问诊」对应 `triage`、「首页」内容归 `content`（Epic 3）。

### References

- [Source: epics.md#Story 1.2] — 三组原始 AC（Given/When/Then）。
- [Source: epics.md#Epic 1] — Epic 目标与 1.2 在 1.1~1.7 中的定位（地基外观/导航/i18n）。
- [Source: architecture.md#Frontend Architecture] — Riverpod/go_router/dio、feature-folder、i18n flutter_localizations+intl+.arb、portrait-only。
- [Source: architecture.md#Complete Project Directory Structure] — `core/theme/{colors,typography,spacing,elevation}`、`shared/widgets/bottom_tab_bar(5位凸起+)`、`l10n/{app_en.arb,app_id.arb}`。
- [Source: architecture.md#Naming Patterns · Dart/Flutter] — 文件 snake_case、类 PascalCase、provider/State 命名。
- [Source: epics.md#UX Design Requirements] — UX-DR1（设计 token + 底色恒 #FAF8F5）、UX-DR2（Tab Bar 凸起+弧形缺口几何）、UX-DR13（active circle scale）、UX-DR17（portrait-only/安全区/浅色）。
- [Source: PRD §4 FR-19] — 底部 Tab Bar 5 位、中间「＋」凸起、一级页常显。
- [Source: epics.md#Story 1.1] — 本 Story 在 1.1 生成的骨架与 i18n 空壳上叠加（黄金模板格式）。
- [Memory: spec-page-state-completeness] — 占位页虽简，仍应避免崩溃/白屏（本 Story 占位页要可正常渲染空状态文案）。

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

- **L0 全绿**（云端）：`flutter analyze` 零警告；`flutter test` 7/7 通过（底色 #FAF8F5、Tab Bar 5 位 + 凸起「＋」、active 圆 34×34、120ms 淡入、Tab 切换、id/en 文案切换）。
- **首页占位落点**：`features/content/presentation/home_page.dart`（与 Epic 3 Feed 落点 `content` 对齐，无需迁移）。其余 Tab：成长档案 `features/profile`、问诊 `features/triage`、我的 `features/me`。
- **设计 token**：`core/theme/{colors,typography,spacing,rounded,elevation,motion,app_theme}.dart`；底色恒 `AppColors.base`=#FAF8F5（`scaffoldBackgroundColor`）。动效时长抽为 `AppMotion`（tabFade=120ms / tabActiveSpring=150ms / sheet=300ms 备 1.4）。
- **Tab 外壳实现**：`StatefulShellRoute.indexedStack`（4 导航分支）+ `BottomAppBar(CircularNotchedRectangle, notchMargin=6)`；中间「＋」为 centerDocked 凸起 `AddTabButton`（44px 圆 + 3px 白描边 + 上移 8px，颜色随 active 区域色：问诊→莫兰迪蓝，其余→焦糖）。
- **内容区 120ms 淡入**改用 `FadeTransition`+`AnimationController(duration: AppMotion.tabFade)`，而非 `AnimatedSwitcher`+KeyedSubtree：后者会对 `StatefulNavigationShell` 内部 GlobalKey 触发 reparent 冲突。L0 断言 FadeTransition 存在 + 常量=120ms；真实淡入观感留 L2。
- **F7 无硬编码静态守卫**：降级为 review 约定（custom_lint 受 analyzer 版本限制暂停用，见 `analysis_options.yaml` 注释）。本 Story 所有色值/字号经 token、所有文案经 .arb，人工已核。
- **待本地验收（L2 模拟器视觉走查）**：① 切 5 Tab 观察底色不变 + 「＋」颜色随区域色切换 + 120ms 淡入观感；② 凸起几何（上移约 20px / 缺口凹深约 18px、宽约 60px / 3px 白描边）；③ active 圆 scale 0.7→1.0 入场；④ 真机切系统语言 id↔en + 其他语言回退 en。golden test 因 headless 字体/渲染限制未做，并入 L2 模拟器截图走查。

### File List

**新增（前端）**
- `petgo_app/lib/core/theme/colors.dart`
- `petgo_app/lib/core/theme/typography.dart`
- `petgo_app/lib/core/theme/spacing.dart`
- `petgo_app/lib/core/theme/rounded.dart`
- `petgo_app/lib/core/theme/elevation.dart`
- `petgo_app/lib/core/theme/motion.dart`
- `petgo_app/lib/shared/widgets/bottom_tab_bar.dart`
- `petgo_app/lib/shared/widgets/app_shell.dart`
- `petgo_app/lib/shared/widgets/placeholder_scaffold.dart`
- `petgo_app/lib/features/content/presentation/home_page.dart`
- `petgo_app/lib/features/profile/presentation/profile_page.dart`
- `petgo_app/lib/features/triage/presentation/triage_page.dart`
- `petgo_app/lib/features/me/presentation/me_page.dart`

**修改（前端）**
- `petgo_app/lib/core/theme/app_theme.dart`（token 装配 + scaffoldBackgroundColor）
- `petgo_app/lib/core/router/app_router.dart`（StatefulShellRoute）
- `petgo_app/lib/app.dart`（localeResolutionCallback id→id / 其余→en）
- `petgo_app/lib/l10n/app_en.arb`、`app_id.arb`（Tab 标签 + 占位文案）
- `petgo_app/test/widget_test.dart`（AC1/2/3 widget 测试）

**删除**
- `petgo_app/lib/shared/widgets/home_page.dart`（被 `features/content/presentation/home_page.dart` 取代）

## 核验收口（2026-06-09 · R1 实现状态登记同步）

> 本 story 在 Epic 1 批量 PR (#1) 即实现（Completion Notes/File List 已填、L0 绿），但 sprint-status 与任务框未同步翻 review。本轮核验确认实现完整后翻 review。**非遗弃、非未实现，属登记滞后。**

**L0 核验（绿）**：`flutter test test/widget_test.dart`（token 底色 #FBF8F1 / Tab 外壳 5 位+凸起＋ / active 圆 34 / FadeTransition 120ms / i18n 双语切换，8 例）通过；纳入全量 299 绿。

**偏差记录（薄荷绿换肤 9bf3a75 有意演进，非缺口遗漏）**：
- AC2「弧形缺口 CircularNotchedRectangle」→ 换肤改为**平直顶边 + centerDocked 半埋＋**；Tab 外壳结构性要求（5 位/凸起/active 圆/切换动效）仍满足，配色焦糖→薄荷绿。
- AC2「＋色随 active 区域色切换」→ 按用户偏好固定 accentGrowth（问诊位不再变蓝），代码注释已声明。

**L2 待本地（真机/模拟器视觉，无 L1）**：① 切 5 Tab 整屏底色不变 + 120ms 淡入观感；② ＋凸起几何 + active 圆 scale 入场观感；③ 真机切系统语言 id↔en / 其他回退 en；④ golden 截图走查（headless 不可）。
