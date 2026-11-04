# V1.4.2 social-topic 决策日志

> 权威顺序：本日志 > PRD > 架构增量 > UI 稿。PRD 是设计侧快照，本日志与 PRD 不一致处需设计侧同步回改 PRD。

## 2026-11-04 就绪度评审拍板（操作者按推荐选定）

| 编号 | 问题 | 决定 | 落到 |
|---|---|---|---|
| R-1 | B 关注 A 本人且追更 A 的宠物，A 拉黑 B 后，A 宠物 Diary 仍从「关注的人」一路进 B 的关注流（拉黑只删 A→B 的关注），与 PRD FR-153 AC9 矛盾 | **收窄 AC9**：只有 B 未关注 A 本人时，A 宠物的 Diary 才不出现；关注一路维持 1.3.4 现状。⚠️ PRD FR-153 AC9 需设计侧同步改写 | 架构 D-9、Story 1.3 |
| R-2 | 埋点 `topic_id` / `owner_id` 取值（架构不对外暴露自增 id） | `topic_id` = 话题标准名；`owner_id` = 主人公开 token；接口补 `featured`、`official`、`iFollowOwner`、`ownerToken`；`topic_inserted` 改在发布成功后按服务端返回的 `topics` 上报；Hewan 空态推荐卡追更 `source = pet_recommendation`（PRD §4 新增取值） | 架构 D-8 / D-13、Story 1.1 / 1.4 / 1.5 / 4.1 / 4.2 / 4.3 / 4.4 |
| R-3 | 按 ID 名单发送时原 `skipped_count` = 无效 id 数，与「没有宠物」排除混在一起 | **新增列** `ops_notification_campaigns.skipped_no_pet_count`（迁移 ⑧），「跳过 N（没有宠物）」读新列；原 `skipped_count` 不变。偏离 PRD AB-37「复用 skipped_count」写法 | 架构 D-16 / P-8、Story 7.1 / 7.2 |
| R-4 | 账号级里程碑通知在 App 会被拼上宠物名 | 通知中心对 6 个账号级编码不拼宠物名；混养账号 `targetRef` 放第一只 ACTIVE 宠物物种的编码 | 架构 D-12、Story 2.1 / 2.3 |
| R-5 | 新建官方话题时重名用户话题处于隐藏 / 禁用；话题帖子数每小时重算有滞后 | 隐藏 → 照常升级并保持隐藏；禁用 → 拒绝升级、提示先恢复；后台列表帖子数最多滞后 1 小时可接受（抽屉与升级黄条实时） | 架构 D-2、Story 6.1 |

## 2026-11-04 评审中直接更正（无需拍板）

- B1：「其他」物种新编码为 **G-S12 / G-S13**（PRD §3.2 原文），架构与 epics 曾误写 G-S19 / S20，已更正；徽章映射按完整编码，不按后缀。
- I5：`PET_DIARY_POSTED` 由后端拼文案、App 加入 `serverCopyTypes`（PRD §7 文案表缺这一行，文案取 §3.1 原文）。
- I6：迁移 ⑦ 约束值不写死 41，取当前树最后一次重建的全集 + 2。
- 话题页有生效内容顶置时，被顶置帖子不在话题列表重复出现（同首页 Feed 让位）。
