# V1.5.0 规划

集成分支 `dev_1.5.0`（2026-11-14 从 `main` 355a37dee 切出）。九份 PRD 按依赖分六个云端会话并行，跨会话共用的东西先由地基 Story 0.1 落进 `dev_1.5.0`（见 `implementation-artifacts/CROSS-STORY-DECISIONS.md`「2026-11-14 V1.5.0 六会话共用地基」）。

| 会话 | PRD 目录 | 功能分支 | 状态 |
|---|---|---|---|
| ① 健康提醒 | `宠物健康记录提醒/` | `feat/1.5.0-health-reminder` | 规划完成，待起云端 |
| ② 送礼与礼包 | `ui-gift-optimization/` → `超值礼包/` | `feat/1.5.0-gift-pack`（先送礼优化、后超值礼包） | 规划完成，待起云端 |
| ③ 首日与召回 | `首日与新手任务/` → `推送与召回/` | `feat/1.5.0-onboarding-recall`（先首日、后召回） | 规划完成，待起云端 |
| ④ 物种 | `宠物物种扩展/` | `feat/1.5.0-pet-species` | 规划完成，待起云端 |
| ⑤ 点赞 | `自动点赞/` + `Feed双击点赞/` | `feat/1.5.0-likes`（先自动点赞、后双击） | 规划完成，待起云端 |
| ⑥ 运营活动（第二批） | `运营活动规则引擎/` | `feat/1.5.0-ops-activity` | 规划完成；分支等 ② 的 4-1 合进 dev 后再切、再起云端 |

- 合并顺序：送礼优化 → 超值礼包 → 运营活动；首日与新手任务 → 推送与召回、Feed 双击点赞；宠物物种扩展最后。
- 跨 PRD 决策日志：`decision-log.md`（原 `.decision-log.md`）。
- 每份 PRD 同目录下有整合 UI 稿与 `拆页-App/`（一屏一文件，从 `00-目录.html` 进）。

- 一个会话一个功能分支、一份 `sprint-status-v1.5.0-<主题>.yaml`（主题：health-reminder / gift-pack / onboarding-recall / pet-species / likes / ops-activity），云端用 `scripts/cloud-run-story-loop.sh v1.5.0 <主题> <分支>` 起。PRD 里写的拆分支建议以本表为准。

## 规划产物（2026-11-14）

| 主题 | 架构增量 / epics | story 数 | 云端做到 |
|---|---|---|---|
| health-reminder | `architecture-v1.5.0-health-reminder-delta.md` / `epics-v1.5.0-health-reminder.md` | 14 | 4-1 只做 AC1 |
| gift-pack | `architecture-v1.5.0-gift-pack-delta.md` / `epics-v1.5.0-gift-pack.md` | 24 | 先 4-1，跳过 3-1、7-1，到 6-5 |
| onboarding-recall | `architecture-v1.5.0-onboarding-recall-delta.md` / `epics-v1.5.0-onboarding-recall.md` | 18 | 4-1 后记首日检查点继续；4-1/8-1 的 L1/L2 留本地 |
| pet-species | `architecture-v1.5.0-pet-species-delta.md` / `epics-v1.5.0-pet-species.md` | 12 | 到 3-5 |
| likes | `architecture-v1.5.0-likes-delta.md` / `epics-v1.5.0-likes.md` | 10 | 到 4-2 |
| ops-activity | `architecture-v1.5.0-ops-activity-delta.md` / `epics-v1.5.0-ops-activity.md` | 12 | 到 3-2（1-1 第 0 步先核对 4-1 契约在树里） |

各线「待拍板」在各自 epics 文末，已按推荐默认写进 story，不阻塞开发。
