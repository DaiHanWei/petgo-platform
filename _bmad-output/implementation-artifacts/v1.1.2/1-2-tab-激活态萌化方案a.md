---
baseline_commit: b324308a
---

# Story 1.2: Tab 激活态萌化「方案A」

Status: ready-for-dev

> **所属**：V1.1.2 Epic 1 第二个 Story（**纯前端 · 纯视觉层**）。交付：Tab 激活态按 UI 稿 T3「方案A」萌化，并**替换** V1.0.0 `icon-system.md` 定义的 pop-art 激活态。
> ✅ **OQ-13 已由视觉稿闭合**：早期 PRD 把「替换 vs 叠加」列为待定（假设 A-2 取替换），T3 屏给出的方案A 即替换。本 Story 顺带回改 `icon-system.md` 并把 PRD OQ-13 标为已闭合。
> 本 Story 与 1.1 无强依赖，但建议 1.1 先落地，避免图标位置返工。

## Story

As a **TailTopia 用户**,
I want **点中的标签有一点宠物感的小变化**,
so that **这个 App 摸起来像是给宠物用的，而不是一个通用工具（FR-78A）**。

## Acceptance Criteria

> **验证层**：**L0**（analyze / widget test / 规范文档回改）· **L2**（模拟器视觉与动效）。本 Story 无 L1。

### AC1 — 方案A 激活态视觉（L2）

**Given** UI 稿 T3 屏「方案A」
**When** 用户点击任一 Tab
**Then** 该 Tab 的 glyph **尺寸与形状两态一致**（用户靠形状辨识 Tab，不改变辨识锚点）
**And** 激活时 glyph 转**品牌紫** + 出现**柔和圆角高亮底**
**And** 叠加**一处**宠物特征装饰（Diary = 书 + 猫耳；其余 Tab 按 T3 稿：爪印 / 尾巴 / 项圈）
**And** 未选中态维持 **ink@55% 描边**不变

### AC2 — 入场动效与 reduced-motion 兜底（L2 · NFR-9）

**Given** 激活入场动效
**When** 切换 Tab
**Then** 播放**一次**轻弹跳，时长 **≤150ms**
**And** 系统开启「减少动态效果」时，按 `icon-system.md` 既有 reduced-motion 兜底降级（不播动效，直接切换终态）

### AC3 — 替换 pop-art 激活态 + 规范回改（L0）

**Given** 方案A 为**替换**（而非叠加）V1.0.0 pop-art 激活态（紫实心 + 红 (3,3) 错位投影）
**When** 实现完成
**Then** 错位投影**不再出现**在 Tab 激活态
**And** `V1.0.0/icon-system.md` 的激活态规范已同步回改为方案A
**And** PRD `v1.1.2/PRD-v1.1.2.md` 的 OQ-13 标记为**已闭合**（附「由视觉稿 T3 方案A 回答」）
> ⚠️ 只改 **Tab** 的激活态。pop-art 若在其它组件上使用，不受影响、不得连带删除。

### AC4 — 纯视觉层，零逻辑改动（L0）

**Given** 本 Story 为纯视觉/动效层
**When** 实现完成
**Then** Tab 顺序、路由、登录门控、落地页逻辑均无改动
**And** `flutter analyze` 零新增告警

---

## Tasks / Subtasks

### 🟩 前端子任务（petgo_app / Flutter）

- [ ] **F1. 激活态样式重做** (AC: 1, 3)
  - [ ] `shared/widgets/bottom_tab_bar.dart`：激活态由「紫实心 + 红 (3,3) 错位投影」改为「品牌紫 glyph + 柔和圆角高亮底」。
  - [ ] 移除 Tab 激活态的错位投影绘制；确认 pop-art 在其它组件的用法未受影响。

- [ ] **F2. 五个 Tab 的宠物特征装饰** (AC: 1)
  - [ ] 按 T3 稿为每个 Tab 叠加一处装饰（Diary=猫耳，其余按稿）。装饰为叠加层，**不改动 glyph 本体路径**。
  - [ ] 未选中态不渲染装饰。

- [ ] **F3. 入场动效 + reduced-motion** (AC: 2)
  - [ ] 一次轻弹跳，`AppMotion` 体系内新增或复用时长常量，**≤150ms**。
  - [ ] 接 `MediaQuery.disableAnimations` / 既有 reduced-motion 兜底通道，降级为无动效直切终态。

- [ ] **F4. 规范与 PRD 回改** (AC: 3)
  - [ ] 回改 `V1.0.0/icon-system.md` 激活态章节为方案A。
  - [ ] 回改 `_bmad-output/planning-artifacts/v1.1.2/PRD-v1.1.2.md` OQ-13 为已闭合（源文件 `TailTopia/V1.1.2/改版1-1-2prd.md` 同步）。

- [ ] **F5. 测试** (AC: 1, 2, 4)
  - [ ] widget test：激活/未选中两态 glyph 形状一致；激活态含装饰、未选中态无装饰；错位投影不再出现。
  - [ ] widget test：reduced-motion 下不播动效。

### 🟨 联调验收子任务

- [ ] **J1（L2）**：模拟器逐 Tab 视觉核对 T3 稿；开关系统「减少动态效果」各走一遍。

### 🟦 后端子任务

- ❌ **无**。

---

## Dev Notes

### 关键约定

- **形状是辨识锚点，绝不能变**。方案A 的核心约束是 glyph 尺寸/形状两态一致，只在颜色、底衬、装饰上做文章。
- **装饰是叠加层**，不改 glyph 本体路径——否则未选中/激活两态形状会飘。
- **只动 Tab 激活态**，不动 pop-art 在其它地方的用法。

### 强制护栏

- 动效 ≤150ms 且必须有 reduced-motion 兜底（NFR-9，无障碍）。
- 未选中态维持 ink@55% 描边，不得一起改。
- 零逻辑改动——本 Story 碰到路由或门控即越界。

### Project Structure Notes

- 前端修改：`shared/widgets/bottom_tab_bar.dart`、`core/theme/motion.dart`（如需新增时长常量）。
- 文档修改：`V1.0.0/icon-system.md`、`v1.1.2/PRD-v1.1.2.md`（OQ-13 闭合）+ 源文件同步。

### References

- [Source: epics-v1.1.2.md#Story 1.2] — 4 条 AC 原文。
- [Source: ui-v1.1.2.html#T3] — 方案A 完整规格（形状两态一致 / 品牌紫 + 柔和圆角高亮底 / 一处宠物特征 / ≤150ms 轻弹跳）。
- [Source: PRD-v1.1.2.md#FR-78A] — 需求原文与假设 A-2（替换 pop-art）。
- [Source: architecture-v1.1.2-delta.md#§7 Deferred] — `icon-system.md` 回改属文档收口，随本 Story 一并做。

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
| 2026-08-02 | create-story | 依据 epics-v1.1.2 Story 1.2 + UI 稿 T3 生成；OQ-13 由视觉稿闭合。baseline=b324308a。 |
