# `_bmad-output/` —— 规划与实现产物总入口

> 新会话请先读本文件，再按版本进入。**不要用文件名 grep 猜版本**，每个版本目录都有 README 列全五件套。

## 目录

| 目录 | 放什么 |
|---|---|
| `planning-artifacts/` | 规划层：PRD、UX、架构 delta、epics、就绪度评审 —— **按版本一个子目录** |
| `implementation-artifacts/` | 实现层：story、sprint-status、评审记录、spec —— **按版本一个子目录**，与规划层同名 |
| `_archive/` | 一次性验收截图、V1.0 保真工程、H5 原型页 —— 只读，不再维护 |

两层都用同一套版本目录名：`v1.0.0` `v1.1.0` `v1.1.2` `v1.1.4` `v1.1.6` `v1.3.0` `v1.3.2` `v1.3.4` `v1.4.0`（旧 Toko 规划已改名归档为 `v1.4.0-toko-废弃`），另有三个专题线 `admin-backend`（运营后台，2026-06）、`bug-system`（Bug 同步工具，仅规划层）和 `video`（短视频，暂不定版本）。

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
| v1.3.2 | 插画依赖包：Tailsonality 人格测试 / 宠物护照与场所盖章 / 场所打卡 / 里程碑徽章重绘，集成分支 `dev_1.3.2` | `planning-artifacts/v1.3.2/` | 规划入库（2026-09-28），待拆 epics |
| v1.3.4 | 推荐算法校准 / 原图上传 / 版本更新提醒 / 里程碑两项 / 运营弹窗与通知 / KTP 样式迭代 / 用户关注与通知中心，集成分支 `dev_1.3.4` | `planning-artifacts/v1.3.4/` | app、ktp、follow 三主题已实现并合入 `dev_1.3.4`（2026-10-13） |
| v1.4.0 | 四条线并行：① Tailrent Level 等级 / ② 多宠物 / ③ 送礼 / ④ 推送开关与收藏，集成分支 `dev_1.4.0`；先做共用地基 Story 0.1 | `planning-artifacts/v1.4.0/` | 规划入库（2026-10-18），地基 story 待开发 |
| v1.4.0-toko-废弃 | ~~精选自营电商（Toko）~~ 旧 V1.4.0 规划 | `planning-artifacts/v1.4.0-toko-废弃/` | ⛔ 已废除（2026-10-18），勿参考，仅历史留存 |
| admin-backend | 运营后台重构（Lark 登录 / 审计 / 兽医·用户·内容管理） | `planning-artifacts/admin-backend/` | 已上线 |
| video | 短视频第一版（官方号发视频 + 全屏播放 + 会员等级 + 视频免审名单），暂不定版本，分支 `feat/video` | `planning-artifacts/video/` | PRD / 架构 / epics / 排期已完成（2026-10-16），开发中 |
