# `_bmad-output/` —— 规划与实现产物总入口

> 新会话请先读本文件，再按版本进入。**不要用文件名 grep 猜版本**，每个版本目录都有 README 列全五件套。

## 目录

| 目录 | 放什么 |
|---|---|
| `planning-artifacts/` | 规划层：PRD、UX、架构 delta、epics、就绪度评审 —— **按版本一个子目录** |
| `implementation-artifacts/` | 实现层：story、sprint-status、评审记录、spec —— **按版本一个子目录**，与规划层同名 |
| `_archive/` | 一次性验收截图、V1.0 保真工程、H5 原型页 —— 只读，不再维护 |

两层都用同一套版本目录名：`v1.0.0` `v1.1.0` `v1.1.2` `v1.1.4` `v1.1.6` `v1.3.0` `v1.4.0`，另有两个专题线 `admin-backend`（运营后台，2026-06）和 `bug-system`（Bug 同步工具，仅规划层）。

## 一个版本的五件套

| 件 | 位置 | 说明 |
|---|---|---|
| ① PRD | `planning-artifacts/<ver>/PRD*.md` | 权威需求；v1.0.0 起点，其后都是增量 |
| ② 架构 delta | `planning-artifacts/<ver>/architecture-<ver>-delta.md` | 相对基线 `planning-artifacts/architecture.md` 的增量 |
| ③ epics | `planning-artifacts/<ver>/epics-<ver>.md` | Epic / Story 拆分 |
| ④ sprint-status | `implementation-artifacts/<ver>/sprint-status-<ver>.yaml` | 执行顺序 + 各 story 状态；`story_location` 指向 ⑤ |
| ⑤ story 文件 | `implementation-artifacts/<ver>/<epic>-<n>-<中文名>.md` | 自包含，可直接 `bmad-dev-story` |

跨版本常设文件只有两个：`planning-artifacts/architecture.md`（基线架构）和 `implementation-artifacts/CROSS-STORY-DECISIONS.md`（跨 story 契约，冲突时以它为准）。

## 版本一览

| 版本 | 主题 | 规划 README | 状态 |
|---|---|---|---|
| v1.0.0 | 首发：档案 / 内容 / AI 分诊 / 兽医问诊 / 推送 / 里程碑 | `planning-artifacts/v1.0.0/` | 已上线，冻结 |
| v1.1.0 | 资金子系统（PawCoin / 付费问诊 / 退款）+ 身份证 + 健康记录 + 后台 Epic 9 | `planning-artifacts/v1.1.0/` | 已上线 |
| v1.1.2 | Diary 时间线重构 / Tab 重排 / 内容可见范围 / Splash | `planning-artifacts/v1.1.2/` | 已上线 |
| v1.1.4 | 拉黑 / 举报 / 工单队列 | `planning-artifacts/v1.1.4/` | 已上线 |
| v1.1.6 | 内容运营与增长：访客态 / 顶置 / 标签 / 批量发布 / 推荐序 / 分享 | `planning-artifacts/v1.1.6/` | 2026-09-07 合 main |
| v1.3.0 | 多 PRD 组合（首个：运营后台 UI 重构），集成分支 `dev_1.3.0` | `planning-artifacts/v1.3.0/` | 开工中，PRD 陆续传入 |
| v1.4.0 | 精选自营电商（Toko） | `planning-artifacts/v1.4.0/` | 开发中（并行线） |
| admin-backend | 运营后台重构（Lark 登录 / 审计 / 兽医·用户·内容管理） | `planning-artifacts/admin-backend/` | 已上线 |
