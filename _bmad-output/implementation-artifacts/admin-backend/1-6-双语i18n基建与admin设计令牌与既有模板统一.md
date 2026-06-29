# Story 1.6: 双语 i18n 基建 + admin 设计令牌与既有模板统一

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **范围域**：管理后台 V1.0.0 · Epic 1（后台地基）· Story 1.6（**Epic 1 收尾故事**）。统一 i18n 基建 + 视觉令牌，并把 1.1~1.5 期间「先中文」的既有模板外化与换肤。
> **建立在 1.1~1.5 之上**：登录/布局/账号/审计/会话/兽医/举报/种子等模板已存在（多为硬编码中文 + 内联 `<style>`）。
> **产物归属**：仅后端 `petgo-backend`（**无 Flutter 侧**）。**无 Flyway 迁移**（纯前端资源 + 视图层 + 配置）。
> **视觉源真相**：`_bmad-output/planning-artifacts/admin-backend/UX_DESIGN.md`（淡紫品牌 `#845EC9` + 中性高密度底；色值对齐 App `petgo_app/lib/core/theme/colors.dart`）。

## Story

As a **运营成员**,
I want **后台 UI 可在中英间切换并记住偏好，且全后台视觉统一带品牌识别**,
so that **中英团队都能顺畅使用（NFR2 双语），且页面风格一致不漂移（运营 1 天上手 AM-5）**。

## Acceptance Criteria

> 验证层：**L0** 编译/单测/MockMvc 切片 + 视觉走查（无需 DB）· **L1** 集成（Docker postgres+redis，全量回归 + 语言切换真跑）· **L2**（本故事无）。

1. **AC1（i18n 基建，L0/L1）**：引入 Spring `MessageSource`（`messages_zh_CN.properties` + `messages_en.properties`，UTF-8）+ `CookieLocaleResolver`（默认 `zh-CN`，Cookie 记忆）+ `LocaleChangeInterceptor`（或等价顶栏切换端点）；切换语言后页面文案随之切换并**记住偏好**（再次访问保持）；仅作用 admin 视图，不影响 `/api/v1`。

2. **AC2（顶栏语言切换，L0/L1）**：`admin/layout.html` 顶栏提供 `中文 / EN` 切换入口（如 `?lang=zh-CN` / `?lang=en` 或 POST 端点）；切换后回到当前页（或刷新当前视图）并应用新语言。

3. **AC3（两键集一一对应，L0）**：`messages_zh_CN` 与 `messages_en` 的 key 集合**完全一致**（无单边缺失）；提供一个 L0 测试**断言两文件 key 集相等**；缺失键有回退（配置 `useCodeAsDefaultMessage` 或保证不缺）。

4. **AC4（既有模板外化，L0 视觉走查）**：既有 admin 模板硬编码中文外化为 `#{admin.*}` 键——覆盖 **1.1~1.5 期间产出的全部 admin 模板**：`layout` / `login`（含 Lark 主入口 + 紧急登录折叠 + `?error/?locked/?logout/?denied/?expired` 文案）/ `dashboard` / `seed-post` / `reports` / `vets` / `vet-ratings`，以及 1.3/1.4/1.5 新增的 `audit-logs` / `session-logs` / `admin-accounts`；渲染后**无硬编码中文残留**（键命名遵循 `admin.<模块>.<语义>`）。

5. **AC5（单一共享 admin.css + 设计令牌，L0 视觉走查）**：新建单一 `static/admin/admin.css`，`:root` 落 UX_DESIGN 令牌（品牌 `--brand:#845EC9`/`--brand-strong:#6C48AE`/`--brand-tint:#F8F2FF`/`--brand-line:#DCD2F7`；中性 `--bg:#F5F6F8`/`--surface:#FFFFFF`/`--sidebar:#2E2A45`/`--ink`/`--ink-2`/`--muted`/`--line`；状态 `--ok`/`--warn`/`--danger`/`--info` 及其底色）+ 组件类（`.btn`/`.btn-primary`/`.btn-danger`/`.badge`(状态徽章)/`.filter-bar`/表格/侧栏/顶栏）；既有模板**移除内联 `<style>`** 改引用 admin.css，全后台**无内联样式残留**。

6. **AC6（统一视觉到品牌令牌，L0 视觉走查 + L1）**：全后台呈现为「淡紫品牌强调色 + 中性高密度底」——侧栏深墨紫(`--sidebar`)、激活项紫底/左紫条、表格行 hover 浅紫(`--brand-tint`)、主按钮紫底白字、危险按钮红、状态徽章按状态色；圆角克制(6–8px)、表格密集(行高~36–40px)、字号 13–14px。

7. **AC7（后台调性收口，L0 视觉走查）**：**不引入** App 的吉祥物 Momo / 立体「下唇」按钮 / 活泼动效 / 大圆角米白底；桌面 only（无移动端响应式）；仅亮色（不做主题切换，仅语言切换）。

8. **AC8（回归绿，L0/L1）**：`mvn -B compile` + L0 全绿；本地 L1 全量回归绿；既有页面功能（登录/会话/审计/账号/兽医/举报/种子）行为不回归——外化与换肤是表现层改动，不改后端逻辑。

## Tasks / Subtasks

- [ ] **T1 i18n 基建（AC1/AC2/AC3）**
  - [ ] `shared/i18n/AdminLocaleConfig`（或 WebMvc 配置）：`MessageSource`（basename 指向 `i18n/messages`，UTF-8）+ `CookieLocaleResolver`（默认 `zh_CN`）+ `LocaleChangeInterceptor`（param `lang`）注册进 admin MVC；确认仅作用 admin 视图渲染，不污染 api 链
  - [ ] `resources/i18n/messages_zh_CN.properties` + `messages_en.properties`：建全量 `admin.*` 键集（两边一一对应）
  - [ ] 顶栏语言切换入口（`?lang=` 或 POST），切换后回当前页
  - [ ] L0 测试：断言两 properties 的 key 集合完全相等
- [ ] **T2 admin.css 设计令牌（AC5/AC6/AC7）**
  - [ ] 新建 `static/admin/admin.css`：`:root` 令牌（UX_DESIGN 全量色值）+ 组件类（按钮/徽章/筛选条/表格/侧栏/顶栏/空态/内联错误条/确认弹层基础样式）
  - [ ] 排版与密度：基础 13–14px、表格行高紧凑、圆角 6–8px、间距 4/8/12/16/24
  - [ ] （可选）抽 `templates/admin/fragments/{pagination, filter-bar, confirm-dialog}.html` 复用片段（若 1.3/1.5 已内置最小片段，本故事统一收敛）
- [ ] **T3 既有模板外化 + 换肤（AC4/AC5/AC6）**
  - [ ] `layout.html`：外化导航/顶栏文案为 `#{admin.*}`、加语言切换、移除内联 `<style>` 引 `admin.css`、侧栏/激活态换令牌
  - [ ] `login.html`：外化全部文案（Lark 入口 + 紧急折叠 + 各状态参数文案）、换肤
  - [ ] `dashboard.html` / `seed-post.html` / `reports.html` / `vets.html` / `vet-ratings.html`：外化 + 引 admin.css + 状态用 `.badge`、按钮用 `.btn*`、筛选用 `.filter-bar`、移除内联样式
  - [ ] 1.3/1.4/1.5 新增页 `audit-logs.html` / `session-logs.html` / `admin-accounts.html`：外化 + 引 admin.css（这些页本批先以中文交付，本故事统一外化换肤）
- [ ] **T4 测试 + 回归（AC3/AC8）**
  - [ ] L0：key 集对齐测试；既有 `AdminWebControllerTest` 等视图名/渲染单测保持绿（外化不改视图名/model）；可加 MockMvc 验登录页含语言切换入口、`lang` 切换返回不同语言文案
  - [ ] L1：全量回归绿；本地起服务人工走查中英切换 + 视觉统一（截图留档）
  - [ ] 云端 headless 只跑 L0（含 key 对齐 + MockMvc）；视觉走查 + 切换体验属本地

## Dev Notes

### 架构约束（必须遵守）
- 后端 Spring Boot 4 / Java 21；i18n = Spring `MessageSource`（`messages_zh_CN/_en`）+ Cookie `LocaleResolver` + 顶栏切换；**语言集独立于 App**（App 为 id/en，后台为 zh-CN/en，不复用 `.arb`）。[Source: architecture.md#Frontend Architecture / Cross-Cutting Concerns #7]
- i18n 键 `admin.<模块>.<语义>` 全小写点分；两套 properties **一一对应**；文案外化 `#{admin.*}` **不硬编码**中/英文。[Source: architecture.md#Naming Patterns（i18n 键）/ Enforcement]
- 样式：单一共享 `static/admin/admin.css`（`:root` 令牌 + 组件类）；HTMX/css **本地静态托管**（不走 CDN）。[Source: architecture.md#Frontend Architecture / Structure Patterns]
- 后台 SSR + HTMX，桌面 only、无响应式、仅亮色。[Source: architecture.md#Frontend Architecture]
- 视觉令牌全量见 UX_DESIGN.md（颜色/排版/密度/组件/不做清单）。[Source: admin-backend/UX_DESIGN.md §1~§5]

### 既有代码基线（READ）
- `templates/admin/layout.html`：现含内联 `<style>`（深蓝 `#1f2733` 侧栏、`#2d6cdf` 激活蓝）+ 硬编码中文（「概览/种子内容发布/举报队列/兽医账号/评分查看/退出登录」「🐾 TailTopia 运营」）——**本故事外化 + 换令牌**（侧栏改 `--sidebar:#2E2A45`、激活改 `--brand`）；保留 `th:fragment="page(content)"` 契约不动。[Source: layout.html]
- `templates/admin/{login,dashboard,seed-post,reports,vets,vet-ratings}.html`：均待 READ，外化硬编码中文 + 去内联样式。`login.html` 已有 `?error/?locked/?logout/?denied/?expired` 中文文案（1.1/1.2 加），全部外化。
- 新增页 `audit-logs.html`(1.3) / `session-logs.html`(1.4) / `admin-accounts.html`(1.5)：本批先中文交付，本故事外化换肤。
- `AdminWebControllerTest`：断言视图名/model（如 `admin/login`、`active`、`seedPostForm`）——**外化不改视图名与 model key**，保持其绿；构造 `AdminUserDetails` 若 1.5 已改签名以 1.5 为准。[Source: AdminWebControllerTest.java]
- App 色值源：`petgo_app/lib/core/theme/colors.dart`（常量名 `mint*` 是遗留别名，实际值 violet）——令牌以 UX_DESIGN 抄录值为准。[Source: UX_DESIGN.md §0/§1]

### 关键边界 / 防坑
- **MessageSource 仅作用 admin 视图**：admin 是 SSR；api 链是 JSON/ProblemDetail（其文案不走 admin MessageSource）。配置 LocaleResolver/Interceptor 时确保不干扰 api 链（admin 用 SSR MVC，注册到 MVC 即可；不要把 LocaleChangeInterceptor 误加到影响 `/api/v1` 行为）。
- **properties 编码**：Spring Boot 4 默认 UTF-8 读取 messages（确认 `spring.messages.encoding=UTF-8` 或默认）；中文键值勿用 native2ascii 转义反而易错，直接 UTF-8。
- **key 一一对应是 AC 硬指标**：用 L0 测试机械保证（读两文件 `Properties` 比 keySet），避免单边漏键导致另一语言回退到 code。
- **外化不改后端**：只动模板文案与 css 引用，**不改**控制器视图名、model attribute、表单字段 name（改了会断 1.1~1.5 的逻辑与测试）。
- **去内联样式要彻底**：AC5/AC6 要求「无内联样式残留」——所有 `<style>` 块与零散 `style="..."` 收敛到 admin.css 类；走查时全文 grep `style=` 与 `<style`。
- **不做 App 俏皮元素**：UX_DESIGN §5 明确收口——无 Momo、无立体按钮、无活泼动效、无大圆角米白底；勿从 App 复制组件。
- **HTMX 片段也要外化/换肤**：筛选/分页/行片段同样走 `#{admin.*}` + admin.css 类，勿只改整页。

### 范围边界（不做）
- **本故事不做**：任何后端业务逻辑/新表/新端点（纯表现层 + i18n 配置）；App 侧 i18n（与本故事无关）；主题切换（仅语言切换，UX_DESIGN §5）；Epic 2~6 新页面（各自故事建时即用本故事令牌/键规范）。
- 本故事**只**交付：i18n 基建（MessageSource + LocaleResolver + 切换）+ 全量 `admin.*` 双键集 + admin.css 设计令牌 + 既有/本批模板外化与换肤。

### Flyway
- **本故事无新迁移**（纯前端资源 + 视图层 + 配置）。

### 测试标准
- L0：两 properties key 集相等断言；既有控制器视图/渲染单测保持绿；MockMvc 验语言切换入口与 `lang` 切换文案差异。
- L1（本地，需 Docker PG/Redis）：全量回归绿；人工走查中英切换 + 视觉统一（侧栏紫调、徽章状态色、表格密度），截图留档。**云端 headless 只跑 L0；视觉走查 + 切换体验留本地验收**（CLAUDE.md 云端无 GUI）。

### Project Structure Notes
- 新增：`shared/i18n/AdminLocaleConfig`、`resources/i18n/messages_zh_CN.properties` + `messages_en.properties`、`static/admin/admin.css`（+ 可选 `templates/admin/fragments/*`）。
- 修改：`templates/admin/*.html` 全量（外化 + 引 css）。
- 与架构目录树一致。[Source: architecture.md#Project Structure（i18n/messages_* + static/admin/admin.css + shared/i18n AdminLocaleConfig）]

### References
- [Source: admin-backend/epics.md#Story 1.6 双语 i18n 基建 + admin 设计令牌与既有模板统一]
- [Source: admin-backend/UX_DESIGN.md（颜色/排版/组件/双语/不做/落地方式 §1~§6）]
- [Source: admin-backend/architecture.md#Frontend Architecture / Naming Patterns（i18n 键）/ Structure Patterns（admin.css、本地托管）/ Enforcement（双键集一一对应、外化不硬编码）]
- [Source: admin-backend/PRD.md#§5 明确不做（中英双语修正 / 桌面 only）/ NFR2]
- [Source: 1-1 / 1-2 / 1-3 / 1-4 / 1-5 故事（既有/本批模板与登录页状态文案来源）]
- [Source: templates/admin/layout.html（现内联样式 + 硬编码中文基线）]
- [Memory: [[petgo-mint-brand-switch]]（薄荷绿别名实为 violet，色值对齐 colors.dart）/ [[petgo-i18n-model-and-debt]]（i18n 键与文案债）]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
