# Story 2.4: 成长档案 Tab 时间线与状态快捷编辑

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **有宠用户**,
I want **在成长档案 Tab 看到宠物信息与按时间倒序的记录时间线**,
so that **我能一览宠物的成长足迹与健康事件**。

> 本 Story 组装成长档案 Tab 主屏：信息卡 + 名片 FAB 占位 + 倒序时间线（快乐时刻 + 健康事件），并复用 FR-0F 状态快捷编辑。**快乐时刻数据来自 2.3（content_posts type=GROWTH_MOMENT）**，**健康事件数据由 2.5 承接**（本 Story 只渲染其条目样式 + 留接口，数据写入在 2.5）。本 Story **不做** FAB 动效/分享逻辑（2.7）、不做名片 H5（2.6）、不做档案编辑（2.8）。
>
> **依赖前序**：1.1、1.3、1.6（用户状态 A/B/C + FR-0F 状态选择界面）、2.2（pet_profiles 信息卡数据）、2.3（成长日历快乐时刻数据源）。健康事件数据 → 2.5。

## Acceptance Criteria

> **验证层标注**：**L0 静态** · **L1 集成/运行时**（Docker+postgres+redis）· **L2 端到端/外部凭证**。本 Story 全部落 **L0/L1**（时间线读库聚合 + 多态空状态 + 状态同步），无 L2。

### AC1 — 状态 A 已创建档案：信息卡 + FAB 占位 + 倒序时间线

**Given** 状态 A 且已创建档案的用户
**When** 进入成长档案 Tab
**Then** 从上至下显示：宠物基本信息卡（头像/名字/品种/年龄/介绍 + 状态快捷编辑入口）、分享名片 FAB 占位、垂直时间线（快乐时刻 + 健康事件，时间倒序）（FR-37）
**And** 快乐时刻条目样式=日期+照片+文字；健康事件条目样式=日期+「🏥 问诊记录」标签+AI 评级+症状摘要（健康事件数据由 Story 2.5 承接）
> 验证层：**L1**（时间线聚合端点合并快乐时刻 + 健康事件按 created_at 倒序、游标分页，需 postgres；本 Story 健康事件表/数据可能尚空，聚合需对空健康源稳健）+ **L0**（信息卡/两类条目样式/年龄由 birthday 计算 widget 测试）。

### AC2 — 状态 A 跳过创建：空状态

**Given** 状态 A 但跳过创建的用户
**When** 进入成长档案 Tab
**Then** 显示空状态「还没有宠物档案，立即创建」+ 创建按钮（UX-DR8）
> 验证层：**L0**（`GET /pet-profiles/me` 404 → 渲染空状态 + 跳 2.2 创建 widget 测试）。

### AC3 — 状态 B/C：非有宠态文案

**Given** 状态 B 或 C 用户
**When** 进入成长档案 Tab
**Then** 显示「成长档案为有宠用户专属，更换宠物状态即可开启」+ 修改状态入口
> 验证层：**L0**（按用户状态分支渲染 + 修改状态入口 widget 测试）。

### AC4 — 状态快捷编辑同步（复用 FR-0F）

**Given** 成长档案 Tab 内的状态快捷编辑入口
**When** 用户修改宠物状态
**Then** 复用 FR-0F 状态选择界面，修改后与「我的」一致同步、首页 Feed 即时按新状态刷新（FR-21）
> 验证层：**L1**（状态更新端点 + 全局状态 provider 失效/刷新，「我的」与 Feed 一致）+ **L0**（复用 FR-0F 组件、修改后 provider 通知刷新 widget 测试）。

---

## Tasks / Subtasks

> 三段组织。**前端重**（时间线 UI + 多态 + 状态同步），后端提供时间线聚合读端点 + 复用状态更新。建议：后端 → 前端 → 联调。

### 🟦 后端子任务（petgo-backend / `com.petgo.profile`）

- [ ] **B1. 成长时间线聚合读端点** (AC: 1)
  - [ ] `ProfileApiController` `GET /api/v1/pet-profiles/me/timeline?cursor=&limit=20`（JWT）：合并**快乐时刻**（content_posts type=GROWTH_MOMENT，经 content service 接口取，**禁直接 join content 表**）与**健康事件**（2.5 的健康事件源，经其 service 接口；本 Story 该源可能为空 → 返回空段稳健处理），按 `created_at` **倒序游标分页** `{items, nextCursor, hasMore}`。
  - [ ] DTO `TimelineItemResponse`：含 `kind ∈ {HAPPY_MOMENT, HEALTH_EVENT}` + 各自字段（快乐时刻:date/imageUrls/text；健康事件:date/aiLevel/symptomSummary）。统一倒序合并由 service 完成。
- [ ] **B2. 信息卡数据** (AC: 1) — 复用 2.2 `GET /pet-profiles/me`（含头像/名字/品种/生日/介绍）；年龄由 birthday 前端或后端计算（择一，记录）。
- [ ] **B3. 状态快捷编辑端点（复用 FR-0F）** (AC: 4)
  - [ ] 复用 1.6 已有的用户宠物状态更新端点（`PATCH /api/v1/me`，当前用户主体统一端点，不用 `/users/me`）；**不重复造**。确认更新后「我的」与 Feed 读同一权威状态源。

### 🟩 前端子任务（petgo_app / `lib/features/profile`）

- [ ] **F1. 成长档案 Tab 多态容器** (AC: 1, 2, 3)
  - [ ] `features/profile/presentation/growth_archive_page.dart`：按 (用户状态 + 是否有档案) 三分支——A+有档案=主屏；A+无档案=空状态(UX-DR8「立即创建」跳 2.2)；B/C=「有宠专属」+修改状态入口。
- [ ] **F2. 信息卡 + FAB 占位** (AC: 1)
  - [ ] 信息卡：头像/名字/品种/年龄(由 birthday 算)/介绍 + 右上角状态快捷编辑入口（+编辑入口占位留给 2.8）。**分享名片 FAB 此处仅占位**（动效/分享逻辑 2.7）。
- [ ] **F3. 倒序时间线 + 两类条目** (AC: 1)
  - [ ] 拉 `/timeline` 游标分页（无限滚动 20/批）；`HappyMomentTile`(日期+照片+文字)、`HealthEventTile`(日期+🏥问诊记录标签+AI 评级+症状摘要)。健康事件**数据为空时**该类条目自然不出现（2.5 接入后自动显示）。空时间线给轻量空态。
- [ ] **F4. 状态快捷编辑复用 FR-0F + 同步** (AC: 4)
  - [ ] 状态入口打开 FR-0F 状态选择组件（1.6 已建，复用）；修改后刷新全局状态 provider → 「我的」一致、首页 Feed 即时按新状态刷新（监听同一 provider）。i18n 双套。

### 🟨 联调验收子任务

- [ ] **J1. 倒序聚合（L1）** (AC: 1) — 有快乐时刻（健康事件暂空）→ 时间线倒序正确、游标翻页、信息卡数据齐。
- [ ] **J2. 三态（L0/L1）** (AC: 1, 2, 3) — A+档案/A+无档案/B/C 三分支各渲染对应内容与入口。
- [ ] **J3. 状态同步（L1）** (AC: 4) — Tab 内改状态 → 「我的」与 Feed 即时一致刷新。
- [ ] **J4. 健康事件前向兼容（L0）** (AC: 1) — 2.5 健康事件接入后条目样式正确（用 mock 健康事件验样式，无需等 2.5 实表）。

---

## Dev Notes

### 架构约定

- **模块边界**：时间线聚合**经 content/health(2.5) 的 service 接口**取数，**禁 profile 直接 join content_posts / 健康事件表**（架构「禁跨模块 repo 访问」）。
- **游标分页**：`{items, nextCursor, hasMore}` 倒序（与 Feed 一致）；camelCase / UTC ISO / null 省略。
- **状态权威源单一**：FR-0F 状态在 1.6 已有权威端点 + 全局 provider，本 Story **复用不另存**，保证「我的」/Feed/档案三处读同一源（FR-21 即时同步）。
- **错误**：未建档案 404→空态（非错误弹窗）；网络错误 banner 5s。

### 强制护栏

- **禁 MQ/缓存层/新中间件**；不缓存时间线（直读，500 DAU 无需）。
- `ddl-auto=validate`——**本 Story 不建表**（快乐时刻在 2.3 content_posts，健康事件表在 2.5）。
- 状态不重复造端点/不重复持久化。
- **健康事件含症状摘要属健康数据**——展示可，但**日志不落症状/健康摘要明文**；私密健康图（若条目含）走 2.1 签名 URL，不给公开 URL。
- 不信任客户端 ownership，timeline 取当前 JWT 用户档案。

### 范围边界（不做）

- ❌ 不做健康事件**数据写入/存档弹窗/IM 复制**（2.5，本 Story 仅渲染样式+留聚合接口）。
- ❌ 不做 FAB 动效/系统分享（2.7，本 Story 仅占位）。
- ❌ 不做名片 H5（2.6）、档案编辑表单（2.8，仅留编辑入口占位）、多宠物。
- ✅ 只做：成长档案 Tab 三态 + 信息卡 + FAB 占位 + 倒序时间线(快乐时刻实/健康事件样式+空兼容) + 状态快捷编辑复用同步。

### Project Structure Notes

- 后端 `com.petgo.profile.web/ProfileApiController`(加 `/timeline`)、`profile/service/TimelineService`(聚合，调 content + health service 接口)；DTO `TimelineItemResponse`。
- 前端 `lib/features/profile/presentation/{growth_archive_page, widgets/HappyMomentTile, HealthEventTile, pet_info_card}`；状态复用 1.6 的 FR-0F 组件 + 全局状态 provider。FAB 占位 widget 后由 2.7 接入 `shared/widgets`。

### References

- [Source: architecture.md#Format Patterns] — 游标分页 `{items,nextCursor,hasMore}`、camelCase、UTC ISO、null 省略。
- [Source: architecture.md#Architectural Boundaries] — 模块只经 service/事件通信，禁跨模块 join。
- [Source: architecture.md#Requirements to Structure Mapping] — FR-11/14~16/37/39 → profile；FR-21 状态同步 → me/profile。
- [Source: architecture.md#Enforcement Guidelines] — 日志不落 PII/健康数据；私密图签名 URL。
- [Source: epics.md#Story 2.4] — 四条原始 AC（FR-37/UX-DR8/FR-0F/FR-21）。
- [Source: epics.md#Story 2.5] — 健康事件数据承接（本 Story 仅渲染，数据 2.5 写）。

## Dev Agent Record

### Agent Model Used

云端 dev agent（headless，L0 绿）。

### Debug Log References

- 后端：`./mvnw -B -Dtest='TimelineServiceTest,ProfileApiControllerTest,ContentServiceTest' test` 绿；`./mvnw -B -DskipTests package` 绿。
- 前端：`flutter analyze` → No issues；`flutter test` → 79 passed。

### Completion Notes List

**年龄计算放在前端**（`computePetAge` 纯函数，L0 单测）；后端时间线只回 createdAt，避免重复计算。

**L0 状态（云端已验收）：**
- 后端：`GET /pet-profiles/me/timeline` 聚合端点——经 ContentService.findGrowthMoments（快乐时刻）+ HealthEventTimelineSource 端口（健康事件，2.5 实现；2.4 期 ObjectProvider 无 bean → 空段稳健）按 createdAt 倒序游标分页。单测覆盖倒序、跨源合并、空健康源、hasMore/nextCursor、无档案 404、无效游标。
- 前端：GrowthArchivePage 三态（A+档案=信息卡+FAB占位+倒序时间线 / A+无档案=空态立即创建 / B-C=有宠专属+改状态）+ HappyMomentTile/HealthEventTile + PetInfoCard（年龄由 birthday 算）+ 状态快捷编辑（复用 PetStatusSelector → PATCH /me → applyProfile + homeRefresh.bump + invalidate，FR-21 同步）。widget 测试覆盖三态 + 健康事件样式前向兼容。

**模块边界**：profile 时间线经 content/health service 接口取数，未直 join（架构 Boundaries）。
**本 Story 不建表**（快乐时刻在 2.3 content_posts，健康事件表 2.5）。

**⚠️ 待本地验收（L1/L2）：**
- **L1（Docker postgres+redis）**：J1 倒序聚合 + 游标翻页（真实快乐时刻数据，健康事件暂空）；J3 状态同步（Tab 改状态 → 「我的」与 Feed 即时一致）。
- 待肉眼确认界面：成长档案三态布局、信息卡、时间线两类条目、状态编辑 sheet（真机/模拟器视觉）。
- 健康事件真实数据待 Story 2.5 接入（本 Story 已用 mock 验条目样式）。

### File List

**后端**：`content/repository/ContentPostRepository.java`(+查询)、`content/service/{ContentService(+findGrowthMoments),GrowthMomentView}.java`、`profile/service/{TimelineService,HealthEventTimelineSource}.java`、`profile/dto/{TimelineItemResponse,TimelinePageResponse}.java`、`profile/web/ProfileApiController.java`(+/me/timeline)；测试 `TimelineServiceTest`、`ProfileApiControllerTest`(+timeline)。

**前端**：`core/network/api_paths.dart`(+timeline)、`features/profile/domain/{timeline_item,pet_age}.dart`、`features/profile/data/timeline_repository.dart`、`features/profile/presentation/growth_archive_page.dart`、`features/profile/presentation/widgets/{pet_info_card,timeline_tiles}.dart`、`core/router/app_router.dart`(/profile→GrowthArchivePage)、删除占位 `profile_page.dart`、`l10n/*.arb`；测试 `test/profile/{pet_age_test,growth_archive_test}.dart`。
