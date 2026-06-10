---
title: "Sprint Change Proposal — PRD V1.0.0 第二轮（12 处断档补齐）"
date: 2026-06-08
status: proposed
trigger: 上游 PRD「fix 12 logic gaps」提交（92267f6）
scope_classification: Moderate（backlog 重排 + 加列 + stub 接口，无新 epic、无根本性 replan）
---

# Sprint Change Proposal：PRD V1.0.0 第二轮断档补齐

## 1. Issue Summary（问题摘要）

上游 PRD 源（`/Users/dai/work/petGo/V1版本/V1-0-0PRD20260606.md`）于 2026-06-08 16:08 提交 `92267f6 docs(prd): fix 12 logic gaps`，144 增 41 删。**本轮非新增功能，而是补齐 V1 的异常态、退出态、并发竞争、跨模块一致性断档。**

- 第一轮（F1–F8）已收口 incoming PRD 的 F2–F8 功能增量并实现至 review/done。
- 本轮 12 处补齐**尚未进入本仓库**（同步前逐词核对：`授权失败/事件日期/庆祝页/内容关联选择器/原子写入/三方图像` 全部 0 命中）。
- **第 1 步已完成**：12 处 + 配套状态补齐已同步进 `planning-artifacts/PRD.md`（144 增 42 删，50 个 FR 锚点无误删）。

证据：见 `git show 92267f6`（上游 diff 509 行）与本仓库 PRD 当前 diff。

## 2. Impact Analysis（影响分析）

### 2.1 Epic 影响
- **无新增 epic、无 epic 作废、无重排序。** 12 处补齐全部落在既有 Epic 1–7 的现有 FR 上，外加 Epic 8（里程碑 mini-epic）的规格细化。
- Epic 8（`backlog`）：FR-42 打卡 in-page picker / L 级达成推送 / 已过生日补录 —— **仅更新规格，本轮不实现**（维持 6/8 拆出决策 F2）。

### 2.2 Story 影响（按类别）

**A 类 — 仅改 AC 文档（`ready-for-dev`，未实现，零返工）：**

| Story | 补齐点 | 决策 |
|---|---|---|
| 1-3 / 1-4 | Google 授权失败态、注册返回键退出登录 | F13 |
| 1-6 | 返回键回退、状态切换 A↔B/C 分支（FR-21） | F15 邻接 |
| 1-7 | 创建成功庆祝页 + 推送权限时序 | **F15** |
| 2-2 | 字段必填规则表 + 生日完整日期 | （F6 已含 pet_type）|
| 2-3 | 事件日期字段、发布最低内容、B/C 灰选建档返回、**发布自动审核流程** | **F9 / F10 / F15** |
| 2-4 | FR-37 双视图按 event_date、当天详情页、未来格子置灰、加载失败、状态分支 | **F9 / F13** |
| 2-5 | FR-3 红色态存档承接、跳过庆祝页直接回灌 | F15 |
| 2-6 | FR-14 H5 加载失败/链接失效/DeepLink、快乐时刻流按 event_date | **F9 / F13** |

**B 类 — 回改已实现代码（`review` → 退回 `ready-for-dev`）：**

| Story | 补齐点 | 决策 | 轻重 |
|---|---|---|---|
| 3-2 首页 Feed | Feed 加载失败态 | F13 | 轻（前端）|
| 3-3 内容详情页 | 「···」菜单按归属分支、当天详情页入口 | F15 邻接 | 轻 |
| 3-5 两级评论 | 评论发送失败保留输入 | F13 | 轻 |
| 3-7 举报与审核 | 运营判定违规 → 内容下架 + 描述对齐自动审核 | **F10** | 中（admin 后端）|
| 4-3 AI 问诊上传 | 图片改选填（可仅文字提交）| — | 轻 |
| 4-5 红色半屏 | 结果页「存入档案」入口（按状态分支，守红色态零变现）| F15 / FR-3 | 中 |
| 5-2 兽医工作台 | 待接单抢单列表 UI | F11 | 中 |
| **5-3 咨询发起排队** | **抢单原子写入** + 等待退出/kill 取消匹配 | **F11 / F12** | **重（并发安全后端）**|
| 5-6 / 5-7 会话收尾/中断 | 对话进行中 kill → 30 分钟保护窗口恢复 | **F12** | 中（后端）|
| 6-4 推送权限时机 | 建档弹出位置 = 庆祝页后进首页前 | F15 | 轻 |
| 6-6 通知中心铃铛 | L 级里程碑条目（零态）+ 推送已读同步 | FR-34 | 轻 |
| 7-1 「我的」 | 我的发布排序口径文案 | F9 | 轻（可仅 AC）|
| 7-3 账号注销 | 名片链接注销立即失效（重申 D1，triage 删除不回退）| **F14** | 轻（补名片 token 失效）|

**D 类 — 仅规格（Epic 8 mini-epic，本轮不实现）：** FR-42 in-page picker 打卡 / L 级推送 / 已过生日补录 → PRD FR-42 已更新，随 mini-epic 落地。

### 2.3 Artifact 冲突
- **PRD**：已同步（第 1 步）。
- **CROSS-STORY-DECISIONS**：已增补 F9–F15，F1 内容审核条款标记被 F10 反转，表归属增 `content_posts.event_date`。
- **architecture.md**：需补 ① `content_posts.event_date` 加列；② FR-30 接单原子写入/乐观锁说明；③ `ContentModerationService`（发布审核，图像 stub）；④ 会话断线 30 分钟保护窗口语义。← 待第 4 步/dev 时同步。
- **UX**：庆祝页、当天详情页、抢单列表、各加载失败态以 **PRD §4 + mint 原型**为准（F8）。

### 2.4 技术影响
- **Flyway 加列**：`content_posts.event_date date null`（F9）；FR-30 如用乐观锁需 `consult_sessions` 版本列或依赖既有 status 条件更新（F11）。序号按执行顺序顺延（E2）。
- **新接口**：`ContentModerationService`（F10，图像识别 stub，流程真跑）。
- **并发安全**：FR-30 原子写入是安全攸关节点，DB 层互斥，**禁中间件**（护栏）。
- **退出态**：前端 lifecycle 检测 + 后端取消/保活（F12）。

## 3. Recommended Approach（推荐路径）

**Direct Adjustment（在既有计划内修改/补充 story）**，不回滚整段、不缩 MVP、不重排 epic。

- A 类：直接改 story AC，状态不变。
- B 类：冲突的 `review` story 退回 `ready-for-dev`，AC 标 `[PRD1.0.0✏️·R2]` 返工点。
- C 类：event_date 加列 + ContentModerationService stub 登记。
- D 类：仅规格。

**理由**：12 处多为给既有 FR 补边界态，工作量集中在「未实现 story 改 AC（零返工）」+「少量 review 前端补异常态」；唯二重活是 FR-30 并发 / FR-12 退出态两个后端安全点，单独优先验。风险低、可增量交付。

## 4. Detailed Change Proposals（详细变更）

PRD 正文变更已落地（第 1 步，41 处编辑）。Story 层变更为**第 3 步**执行项，逐条对应上表「Story 影响」；每条 B 类 story 在退回时 AC 追加对应决策编号（F9–F15）引用。架构补充为第 4 步。**本提案不直接编辑 story 文件**（按用户「先出方案/分步确认」节奏）。

## 5. Implementation Handoff（实施交接）

- **Scope：Moderate** —— backlog 重排（~13 个 review story 退回 + 8 个 AC 改）+ 加列 + stub 接口；无新 epic、无根本 replan。
- **Route：PO / DEV** —— 第 3 步更新 story AC + sprint-status（退回状态），第 4 步 Flyway 占位，第 5 步按 Epic 1→7 升序 dev-story（FR-30/F12 安全点优先单独验，云端 L0 绿、L1/L2 待本地）。
- **成功标准**：每条补齐点有对应 story AC 承接且标决策编号；FR-30 双接单、FR-12 退出取消有可证伪测试；内容审核 stub 流程 L0 绿。
