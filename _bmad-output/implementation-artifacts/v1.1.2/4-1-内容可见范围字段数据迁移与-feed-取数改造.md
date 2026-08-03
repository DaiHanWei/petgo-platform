---
baseline_commit: b324308a
---

# Story 4.1: 内容可见范围字段、数据迁移与 Feed 取数改造

Status: ready-for-dev

> **所属**：V1.1.2 Epic 4 第一个 Story（**纯后端 · 本版本唯一 schema 变更**）。交付：`content_posts` 新增通用 `visibility` 列 + 存量回填 + 所有消费公开内容的查询统一过滤。
> ⚠️ **安全攸关（NFR-4）**：`visibility` 过滤**只作用于「他人可见」视图**。作者自视视图（成长档案时间线 / 日历 / 当天详情 / 我的发布）**必须完整展示含私密内容**。写反了，用户主动设为私密的日记会**从自己的成长档案里消失**——恰好摧毁 FR-83 私密能力的全部意义。
> ⚠️ **默认值方向易写反**：PRD 初稿（2026-07-23）曾定「默认私密」，2026-07-30 已反转为**默认公开**。归档 PRD 的迁移表首行原本残留旧值，已订正。**以本 Story 与 AD-4 为准，不要回头翻 PRD 早期版本。**

## Story

As a **平台**,
I want **每条内容都带一个明确的「谁能看」标记**,
so that **所有要取公开内容的地方只需问一句话，而不是各写各的判断（FR-83 / AD-4）**。

## Acceptance Criteria

> **验证层**：**L0**（DTO/契约 test）· **L1**（Docker pg：迁移、回填、过滤、作者自视）。本 Story 无 L2（纯后端）。

### AC1 — 新增通用 `visibility` 列（L1 · AD-4 Rule 1）

**Given** `content_posts` 现无任何公开/私密字段
**When** 迁移完成
**Then** 新增 `visibility varchar`，取值 `PUBLIC` / `PRIVATE`（UPPER_SNAKE + CHECK），**`DEFAULT 'PUBLIC'`**，`NOT NULL`
**And** 三类内容（Diary / Moment / Tips）**统一携带**，未做 Diary 专属布尔
**And** Flyway 占位 `V<n>__`，实际号按执行顺序顺延（现有最高 **V97** → 本 Story 用 **V98** 起）
**And** `ddl-auto=validate` 与实体一致，上下文启动通过

### AC2 — 存量统一回填 PUBLIC（L1 · NFR-6）

**Given** 存量内容须维持公开（PRD：不批量转私密，避免老用户内容突然从 Feed 消失）
**When** 迁移执行
**Then** **全部存量已发布内容统一回填 `PUBLIC`**
**And** 迁移**在功能开关生效前完成**，不接受「先上线再补回填」
**And** 无任何老用户内容因本次迁移从 Feed 消失
**And** 私密只在用户**主动关掉开关**时产生，**不得**实现为需额外回填的状态

### AC3 — 消费公开内容的查询统一过滤（L1 · AD-4 Rule 2）

**Given** 一切消费公开内容的查询
**When** 取数
**Then** 统一按 **`visibility = PUBLIC`** 过滤，**不按内容类型分支**
**And** 覆盖：Feed、话题聚合、他人主页/迷你主页预览卡、任何平台公开位

### AC4 — 作者自视视图不过滤（L1 · NFR-4 · AD-4 Rule 3，安全攸关）

**Given** 过滤范围严格限定「他人可见」视图
**When** 作者查看自己的**成长档案时间线 / 日历 / 当天详情 / 「我的发布」**
**Then** **完整展示含私密在内的全部内容，不过滤**
**And** 存在**测试锁定**：作者设为私密的 Diary **仍出现在其成长档案时间线中**
**And** 该测试为**回归防线**，防止后续 story 为「统一口径」误加过滤
**And** ⚠️ **宠物名片 H5（FR-14）不过滤**（OQ-18，2026-08-03 拍板）——即使包含作者手动设为私密的 Diary 仍照常展示。理由：`visibility` 约束的是平台自动分发，不约束用户自己按下分享键的行为
**And** 存在测试锁定：作者设私密的 Diary **仍出现在其分享的名片 H5 中**
**And** 三分法判定口径：**作者自视 → 不过滤；平台自动分发（Feed / 聚合 / 他人主页 / 公开位）→ 过滤；作者主动分享（名片 H5）→ 不过滤**

### AC5 — Feed 取消按用户宠物状态过滤（L1 · FR-83）

**Given** V1.0.0「状态 B 用户 Feed 不显示成长日历快乐时刻」规则**整条废止**
**When** 实现完成
**Then** 公开内容对所有用户**一视同仁**，不按宠物状态过滤
**And** Feed 分类筛选保留，公开 Diary 归入「**成长时刻**」分类（类型不因同步而改变）

### AC6 — 包含关系与删除联动（L1 · AD-14）

**Given** Diary 与 Moment 为**同一条内容的两处展示（非副本）**
**When** 用户删除任一处
**Then** 两处同步消失（沿用 V1.0.0 FR-12 包含关系语义与 FR-36 删除联动规则）
**And** **未新建任何关联表**——由 `visibility` 单字段表达
**And** Moment / Tips **不可回灌 Diary**

### AC7 — 契约三处同步（L0 · NFR-8）

**Given** 新增对外字段 `visibility`
**When** 实现完成
**Then** **后端 record、App data DTO、后端契约 test 三处已同步**（App mock 层已随 `8e85b40d` 删除，不适用）
**And** ⚠️ **App data DTO 镜像归本 Story**，不得推迟到 4.2——否则本 Story 的契约同步无法闭合

---

## Tasks / Subtasks

> **纯后端**。顺序：迁移 → 实体/枚举 → 查询过滤改造 → 作者自视回归锁定 → 契约 test。

### 🟦 后端子任务（petgo-backend / Spring Boot）

- [ ] **B1. Flyway 迁移 `V98__add_content_visibility.sql`** (AC: 1, 2)
  - [ ] `ALTER TABLE content_posts ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' CHECK (visibility IN ('PUBLIC','PRIVATE'))`。
  - [ ] 存量统一为 `PUBLIC`（`DEFAULT` 已覆盖新增列的既有行，显式 `UPDATE` 兜底并留痕）。
  - [ ] 视 Feed 查询计划决定是否加 `(visibility, created_at DESC)` 组合索引。
  - [ ] ⚠️ 迁移号按执行顺序顺延（决策 E2），当前最高 V97。改迁移后重跑 `test-compile` 重拷资源。

- [ ] **B2. 枚举 + 实体** (AC: 1)
  - [ ] `content/domain/ContentVisibility`（PUBLIC / PRIVATE）。
  - [ ] `content/domain/ContentPost` 加 `visibility` 字段，默认 `PUBLIC`。
  - [ ] ⚠️ **`ContentType` 枚举不改名**（继承 V1.1 决策 A-7：DB 保留 `DAILY`）。

- [ ] **B3. 消费公开内容的查询加过滤** (AC: 3, 5)
  - [ ] `content/service/ContentService` 及 repository：Feed、话题聚合、他人主页预览卡等**统一加 `visibility = PUBLIC`**。
  - [ ] **移除**按用户宠物状态过滤成长日历的既有逻辑（V1.0.0 内容可见范围表整条废止）。
  - [ ] 分类筛选保留，公开 Diary 归「成长时刻」。

- [ ] **B4. 作者自视视图确认不过滤 + 回归锁定** (AC: 4)
  - [ ] 逐一核对：`findGrowthMoments`（成长档案时间线）、`findGrowthMomentsInMonth`（日历）、`findGrowthMomentsOnDate`（当天详情）、`findMyPosts`（我的发布）——**均不加 visibility 过滤**。
  - [ ] ⚠️ **名片 H5 取数同样不加过滤**（OQ-18）：`CardPageController` / 名片快乐时刻流保持现状，**不因本次改造被连带过滤**。
  - [ ] 在这些方法上加注释说明「作者自视，不得加 visibility 过滤（NFR-4）」。
  - [ ] **L1 回归 test**：作者设私密的 Diary 仍出现在其成长档案时间线 / 日历 / 当天详情 / 我的发布。

- [ ] **B5. 删除联动核实** (AC: 6)
  - [ ] 确认既有 FR-36 删除联动在新字段下仍成立（同一条内容，删即两处消失）。
  - [ ] 确认未新建关联表。

- [ ] **B6. 契约 test** (AC: 7)
  - [ ] `FeedResponseContractTest` 等既有契约 test 补 `visibility` 字段集与枚举取值断言。

- [ ] **B7. 测试** (AC: 1~6)
  - [ ] **L1**：迁移干净应用 + `validate` 过；存量全 `PUBLIC`；Feed 只出公开；B 状态用户能看到公开成长日历（AC5）。
  - [ ] **L1** 安全回归（AC4）：作者自视四处均含私密内容。
  - [ ] **L1** 回归：`content` 域与 `profile` 域既有测试全绿。

### 🟩 前端子任务

- ✅ **仅一项：App data DTO 镜像 `visibility` 字段**（AC7 契约三处同步的第二处）。
  - [ ] 内容模型加 `visibility`，解析后端下发值。**归本 Story，不推给 4.2** —— 否则契约三处同步在本 Story 无法闭合，AC7 落空。
  - [ ] 发布页交互与「我的」页标识归 Story 4.2，本 Story 不碰 UI。

### 🟨 联调验收子任务

- [ ] **J1（L1）**：真库跑迁移，确认存量内容在 Feed 中一条不少。
- [ ] **J2（L1）**：AC4 安全回归端到端跑一遍。

---

## Dev Notes

### 关键约定

- **默认公开，不是默认私密**。这是 2026-07-30 反转后的结论。反转的意义是：本 FR 从「改变默认分发」降级为「新增一个可选开关」，**用户侧无感知变更**，风险大幅下降。
- **通用字段而非 Diary 专属布尔**（AD-4）：三类内容统一携带，所有消费点只需一句「只要公开的」。以后加「仅好友可见」也不用改表。
- **AC4 是本 Story 最容易出事的地方**。AD-4 Rule 2 说「一切消费公开内容的查询统一过滤」，很容易被理解成「所有查询都加」。Rule 3 明确限定了范围，AC4 的回归测试是防线。

### 强制护栏（违反即返工）

- 迁移**在功能开关生效前完成**，不接受先上线后回填。
- 作者自视视图**不得**加 visibility 过滤（NFR-4，安全攸关）。
- 不新建关联表（AD-14）。
- `ContentType` 枚举不改名。
- 契约三处同步。

### Project Structure Notes

- 后端新增：`db/migration/V98__add_content_visibility.sql`、`content/domain/ContentVisibility.java`。
- 后端修改：`content/domain/ContentPost.java`、`content/service/ContentService.java` + repository（Feed/聚合加过滤、移除宠物状态过滤）、既有契约 test。
- 后端新增测试：`ContentVisibilityMigrationTest`（L1）、`AuthorSelfViewNoFilterTest`（L1，AC4 回归防线）。

### References

- [Source: epics-v1.1.2.md#Story 4.1] — 7 条 AC 原文。
- [Source: architecture-v1.1.2-delta.md#AD-4] — 通用 visibility 列八条 Rule（Rule 3 为作者自视不过滤）。
- [Source: architecture-v1.1.2-delta.md#AD-14] — 包含关系不建关联表。
- [Source: architecture-v1.1.2-delta.md#§4] — Flyway V98 起；本版本唯一 schema 变更。
- [Source: PRD-v1.1.2.md#FR-83] — 同步开关、数据模型变更与迁移方向（含 2026-07-31 订正）、Feed 取消按状态过滤、存量维持公开。
- [Source: CROSS-STORY-DECISIONS.md#E2] — Flyway 号按执行顺序单调分配。
- [Source: CROSS-STORY-DECISIONS.md#C4/C5] — 契约同步（第四处 App mock 已失效）。
- [Source: ContentPost.java / ContentService.java] — 现状实现（无可见性字段）。

## Dev Agent Record

### Agent Model Used

_(待填)_

### Debug Log References

_(待填)_

### Completion Notes List

_(待填。须记录：实际 Flyway 号、AC4 回归 test 类名、App data DTO 镜像归本 Story 还是 4.2。)_

### File List

_(待填)_

## Change Log

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-08-02 | create-story | 依据 epics-v1.1.2 Story 4.1 + AD-4/AD-14 生成。baseline=b324308a。 |
| 2026-08-03 | 修订 | 明确 App data DTO 镜像 `visibility` 归本 Story（原写「随 4.2 或本 Story 二选一」，归属含糊会导致两条都不做、AC7 落空）。 |
| 2026-08-03 | 修订 | OQ-18 已闭合：名片 H5 不过滤（作者主动分享属自主授权），补入 AC4 的三分法与测试锁定。 |
