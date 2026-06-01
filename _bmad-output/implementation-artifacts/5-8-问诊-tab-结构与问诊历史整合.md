# Story 5.8: 问诊 Tab 结构与问诊历史整合

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **用户**,
I want **问诊 Tab 同时承载功能入口与我的问诊历史**,
so that **我能一处发起问诊并回看所有历史记录**。

> 本 Story 是 Epic 5 的**用户侧收口**：把 AI 分诊（Epic 4）与兽医咨询（5.3~5.7）整合进同一个「问诊 Tab」——功能入口区（AI 问诊 + 兽医咨询平级）、进行中会话卡（若有）、我的问诊历史列表（AI 评级 / 兽医评分 / 是否存档标记），并落地从历史/深链进入已关闭/已中断会话的**只读终态**展示。
>
> 做：问诊 Tab 三段结构（入口区 / 进行中卡 / 历史列表）、问诊历史统一列表（AI + 兽医两类条目）、历史条目内容（日期 + 类型 + AI 评级/症状摘要 或 兽医昵称/会话摘要/用户评分 + 是否已存档标记）、历史独立于 FR-16 存档保留、只读会话页（已结束/未评分/已中断终态标签）、深链进入只读会话、5.6 补弹评分入口挂载。
> 不做：AI 分诊本身（Epic 4，本 Story 消费其历史数据）、兽医会话发起/接单/对话（5.3~5.5，本 Story 是入口 + 历史回看）、评分逻辑（5.6，本 Story 挂补弹入口 + 展示用户评分）、推送深链路由表本身（Epic 6 / 6.1，本 Story 提供「只读会话页」落地目标，路由表在 6.1）。
>
> 依赖前序：Epic 4（AI 分诊历史）、5.3（发起入口 + 进行中会话）、5.5（进行中会话卡数据）、5.6（CLOSED/评分/存档标记 + pending-rating 补弹）、5.7（INTERRUPTED 已中断标记）。本 Story 是问诊模块的**用户侧主页**——5.3 的「取消后返回问诊模块主页」「离线软引导」、5.6 补弹、5.7 重新发起均落于此。

## Acceptance Criteria

> **验证层标注**：**L0**（列表组装 / 条目渲染 / lint） · **L1**（需 Docker + pg + redis：问诊历史聚合查询 AI+兽医两类、进行中会话查询、只读会话数据、补弹查询） · **L2**（真机跑问诊 Tab 三段 + 只读会话 + 深链落地 + 补弹弹窗）。
> 本 Story 历史聚合与只读数据主体在 **L1**；问诊 Tab 渲染、只读会话、深链落地在 **L2**。

### AC1 — 问诊 Tab 三段结构（入口区 / 进行中卡 / 历史列表）

**Given** 用户进入问诊 Tab
**When** 查看页面
**Then** 从上至下：功能入口区（AI 问诊 + 兽医咨询平级）、进行中会话卡（若有，点击进入对话）、我的问诊历史列表（FR-35）
> 验证层：三段结构渲染 = **L2**；进行中会话卡数据（若有 IN_PROGRESS/PENDING_CLOSE 会话 → 显示卡，点击进对话 5.5）= **L1**（查询）+ **L2**（渲染）。功能入口区：AI 问诊（跳 Epic 4 分诊）与兽医咨询（跳 5.3 发起，含在线/离线态）**平级并列**。**多态**：无进行中会话 → 不显示进行中卡；无历史 → 历史区空态。

### AC2 — 问诊历史条目内容（AI / 兽医两类）

**Given** 问诊历史列表
**When** 展示条目
**Then** 含日期 + 类型（AI/兽医）、AI 问诊显示评级+症状摘要、兽医问诊显示兽医昵称+会话摘要+用户评分、是否已存档标记
**And** 无论是否存入档案（FR-16），问诊历史均保留于此（存档是额外操作而非唯一留存路径）
> 验证层：历史聚合查询（AI 来自 triage 历史、兽医来自 consult_sessions + ratings）+ 条目 DTO = **L1**；条目渲染（AI：评级标签 + 症状摘要；兽医：兽医昵称 + 会话摘要 + 用户评分 + 已存档标记）= **L2**。**关键**：历史保留**独立于存档**——即使未存档（FR-16），会话/分诊记录仍在此列表；「是否已存档」只是一个标记，非「是否保留」的条件。**多态**：兽医会话「未评分」条目显示「未评分」而非星级；INTERRUPTED 条目显示「已中断」。

### AC3 — 从历史/深链进入只读会话（终态标签）

**Given** 用户从历史列表或推送深链进入一个已关闭/已中断的会话
**When** 打开会话
**Then** 以只读形式展示该会话历史与其终态标签（已结束 / 未评分 / 已中断），不可继续发消息（UX-DR18 ④ 的会话变体）
> 验证层：只读会话数据（会话消息历史 + 终态）= **L1**（查询）；只读渲染（消息历史只读、输入框禁用、终态标签「已结束 / 未评分 / 已中断」）= **L2**。**多态完整性**：覆盖 ① CLOSED 已评分（「已结束」）② CLOSED 未评分（「未评分」+ 可能补弹入口 5.6）③ INTERRUPTED（「已中断」+ 重新发起入口 5.7）④ 会话不存在/无权限（not-found / 403，不白屏——深链目标失效态，UX-DR18 ⑥）。深链落地（推送进只读会话）= **L2**（路由表在 6.1，本 Story 提供页）。

---

## Tasks / Subtasks

> 三段组织。后端：问诊历史聚合（跨 triage + consult，经 service 接口）+ 进行中会话查询 + 只读会话数据 + 多态。前端：问诊 Tab 三段 + 历史列表 + 只读会话页 + 补弹挂载。本 Story 是**用户侧 `features/consult`**（问诊 Tab 是用户侧问诊模块主页，非兽医 `features/vet`）。建议顺序：后端聚合 → 前端 Tab/历史/只读 → 联调多态。

### 🟦 后端子任务（petgo-backend / Spring Boot）

- [ ] **B1. 问诊历史聚合查询** (AC: 2)
  - [ ] `GET /api/v1/consult/history`（`role=user`，游标分页 `{items,nextCursor,hasMore}` 架构格式）：聚合该用户的 AI 分诊历史（经 `triage` service 接口，禁跨 repository）+ 兽医咨询历史（consult_sessions + consult_ratings）。
  - [ ] 条目统一 DTO：`type`(AI|VET)、`date`、AI 项含 `dangerLevel`+`symptomSummary`、VET 项含 `vetDisplayName`+`sessionSummary`+`userRating`(stars/未评分)+`archived`(bool)+`terminalState`(CLOSED/INTERRUPTED)。
  - [ ] **历史独立于存档**：列表来源是 triage/consult 自身记录，`archived` 仅标记位（FR-16 是否存入成长档案），不影响是否出现在历史。
- [ ] **B2. 进行中会话查询** (AC: 1)
  - [ ] `GET /api/v1/consult/active`（复用 5.3 `findActiveByUser`）：返回当前 WAITING/IN_PROGRESS/PENDING_CLOSE 会话（供进行中卡）；无则空。
- [ ] **B3. 只读会话数据** (AC: 3)
  - [ ] `GET /api/v1/consult-sessions/{id}`（5.5 已有，扩只读视角）：CLOSED/INTERRUPTED 返回消息历史（IM 历史或存档引用）+ 终态 + closed_reason(RATED/UNRATED)/interrupted_reason。
  - [ ] **失效态**：会话不存在 → 404 ProblemDetail；非本人会话 → 403（深链/历史进入的越权/失效兜底，UX-DR18 ⑥）。
  - [ ] 消息历史来源：IM 会话历史（只读拉取）或 5.6 存档引用——记选型（CLOSED 已存档用档案引用，未存档/INTERRUPTED 拉 IM 历史）。
- [ ] **B4. 补弹评分接入** (AC: 3)
  - [ ] 复用 5.6 `GET /api/v1/consult/pending-rating`：进问诊 Tab 时查需补弹的已关闭(UNRATED)会话 → 前端弹一次（5.6 流转）。

### 🟩 前端子任务（petgo_app / Flutter）

- [ ] **F1. 问诊 Tab 三段结构** (AC: 1)
  - [ ] `features/consult/presentation`：问诊 Tab 页（用户侧 5-Tab 之一）从上至下——① 功能入口区（AI 问诊卡 + 兽医咨询卡，**平级**；兽医咨询卡含 5.2/5.3 在线/离线态）② 进行中会话卡（读 `consult/active`，有则显示，点击进对话 5.5）③ 我的问诊历史列表（读 `consult/history`，游标分页无限滚动）。
  - [ ] 进问诊 Tab 时触发 5.6 补弹查询（pending-rating）→ 有则弹一次评分弹窗。
- [ ] **F2. 历史列表条目** (AC: 2)
  - [ ] AI 条目：日期 + 「AI」类型标 + 评级标签（绿/黄/红 icon+text+color，不依赖单一颜色，NFR-13/UX-DR15）+ 症状摘要。
  - [ ] 兽医条目：日期 + 「兽医」类型标 + 兽医昵称 + 会话摘要 + 用户评分（星级 / 「未评分」/ 「已中断」终态）+ 已存档标记。
  - [ ] 空态（无历史）；点击条目 → AI 进分诊结果只读（Epic 4）/ 兽医进只读会话（F3）。
- [ ] **F3. 只读会话页** (AC: 3)
  - [ ] 只读会话：消息历史只读渲染、输入框禁用、终态标签（已结束 / 未评分 / 已中断）。
  - [ ] CLOSED 未评分 → 可挂补弹评分入口（5.6）；INTERRUPTED → 挂「重新发起咨询」（5.7，复用 5.3 发起）。
  - [ ] **失效态**：会话 404/403 → not-found / 无权限态（不白屏、不停首页，UX-DR18 ⑥）；深链冷启动直达此页（路由表 6.1，本 Story 提供页）。

### 🟨 联调验收子任务

- [ ] **J1. 问诊 Tab 三段** (AC: 1 / **L1+L2**)
  - [ ] 入口区 AI/兽医平级；有进行中会话 → 显进行中卡可进对话；无则不显示；历史列表加载 + 无限滚动。
- [ ] **J2. 历史两类条目 + 存档独立** (AC: 2 / **L1+L2**)
  - [ ] AI 条目显评级+摘要；兽医条目显昵称+摘要+评分+已存档标记；未评分显「未评分」、中断显「已中断」；未存档的记录仍在历史（验证历史独立于存档）。
- [ ] **J3. 只读会话多态** (AC: 3 / **L1+L2**)
  - [ ] 从历史/深链进 CLOSED(已结束)/CLOSED(未评分)/INTERRUPTED(已中断)→ 只读 + 正确终态标签 + 不可发消息；未评分挂补弹、中断挂重新发起；会话不存在/越权 → not-found/403 不白屏。

---

## Dev Notes

### 关键架构约定（本 Story 必须落实）

- **问诊 Tab = 用户侧问诊模块主页**：是用户侧 5-Tab 之一（`features/consult` presentation），承载 AI（Epic 4）+ 兽医（5.3~5.7）的入口与历史整合（FR-35）。5.3「取消返回问诊模块主页」、5.6 补弹、5.7 重新发起均落此。
- **历史独立于存档（FR-16 / FR-35）**：问诊历史无论是否存入成长档案都保留于此——存档（FR-16，Epic 2 profile）是「额外操作而非唯一留存路径」。`archived` 仅标记位。
- **跨模块经 service 聚合**：历史聚合跨 triage（AI）+ consult（兽医），经各模块 service 接口，禁跨 repository（架构 §模块边界）。
- **游标分页 + camelCase + UTC ISO**：列表用 `{items,nextCursor,hasMore}`（架构 §Format）。
- **只读会话 = UX-DR18 ④ 会话变体 + ⑥ 失效态**：终态标签 + 不可发消息；深链/历史进入失效会话不白屏（404/403 友好态）。这是 6.1 深链落地的目标页之一（本 Story 提供页，路由表 6.1）。
- **无障碍**：评级/终态用 icon+text+color，不依赖单一颜色（NFR-13/UX-DR15）。

### 会话状态机专项（本 Story 的消费侧）

> 本 Story 不迁移状态，是状态机**全生命周期的用户侧视图收口**：
> - 进行中卡：WAITING（5.3 等待）/ IN_PROGRESS（5.5 对话）/ PENDING_CLOSE（5.6 待评分）——「进行中」语义；点击进对应界面。
> - 历史列表 + 只读会话：CLOSED（5.6，RATED「已结束」/ UNRATED「未评分」）、INTERRUPTED（5.7「已中断」）、CANCELLED（5.3 取消——按 PRD 是否入历史记 Dev Notes，默认取消不入历史或弱化展示）。
> - 补弹（5.6）、重新发起（5.7）、存档标记（5.6/Epic 2）在此汇聚。

### 强制护栏（架构 §Enforcement —— 违反即返工）

- **禁 MQ / 通用缓存 / 新中间件**；**Redis 收窄**（本 Story 不新增 Redis 用途，进行中查询走 DB/service）。
- **跨模块经 service**：历史聚合经 triage/consult service，不跨 repository。
- **角色门控**：问诊 Tab/历史 `role=user`；游客进问诊 Tab 触发受控功能 → FR-0C 登录引导。
- **失效态不白屏**：深链/历史进入失效会话 → 404/403 友好态（UX-DR18 ⑥），不停首页不白屏。
- **私密图只走签名 URL / 日志不落 PII**：只读会话媒体（AI 上下文图/存档图）经签名 URL。

### 范围边界（防 scope creep —— 本 Story 明确不做）

- ❌ 不做 AI 分诊本身 / AI 历史的产生（Epic 4，本 Story 消费）。
- ❌ 不做兽医会话发起/接单/对话/结束/评分逻辑（5.3~5.6，本 Story 是入口 + 历史回看 + 只读 + 挂补弹/重发入口）。
- ❌ 不做成长档案 FR-16 落地（Epic 2 profile，本 Story 只显「是否已存档」标记）。
- ❌ 不做推送深链路由表（Epic 6 / 6.1，本 Story 提供只读会话「落地页」，路由表在 6.1）。
- ✅ 只做：问诊 Tab 三段(入口平级 + 进行中卡 + 历史列表)+ 历史两类条目(AI 评级/摘要、兽医昵称/摘要/评分/存档标记)+ 历史独立于存档 + 只读会话页(已结束/未评分/已中断 + 失效态)+ 补弹/重发入口挂载。

### Project Structure Notes

- 后端：`com.petgo.consult.web`(`/api/v1/consult/history`、`/active`、`pending-rating` 复用 5.6、`/consult-sessions/{id}` 只读视角)；历史经 `triage` service 拿 AI 历史。游标分页。
- 前端：`lib/features/consult/presentation`——问诊 Tab 主页(三段)、历史列表、只读会话页；功能入口区 AI 卡跳 `features/triage`、兽医卡走 5.3 发起。补弹复用 5.6、重发复用 5.3/5.7。
- 用户侧问诊在 `features/consult`（本 Story 是其用户侧主页）；兽医工作台 `features/vet`（本 Story 不碰）。

### References

- [Source: architecture.md#Format Patterns] — 游标分页 `{items,nextCursor,hasMore}`、camelCase、UTC ISO、null 省略。
- [Source: architecture.md#Architectural Boundaries] — 模块间经 service/事件、禁跨 repository；presentation 只依赖 domain、不跨 feature import。
- [Source: architecture.md#Data Architecture 媒体三层] — 只读会话媒体/AI 图私密桶②签名 URL；存档引用应用自有 URL。
- [Source: architecture.md#Decision Impact Analysis] — 深链路由表(go_router)连接 notify→consult 会话（6.1 落表，本 Story 提供页）。
- [Source: epics.md#Story 5.8] — 三条原始 AC（三段结构 / 两类历史条目 + 存档独立 / 只读终态 UX-DR18④）。
- [Source: epics.md#Story 5.3 / 5.5 / 5.6 / 5.7] — 取消返回主页 / 进行中卡 / 补弹 + 存档标记 / 已中断重发。
- [Source: epics.md#Story 6.1] — 深链目标失效态(not-found/已关闭/已中断)落地，UX-DR18 ④⑥。
- [Memory: spec-page-state-completeness] — 显式覆盖无进行中卡、无历史空态、未评分/已中断条目、深链失效会话 404/403 不白屏等多态。

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

- 记录 CANCELLED 是否入历史、只读会话消息来源（IM 历史 vs 存档引用）选型、历史聚合的游标分页跨两源排序实现。

### File List
