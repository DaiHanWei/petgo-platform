# Story 6.5: App 版本更新提醒

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **用户**,
I want **App 有新版本时提醒我更新**,
so that **我能用上最新功能、重大问题能被强制修复**。

> 本 Story 实现**冷启动版本检测 + App 内更新提醒弹窗**（FR-22C）：推荐更新可「稍后」、强制更新不可跳过必须前往商店。
> **范围澄清**：此提醒为 **App 内提示**，**不使用系统推送、不依赖推送权限**——与本 Epic 其它 Story（依赖 IM 离线推送）路径完全不同。后端仅提供一个轻量「最新版本/最低支持版本」查询端点；前端负责冷启动比对、推荐/强制两态弹窗与跳商店。

## Acceptance Criteria

> **验证层标注**：**L0** 静态/单测 · **L1** 集成/运行时（Docker + postgres，版本查询端点）· **L2** 端到端/外部（真机冷启动、跳 App Store / Google Play）。
> 跳商店为 L2（真机/平台）；版本比对逻辑、推荐 vs 强制分支、「稍后」下次再提示为 L0/L1。

### AC1 — 冷启动检测，推荐更新可「稍后」、强制更新不可跳过

**Given** App 冷启动
**When** 检测到有新版本
**Then** 推荐更新弹「发现新版本，建议更新」可「稍后」（下次启动继续提示）；强制更新弹窗不可跳过、必须前往商店更新后才能继续（FR-22C）
> 验证层：**L1**（冷启动请求版本端点、比对当前版本号 → 判定无更新/推荐/强制；强制态阻断 App 继续）+ **L0**（版本比对函数 + 三态分支：无更新/推荐(可稍后)/强制(不可关) 单测）+ **L2**（真机冷启动实际弹窗）。

### AC2 — 跳转目标平台商店；App 内提示不走系统推送

**Given** 用户在更新弹窗点击「前往更新」
**When** 跳转
**Then** 跳转目标 iOS→App Store / Android→Google Play；此提醒为 App 内提示、不使用系统推送、无需推送权限
> 验证层：**L2**（真机打开 App Store / Google Play 对应 App 页为端到端）。**L0**：平台判定（iOS→App Store URL / Android→Play URL）+ 「此弹窗不调用任何推送权限 API」的代码审查/单测断言。

---

## Tasks / Subtasks

> 三段组织。本 Story 形态 = **前端重（冷启动检测 + 推荐/强制两态弹窗 + 跳商店 + 强制态阻断）+ 后端轻（一个版本查询端点）**。执行顺序：后端（端点）→ 前端 → 联调。

### 🟦 后端子任务（petgo-backend / Spring Boot）

- [ ] **B1. 版本信息查询端点** (AC: 1, 2)
  - [ ] `GET /api/v1/app-version`（公开/游客可读，无需 JWT）返回 `{latestVersion, minSupportedVersion, iosStoreUrl, androidStoreUrl}`。归属：放 notify 或一个轻量 meta slice（与团队约定一致，不新建模块）。
  - [ ] 版本号配置走外部化配置/简单表，运营可调；**不引入新中间件**。`minSupportedVersion` 用于强制更新判定。
  - [ ] 错误/超时返回标准 ProblemDetail；前端拿不到版本信息时**默认放行**（不因检测失败阻断启动）。

### 🟩 前端子任务（petgo_app / Flutter）

- [ ] **F1. 冷启动版本检测** (AC: 1)
  - [ ] App 启动（冷启动）后异步请求 `/api/v1/app-version`；取本机版本（`package_info_plus` 类）与 `latestVersion`/`minSupportedVersion` 比对（语义版本比较函数）。
  - [ ] 判定：当前 < minSupported ⇒ **强制**；minSupported ≤ 当前 < latest ⇒ **推荐**；当前 ≥ latest ⇒ 无更新。
- [ ] **F2. 推荐更新弹窗（可稍后）** (AC: 1)
  - [ ] 「发现新版本，建议更新」+ 「前往更新」/「稍后」；点「稍后」关闭，**下次冷启动继续提示**（不持久化忽略——除非按 PRD 另有要求，V1 每次冷启动再提示）。
- [ ] **F3. 强制更新弹窗（不可跳过）** (AC: 1, 2)
  - [ ] 不可关闭（拦截返回键/点遮罩不关）、无「稍后」；唯一操作「前往更新」；未更新前**阻断进入 App**主功能。
- [ ] **F4. 跳商店深链** (AC: 2)
  - [ ] 平台判定：iOS → `iosStoreUrl`（App Store）/ Android → `androidStoreUrl`（Google Play），`url_launcher` 打开。**不调用任何推送权限 API**。
- [ ] **F5. 文案双语** (AC: 1)
  - [ ] 推荐/强制弹窗文案、按钮走 i18n（id/en），无写死字符串。

### 🟨 联调验收子任务

- [ ] **J1. 版本端点验收** (AC: 1 / L1)
  - [ ] 配置 latest/minSupported → `curl /api/v1/app-version` 返回正确 JSON；游客可读；端点超时时客户端放行启动。
- [ ] **J2. 三态弹窗验收** (AC: 1 / L1+L2)
  - [ ] 模拟当前版本 < min（强制：不可关、无稍后、阻断）/ min~latest 间（推荐：可稍后、下次再弹）/ ≥ latest（无弹窗）。
- [ ] **J3. 跳商店真机** (AC: 2 / L2)
  - [ ] iOS 真机点「前往更新」→ App Store；Android → Google Play。确认全程无推送权限请求。
- [ ] **J4. 版本比对单测** (AC: 1 / L0)
  - [ ] 语义版本比较 + 三态判定 + 平台 URL 选择单测覆盖（含相等、预发/补丁号边界）。

---

## Dev Notes

### 关键架构约定（沿用全局）

- 前端 `lib/features/notify/{data,domain,presentation}`（版本检测/弹窗，notify feature 内或一个 update slice）+ `core/network` 调端点 + i18n。
- 后端版本端点公开可读（与 Feed/详情游客可读同语义），错误走 ProblemDetail。

### 推送/版本专项约定

- **此提醒为 App 内提示，不走系统推送、不需推送权限**——与 6.1~6.4 的 IM 离线推送路径**完全独立**（[epics.md FR-22C]）。
- 强制更新阻断 = 客户端门控（类似登录门控的阻断式 gate），不污染推送链路。
- 检测失败默认放行，**绝不因版本检测异常把用户挡在 App 外**（除强制更新明确判定外）。

### 强制护栏（违反即返工）

- **禁** 在版本提醒里调用任何推送权限/推送通道 API（它是纯 App 内 UI + 一个只读端点）。
- **禁** 为版本配置引入新中间件/MQ（外部化配置或简单表即可，V1 轻量姿态）。
- 强制态必须真正不可跳过（拦截系统返回手势/物理键），否则 FR-22C 失效。

### 范围边界（本 Story 明确不做）

- ❌ 不做推送基建/深链/通知中心（其它 6.x）。
- ❌ 不申请/不触碰推送权限（6.4——本 Story 明确无关）。
- ❌ 不做 App 内热更新/灰度（仅引导去商店）。
- ❌ V1 不做「忽略此版本」长期记忆（推荐更新每次冷启动再提示，除非 PRD 另有要求）。
- ✅ 只做：冷启动版本检测 + 推荐(可稍后)/强制(不可跳过)两态弹窗 + 跳对应商店 + 版本查询端点。

### Project Structure Notes

- 版本检测放在 App 启动编排（`app.dart`/路由 redirect 前置）触发，强制态在进入主框架前拦截。
- 后端版本端点不强行塞 notify 模块——可放 notify/web 或一个轻量 meta，团队约定优先；本 Story 不为它新建独立模块。

### References

- [Source: epics.md#Story 6.5] — 两条原始 AC（FR-22C 冷启动检测、推荐/强制、跳商店、不走推送）。
- [Source: epics.md#Epic 6] — App 版本更新提醒为 App 内提示。
- [Source: architecture.md#API 边界] — Feed/详情只读游客可见语义（版本端点同类公开只读）。
- [Source: architecture.md#部署] — Flutter V1 手动上架（商店分发，故引导去商店）。
- [Memory: story-acceptance-layering] — AC 标 L0/L1/L2；跳商店/真机冷启动为 L2。
- [Memory: v1-architecture-posture] — 轻量姿态，不为版本配置上新中间件。

## Dev Agent Record

### Agent Model Used

云端 dev agent（Epic 6 批量）

### Debug Log References

### Completion Notes List

**L0 绿（云端已验）**：后端 `package` 通过；前端 `flutter analyze` 零问题 + `flutter test`(185) 绿（新增 `app_version_check_test`(3) + `app_update_dialog_test`(2)）。

**关键实现**：
- **后端版本端点**：`GET /api/v1/app-version`（**公开/游客可读**，permitAll）→ `{latestVersion, minSupportedVersion, iosStoreUrl, androidStoreUrl}`，外部化配置 `petgo.app-version.*`（env 注入，运营可调，**不引入新中间件**）。
- **三态判定（核心，L0 测）**：`AppVersionCheck.decide(current, latest, minSupported)` → forced(current<min) / recommended(min≤current<latest) / none；`compareSemver` 点分段数值比较（缺位补 0，忽略预发后缀）；`storeUrl` 平台选择。
- **弹窗**：`AppUpdateDialog`——推荐(可「稍后」下次再提示、可关) / 强制(`PopScope canPop=false` 拦返回 + `barrierDismissible=false`，无「稍后」，唯一「前往更新」**不可跳过**)。
- **检测失败默认放行**：`AppVersionRepository.fetch` 失败返回 null（不阻断启动）。
- **不走推送**：纯 App 内 UI + 只读端点，**不调用任何推送权限/通道 API**（与 6.1~6.4 路径完全独立）。
- **当前版本来源**：V1 用 `--dart-define=APP_VERSION`（缺省 1.0.0）——未引入 package_info_plus 原生插件以免动摇前端 L0；可后续切真机包信息。

**待本地（L1）**：`curl /api/v1/app-version` 返回正确 JSON + 游客可读 + 超时客户端放行。
**待本地（L2，真机）**：冷启动三态弹窗实弹；「前往更新」跳 App Store/Google Play（`url_launcher`）；强制态真正阻断进入主功能（冷启动编排挂接，需 main/app 启动前置 + 真机版本）。

### File List

**后端（新增）**：`shared/config/{AppVersionProperties,AppVersionController}.java`
**后端（修改）**：`shared/security/SecurityConfig.java`（/api/v1/app-version permitAll）、`application.yml`
**前端（新增）**：`features/notify/domain/app_version_check.dart`、`features/notify/data/app_version_repository.dart`、`features/notify/presentation/app_update_dialog.dart`、`test/notify/{app_version_check_test,app_update_dialog_test}.dart`
**前端（修改）**：`core/network/api_paths.dart`、`l10n/app_en.arb`、`l10n/app_id.arb`
