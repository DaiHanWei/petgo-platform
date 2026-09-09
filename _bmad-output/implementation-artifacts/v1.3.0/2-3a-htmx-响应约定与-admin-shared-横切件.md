---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 2
story: 2.3a
ad: [AD-9, AD-10]
decisions: [D-8, D-22, D-31]
depends_on: [2-2 (admin-core.js 的 beforeSwap 钩子、StagOnly、AdminPageCatalog 消费)]
---

# Story 2.3a: htmx 响应约定与 admin/shared 横切件（AD-9）

Status: ready-for-dev

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`。
> 本 story 交付的是**给后续 49 页复用的 Java 端横切件**：`HxRequest` 参数解析、fragment 形态的 422/403 异常出口、角标聚合端点、WIB 时间工具、导出工具。**不改任何业务页面**，用一个既有 Controller 做范式接入验证。

## Story

As a 后续每个页面的实现者，
I want 一套统一的 fragment 响应、错误呈现、WIB 格式化与导出工具，
so that 49 页的交互口径与导出格式一致。

## Acceptance Criteria

**AC1 · `HxRequest` 判别**
**Given** Controller 方法签名里声明 `HxRequest hx` 参数
**When** 请求头含 `HX-Request: true`
**Then** `hx.isHtmx()` 为 true，`hx.target()` / `hx.trigger()` 分别取 `HX-Target` / `HX-Trigger` 头；无头则全为空/false `[L0]`
**And** 同一 Controller 方法两条路径共用 Model：`hx.isHtmx() ? "admin/fragments/xxx :: yyy" : "admin/xxx"` `[L0]`

**AC2 · 422 fragment 出口**
**Given** admin 链请求带 `HX-Request`，服务层抛 `AppException`（`status=422/400/404/409`）
**When** `AdminBusinessExceptionAdvice` 捕获
**Then** 返回 **422 + `admin/fragments/tpl-shared :: inline-error`** fragment（含 `messages.resolve(ex)` 文案、`role="alert"`），响应头 `HX-Reswap: innerHTML` + `HX-Retarget: <hx.target() 或 #admin-inline-error>`，不重定向、不返回 JSON `[L1]`
**And** 非 htmx 请求维持现状：`GlobalExceptionHandler.handleApp` 的 ProblemDetail / 各 Controller 的 PRG flash 一律不变 `[L1]`

**AC3 · 403 禁用态 fragment**
**Given** htmx 请求触发 `AccessDeniedException`（`@PreAuthorize` 拒绝）
**Then** 返回 **403 + `tpl-shared :: forbidden`** fragment，文案「需要「{permission}」权限」（`admin.v130.err.forbidden`），`{permission}` 取所缺权限码的三语显示名（`admin.perm.<code>`，无则回退 code）`[L1]`
**And** 非 htmx 请求维持现状 forward `/admin/denied` `[L1]`
**And** ⚠️ 权限名是否可展示见 §待拍板；实现留 `AdminSharedProps.forbiddenShowsPermission` 开关（默认 true）

**AC4 · 角标聚合端点**
**Then** `GET /admin/nav/badges`（`AdminNavController`，任何已登录后台账号可访问）返回 `fragments/badges :: badges` fragment：待办中心组总数 + 各队列计数的 `hx-swap-oob` 片段，只包含**登录者有查看权**的队列；单条聚合查询（一次 SQL 多个 `COUNT(*) FILTER` 或各服务的 count 方法在一个只读事务里）`[L1]`
**And** 响应头不带 `HX-Trigger`（避免自触发循环）`[L0]`

**AC5 · `HX-Trigger` 约定**
**Then** 提供 `AdminFragmentResponses.ok(model, view).triggerBadgeRefresh()` 辅助：写响应头 `HX-Trigger: {"admin:badge-refresh":{}}`；事件名集中在 `AdminHxEvents` 常量（`BADGE_REFRESH="admin:badge-refresh"`, `DRAWER_CLOSE="admin:drawer-close"`）`[L0]`

**AC6 · `AdminTime`**
**Then** `admin/shared/time/AdminTime`：`fmt(Instant) → "yyyy-MM-dd HH:mm"`（WIB）、`fmtDate(Instant)`、`today()`（WIB `LocalDate`）；内部用 `ScheduleWindow.WIB`；注册为 Thymeleaf 表达式对象 `${@adminTime.fmt(x)}` `[L0]`
**And** 审计页等既有 UTC 显示不在本 story 改（Story 6.5），但工具已可用 `[L0]`

**AC7 · `AdminExportWriter`**
**Then** `admin/shared/export/AdminExportWriter`：`xlsx(sheetName, headers, rows) → byte[]`（POI，数字列用数字单元格，`autoSizeColumn`）、`csv(headers, rows) → String`（RFC 4180：含 `"`、`,`、换行的字段加引号并转义 `"`→`""`；前导 `= + - @` 的单元格前加 `'` 防公式注入；UTF-8 BOM 由调用方拼）、表头由调用方按当前 locale 解析后传入 `[L0]`
**And** 单测覆盖：逗号 / 引号 / 换行 / `=SUM` 注入 / 中文 `[L0]`

**AC8 · 范式接入验证**
**Then** 选 `AdminAccountAdminController` 的「停用」动作做四条 MockMvc：整页 POST 仍 PRG 302；`HX-Request` + 成功 → 200 fragment + `HX-Trigger`；`HX-Request` + 业务错（停用最后超管）→ 422 inline-error fragment；`HX-Request` + 无权限 → 403 forbidden fragment `[L1]`
**And** 该 Controller 其余方法不改（真正套模板在 Story 6.5）`[L0]`

---

## Tasks / Subtasks

- [ ] **T1 · `admin/shared/web/HxRequest`**（AC1）
  - [ ] `record HxRequest(boolean htmx, String target, String trigger, String currentUrl)` + `HxRequestArgumentResolver implements HandlerMethodArgumentResolver`，注册进 `WebMvcConfigurer`（查项目现有 `WebMvcConfigurer` 实现，有则追加；无则新建 `admin/shared/web/AdminWebMvcConfig`）
  - [ ] `HxRequest.isHtmx()`；静态 `HxRequest.of(HttpServletRequest)` 供过滤器/advice 复用

- [ ] **T2 · `AdminBusinessExceptionAdvice`**（AC2/AC3）
  - [ ] `@ControllerAdvice(basePackages="com.tailtopia.admin")` + `@Order(Ordered.HIGHEST_PRECEDENCE)`，**只在 `HX-Request` 头存在时接管**，否则 `throw ex` 交回 `GlobalExceptionHandler`（它对 `AppException` 出 ProblemDetail、对 `AccessDeniedException` 重抛给 Security 链）
  - [ ] `@ExceptionHandler(AppException.class)` → `ModelAndView("admin/fragments/tpl-shared :: inline-error", status=ex.getStatus() 为 4xx 时保留原状态否则 422)`，model：`message=messages.resolve(ex)`, `code=ex.getMessageCode()`；响应头 `HX-Reswap: innerHTML`、`HX-Retarget`（优先 `HX-Target` 头，其次 `#admin-inline-error`）
  - [ ] `@ExceptionHandler(AccessDeniedException.class)` → 403 `tpl-shared :: forbidden`；所缺权限码：从 `AuthorizationDeniedException.getAuthorizationResult()` 拿不到码时，回退解析 `HandlerMethod` 上的 `@PreAuthorize` 表达式里的 `hasAuthority('x')` 字面量（正则），取第一个非 SUPER_ADMIN 的码
  - [ ] 🔴 `GlobalExceptionHandler.handleAccessDenied` 现状是 `throw ex` 让 Security 链 forward `/admin/denied`；本 advice 排序更高且只吃 htmx 请求，非 htmx 路径不受影响——写测试钉住
  - [ ] 🔴 `BindingResult`/`MethodArgumentNotValidException`（表单校验）在 htmx 下同样走 422 inline-error，文案取第一个 field error

- [ ] **T3 · `fragments/tpl-shared.html`**（AC2/AC3）
  - [ ] `th:fragment="inline-error(message)"`：`<p class="err" role="alert" th:text="${message}">`
  - [ ] `th:fragment="forbidden(permission)"`：`<p class="err muted" th:text="#{admin.v130.err.forbidden(${permission})}">`
  - [ ] `th:fragment="toast(message)"`：复用 layout 的 `.toast` 样式，供 oob 追加
  - [ ] 三语 key：`admin.v130.err.forbidden=需要「{0}」权限` / en / id

- [ ] **T4 · `AdminNavController` + `fragments/badges.html`**（AC4）
  - [ ] `GET /admin/nav/badges`：注入 `AdminPageCatalog`（1.5）判可见队列 + `AdminBadgeService.counts(principal)` 返回 `Map<queueKey,int>`；现阶段五队列 count 分别调 `ManualReviewService` / `UnifiedTicketQueryService` / `ConsultAnomalyService` / `AdminSupportTicketQueryService` / `AdminRefundQueryService` 的既有 pending count（先读各类现有方法名，无则加一个 `countPending()`；**禁止**在 Controller 直接注入 Repository，AD 模式规则）
  - [ ] fragment：`<span id="nav-badge-total" class="badge" hx-swap-oob="true">23</span>` + 每队列 `<span id="nav-badge-manual-review" hx-swap-oob="true">12</span>`；0 时渲染空串（隐藏）
  - [ ] `@Transactional(readOnly=true)` 一次事务；不写日志

- [ ] **T5 · `AdminFragmentResponses` / `AdminHxEvents`**（AC5）
  - [ ] `AdminHxEvents.BADGE_REFRESH`、`DRAWER_CLOSE`；`AdminFragmentResponses.triggerBadgeRefresh(HttpServletResponse)` 写 `HX-Trigger` JSON（多事件合并成一个对象）

- [ ] **T6 · `AdminTime`**（AC6）
  - [ ] `@Component("adminTime")`；`fmt/fmtDate/today`；WIB 常量引用 `shared/schedule/ScheduleWindow.WIB`
  - [ ] 现状 `AdminContentScheduleController` / `SeedBatchExcelService` 各自定义了 `ZoneId WIB`——本 story 不动它们（Story 7.5 / 7.6 顺手改用 `AdminTime`）

- [ ] **T7 · `AdminExportWriter`**（AC7）
  - [ ] 参考 `AdminPaymentExportService.exportXlsx` 的 POI 写法（`XSSFWorkbook` / `Sheet` / `Row` / 数字单元格 / `autoSizeColumn`），抽成通用：`xlsx(String sheet, List<String> headers, List<List<Object>> rows)`，`Number` → 数字单元格，其余 `toString`
  - [ ] `csv(headers, rows)`：RFC 4180 + 公式注入防护；调用方负责 `'﻿' +` BOM（现状 `AdminContentManageController:194` 已这样做，保持）
  - [ ] 单测 `AdminExportWriterTest`（L0）
  - [ ] 现有三处导出（支付 xlsx / 内容 csv / 用户召回 xlsx）**本 story 不迁移**，由各页 story 迁（7.1 / 8.1 / 8.5）

- [ ] **T8 · 范式接入 + 测试**（AC8）
  - [ ] `AdminAccountAdminController.deactivate`：加 `HxRequest hx` 参数；htmx 时返回行 fragment（可先返回 `tpl-shared :: toast` 占位 + `triggerBadgeRefresh`），否则原 PRG
  - [ ] MockMvc 四条（见 AC8）；`AdminBusinessExceptionAdviceTest` 单测覆盖 htmx / 非 htmx 两路
  - [ ] 全量既有测试回归

- [ ] **T9 · 云端执行须知**
  - [ ] 云端 L0：`mvn -B clean package -DskipITs`；MockMvc 切片属 L0/L1 边界，云端可跑
  - [ ] 无 L2

---

## Dev Notes

### 🔴 现状错误呈现是「整页 PRG + flash」，htmx 下会失效

现状 Controller 范式（`AdminAccountAdminController:63-88`）：`try { service.x(); flash.addFlashAttribute("notice", msg.get(...)); } catch (AppException e) { flash.addFlashAttribute("error", msg.resolve(e)); } return "redirect:/admin/accounts";`。htmx 请求收到 302 会跟随重定向并把整页 HTML swap 进目标区——**页面套页面**。所以 AD-9 规定：htmx 请求下 4xx 走 fragment，不走 PRG。本 story 用 `@ControllerAdvice` 统一收口，各页 Controller 的 htmx 分支**不再 try/catch**，直接让 `AppException` 冒出去。

### 🔴 `GlobalExceptionHandler` 的两条既有规则不能破

1. `handleApp(AppException)` 出 RFC 9457 ProblemDetail——App 端 `/api/v1` 依赖它。本 advice `basePackages=com.tailtopia.admin` + 只吃 `HX-Request`，`/api/v1` 路径永远不进。
2. `handleAccessDenied` 是 `throw ex`（注释：「原样重抛，交还 Spring Security 的 ExceptionTranslationFilter 按各链 accessDeniedHandler 处理」）。非 htmx 的 admin 请求仍要走到 `/admin/denied` 整页。本 advice 对非 htmx 也 `throw ex`。写一条测试：`GET /admin/accounts` 无权限、无 `HX-Request` → forward `/admin/denied`。

### 🔴 htmx 1.9 不会渲染 4xx 响应体

`htmx:beforeSwap` 里 `shouldSwap=true` 由 Story 2.2 在 `admin-core.js` 加（AD-9 校验时修订，不用 `response-targets` 扩展）。本 story 的 422/403 fragment 若在没有 2.2 的环境里测，浏览器端不会渲染——**依赖顺序 2.2 → 2.3a**。服务端 MockMvc 测试不受影响。

`HX-Retarget` / `HX-Reswap` 响应头是 1.9 已支持的（1.6 起）。

### `AccessDeniedException` 里拿不到「缺哪个码」

`@PreAuthorize` 拒绝抛的是 `AuthorizationDeniedException`（`AccessDeniedException` 子类），异常本身不携带表达式。可行方案：在 advice 里通过 `HandlerMethod`（`@ExceptionHandler` 可注入）读方法上的 `@PreAuthorize` 值，正则 `hasAuthority\('([a-z_.]+)'\)` 取第一个码。类级 `@PreAuthorize` 也要查。这是「尽力而为」：拿不到就显示通用文案「权限不足」。

### 角标计数的既有方法在哪

| 队列 | 现状 service | 现有 count 方法（读后确认） |
|---|---|---|
| 统一复核 | `admin/moderation/service/ManualReviewService` / `UnifiedTicketQueryService` | 页面顶部页签计数已有实现，找 `count*` |
| 被举报用户 | `UnifiedTicketQueryService`（TicketType ACCOUNT_REPORT） | 同上 |
| 异常工单 | `admin/anomaly/service/ConsultAnomalyService` | 找 `countOpen` 类方法，无则加 |
| 客服工单 | `admin/support/service/AdminSupportTicketQueryService` | 同 |
| 退款 | `admin/refund/service/AdminRefundQueryService` | 按段计数（判定/审批/打款）求和 |

Story 4.4（暖贴跟进）、5.4（场所举报）各加一项进 `AdminBadgeService`。

### 导出规则 12 的落点

UI 稿 9-7 第 12 条与逐页规格「规则 12」：CSV 正确转义、XLSX 一字段一列、表头随界面语言。现状 `AdminUserService` 注释写着「导出改真 .xlsx（原 CSV 在运营的 Excel 里挤成一列）」——就是这个事故。`AdminExportWriter` 是统一出口；架构模式规则明写「禁止各页自己拼字符串」。

### 与 `Messages` 的关系

`shared/i18n/Messages`：`get(code, args...)`、`resolve(AppException)`（按当前 locale 解析 `messageCode`）。advice 直接复用，不新建 i18n 工具。

### Project Structure Notes

- 新包 `com.tailtopia.admin.shared`：`web/{HxRequest, HxRequestArgumentResolver, AdminWebMvcConfig, AdminBusinessExceptionAdvice, AdminFragmentResponses, AdminHxEvents}`、`time/AdminTime`、`export/AdminExportWriter`
- 新 Controller：`admin/web/AdminNavController`（+ `admin/shared/AdminBadgeService`）
- 新模板：`fragments/tpl-shared.html`、`fragments/badges.html`
- 改：`AdminAccountAdminController.deactivate`（范式接入，最小）
- 不改：`GlobalExceptionHandler`、`SecurityConfig`、任何 Repository

### 待拍板

1. **403 fragment 是否显示所缺权限名**——PRD/逐页规格/架构三处要求「注明所缺权限名」，现状 `denied.html` 刻意「不泄露所缺权限点细节」。本 story 按 PRD 实现并留开关，请产品确认默认值。
2. `AdminBadgeService` 的五个 count 若现状 service 无对应方法，需在各 service 加只读方法——属「零功能改动」范畴，确认可接受。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md#AD-9 / AD-10 / §Format Patterns / §Process Patterns / §Validation Issues Addressed（beforeSwap）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/后台重构逐页规格.md#模板 A 通用规格（处置动线、规则 12）/ 模板 B 通用规格]
- [Source: _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html 泳道 9（9-1 缺权限禁用态、9-5 反馈件、9-7 防呆 7/12）、泳道 10（10-3 提交流转）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-admin.md#Story 2.3a]
- [Source: petgo-backend/src/main/java/com/tailtopia/shared/error/GlobalExceptionHandler.java（handleApp / handleAccessDenied）]
- [Source: petgo-backend/src/main/java/com/tailtopia/shared/error/AppException.java]
- [Source: petgo-backend/src/main/java/com/tailtopia/shared/i18n/Messages.java]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/payment/service/AdminPaymentExportService.java#exportXlsx]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/moderation/web/AdminContentManageController.java#export.csv（BOM）]
- [Source: petgo-backend/src/main/java/com/tailtopia/admin/account/web/AdminAccountAdminController.java（PRG + flash 范式）]
- [Source: petgo-backend/src/main/java/com/tailtopia/shared/schedule/ScheduleWindow.java#WIB]

## Dev Agent Record

### Agent Model Used

（dev-story 填写）

### Debug Log References

### Completion Notes List

### File List

## 拍板回写（2026-09-09）

- **D-37**：403 禁用态 fragment **固定显示所缺权限名**（三语 key 取 `AdminPageCatalog` 的权限显示名），删除原「不泄露」开关；整页 `/admin/denied` 文案维持现状。
