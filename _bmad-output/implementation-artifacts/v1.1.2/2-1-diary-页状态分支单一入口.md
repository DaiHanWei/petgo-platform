---
baseline_commit: b324308a
---

# Story 2.1: Diary 页状态分支单一入口

Status: review

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

- [x] **F1. 抽出状态分发入口** (AC: 1)
  - [x] `features/profile/presentation/growth_archive_page.dart`：在页面根抽出一处 `resolveDiaryUserState()`（纯函数，顶层可测）→ 返回 `DiaryUserState` 四态枚举 → `switch` 分发到四个 widget。
  - [x] 状态判定输入：`authControllerProvider` 的 `isLoggedIn` / `profile.petStatus` + 「有无档案」（沿用现有信号，不新增字段）。
  - [x] 清理页面内其它散落的用户状态判断，全部收敛到此处。

- [x] **F2. 四分支布线** (AC: 1, 2)
  - [x] 已建档 → 现有档案页组件（原样引用，不改）。
  - [x] B/C → 既有「有宠专属」页组件（原样引用，不改）。
  - [x] 游客 → 新增 `_GuestGuidePlaceholder`（标 `// TODO(2-2)`）；A 未建档 → 沿用既有 `_EmptyProfileView`（标 `// TODO(2-3)`）。

- [x] **F3. 测试** (AC: 1, 2, 3)
  - [x] widget test：四态各构造一次，断言渲染到正确分支（游客渲染占位、未建档渲染既有建档引导），并交叉断言未误落其它分支。
  - [x] 纯函数 test：穷举 `isLoggedIn × petStatus × hasPetProfile` 组合，验互斥 + 穷尽 + 枚举恰好四态。
  - [x] 回归 test：门控未变——`kUngatedTabs` 不含 Diary；游客深链 `/profile` 仍 redirect 回 `/home`；游客点 Diary Tab 仍弹强登录窗且不切换目的地。

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

claude-opus-5[1m]（本地 dev-story；L0 全绿 + L1 真后端验收 2/3 分支，第 3 条按用户决定留待验收）

### Debug Log References

- **L0**：`flutter analyze` → **No issues found**；新增 `test/profile/growth_archive_state_branch_test.dart` **12 绿**；`flutter test` 全量 **521 绿 0 失败**（改前 509 → 零回归）。
- **L1**（真后端：docker postgres+redis + `mvnw spring-boot:run`，`/actuator/health` = UP；模拟器 `petgo_phone` 装 `--dart-define=PETGO_API_BASE_URL=http://10.0.2.2:8080` 的 debug 包）：
  - **状态 A · 未建档**（该账号真实态，后端 200 返回无档案）→ 渲染既有「No pet profile yet + Create now + Change status」，与改版前一致。截图 `l1-02-diary.png`。
  - **状态 B/C**（经状态编辑器真实改为 PLANNING 落库）→ 渲染既有「有宠专属 + Change status」页，**不引导建档**（UX-DR6 回归基准）。截图 `l1-04-bc.png`。验完**已改回 HAS_PET**，账号恢复原状（`l1-05-restore.png`）。
  - **状态 A · 已建档** → **未在真后端跑**：该模拟器账号刻意无档案（是 Story 2-3 的现成样本），建档会破坏样本、删档在后端是级联操作不可逆。**用户 2026-08-03 决定不建档、这条留待验收**。该分支渲染由 L0 widget test 覆盖（信息卡 + 分享 FAB 均在），且本 Story 对它是零改动引用。
  - **游客分支**在 L1 不可达（门控未解，符合 AC3 预期）；其渲染 + 「不拉档案」由 widget test 覆盖。

### Completion Notes List

**AD-15 落地方式**：页面根 `build()` 里只剩一次判定 —— 顶层纯函数 `resolveDiaryUserState(isLoggedIn, petStatus, hasPetProfile)` → `DiaryUserState` 四态枚举 → `switch` 分发。枚举 + 穷尽 switch 意味着 **2.2 / 2.3 想加状态必须改枚举，改了不布线就编译期报错**，不可能像原先那样各自在页面根塞 `if` 互相覆盖。

- **「已建档」判定口径沿用真实档案，不用 `hasPetProfile` 字段**：登录响应里的 `hasPetProfile` 可能 stale（`me_page.dart` 早有同样注释），Dev Notes 提的两个信号里，这一个以 `petProfileProvider` 的真实结果代入 —— 既满足「不新增字段」，也保证 AC2 的零回归。加载中 / 失败态仍由原来的 `when(loading/error)` 承接，渲染逐字未动。
- **顺带修掉一个真实隐患（游客不再拉档案）**：改版前游客进本页会订阅 `petProfileProvider`（游客 `petStatus` 为 null → 落入 HAS_PET 路径）。游客无令牌 → 401 → 拦截器弹全局强登录窗。现在游客分支短路，不订阅任何档案/时间线/统计 provider。门控未解时用户碰不到，但 **2.4 放行游客后这就是必现问题**，故在此一并封住，并用「fetch 次数 == 0」的断言常驻护栏。
- **游客占位刻意不含文案**：门控在 2.4 才解除，本分支当前对真实用户不可达；提前写 ARB 会与 2.2 最终稿重复返工（OQ-1 的印尼语文案 + Milo 配图也未到）。占位只有 cream 底空屏 + `ValueKey('diaryGuestGuide')` 供测试寻址。
- **未建档分支不写新占位**：直接沿用 V1.0.0 既有 `_EmptyProfileView`（含「删档后可切回 B/C」的逃生入口，bug 20260702-237），标 `// TODO(2-3)`。比塞一个 stub 更符合 AC2 的零回归要求。
- **门控零改动已被机械化断言**：`kUngatedTabs` 仍为 `{Discovery}`、游客深链 `/profile` 仍 redirect 回 `/home`、游客点 Diary Tab 仍弹 `LoginHardDialog` 且不切换目的地 —— 三条断言常驻，2.4 改门控时会正面撞上它们（届时按 2.4 的 AC 更新，不得静默删除）。
- **零后端改动、零迁移。**

### File List

**前端（修改）：**
- `petgo_app/lib/features/profile/presentation/growth_archive_page.dart`（新增 `DiaryUserState` 枚举 + `resolveDiaryUserState()` 单一判定入口 + switch 四分支布线 + `_ownerBranch` 抽出 + `_GuestGuidePlaceholder` 占位 + 类文档更新）

**测试（新增）：**
- `petgo_app/test/profile/growth_archive_state_branch_test.dart`（L0，12：纯函数穷举 5 + 四分支渲染 4 + 门控回归 3）

**规划产物（修改）：**
- `_bmad-output/implementation-artifacts/v1.1.2/2-1-diary-页状态分支单一入口.md`、`_bmad-output/implementation-artifacts/sprint-status-v1.1.2.yaml`

## Change Log

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-08-02 | create-story | 依据 epics-v1.1.2 Story 2.1 + AD-15 生成。baseline=b324308a。 |
| 2026-08-03 | dev-story | Diary 页四态收敛为单一判定入口：顶层纯函数 `resolveDiaryUserState` + `DiaryUserState` 枚举 + 穷尽 switch 分发；已建档 / B·C 两分支零改动引用，游客为新占位（TODO 2-2）、未建档沿用既有空态（TODO 2-3）。顺带封住「游客进本页会拉档案 → 401 弹强登录窗」的隐患（2.4 放行后必现），并以 fetch 次数断言常驻。L0：analyze 零问题 / 新测 12 / 全量 521 绿。L1：真后端验通未建档 + B·C（改 PLANNING 后已改回 HAS_PET）；「已建档」按用户决定不建测试档案，留待验收。门控未解（`kUngatedTabs` 不含 Diary，深链与 Tab 点击回归断言常驻）。 |
