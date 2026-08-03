---
baseline_commit: b324308a
---

# Story 2.1: Diary 页状态分支单一入口

Status: ready-for-dev

> **所属**：V1.1.2 Epic 2 第一个 Story（**纯前端 · 薄但必须先立**）。交付：Diary 页（`/profile`）根节点的用户状态分支收敛为**单一判定入口**，四状态互斥分发。
> ⚠️ **这是 AD-15 的基座**，Story 2.2（游客态）与 2.3（未建档态）的共同前置。若不先立，两条 story 会各自在同一个页面根上加判断，互相覆盖或漏掉状态组合。
> ⚠️ **本 Story 不解门控**（归 2.4），完成后游客仍进不来——游客分支通过 debug 路由 / widget test 验收。

## Story

As a **开发者**,
I want **Diary 页的四种用户状态由一处统一分发**,
so that **后续两条 story 各自往里加分支时不会互相覆盖或漏掉状态组合（AD-15）**。

## Acceptance Criteria

> **验证层**：**L0**（analyze / widget test 覆盖四分支）· **L1**（真后端，已登录路径回归）。本 Story 无 L2。

### AC1 — 单一判定入口，四分支互斥且穷尽（L0）

**Given** AD-15 要求状态分支收敛为单一判定入口
**When** 进入 Diary 页
**Then** 存在**唯一一处**按序判定并分发的入口，四个分支**互斥且穷尽**：

| 用户状态 | 渲染 |
|---|---|
| 游客（未登录） | FR-80 游客引导态（**本 Story 为占位**，2.2 填充） |
| 状态 A · 未建档 | FR-0G 既有建档引导（**本 Story 为占位**，2.3 填充） |
| 状态 A · 已建档 | 现有真实档案页（**零改动**） |
| 状态 B / C | V1.0.0 既有「有宠专属 + 修改宠物状态入口」页（**零改动**） |

**And** 页面其它位置**不得**再出现独立的用户状态判断
**And** widget test 覆盖四个分支各一例

### AC2 — 已交付分支零改动（L0/L1）

**Given** 本 Story 只立骨架
**When** 实现完成
**Then** 「状态 A 已建档」分支渲染现有真实档案页，行为与改版前完全一致
**And** 「状态 B·C」分支渲染既有页面，**零改动、不引导建档**（UX-DR6 为回归基准）
**And** 游客与「状态 A 未建档」两分支为**明确占位**（可渲染最简占位内容），由 2.2 / 2.3 填充

### AC3 — 门控未解，行为零回归（L1）

**Given** 门控在 Story 2.4 才解除
**When** 游客尝试进入 Diary
**Then** 仍按现状被拦截（深链 redirect 回 `/home`；点 Tab 弹强弹窗）
**And** 已登录用户的 Diary 体验与改版前完全一致
**And** 本 Story **不得**为了自测而提前放行门控——游客分支走 debug 路由 / widget test 验收

---

## Tasks / Subtasks

### 🟩 前端子任务（petgo_app / Flutter）

- [ ] **F1. 抽出状态分发入口** (AC: 1)
  - [ ] `features/profile/presentation/growth_archive_page.dart`：在页面根抽出一处 `_resolveDiaryState()`（或等价命名）→ 返回四态枚举 → `switch` 分发到四个 widget。
  - [ ] 状态判定输入：`authControllerProvider` 的 `isLoggedIn` / `profile.petStatus` / `profile.hasPetProfile`（沿用现有字段，不新增）。
  - [ ] 清理页面内其它散落的用户状态判断，全部收敛到此处。

- [ ] **F2. 四分支布线** (AC: 1, 2)
  - [ ] 已建档 → 现有档案页组件（原样引用，不改）。
  - [ ] B/C → 既有「有宠专属」页组件（原样引用，不改）。
  - [ ] 游客 / A 未建档 → 占位组件，标 `// TODO(2-2)` / `// TODO(2-3)`。

- [ ] **F3. 测试** (AC: 1, 2, 3)
  - [ ] widget test：四态各构造一次，断言渲染到正确分支（游客/未建档断言渲染占位）。
  - [ ] 回归 test：门控未变——游客深链 `/profile` 仍 redirect；点 Tab 仍弹窗。

### 🟨 联调验收子任务

- [ ] **J1（L1）**：真后端，已登录已建档 / 已登录未建档 / B·C 三种账号进 Diary，行为与改版前一致。

### 🟦 后端子任务

- ❌ **无**。

---

## Dev Notes

### 关键约定

- **这条 story 很薄，但顺序不能动**。它的价值不在功能，在于给 2.2 / 2.3 提供唯一的挂载点。跳过它直接做 2.2，两条 story 会在同一个页面根打架。
- **状态判定不新增字段**，复用 `petStatus`（HAS_PET / 其它）+ `hasPetProfile` 两个既有信号。
- **穷尽性**：四分支必须覆盖所有组合，不留隐式 fallback；未预期组合应显式落到某一分支并可测。

### 强制护栏

- 不得解除门控（属 2.4）。
- 已建档分支与 B/C 分支**零改动**——本 Story 碰它们的渲染即越界。
- 页面内不得残留第二处用户状态判断（AD-15 的核心约束）。

### Project Structure Notes

- 前端修改：`features/profile/presentation/growth_archive_page.dart`（抽分发入口 + 四分支布线）。
- 前端新增：占位组件（2.2 / 2.3 会替换）、`growth_archive_state_branch_test.dart`。

### References

- [Source: epics-v1.1.2.md#Story 2.1] — 3 条 AC 原文。
- [Source: architecture-v1.1.2-delta.md#AD-15] — 状态分支归属单一入口；四状态表；FR-0H 废止（归 2.3）。
- [Source: PRD-v1.1.2.md#FR-81] — 状态 A 未建档 vs B/C 的区分理由。
- [Source: ui-v1.1.2.html#A2b] — 状态 B/C 的 Diary 非落地态（回归基准，零改动）。
- [Source: growth_archive_page.dart] — 现状实现。

## Dev Agent Record

### Agent Model Used

_(待填)_

### Debug Log References

_(待填)_

### Completion Notes List

_(待填)_

### File List

_(待填)_

## Change Log

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-08-02 | create-story | 依据 epics-v1.1.2 Story 2.1 + AD-15 生成。baseline=b324308a。 |
