# PetGo Platform

> 印尼第一款以「宠物主人全生命周期陪伴」为核心的宠物社区 App —— 研发主仓库。

**PetGo** 为雅加达及印尼各地的宠物主解决三个长期痛点：宠物突发健康问题时找不到低价可信的专业指导、宠物成长记忆没有专属归处、以及缺少与同好宠物主连接的社区。

本仓库是 PetGo 的**工程研发仓库**。产品需求、UX 规范与技术框架等规划产物，作为唯一事实源沉淀在 [`_bmad-output/planning-artifacts/`](./_bmad-output/planning-artifacts/) 下，供架构、Epic、Story 等下游开发环节引用。

---

## V1.0 范围

V1 以两个核心功能构建最小完整体验，**全功能免费，不涉及支付**，核心目标是用户规模积累与功能验证。

| 模块 | 名称 | 说明 |
|------|------|------|
| 🩺 专业问诊 | Konsultasi Kilat | AI 图文分诊（绿/黄/红三级 + 观察建议 + 用药参考）+ 免费在线兽医实时咨询 |
| 📖 成长档案 | Paspor Tumbuh Kembang | 单账号单宠物档案、成长日历快乐时刻、可对外分享的 H5「宠物名片」 |

两者通过「问诊记录存入健康档案」相互连接，沉淀为用户不愿离开的核心数据资产。

**配套能力：** 首页 Feed、内容发布与互动（点赞/两级评论）、推送通知与深链接、内容举报审核、兽医工作台（独立 Tab 工作台 + 接单 + 评分确认门）、双语（印尼语 / 英语）。

**明确移至 V2：** 宠物聚会活动（Gabung Gath）、支付体系、内容社区视频发布、搜索、用户公开主页、多宠物管理。

详见 [`PRD.md`](./_bmad-output/planning-artifacts/PRD.md)（39 个功能需求 FR，唯一权威范围定义）。

---

## 技术栈

| 层 | 选型 |
|----|------|
| 移动端 | **Flutter**（iOS + Android 单一代码库，用户端 + 兽医端角色门控） |
| 后端 | **Java / Spring Boot 3.x（Java 21）**，模块化单体 |
| 数据 | PostgreSQL + Redis（缓存 / 兽医在线态 / 队列态 / 限流） |
| AI 分诊 | **Google Gemini** 单模型（原生多模态，图像理解 + 分诊推理 + 结构化 JSON）+ 确定性安全规则层 |
| 实时通信 | **腾讯云 IM (TIM)** —— 承载兽医 ↔ 用户图文 / 视频咨询 |
| H5 名片 | 服务端渲染（OG 预览 + 不可枚举 token + noindex） |

### 多云拓扑

```
Cloudflare(边缘 TLS/WAF/路由)  →  德国源站(后端 + PostgreSQL)
                                    ├─ 阿里云 OSS + CDN(雅加达)  —— 应用媒体(公开桶 + 私密桶)
                                    ├─ 腾讯云 IM                —— 实时聊天媒体(IM 托管)
                                    └─ Google Gemini / Vertex(新加坡) —— AI 分诊
```

**媒体三层存储**（隐私边界严格隔离）：① 阿里 OSS 公开桶（Feed / 档案 / 名片）· ② 阿里 OSS 私密桶（AI 分诊图，医疗敏感，仅签名 URL）· ③ 腾讯 IM 托管（兽医聊天图 / 视频）。

详见 [`TECH_FRAMEWORK.md`](./_bmad-output/planning-artifacts/TECH_FRAMEWORK.md)。

### 后端模块边界

`auth` · `triage` · `consult` · `content` · `profile` · `notify` · `moderation` · `vet` —— 模块边界即未来微服务拆分线。

---

## 仓库结构

```
petgo-platform/
├── _bmad-output/planning-artifacts/   # 规划产物（唯一事实源）
│   ├── index.md                       #   产物索引 + PRD 参考目录 + FR→模块映射
│   ├── PRD.md                         #   产品需求文档（核心，39 个 FR）
│   ├── UX_DESIGN.md                   #   UX 视觉规范（Design System）
│   ├── UX_EXPERIENCE.md               #   UX 行为规范（信息架构 / 流程 / 无障碍）
│   ├── TECH_FRAMEWORK.md              #   技术框架与多云拓扑对齐稿
│   ├── PetGo_V1_mockups.html          #   21 屏全屏效果图（S01–S21）
│   └── implementation-readiness-report-2026-06-01.md  # 就绪度评审
├── _bmad/                             # BMAD 工作流配置
├── docs/                              # 工程文档（架构 / API 契约，待产出）
└── README.md
```

---

## 规划产物索引

| 文件 | 类型 | 说明 |
|------|------|------|
| [`index.md`](./_bmad-output/planning-artifacts/index.md) | 🗂️ 索引 | 产物目录、PRD 章节速查、FR → 技术模块映射 |
| [`PRD.md`](./_bmad-output/planning-artifacts/PRD.md) | 📋 PRD | 产品需求（核心，唯一权威范围） |
| [`UX_DESIGN.md`](./_bmad-output/planning-artifacts/UX_DESIGN.md) | 🎨 UX 视觉 | 色彩 / 字体 / 间距 / 组件 / Do's & Don'ts |
| [`UX_EXPERIENCE.md`](./_bmad-output/planning-artifacts/UX_EXPERIENCE.md) | 🧭 UX 行为 | 导航 / 状态 / 关键流程 / 无障碍 |
| [`TECH_FRAMEWORK.md`](./_bmad-output/planning-artifacts/TECH_FRAMEWORK.md) | 🏗️ 技术框架 | 技术选型与多云拓扑 |
| [`PetGo_V1_mockups.html`](./_bmad-output/planning-artifacts/PetGo_V1_mockups.html) | 🖼️ 效果图 | 21 屏全屏效果图 |

---

## 下一步

规划阶段已对齐（导航 5-Tab、AI 仅图文分诊、兽医聊天支持视频、攒局移 V2）。下一步进入正式架构设计（数据模型 / API 契约 / 时序 / 分诊管线与安全规则层 / 部署 CI-CD），再拆分 Epic 与 Story 进入开发。

> ⚠️ 状态：规划产物为 `draft`。开发前请以 `_bmad-output/planning-artifacts/` 内最新版本为准。
