# V1.3.4 规划产物

> 集成分支 `dev_1.3.4`。本版本 = 推荐算法校准（FR-122 / 135）· 发布图片原图上传（FR-125 / 139）· 版本更新提醒（FR-124）· 里程碑新增两项（FR-126）· 运营触达（FR-137 / 138）。
> 文件约定沿用 V1.3.0：**一主题一套**，文件名带主题后缀。

## 主题登记表

| 主题 | 功能分支 | Epic 号段 | Story | 状态 |
|---|---|---|---|---|
| **app** · App + 后端 | `feat/1.3.4-app-ux-revamp` | Epic 1–6 | 23 | PRD / UI 稿入库，架构 delta（AD-1~AD-20）+ epics 定稿；上云预检拍板 D-15~D-25；**23 条 story 全部 ready-for-dev**（`implementation-artifacts/v1.3.4/`，含 `sprint-status-v1.3.4-app.yaml` 与 `cloud-rules-app.md`，2026-10-12）—— 云端批跑 |
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
