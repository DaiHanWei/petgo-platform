# V1.3.4 规划产物

> 集成分支 `dev_1.3.4`。本版本 = 推荐算法校准（FR-122 / 135）· 发布图片原图上传（FR-125 / 139）· 版本更新提醒（FR-124）· 里程碑新增两项（FR-126）· 运营触达（FR-137 / 138）· KTP 样式迭代（FR-142）· 用户相互关注与通知中心优化（FR-140 / 141）。
> 文件约定沿用 V1.3.0：**一主题一套**，文件名带主题后缀。

## 主题登记表

| 主题 | 功能分支 | Epic 号段 | Story | 状态 |
|---|---|---|---|---|
| **app** · App + 后端 | `feat/1.3.4-app-ux-revamp` | Epic 1–6 | 23 | 云端已实现，23 条全部 review |
| **ktp** · KTP 样式迭代 | `feat/1.3.4-ktp-revamp` | Epic 1–2 | 11 | 云端已实现，11 条全部 review |
| **follow** · 用户关注与通知中心 | `feat/1.3.4-follow-notify` | Epic 1–3 | 11 | 云端已实现，11 条全部 review |
| admin · 后台 AB-22 / AB-30 / AB-31 | 待开 | — | — | 未开始（数据面与核心服务由 app 主题提供，见 AD-1） |

## 产物文件（app）

| 件 | 文件 | 说明 |
|---|---|---|
| PRD | `PRD-v1.3.4-app.md` | 原名 `1-3-4prd.md`，原样入库 |
| UI 稿 | `ui-v1.3.4-app.html` | 原名 `ui-1-3-4-integrated-v1.html`，2026-10-12 定稿，31 屏 |
| UI 决策记录 | `UI决策记录-app.md` | UI 稿阶段的决策（原 `.decision-log.md`） |
| **决策日志** | `决策日志-app.md` | D-1 ~ D-14，**冲突时以此为准** |
| 架构 delta | `architecture-v1.3.4-app-delta.md` | AD-1 ~ AD-20 |
| epics | `epics-v1.3.4-app.md` | 6 个 Epic · 23 条 story |

**权威顺序**：决策日志 > PRD > 架构 delta > UI 稿。

## 产物文件（ktp）

| 件 | 文件 | 说明 |
|---|---|---|
| PRD | `PRD-v1.3.4-ktp.md` | 原名 `KTP样式迭代.md`（FR-142A~F），原样入库 |
| UI 稿 | `ui-v1.3.4-ktp.html` | 原名 `ui-ktp-1-3-4-integrated-v1.html`，整合稿即交付版 |
| 设计素材 | `ktp-assets/` | 5 款新卡面素材（底图 / Logo / 签章 / 抬头 / Font Note）+ 概念图 16~20 |
| **决策日志** | `决策日志-ktp.md` | D-1 ~ D-11，**冲突时以此为准** |
| 架构 delta | `architecture-v1.3.4-ktp-delta.md` | AD-1 ~ AD-14 |
| epics | `epics-v1.3.4-ktp.md` | 2 个 Epic · 11 条 story |

**权威顺序**：决策日志 > PRD > 架构 delta > UI 稿。

## 产物文件（follow）

| 件 | 文件 | 说明 |
|---|---|---|
| PRD | `PRD-v1.3.4-follow.md` | 原名 `1-3-4关注与通知prd.md`（FR-140 / 141） |
| UI 稿 | `ui-v1.3.4-follow.html` | 原名 `ui-1-3-4-follow-notify-v1.html`，定稿不拆页 |
| **决策日志** | `决策日志-follow.md` | D-1 ~ D-7，**冲突时以此为准** |
| 架构 delta | `architecture-v1.3.4-follow-delta.md` | AD-1 ~ AD-16 |
| epics | `epics-v1.3.4-follow.md` | 3 个 Epic · 11 条 story |

**权威顺序**：决策日志 > PRD > 架构 delta > UI 稿。三个主题已于 2026-10-13 合入 `dev_1.3.4`（顺序 ktp → follow → app）。
