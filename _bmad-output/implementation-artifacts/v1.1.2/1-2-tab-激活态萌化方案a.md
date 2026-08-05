---
baseline_commit: b324308a
---

# Story 1.2: Tab 激活态萌化「方案A」

Status: review

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

- [x] **F1. 激活态样式重做** (AC: 1, 3)
  - [x] `shared/widgets/bottom_tab_bar.dart`：激活态由「紫实心 + 红 (3,3) 错位投影」改为「品牌紫 glyph + 柔和圆角高亮底」。
  - [x] 移除 Tab 激活态的错位投影绘制；确认 pop-art 在其它组件的用法未受影响。

- [x] **F2. 五个 Tab 的宠物特征装饰** (AC: 1)
  - [x] 按 T3 稿为每个 Tab 叠加一处装饰（Diary=猫耳，其余按稿）。装饰为叠加层，**不改动 glyph 本体路径**。
  - [x] 未选中态不渲染装饰。

- [x] **F3. 入场动效 + reduced-motion** (AC: 2)
  - [x] 一次轻弹跳，`AppMotion` 体系内新增或复用时长常量，**≤150ms**。
  - [x] 接 `MediaQuery.disableAnimations` / 既有 reduced-motion 兜底通道，降级为无动效直切终态。

- [x] **F4. 规范与 PRD 回改** (AC: 3)
  - [x] 回改 `V1.0.0/icon-system.md` 激活态章节为方案A。
  - [x] 回改 `_bmad-output/planning-artifacts/v1.1.2/PRD-v1.1.2.md` OQ-13 为已闭合（源文件 `TailTopia/V1.1.2/改版1-1-2prd.md` 同步）。

- [x] **F5. 测试** (AC: 1, 2, 4)
  - [x] widget test：激活/未选中两态 glyph 形状一致；激活态含装饰、未选中态无装饰；错位投影不再出现。
  - [x] widget test：reduced-motion 下不播动效。

### 🟨 联调验收子任务

- [x] **J1（L2）**：模拟器逐 Tab 视觉核对 T3 稿；开关系统「减少动态效果」各走一遍。

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

claude-opus-5[1m]（本地 dev-story；L0 全绿 + L2 真模拟器视觉验收）

### Debug Log References

- **L0**：`flutter analyze` → **No issues found**；新增 `test/shared/tab_cutified_active_test.dart` **8 绿**；`flutter test` 全量 **509 绿 0 失败**（501 → 509）。
- **L2**（真模拟器 `petgo_phone`）：Discovery 激活态渲染为 **紫罗盘 glyph + 柔和圆角高亮底 + 右上角尾巴装饰**，红色错位投影已消失；未选三位维持灰描边；[+] 未参与萌化。截图 `tab-03-charm-discovery.png` / 放大版 `tab-03-zoom.png`（scratchpad）。

### Completion Notes List

- **方案A 三要素落地**：① glyph 转品牌紫实心 + **柔和圆角高亮底**（violet @14%，圆角 10）② 右上角**一处宠物特征装饰** ③ 一次轻弹跳 `AppMotion.tabCharmBounce` = **150ms**（硬上限）。
- **装饰按 T3 稿逐 Tab 分配**：Diary=猫耳 / Health=爪印 / Discovery=尾巴 / Me=项圈铃铛；`[+]` 不参与。装饰为**独立叠加层，不改 glyph 本体路径**——glyph 是辨识锚点，形状必须两态一致（已由 widget test 断言两态尺寸相同）。
- **AC3 替换而非叠加**：红色 (3,3) 错位投影层与 `_kPopRed` 常量**已整体移除**；测试 `activeTabPopShadow` findsNothing 作为回归守卫。仅动 Tab 激活态，pop-art 在其它组件的用法未受影响。
- **reduced-motion**：按 `icon-system.md`「Reduced Motion」条款——`disableAnimations` 时 `duration=0`，**状态照常切换、仅去动画过程**（非跳过激活态）。已加断言。
- **⚠️ 顺带更换 Discovery 图标：`House` → `Compass` 罗盘**（2026-08-03 用户拍板）。首页改名探索后房子语义不成立；T3 稿与 PRD FR-78A 均作「探索=罗盘」，而现网代码与 `icon-system.md` 都还是房子。属本 Story 之外的可见变化，已单独拍板后执行。
- **OQ-13 已闭合**：`V1.0.0/icon-system.md` 顶部加「V1.1.2 起激活态规范已更新」横幅（原 Pop Art 双层结构章节标注对 Tab Bar 不再生效，保留供追溯）+ Tab 图标映射表按 V1.1.2 重排与改名更新；PRD 两份副本 OQ-13 标为已解决。
- **⚠️ 当前为简笔 SVG 近似**：T3 稿自述「本稿用简笔 SVG 近似，**实际由设计出精修萌化图标**」「具体萌化元素/动效为设计草案，可逐 tab 调整」。本 Story 交付的是**机制 + 与稿同水位的近似图形**；设计精修图标到位后**只需替换 SVG 常量，结构不变**。
- **L2 覆盖范围说明（如实记录）**：真机只验证了 **Discovery 激活态**——其余三个 Tab 受登录门控，游客态切不过去。四个 Tab 各自的装饰由 widget test `每个 Tab 激活时都只有一处装饰` 覆盖；真机逐 Tab 视觉留待登录态联调时顺带核对。
- **零逻辑改动**：Tab 顺序、路由、登录门控、落地页均未触碰（AC4）。


#### ⚠️ 2026-08-03 二次对稿修正（用户反馈「和 UI 设计相差太大」）

首版把**四处装饰实现成了同一个「右上角 13×13 紫色小图标」**，与 T3 稿逐项都有差距。按稿逐条重做：

| 项 | T3 稿 | 首版实现 | 已修正为 |
|---|---|---|---|
| 高亮底 | 44×44、violet-tint **实底** #F8F2FF、圆角 13 | 30×30、紫 **14% 半透明**、圆角 10 | 按稿 |
| **Diary 猫耳** | **顶部居中**长在书上沿、23×11、紫外耳 + **粉内耳 #F7C9D9** | 右上角 13×13 纯紫两个三角 | 按稿（含粉内耳、扁比例不拉方） |
| Health 爪印 | 右上 (-3,-3)、16×16、紫 + **白发光** | 右上 (-5,-5)、13×13、无白边 | 按稿（白粗描边垫底近似 filter） |
| Discovery 尾巴 | **右下** (-4,-1)、18×18、**描边** 2.6 + 白发光 | 右上角、13×13、**填充**（弧线糊成一坨） | 按稿 |
| Me 项圈铃铛 | **居中盖在人像上**、26×26、**金色 #F6A609** + 白高光 | 右上角、13×13、**紫色** | 按稿 |
| Health 激活 glyph | 仍是**描边**（实心会糊） | 改成了实心 | 按稿恢复描边（1.8） |

**为什么首版能过测**：原测试只断言「有高亮底 / 有一处装饰 / 弹跳 ≤150ms」，**没有一条校验位置、尺寸、配色**。本次补 6 条几何/配色断言（高亮底尺寸与实底色、猫耳居中且含粉色、爪印白边、尾巴在右下且为描边、项圈金色且居中、Health 激活为描边）—— 以后再偏就会红。

顺带把 glyph 表与「激活态是否描边」抽成 `_kTabIcons` / `_kActiveOutlineTabs`，并暴露 `tabActiveGlyphSvg` / `tabCharmSvg`（`@visibleForTesting`）供断言取源串（`SvgStringLoader` 不暴露 svg 字符串）。

L0：analyze 零问题 / 本文件 14 绿 / 全量 561 绿。L2：模拟器逐个 Tab 截图放大核对（`t3-01..04-*-zoom.png`）。

### File List

**前端（修改）：**
- `petgo_app/lib/shared/widgets/bottom_tab_bar.dart`（罗盘图标 + `_kTabCharm` 装饰表 + `_TabItem` 改方案A + 移除 `_kPopRed`）
- `petgo_app/lib/core/theme/motion.dart`（+`tabCharmBounce` = 150ms）

**测试（新增）：**
- `petgo_app/test/shared/tab_cutified_active_test.dart`（L0，8）

**规范/规划产物（修改）：**
- `TailTopia/V1.0.0/icon-system.md`（V1.1.2 激活态更新横幅 + Tab 图标映射表）
- `_bmad-output/planning-artifacts/v1.1.2/PRD-v1.1.2.md` 与源文件 `改版1-1-2prd.md`（OQ-13 闭合）
- `_bmad-output/implementation-artifacts/v1.1.2/1-2-tab-激活态萌化方案a.md`、`sprint-status-v1.1.2.yaml`

## Change Log

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-08-02 | create-story | 依据 epics-v1.1.2 Story 1.2 + UI 稿 T3 生成；OQ-13 由视觉稿闭合。baseline=b324308a。 |
| 2026-08-03 | dev-story | 方案A 萌化落地：紫 glyph + 柔和圆角高亮底 + 逐 Tab 宠物特征装饰（猫耳/爪印/尾巴/项圈铃铛）+ 150ms 轻弹跳 + reduced-motion 归零；移除 pop-art 红色错位投影。顺带按拍板把 Discovery 图标由房子改罗盘。回改 icon-system.md 并闭合 OQ-13。L0 analyze 零问题 / 新测 8 / 全量 509 绿；L2 真机验收 Discovery 激活态。 |
| 2026-08-03 | fix-visual | 二次对稿：四处装饰按 T3 逐条修正（位置/尺寸/配色/描边-填充全部对齐），高亮底改 44×44 violet-tint 实底 r13，Health 激活恢复描边；补 6 条几何配色断言（首版偏差之所以漏过，是因为原测试只验「有装饰」不验几何）。 |
