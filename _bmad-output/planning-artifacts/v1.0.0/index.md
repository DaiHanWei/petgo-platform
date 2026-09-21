---
title: "TailTopia V1.0 规划产物索引（Planning Artifacts Index）"
project: petGo
created: 2026-06-01
purpose: BMAD 规划阶段产物目录；以 PRD 为核心参考目录，便于架构/Epic/Story 下游引用
note: 本目录内 PRD / UX / 技术框架为源文档的原样副本（内容未改动）；唯一事实源仍为 PRD/V1版本20260601/
---

# TailTopia V1.0 — 规划产物索引

> 本目录（`_bmad-output/planning-artifacts/`）汇集 BMAD 规划阶段的全部有用产物。
> **PRD 为核心参考目录**，UX 与技术框架围绕 PRD 的 FR 编号对齐。
> 各文档为源文件原样副本，**内容保持不变**；如需修订请在源目录 `PRD/V1版本20260601/` 操作后重新同步。

---

## 1. 产物清单

| 文件 | 类型 | 说明 | 源文件 |
|------|------|------|--------|
| [`PRD.md`](./PRD.md) | 📋 PRD（核心）| 产品需求文档，39 个 FR，唯一权威范围定义 | `V1-0-0PRD20260601.md` |
| [`UX_DESIGN.md`](./UX_DESIGN.md) | 🎨 UX 视觉规范 | Design System：色彩 / 字体 / 间距 / 组件 / Do's & Don'ts | `UX_DESIGN.md` |
| [`UX_EXPERIENCE.md`](./UX_EXPERIENCE.md) | 🧭 UX 行为规范 | 信息架构 / 导航 / 状态 / 关键流程 / 无障碍 | `UX_EXPERIENCE.md` |
| [`TECH_FRAMEWORK.md`](./TECH_FRAMEWORK.md) | 🏗️ 技术框架 | 技术选型与多云拓扑对齐稿（正式架构前置）| `TECH_FRAMEWORK.md` |
| [`TailTopia_V1_mockups.html`](./TailTopia_V1_mockups.html) | 🖼️ 效果图 | 21 屏全屏效果图（S01–S21）| `TailTopia_V1_全屏效果图.html` |
| [`implementation-readiness-report-2026-06-01.md`](./implementation-readiness-report-2026-06-01.md) | ✅ 就绪度评审 | PRD/UX/技术交叉评审 + 修订记录 | （本目录生成）|

---

## 2. PRD 参考目录（章节 + FR 速查）

> 跳转锚点对应 `PRD.md` 内章节。FR 编号为跨文档稳定引用键。

### 第 4 章 · 功能与需求

| 章节 | 模块 | FR 范围 |
|------|------|---------|
| 4.0 | 用户登录与引导 | FR-0A ~ FR-0H |
| 4.1 | 专业问诊（AI 图文分诊 + 兽医咨询）| FR-1 ~ FR-5 |
| 4.2 | 宠物聚会活动 ⚠️ 已移 V2 | （FR-6 ~ FR-10, V2）|
| 4.3 | 成长档案 | FR-11, FR-12, FR-14 ~ FR-16（FR-13 → V2）|
| 4.4 | 首页 Feed | FR-17, FR-18 |
| 4.5 | App 导航结构（5-Tab）| FR-19 |
| 4.6 | 个人中心（我的）| FR-20, FR-21, FR-27, FR-37, FR-39 |
| 4.7 | 推送通知 | FR-22A ~ FR-22E |
| 4.8 | 内容互动（点赞与评论）| FR-23, FR-24, FR-36 |
| 4.9 | 内容详情页 | FR-28 |
| 4.10 | 内容举报与审核 | FR-25 |
| 4.11 | 其他用户头像（迷你主页预览卡）| FR-26 |
| 4.12 | 兽医端（Vet Workbench）| FR-29 ~ FR-32 |
| 4.13 | 兽医评分 | FR-33 |
| 4.14 | 通知中心与问诊历史 | FR-34, FR-35, FR-38 |

### 其他章节
- §1 产品愿景 · §2 目标用户（UJ-1 Putri / UJ-2 Aurel）· §3 术语表
- §5 明确不做 · §6 MVP 范围 · §7 成功指标（SM-1 ~ SM-C2）
- §8 待解决问题 · §9 假设索引 · 附录 A 平台 / B 变现 / C 约束与护栏

---

## 3. FR → 技术模块映射（来自 TECH_FRAMEWORK §3）

| 模块 | 职责 | 对应 FR |
|------|------|---------|
| `auth` | 登录/JWT/引导/角色 | FR-0A~0H, FR-19 |
| `triage` | AI 图文分诊 + 安全规则层 | FR-1~FR-5 |
| `consult` | 兽医队列/接单/评分门/封禁中断 | FR-4B, FR-30~FR-33 |
| `content` | 发布/Feed/互动/详情/删除 | FR-12, FR-17, FR-23, FR-24, FR-28, FR-36 |
| `profile` | 宠物档案/时间线/H5 名片 | FR-11, FR-14~16, FR-37~39 |
| `notify` | 推送/通知中心/深链接 | FR-22*, FR-34, FR-38 |
| `moderation` | 举报/人工队列 | FR-25 |
| `vet` | 兽医工作台/接单/评分 | FR-29~33 |

---

## 4. 关键选型快照（来自 TECH_FRAMEWORK §0）

- **移动端** Flutter · **后端** Java(Spring Boot) · **AI** Google Gemini（单模型图文分诊）· **IM** 腾讯云 IM
- **多云拓扑**：Cloudflare(边缘) + 德国(后端/DB) + 阿里云雅加达(媒体 OSS/CDN) + 腾讯(IM) + Google(AI/Vertex 新加坡)
- **媒体三层**：阿里 OSS 公开桶 / 阿里 OSS 私密桶（分诊图）/ 腾讯 IM 托管（聊天图&视频）

---

## 5. 状态与下一步

- ✅ PRD / UX / 效果图 / 技术框架已对齐到 **TailTopia V1**（导航 5-Tab、AI 仅图文、兽医聊天支持视频、攒局移 V2）
- ⏭️ 下一步：`bmad-create-architecture` 产出正式架构（数据模型 / API 契约 / 时序 / 部署 CI-CD）→ `bmad-create-epics-and-stories`
- 详见 [`implementation-readiness-report-2026-06-01.md`](./implementation-readiness-report-2026-06-01.md)
