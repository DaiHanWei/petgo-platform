---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 2
story: 2.2
ad: [AD-9, AD-11]
decisions: [D-2, D-8, D-31]
depends_on: [1-5 (AdminPageCatalog), 1-1 (顶栏显示名依赖 principal 字段不变)]
---

# Story 2.2: G0 全局壳——新导航、顶栏账号菜单、STAG 角标、登录页

Status: ready-for-dev

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`。
> 本 story 改的是**所有 64 个后台页面共用的外壳**，任何一处写坏全站一起坏。改动集中在 `layout.html`、新 `fragments/nav.html`、`admin.js → admin-core.js` 拆分、`login.html`、`SecurityConfig` 静态资源放行、`scripts/ci/check-i18n-keys.sh`。**不改任何业务页面内容区**。

## Story

As a 运营，
I want 登录后看到按 8 组重排的侧导航、顶栏显示我是谁、staging 环境有醒目标识，
so that 找页面不用记旧分组，也不会把测试环境当生产误操作。

## Acceptance Criteria

**AC1 · 侧导航抽 fragment，数据源 `AdminPageCatalog`**
**Given** `layout.html` 现状把 10 组导航硬编码在 `<nav>` 里（每组一个 `<details class="nav-section">`，每项 `sec:authorize` 手写权限表达式）
**When** 重构
**Then** `<nav>` 内容抽为 `fragments/nav.html`（`th:fragment="nav(active, badges)"`），组与项由 `AdminPageCatalog`（Story 1.5）驱动渲染：8 组 / 泳道顺序 = 概览 → 📥 待办中心 → ✍️ 内容 → 👥 用户 → 💰 订单与资金 → 🛍 商城 → 🩺 兽医与问诊 → ⚙️ 配置与安全 `[L1]`
**And** 每项的可见性 = 该页面在 catalog 里登记的「查看权限码集合」任一命中或 SUPER_ADMIN；**组的可见性 = 组内各项可见性的并集**（现状 layout 注释里反复强调的规则）；整组无权限则整个 `<details>` 不渲染（不是置灰）`[L1]`
**And** 当前页所在组自动 `open`、当前项 `.active`；`AdminNavSectionOpenTest` 两条既有断言继续通过 `[L1]`
**And** 商城组仍以 `active` 前缀 `shop` 判定展开（现状约定，注释保留）`[L1]`

**AC2 · 待办中心组角标**
**Then** 待办中心组 `<summary>` 右侧渲染未处理总数角标，组内各项渲染各自计数；数据由 `GET /admin/nav/badges`（Story 2.3a 的 `AdminNavController`）提供的 fragment 填充，`hx-trigger="load, admin:badge-refresh from:body"` `[L1]`
**And** 本 story 阶段五个队列计数取现有各页的「待处理」count 查询（`ManualReviewService` / `UnifiedTicketQueryService` / `ConsultAnomalyService` / `AdminSupportTicketQueryService` / `AdminRefundQueryService` 现有方法），暖贴跟进（Story 4.4）与场所举报（5.4）后续接入同一聚合 `[L1]`
**And** 组角标只汇总**登录者可见**的队列（UI 稿 0-2 规则）`[L1]`

**AC3 · 顶栏账号菜单**
**Then** 顶栏右侧新增账号菜单：常驻「显示名 · 岗位角色徽标」（角色名走 `admin.role.<CODE>` 三语 key，取 principal 的 `accountType` / `role`），点击下拉显示 显示名 / Lark 邮箱 / 岗位角色 + 「退出登录」（既有 `POST /admin/logout` 表单，无 data-confirm）`[L2]`
**And** 显示名与角色取自**登录时的 principal**，改名 / 改角色后按 D-2 在对方重新登录后更新（顶栏不查库）`[L0]`
**And** 语言切换三链接保留；当前语言加 `.on` 高亮（现状 CSS 已有 `.topbar .lang a.on`，模板未用）`[L2]`

**AC4 · STAG 角标**
**Then** `stag` profile 顶栏渲染黄色「STAG」角标（`admin.v130.topbar.stag`），生产不渲染；实现为 `admin/shared/StagOnly` 注解 + `@Profile("stag")` 的 `StagFlag` bean 注入模板 `${stag}` 布尔（与 B12 模拟回调、Story 3.3 手动跑批共用同一门控）`[L1]`

**AC5 · 登录页新视觉**
**Then** `login.html` 套 UI 稿 0-5：Lark 主入口 + 超管紧急账密折叠入口**一个不少**；`?error / ?locked / ?denied / ?expired / ?relogin / ?logout` 六条提示全保留（`?relogin` 来自 Story 1.1）`[L2]`

**AC6 · `admin.js` → `admin-core.js`**
**Then** 现有 `admin.js`（664 行）**整体改名**为 `admin-core.js`，能力一行不丢（见 Dev Notes 清单），新增：① `htmx:beforeSwap` 监听——`detail.xhr.status` 为 422 或 403 时 `detail.shouldSwap = true`（`isError=false`），其余 4xx/5xx 不动；② `htmx:configRequest` 统一注入 `X-CSRF-TOKEN`（取 `<meta name="_csrf">`，现状分散在两处 fetch 里的取法收拢）；③ `admin:badge-refresh` 事件的默认监听（触发 `htmx.trigger(document.body,'admin:badge-refresh')` 的入口由 2.3a 的 `HX-Trigger` 响应头带出） `[L1]`
**And** 64 个模板里 `<script defer th:src="@{/admin/admin.js}">` 全部改为 `admin-core.js`；静态资源指纹链（`spring.web.resources.chain.strategy.content.paths: /admin/**` + `ResourceUrlEncodingFilter`）自动覆盖新文件名，`SecurityConfig` 的 `requestMatchers("/admin/*.js")` 放行模式不需要改 `[L1]`
**And** 全部既有页面回归：`AdminPagesRenderSmokeTest`、`AdminTemplateStructureTest`、`AdminNavSectionOpenTest` 绿 `[L1]`

**AC7 · 三语 CI 守门**
**Then** 新增 `scripts/ci/check-i18n-keys.sh`：比对 `messages_zh_CN / messages_en / messages_id`（及默认 `messages.properties`）的 key 集合，任一不等打印缺失 key 并 exit 1；接入 `.github/workflows` 现有 CI job（与 `check-flyway-versions.sh` 并列）`[L0]`
**And** 本 story 新增的全部 key（`admin.v130.nav.*`、`admin.v130.topbar.*`、`admin.role.*`、登录页新 key）三包同批 `[L0]`

**AC8 · 现有测试适配**
**Then** `AdminPagesRenderSmokeTest.contentNavKeepsTheProductSpecifiedOrder`（断言旧的内容组顺序）按新 IA 改写为断言 8 组顺序与内容组 8 项顺序；`navShowsManualReviewForTakedownOnlyStaff` 继续通过（人工复核入口权限并集含 `content.takedown`）`[L1]`

---

## Tasks / Subtasks

- [ ] **T1 · `AdminPageCatalog` 消费接口对齐**（AC1，前置 1.5）
  - [ ] 读 Story 1.5 落地的 `admin/shared/AdminPageCatalog`：确认提供 `groups()`（有序）→ `pages()`（有序）→ 每页 `{ key, pathTemplate, navKey(i18n), viewPermissionCodes, activeKey, badgeKey? }`；若 1.5 尚未提供 `activeKey` / `badgeKey` 字段，在本 story 追加（向后兼容）
  - [ ] 现状 `active` 值清单（模板里 `model.addAttribute("active", "...")`）全部登记进 catalog：`dashboard, seed, seed-batches, content-schedules, content, comments, manual-review, content-pins, content-tags, users, tickets, user-tags, vets, online, failed-requests, ratings, anomalies, consult-sessions, support-tickets, refunds, config, algo-params, consult-orders, ai-orders, settlements, payments, red-overage, virtual-accounts, audit-logs, accounts, shop*`；控制器不改 `active` 值

- [ ] **T2 · `fragments/nav.html`**（AC1/AC2）
  - [ ] `th:fragment="nav(active, badges)"`；两层 `th:each`：组 → 项；组级 `th:if="${group.visibleFor(#authentication)}"`（或 controller 侧预算好 `visibleGroups` 传入，**推荐后者**：模板里不写权限表达式，避免 64 页各写一份）
  - [ ] 组 `<details class="nav-section" th:attr="open=${group.contains(active)} ? 'open' : null">`；商城组沿用 `#strings.startsWith(active,'shop')`
  - [ ] 待办中心组：`<summary>` 内 `<span class="badge" id="nav-badge-total" hx-get="/admin/nav/badges" hx-trigger="load, admin:badge-refresh from:body" hx-swap="outerHTML">`；各项 `<span class="badge" data-badge="manual-review">`，由 badges fragment 一次 oob 替换全部
  - [ ] 保留现状全部注释里的「权限并集」提醒，改写为一句：可见性由 catalog 计算，**不得**在模板手写 `sec:authorize`

- [ ] **T3 · `layout.html` 顶栏**（AC3/AC4）
  - [ ] `<div class="topbar">`：语言切换（当前语言加 `.on`，用 `${#locale.toString()}` 比对）→ `${stag}` 时 `<span class="stag-badge">STAG</span>` → 账号菜单 `<details class="account-menu"><summary>显示名 · 角色徽标</summary><div>显示名 / 邮箱 / 角色 / 退出表单</div></details>`
  - [ ] principal 取值：`${#authentication.principal.username}`（邮箱）与显示名——现状 `AdminUserDetails` **没有 displayName 字段**（只有 email / accountType / permissionCodes / 1.1 加的 securityVersion）→ 本 story 给 `AdminUserDetails` 加 `displayName` 与 `roleCode`（`AdminRole.name()`），`loadByEmail` 填充；旧构造器保持兼容（Story 1.1 已经在动这个类，注意合并顺序：1.1 先合）
  - [ ] 角色徽标文案 key `admin.role.SUPER_ADMIN / OPS_MANAGER / OPERATIONS / FULFILLMENT / SUPPORT / FINANCE / CUSTOM`，三语（Story 1.5 预置角色 `name_key` 可复用同一组 key）
  - [ ] `StagFlag`：`@Component @Profile("stag")` 提供 `boolean stag=true`，非 stag 无 bean → `@ControllerAdvice` 用 `Optional<StagFlag>` 注入 `@ModelAttribute("stag")`；同时定义 `admin/shared/StagOnly` 注解（`@Profile("stag")` 的元注解），供 B12 / 3.3 复用

- [ ] **T4 · 登录页**（AC5）
  - [ ] `login.html` 按 UI 稿 0-5 重排版式（居中卡、品牌紫主按钮、紧急入口 `<details>` 折叠）；六条提示 `<p class="err|ok" th:if="${param.x}">` 一条不少
  - [ ] 不改 `SecurityConfig` 的 formLogin / oauth 路由

- [ ] **T5 · `admin.js` → `admin-core.js`**（AC6）
  - [ ] `git mv static/admin/admin.js static/admin/admin-core.js`；全部模板 `sed` 替换引用（含 `denied.html`、`login.html` 如有）
  - [ ] 文件头新增 `// ===== htmx 全局钩子（V1.3.0 Story 2.2 · AD-9）=====`：
    ```js
    document.body.addEventListener('htmx:beforeSwap', function (e) {
      var s = e.detail.xhr && e.detail.xhr.status;
      if (s === 422 || s === 403) { e.detail.shouldSwap = true; e.detail.isError = false; }
    });
    document.body.addEventListener('htmx:configRequest', function (e) {
      var t = document.querySelector('meta[name="_csrf"]'), h = document.querySelector('meta[name="_csrf_header"]');
      if (t && h) { e.detail.headers[h.getAttribute('content')] = t.getAttribute('content'); }
    });
    ```
  - [ ] 🔴 `htmx:beforeSwap` 要挂在 `document.body` 上且在 htmx 加载后执行：`admin-core.js` 是 `defer`，htmx.min.js 的引入顺序要在它之前（现状各页 `<head>` 只引 admin.js，htmx 由用到的页面单独引——**本 story 把 `vendor/htmx.min.js` 提到 `layout.html` 统一引入**，去掉各页零散引入）
  - [ ] 现状 `meta[name="_csrf"]` 在哪些模板有？grep 后统一放进 `layout.html` `<head>` 不现实（layout 只提供 body fragment）→ 放在 `page` fragment 顶部一个隐藏 `<span data-csrf>` 或让各页 `<head>` 保留 meta；**选前者**，与 `htmx:configRequest` 取值对齐

- [ ] **T6 · CI 三语守门**（AC7）
  - [ ] `scripts/ci/check-i18n-keys.sh` 草案：
    ```bash
    #!/usr/bin/env bash
    # 三语 message key 集合守门（V1.3.0 NFR-3）：zh_CN / en / id / 默认包 四份 key 集合必须逐一相等。
    set -uo pipefail
    DIR="petgo-backend/src/main/resources/i18n"
    keys() { grep -oE '^[A-Za-z0-9_.-]+' "$DIR/$1" | sort -u; }
    base=$(keys messages_zh_CN.properties); fail=0
    for f in messages_en.properties messages_id.properties messages.properties; do
      diff <(echo "$base") <(keys "$f") >/tmp/i18n.diff || { echo "::error::$f 与 zh_CN key 集合不一致"; cat /tmp/i18n.diff; fail=1; }
    done
    exit $fail
    ```
  - [ ] 接入 `.github/workflows/*.yml` 现有 backend job（与 flyway-guard 同一步骤组）
  - [ ] 🔴 grep 要排除注释行与空行：`^[A-Za-z0-9_.-]+` 天然不匹配 `#` 开头

- [ ] **T7 · 新 key 三语**（AC7）
  - [ ] `admin.v130.nav.group.todo / content / users / finance / shop / vet / settings / overview`（组名，带 emoji 或 emoji 放模板）；`admin.v130.topbar.stag`、`admin.v130.topbar.account`、`admin.role.*` 7 个
  - [ ] 现状 `admin.nav.*` 52 个 key 复用于各项文案，不重复造 key；退役页面的 key（`admin.nav.reports / online / ratings / contentSchedules`）本 story **保留**，由对应退役 story 删除

- [ ] **T8 · 测试**（AC1/AC6/AC8）
  - [ ] `AdminNavSectionOpenTest` 不改断言，跑绿
  - [ ] `AdminPagesRenderSmokeTest.contentNavKeepsTheProductSpecifiedOrder` 改为新 IA 顺序断言；新增 `navGroupsFollowCatalogOrder`（8 组顺序）、`todoGroupHiddenWhenNoQueuePermission`（只持 `config.view` 的账号看不到待办中心组）、`stagBadgeOnlyInStagProfile`（`@ActiveProfiles("stag")` 渲染含 STAG，默认不含）
  - [ ] `AdminTemplateStructureTest` 三条继续绿（nav fragment 也走它的扫描）
  - [ ] L0：`check-i18n-keys.sh` 在本地跑一次贴结果

- [ ] **T9 · 云端执行须知**
  - [ ] 云端 L0：`mvn -B clean package -DskipITs` + 两个 shell 脚本；模板渲染测试属 MockMvc 切片，云端可跑
  - [ ] L2（视觉：8 组导航、账号菜单、STAG 角标、登录页）留本地 stag 验收，对照 UI 稿泳道 0 五帧

---

## Dev Notes

### 🔴 现状导航的写法与本 story 要消灭的东西

`layout.html`（约 300 行）：10 个 `<details class="nav-section">`，每项一行 `sec:authorize="hasRole('SUPER_ADMIN') or hasAuthority('…')"` **手写权限表达式**，组级再手写一遍**并集**。注释里至少 6 处写着「🛡 与 XxxController 的 @PreAuthorize 逐字一致」「分组门必须是组内各项入口门的并集」——说明这套写法已经出过多次「有权限却看不见入口」的事故。本 story 的根治方式是：**权限表达式只存在于 `AdminPageCatalog` 一处**，模板不再写 `sec:authorize`。

### 🔴 现状 10 组 → 新 8 组的映射（按 UI 稿泳道，D-8）

| 现状组 | 现状项 | 去向 |
|---|---|---|
| 概览 | dashboard | 📊 概览 |
| 内容 | seed / seed-batches / content / comments / manual-review / content-pins / content-tags | manual-review → 📥 待办中心；其余 → ✍️ 内容（+ places 5.2） |
| 用户运营 | users / tickets / user-tags | tickets → 📥 待办中心；users / user-tags → 👥 用户 |
| 兽医 | vets / online / failed-requests / ratings | → 🩺 兽医与问诊（online / ratings 随 9.1 退役） |
| 问诊异常 | anomalies / consult-sessions | anomalies → 📥 待办中心；consult-sessions → 🩺 |
| 客服 | support-tickets | → 📥 待办中心 |
| 退款 | refunds | → 📥 待办中心 |
| 运营 | config / algo-params / consult-orders / ai-orders / settlements / payments / red-overage / virtual-accounts | config / algo-params → ⚙️ 配置与安全；consult-orders / ai-orders / settlements / payments / red-overage → 💰 订单与资金；virtual-accounts → 👥 用户 |
| 安全 | audit-logs / accounts | → ⚙️ 配置与安全（+ roles 1.5） |
| 电商 | 12 项 | → 🛍 商城（不拆，AD-12） |

待办中心组本 story 5 项（manual-review / tickets / anomalies / support-tickets / refunds），4.4 加 warm-replies 成 6 项。

### 🔴 `AdminUserDetails` 没有显示名

顶栏要显示「显示名 · 角色」，但 principal 现状只有 `adminAccountId / operatorUserId / email / passwordHash / accountType / permissionCodes`（Story 1.1 再加 `securityVersion`）。两个选择：① 顶栏每请求查库；② principal 加 `displayName` + `roleCode`。D-2 已拍板「改名后重登生效」，所以选 ②，零查库。**与 Story 1.1 同时改同一个类**，合并顺序 1.1 → 2.2，2.2 在 1.1 的构造器基础上再加两个字段。

### 🔴 htmx 引入位置与 `beforeSwap` 时序

现状 htmx 由用到它的页面各自 `<script src="/admin/vendor/htmx.min.js">`（13 个模板、45 处 `hx-*`），`admin.js` 只在 `seed-batches` 里 `typeof htmx === 'undefined'` 兜底调用 `htmx.ajax`。本 story 把 htmx 统一提到 `layout.html` 的 `page` fragment 末尾（body 内，`defer` 之前），保证 `admin-core.js` 里 `document.body.addEventListener('htmx:beforeSwap', …)` 在 htmx 初始化后注册。**不升级 htmx**（D-31，留 1.9.12）；1.9 里 4xx 默认 `shouldSwap=false`，所以 `beforeSwap` 放行是 422/403 fragment 能渲染的唯一前提（AD-9 校验时已修订，不用 `response-targets` 扩展）。

### `admin.js` 现状能力清单（664 行，拆分时一行不丢）

| 行段 | 能力 | 备注 |
|---|---|---|
| 4-14 | `form[data-confirm]` 提交二次确认 | 全站 |
| 15-40 | `[data-open-dialog]` / `[data-close-dialog]` 原生 `<dialog>` 开关 | 注释第 39 行提到 hx-get 与 data-confirm 的兼容 |
| 42-55 | `form[data-autosubmit]` change 即提交 | 模板 B 筛选栏沿用 |
| 56-71 | `img[data-lightbox]` 大图 | 全站 |
| 72-82 | `dialog[data-autoopen]` + `.toast` 自动消失 | toast 机制 |
| 83-164 | 工单批量勾选（`data-batch-scope` / MAX 50 / 同类型锁定） | A2 用 |
| 165-193 | 账号创建页角色下拉联动权限勾选 | 1.6 会改 |
| 194-215 | 「以真实账号发布」二次确认 | E1 |
| 216-243 | `adminUploadError` 上传错误落点 | 全站上传 |
| 244-513 | 单条发布图片上传控件（拖拽排序 / 粘贴 / 封面） | E1 |
| 514-640 | 批次素材上传拦截与计数、`htmx.ajax` 刷新素材墙 | E2 |
| 641-664 | 「关联物种」跟随发布账号 | E1/E2 |

本 story **只改名 + 头部加两个全局钩子**，不拆分；拆出 `admin-workbench.js` / `admin-drawer.js` / `admin-charts.js` 是 Story 2.3b / 3.4 的事。

### 静态资源指纹：新文件名自动覆盖

`application.yml` `spring.web.resources.chain.strategy.content.paths: /admin/**` + `StaticResourceVersionConfig` 注册的 `ResourceUrlEncodingFilter`（Boot 4 不再自动注册，那个类的注释写得很清楚）。`@{/admin/admin-core.js}` 会自动改写为 `/admin/admin-core-<md5>.js`；`SecurityConfig` 放行用的是 `"/admin/*.js"` 模式（bug 20260901-471 修的），新文件名匹配。**不要把放行改成精确文件名。**

### 现状测试要动的点

- `AdminPagesRenderSmokeTest:147 contentNavKeepsTheProductSpecifiedOrder` 断言的是旧顺序（seed-post → content → comments → manual-review → content-pins → content-tags → users → tickets → user-tags），新 IA 下 manual-review / tickets 挪走，必改。
- `AdminNavSectionOpenTest` 只检查渲染结果（高亮链接所在 `<details>` 必 open、组头高亮与展开一致），不检查模板写法，应零改动通过。
- `AdminTemplateStructureTest` 扫 `templates/admin/*.html` 顶层结构，新 `fragments/nav.html` 在 `fragments/` 子目录，确认它的 `templates()` 是否递归；若递归，nav.html 必须满足「内容都在 fragment 内」规则。

### 403 落点现状与本 story 的边界

admin 链 403 = `AccessDeniedHandlerImpl` forward `/admin/denied`（整页，注释明写「不泄露所缺权限点细节」）。本 story **不改**这个整页行为；htmx 请求下 403 返「禁用态 fragment」由 Story 2.3a 的 `AdminBusinessExceptionAdvice` 处理。⚠️ 见 §待拍板。

### Project Structure Notes

- 改：`templates/admin/layout.html`、`templates/admin/login.html`、64 个模板的 script 引用、`AdminUserDetails` / `AdminUserDetailsService`、`AdminPagesRenderSmokeTest`
- 新：`templates/admin/fragments/nav.html`、`static/admin/admin-core.js`（改名）、`admin/shared/StagOnly.java`、`admin/shared/StagFlag.java`、`admin/shared/web/GlobalModelAdvice.java`（`stag` 与 catalog 注入）、`scripts/ci/check-i18n-keys.sh`
- 不改：`SecurityConfig`、任何 Controller 的 `active` 值、任何业务页内容区

### 待拍板（写进 Completion Notes 提醒产品）

1. **403 禁用态「注明所缺权限名」 vs 现状 `denied.html`「不泄露所缺权限点细节」**——PRD 逐页规格模板 A/B 通用件与架构 AD-9 都要求注明所缺权限名，现状整页 403 刻意不泄露。两者面向的都是已登录运营，建议统一为「注明」；但需产品确认。本 story 不改 `denied.html`。
2. UI 稿 9-2 仍写「操作审计列表为 UTC」，与 D-22 冲突，UI 稿文字待设计师同步（不阻塞）。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-delta.md#AD-9 / AD-11 / §Implementation Patterns 模板与前端]
- [Source: _bmad-output/planning-artifacts/v1.3.0/后台重构逐页规格.md#G0 · 全局框架]
- [Source: _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html 泳道 0（0-1～0-5）、泳道 9（9-6 导航件、9-7 防呆 10）、泳道 10（10-7）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md#5. AB-19A ① 新导航结构]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志.md D-2 / D-8 / D-31]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0.md#Story 2.2]
- [Source: petgo-backend/src/main/resources/templates/admin/layout.html]
- [Source: petgo-backend/src/main/resources/static/admin/admin.js]
- [Source: petgo-backend/src/main/java/com/tailtopia/shared/web/StaticResourceVersionConfig.java]
- [Source: petgo-backend/src/main/java/com/tailtopia/shared/security/SecurityConfig.java#adminFilterChain]
- [Source: petgo-backend/src/test/java/com/tailtopia/admin/web/AdminPagesRenderSmokeTest.java / AdminNavSectionOpenTest.java / AdminTemplateStructureTest.java]

## Dev Agent Record

### Agent Model Used

（dev-story 填写）

### Debug Log References

### Completion Notes List

### File List
