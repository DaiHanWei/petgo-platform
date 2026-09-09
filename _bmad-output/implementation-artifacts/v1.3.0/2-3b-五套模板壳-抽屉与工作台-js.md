---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 2
story: 2.3b
ad: [AD-11]
decisions: [D-8, D-14, D-31]
depends_on: [2-3a (fragment 响应约定、HX-Trigger 事件名、tpl-shared), 2-2 (admin-core.js、htmx 统一引入)]
---

# Story 2.3b: 五套模板壳、抽屉与工作台 JS（AD-11）

Status: review

> 自包含 story，可本地或云端 L0 执行。与用户沟通用中文。执行纪律见根 `CLAUDE.md`。
> 本 story 交付**前端底座**：五个 fragment 壳 + 两个 JS + CSS 增量。**不改任何业务页**；壳的正确性靠 Thymeleaf 渲染单测 + 一个 stag-only 的 kitchen-sink 演示页验证。首个真实消费者是 Story 2.4（模板 A），B/C/D/E 在 Epic 3～7 首次使用。

## Story

As a 后续每个页面的实现者，
I want 现成的 A/B/C/D/E 五套模板 fragment、抽屉与工作台 JS、CSS 底座，
so that 页面只填内容不重写壳。

## Acceptance Criteria

**AC1 · 五套壳的槽位签名固定**
**Then** `templates/admin/fragments/` 下五个文件，`th:fragment` 参数即槽位，调用方只传 fragment 表达式：
- `tpl-a-workbench.html` :: `workbench(tabs, filters, queue, detail, actions)`
- `tpl-b-list.html` :: `list(filters, summary, table, drawer)`（含抽屉壳 `#<res>-drawer` 与遮罩）
- `tpl-c-report.html` :: `report(range, cards, detail)`
- `tpl-d-config-card.html` :: `configCard(title, form, saveLabel)`（每卡一个 `<form>`，自带保存钮）
- `tpl-e-steps.html` :: `steps(steps, body, footer)`（顶部步骤条 + 底部吸底条）
每个壳一条 Thymeleaf 渲染单测（`SpringTemplateEngine` 直渲，断言槽位内容落到对应容器、缺槽位不炸）`[L0]`

**AC2 · 模板 A 壳的行为约束**
**Then** 左栏固定 360px、右栏 `min-width:720px`；<1024px 切两屏（`.wb--narrow` 下 `data-pane="queue|detail"` 切换）；队列行 56px 两行式 + 状态色点四色类（`.dot--todo/--wait/--hot/--done`）+ 选中行左 3px 品牌竖条；页签行 + 筛选行两条；空态槽位默认渲染「队列已清空 🎉」；**无键盘 ↑/↓ 绑定**（D-14） `[L2]`

**AC3 · 模板 B 壳与抽屉**
**Then** 摘要条 `.sum` 3~5 格（抽屉内自动 2×2）；表格行 48px；抽屉 480px 自右滑入 0.2s，遮罩 `rgba(31,35,48,.35)` 0.15s；抽屉容器 `<aside id="<res>-drawer" class="drawer" hidden>`，内容由 `hx-get` 填充；关闭三途径（✕ / 点遮罩 / Esc）后焦点回到触发行；抽屉内操作成功**不自动关**（UI 稿 10-6）`[L2]`

**AC4 · `admin-drawer.js`**
**Then** 对外 `window.Admin.drawer = { open(url, {res, rowEl}), close(res), sync() }`：`open` 用 `htmx.ajax('GET', url, {target:'#<res>-drawer .drawer-body', swap:'innerHTML'})` 后显示；监听 `admin:drawer-close` 事件（2.3a `AdminHxEvents.DRAWER_CLOSE`）关闭；页面加载时若 URL 含 `?open=<id>` 自动 `open(rowEl.dataset.drawerUrl)`；打开/关闭同步 `history.replaceState` 的 `open` 参数 `[L2]`
**And** 委托监听 `tr[data-drawer-url]` 点击（排除 `a`/`button`/`input` 内点击）`[L2]`

**AC5 · `admin-workbench.js`**
**Then** 对外 `window.Admin.workbench = { selectNext(scope), select(rowEl) }`：监听 `htmx:afterSwap`，若 swap 进来的根元素带 `data-next-id`，自动 `htmx.ajax('GET', <queueRow[data-id=next].dataset.detailUrl>)` 并高亮该行；`data-next-id` 为空则显示右栏空态；窄屏下切到详情面板；**不绑定 ArrowUp/ArrowDown**（D-14）`[L2]`
**And** 处置按钮 `hx-post` 期间加 `.is-loading` + `disabled`（`hx-indicator` + `htmx:beforeRequest/afterRequest`），杜绝双击（UI 稿 9-7 第 7 条）`[L2]`

**AC6 · CSS 增量**
**Then** `admin.css` 追加：五壳布局类、`.drawer/.drawer-mask/.drawer-body`、`.badge`（导航角标）、`.stag-badge`、`.account-menu`、`.sum/.sum--grid2`、`.dot--*`、`.is-loading`、`.tabs/.tab--on`（品牌色下划线 + 紫计数）、`.step-bar`、`.sticky-footer`；令牌不改（`:root` 不动）`[L2]`
**And** UX-DR11 组件底座修正：`.sel, .inp { display:inline-block; box-sizing:border-box }`；`.sel` 箭头改 `position:absolute; right:10px` 伪元素；`.sum` 在 `.drawer` 内 `grid-template-columns:1fr 1fr` `[L2]`
**And** UX-DR12 三语占位：`.field label` 置上（`display:block`）；`.btn { min-width: 88px; white-space: nowrap }`；`th, .badge { white-space: nowrap; overflow:hidden; text-overflow:ellipsis }` + `title` 悬浮全文 `[L2]`

**AC7 · kitchen-sink 演示页（stag only）**
**Then** `GET /admin/_kitchen-sink`（`@StagOnly`，SUPER_ADMIN）渲染五壳各一个假数据实例 + 抽屉 + 工作台自动下一条，供 L2 验收与后续页面对照；生产不注册路由 `[L1/L2]`

**AC8 · 不改业务页**
**Then** 本 story diff 不含任何 `templates/admin/<业务页>.html`；既有测试全绿 `[L0]`

---

## Tasks / Subtasks

- [x] **T1 · 五个 fragment 壳**（AC1/AC2/AC3）
  - [x] `tpl-a-workbench.html`：
    ```html
    <div class="wb" th:fragment="workbench(tabs, filters, queue, detail, actions)" data-workbench>
      <div class="wb-head"><div class="tabs" th:replace="${tabs}"></div><div class="filters" th:replace="${filters}"></div></div>
      <div class="wb-body">
        <section class="wb-queue" data-pane="queue"><div th:replace="${queue}"></div></section>
        <section class="wb-detail" data-pane="detail"><div class="wb-detail-body" th:replace="${detail}"></div><div class="wb-actions" th:replace="${actions}"></div></section>
      </div>
    </div>
    ```
    调用方 `queue` 里每行：`<li class="q-row" data-id="…" data-detail-url="…" hx-get="…" hx-target=".wb-detail-body">`
  - [x] `tpl-b-list.html`：`filters` 槽 → `.sum` 槽 → `table` 槽 → `<aside id="${res}-drawer" class="drawer" hidden><header>…✕</header><div class="drawer-body"></div></aside><div class="drawer-mask" hidden>`；`res` 由调用方 `th:with` 传入或从 `drawer` 槽的 id 读
  - [x] `tpl-c-report.html`：`range`（切换器）→ `.cards` 网格 → `detail`；根元素 `data-readonly` 渲染只读标识
  - [x] `tpl-d-config-card.html`：`<form class="cfg-card" data-config-card>` + 标题 + `form` 槽 + `<button class="btn btn-primary" disabled data-save>`；`admin-core.js` 已有 `data-confirm`；保存钮激活逻辑在本 story 的 `admin-workbench.js`? **不**——放 `admin-core.js`（配置页与工作台无关）：监听 `input/change` 于 `[data-config-card]` 内 → 与初始序列化值比对 → 有差异启用保存钮并打「已修改」标
  - [x] `tpl-e-steps.html`：`<ol class="step-bar">` 由 `steps` 槽渲染 → `body` → `<footer class="sticky-footer">` `footer` 槽（上一步/下一步）
  - [x] 每壳顶部注释：槽位契约 + 首个消费者 story 号

- [x] **T2 · `admin-drawer.js`**（AC4）
  - [x] IIFE，`window.Admin = window.Admin || {}`；`Admin.drawer.open/close/sync`
  - [x] 事件：`document.addEventListener('click', tr[data-drawer-url] 委托)`；`keydown Esc`；`.drawer-mask click`；`document.body.addEventListener('admin:drawer-close', …)`
  - [x] `?open=` 深链：`DOMContentLoaded` 读 `URLSearchParams`，找 `tr[data-id=…]`，无匹配则忽略（该 id 不在当前页）
  - [x] 焦点管理：打开时记住 `rowEl`，关闭后 `rowEl.focus()`

- [x] **T3 · `admin-workbench.js`**（AC5）
  - [x] `htmx:afterSwap` 监听：`e.detail.target.closest('[data-workbench]')` 内且 `e.detail.elt` 根带 `data-next-id` → `selectNext`
  - [x] 队列行高亮：移除其它 `.q-row.on`，给目标加 `.on`；窄屏 `matchMedia('(max-width:1023px)')` 切 `data-pane`
  - [x] 提交中态：`htmx:beforeRequest` 给 `e.detail.elt` 最近的 `button` 加 `.is-loading` + `disabled`，`htmx:afterRequest` 移除
  - [x] 🔴 明确**不**注册键盘 ↑/↓（D-14，全组）；文件头注释写明

- [x] **T4 · `admin-core.js` 增量**（AC1 模板 D 保存钮）
  - [x] `[data-config-card]` 未修改禁用逻辑（序列化 `FormData` 比对）；「已修改」标 `<span class="tag-dirty">`
  - [x] 「有未保存修改时切页/关抽屉拦一次」（9-7 第 6 条）：`beforeunload` 仅在存在 dirty 卡时提示

- [x] **T5 · CSS**（AC6）
  - [x] `admin.css` 末尾新段 `/* ===== V1.3.0 模板壳（Story 2.3b） ===== */`；不改 `:root`；对照 UI 稿 `:root` 令牌（照抄自 admin.css，无差异）
  - [x] 层级（9-4）：`.drawer-mask z:40`、`.drawer z:50`、`dialog z:60`、`.toast z:70`
  - [x] 行三态（10-1）：默认白 / hover `--brand-tint` 淡 / 选中 `--brand-tint` + 左 3px `--brand`

- [x] **T6 · kitchen-sink**（AC7）
  - [x] `admin/web/AdminKitchenSinkController`（`@StagOnly`）+ `templates/admin/_kitchen-sink.html`；五壳假数据；抽屉 fragment 端点 `GET /admin/_kitchen-sink/{id}/drawer`；处置端点 `POST /admin/_kitchen-sink/{id}/done` 返回带 `data-next-id` 的 fragment 演示自动下一条
  - [x] 不进导航、不进 `AdminPageCatalog`、不进写操作清单（脚本按 `_kitchen-sink` 前缀排除——在 Story 2.1 脚本里加一行排除或在 11.1 diff 时忽略）

- [x] **T7 · 测试**（AC1/AC8）
  - [x] `AdminTemplateShellRenderTest`（L0）：五壳各一条，用 `SpringTemplateEngine` + `Context` 渲染，断言槽位 HTML 出现在预期容器内、缺槽位渲染为空不抛异常
  - [x] `AdminTemplateStructureTest` 若递归扫 `fragments/`，五壳需满足「内容在 fragment 内」规则
  - [x] kitchen-sink MockMvc：stag profile 200、默认 profile 404
  - [x] 既有全量回归

- [x] **T8 · 云端执行须知**
  - [x] 云端 L0：`mvn -B clean package -DskipITs`（含渲染单测）
  - [x] L2：本地 stag 起后开 `/admin/_kitchen-sink`，对照 UI 稿泳道 9（9-1～9-6）、泳道 10（10-1～10-8）逐项核

---

## Dev Notes

### 🔴 现状没有任何「壳」：64 页各自从 `<h1>` 往下写

`layout.html` 只提供 `page(content)` 一个 fragment；`fragments/` 目录只有 3 个业务片段（`publish-identity-select` / `seed-asset-wall` / `seed-pet-select`）。每页 `<head>` 各自引 `admin.css` + `admin.js`，用到 htmx 的 13 页各自引 `vendor/htmx.min.js`。本 story 之后，页面模板的形状变成：`page(~{::content})` → content 里一句 `th:replace="~{admin/fragments/tpl-b-list :: list(~{::filters}, ~{::summary}, ~{::table}, ~{::drawer})}"` + 四个具名 fragment 块。

### 🔴 hx-* 现状用法（45 处）与壳的兼容

现状只用了 `hx-get(25) / hx-target(27) / hx-swap(23) / hx-trigger(7) / hx-include(5) / hx-post(4)`，没有 `hx-boost`、没有 oob。壳引入的新用法：`hx-swap-oob`（2.3a 角标 / 左栏行）、`HX-Trigger` 响应头、`hx-indicator`。全部为 htmx 1.9 原生能力，**不升级**（D-31）。

### 🔴 `?open=<id>` 是页内深链，不是旧路由兜底

D-23 拍板旧详情页地址**不做跳转**。`?open=` 只服务两件事：① 抽屉内操作后刷新列表页仍保持抽屉打开；② A4「去取证」深链落 B22 时带会话号自动开抽屉。不要把它做成旧 URL 的重写规则。

### 抽屉不自动关是刻意的

UI 稿 10-6：「抽屉内操作成功不自动关抽屉（运营常连续处理同一对象）；行数据即时刷新」。所以成功响应用 `hx-swap-oob` 刷新对应 `tr`，抽屉体 innerHTML 替换为新状态，不发 `admin:drawer-close`。只有「删除 / 合并 / 下架后对象消失」类动作由服务端带 `HX-Trigger: admin:drawer-close`。

### 模板 D 的保存钮为什么放 `admin-core.js`

配置卡是 D2/D3/D1/商品表单四处用，与工作台、抽屉无关；`admin-core.js` 已是全页加载，放这里最省。`admin-workbench.js` 只在模板 A 页引，`admin-drawer.js` 只在模板 B 页引，`admin-charts.js`（Story 3.4）只在看板页引。

### 静态资源指纹

新 JS 文件走 `spring.web.resources.chain.strategy.content.paths: /admin/**`，模板用 `@{/admin/admin-drawer.js}` 自动加 md5；`SecurityConfig` `"/admin/*.js"` 放行模式覆盖。**不要**在 `SecurityConfig` 加精确文件名。

### `AdminTemplateStructureTest` 的三条规则

`everyLayoutRenderedTemplateDeclaresAFragment` / `noRealContentLivesOutsideAnyFragment` / `blockTagsAreBalanced`——扫 `templates/admin/*.html`（读 `templates()` 确认是否含 `fragments/`）。五壳都是纯 fragment 文件，天然满足；kitchen-sink 页要有 `th:fragment="content"`。

### 与 UI 稿规格图的对照点

- 9-1 按钮：主/次/危险/禁用/加载中；禁用+缺权限右侧灰字（2.3a 的 forbidden fragment 落这里）
- 9-3 色点四语义：`.dot--todo`🔵 `.dot--wait`🟡 `.dot--hot`🔴 `.dot--done`⚪
- 9-4 层级：页面 < 遮罩 < 抽屉 < 确认弹层 < toast
- 9-5 空态三件套：emoji + 一句话 + 可选 CTA；「搜索无结果」与「真空态」文案分开——壳提供两个槽位默认文案 key `admin.v130.empty.queue` / `admin.v130.empty.search`
- 9-6 页签：选中品牌色下划线 + 加粗 + 紫计数；分页器每页 20
- 10-1～10-8 全部流转在 kitchen-sink 里可点

### Project Structure Notes

- 新：`templates/admin/fragments/tpl-a-workbench.html / tpl-b-list.html / tpl-c-report.html / tpl-d-config-card.html / tpl-e-steps.html`、`static/admin/admin-drawer.js`、`static/admin/admin-workbench.js`、`templates/admin/_kitchen-sink.html`、`admin/web/AdminKitchenSinkController.java`、`test/.../admin/web/AdminTemplateShellRenderTest.java`
- 改：`static/admin/admin.css`（追加段）、`static/admin/admin-core.js`（配置卡 dirty 逻辑）
- 不改：任何业务页、`layout.html`（2.2 已改完）、`SecurityConfig`

### 待拍板 / 提醒

1. kitchen-sink 页是否保留到上线后（stag only，零风险）——建议保留作回归对照。
2. `?open=` 参数名与既有页面 query 参数是否冲突：grep 现状无 `open` 参数使用，安全。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.0/architecture-v1.3.0-admin-delta.md#AD-11 / §Structure Patterns（五壳槽位）/ §Process Patterns（data-next-id / hx-indicator）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/后台重构逐页规格.md#模板 A 通用规格 / 模板 B 通用规格 / 模板 E 通用规格 / 组件底座修正记录（2026-09-04）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html 泳道 9（9-1～9-7）、泳道 10（10-1～10-8）]
- [Source: _bmad-output/planning-artifacts/v1.3.0/决策日志-admin.md D-14 / D-23 / D-31]
- [Source: _bmad-output/planning-artifacts/v1.3.0/epics-v1.3.0-admin.md#Story 2.3b / UX-DR4～UX-DR12]
- [Source: petgo-backend/src/main/resources/static/admin/admin.css（:root 令牌、.toast、.nav-*）]
- [Source: petgo-backend/src/main/resources/static/admin/admin.js（现状能力，见 2-2 清单）]
- [Source: petgo-backend/src/test/java/com/tailtopia/admin/web/AdminTemplateStructureTest.java]

## Dev Agent Record

### Agent Model Used

claude-fable-5-1（云端 headless session，2026-09-09）

### Debug Log References

- 云端 L0：`./mvnw -B clean package`（排除需真库的 Spring 上下文测试类）→ 1745 tests, 0 failures, BUILD SUCCESS。
- 新增 L0 `AdminTemplateShellRenderTest` 7/7：`SpringTemplateEngine` + `ClassLoaderTemplateResolver` 直渲五壳（槽位落容器、`null` 槽位渲染默认空态不抛）+ `tpl-shared` 三 fragment；`AdminTemplateStructureTest` 11/11（五壳与 `_kitchen-sink.html` 都过「内容在 fragment 内」等规则）。
- `check-i18n-keys.sh` OK（2219 key）；flyway-guard 树内 174 支（无迁移）；`list-admin-write-ops.sh` 已排除 `/admin/_*`（stag-only 演示路由，Controller 也从 grep 自检剔除），并把 `/admin/nav/*` 归「登录与壳」。
- 复审三条已修：抽屉关后 200ms 内再开会被延迟隐藏吞掉（记录并取消定时器）；工作台选中行 `aria-current` 未清（一并移除）；配置卡提交后保存钮/已修改标未复位（提交时 `refresh`）。

### Completion Notes List

- **L1/L2 待本地验收**：① `StagBadgeRenderIntegrationTest.kitchenSinkRendersInStagProfile`（stag profile：`/admin/_kitchen-sink` 五壳 + 抽屉 fragment + 处置 fragment 带 `data-next-id`、`HX-Trigger`）与 `KitchenSinkAbsentByDefaultIntegrationTest`（默认 profile 404）；② L2：本地 stag 开 `/admin/_kitchen-sink` 对照 UI 稿泳道 9（9-1～9-7）/ 泳道 10（10-1～10-8）逐项核：左栏 360 / 右栏 720、<1024px 两屏切换、队列行 56px 两行式 + 四色点 + 左 3px 竖条、抽屉 480px 0.2s 滑入 + 遮罩 0.15s、关闭三途径后焦点回行、`?open=` 深链、处置后自动下一条、按钮 `.is-loading` 防双击、配置卡未修改禁用 → 修改激活 + 离开拦一次、三语占位（`th`/`.badge` 省略号 + title）。
- 五壳槽位契约按 AC1 固定；壳内**不用 `@{...}`**（模板 D 的 action 由调用方 `th:with="action=@{...}"` 传入），因此渲染单测无需 Web 上下文。缺槽位用 `?: ~{}` / 默认空态 fragment（`tpl-a-workbench :: queue-empty / detail-empty / search-empty`）。
- `admin-drawer.js`（模板 B 页引）/ `admin-workbench.js`（模板 A 页引）对外 `window.Admin.drawer / workbench`；**均不绑定 ↑/↓**（D-14）；`?open=` 只做页内深链（D-23）；抽屉内操作成功不自动关（10-6），对象消失类由服务端 `HX-Trigger: admin:drawer-close`。
- 模板 D 保存钮 dirty 逻辑放 `admin-core.js`（全页加载；配置卡四处用）；`beforeunload` 仅存在 dirty 卡时提示。
- CSS：新段 `/* V1.3.0 模板壳 */`，`:root` 令牌不动；层级 遮罩 40 < 抽屉 50 < dialog 60 < toast 70；UX-DR11（`.sel/.inp` inline-block + 箭头绝对定位、抽屉内 `.sum` 2×2）与 UX-DR12（label 置上、`.btn min-width 88px nowrap`、`th/.badge` 省略号）已加；`.badge`/`.stag-badge`/`.account-menu` 在 2.2 已加。
- kitchen-sink：`admin/web/AdminKitchenSinkController`（`@StagOnly` + `hasRole('SUPER_ADMIN')`）+ `_kitchen-sink.html`；不进导航 / `AdminPageCatalog`；建议上线后保留作回归对照（待拍板 1）。
- 不改任何业务页模板（AC8）。

### File List

- petgo-backend/src/main/resources/templates/admin/fragments/{tpl-a-workbench,tpl-b-list,tpl-c-report,tpl-d-config-card,tpl-e-steps}.html（新增）
- petgo-backend/src/main/resources/static/admin/{admin-drawer.js,admin-workbench.js}（新增）、admin-core.js（配置卡 dirty）、admin.css（模板壳段）
- petgo-backend/src/main/java/com/tailtopia/admin/web/AdminKitchenSinkController.java、templates/admin/_kitchen-sink.html（新增，stag only）
- petgo-backend/src/main/resources/i18n/messages{,_zh_CN,_en,_id}.properties（+10 key）
- scripts/ci/list-admin-write-ops.sh（排除 `/admin/_*`、nav 归组）
- 测试：admin/web/AdminTemplateShellRenderTest、admin/web/KitchenSinkAbsentByDefaultIntegrationTest（新增）；admin/shared/StagBadgeRenderIntegrationTest
