-- V1.3.0 批次 A · Story 1.2（AD-A5.3b）：通用宠物的 G-M1 / G-M2 由「用户打卡」改为「系统自动」。
--
-- 🔴 **为什么必须有这支迁移**：`pet_milestones.trigger_type` 在**建档那一刻就物化成行**，
--    而铺清单入口 `MilestoneService.assignRoster` 有 `existsByPetProfileId` 短路、
--    **对已建档宠物永不回改**。只改编译期目录（MilestoneCatalog）→ 只对**新建档**宠物生效，
--    存量宠物的这两条仍是 USER_CHECKIN，客户端照旧渲染打卡按钮、后端 triggerType 校验照旧放行。
--    先例：V77 正是为同类问题补的迁移。
--
-- 背景：FR-86（V1.1.2 Story 5.2）按后缀 M3/M4/M5/M9 取消健康类打卡，通用清单把「看兽医」放在
--       G-M1、「健康检查 / 疫苗」放在 G-M2，两个后缀都不命中 —— 至今仍是打卡类。属规则漏网。
--
-- ⚠️ **只改 trigger_type，不动完成态**（AD-A5.3「存量不回滚」）：已通过打卡完成的 G-M1 / G-M2
--    保持完成，`milestone_completions` 一行不碰。收回用户已看到的徽章，代价大于收益。
-- ⚠️ **不清理存量脏完成行**（决策 A-9 / AD-A5.3c，生产实查全库仅 3 条）。本迁移与那 3 条无关。
-- ⚠️ **不改 schema**：不加列、不改约束（`ck_pet_milestones_trigger` 一字不动，
--    'SYSTEM_AUTO' 本就是其合法取值）→ ddl-auto=validate 不受影响。
--
-- 幂等：WHERE 已限定 trigger_type <> 'SYSTEM_AUTO'，重跑为 0 行更新。

UPDATE pet_milestones
SET trigger_type = 'SYSTEM_AUTO'
WHERE code IN ('G-M1', 'G-M2')
  AND trigger_type <> 'SYSTEM_AUTO';
